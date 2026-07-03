// src/main/java/com/shixiaoyuan/backend/controller/api/ChatController.java
package com.shixiaoyuan.backend.controller.api;

import com.shixiaoyuan.backend.dto.request.ChatRequest;
import com.shixiaoyuan.backend.dto.response.ChatResponse;
import com.shixiaoyuan.backend.service.chat.ChatService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "对话接口", description = "基于课堂分析的自然语言交互")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Mono<ChatResponse> chat(@RequestBody ChatRequest req) {
        return chatService.chat(req.getMessage(), req.getClassData())
                .map(content -> {
                    ChatResponse resp = new ChatResponse();
                    resp.setContent(content);
                    resp.setMock(false);
                    resp.setJobId(null);
                    return resp;
                });
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger chunkCounter = new AtomicInteger(0);
        long startedAt = System.currentTimeMillis();

        log.info("[ChatController] stream start, sessionId={}, messageLen={}",
                req.getSessionId(),
                req.getMessage() == null ? 0 : req.getMessage().length());

        Disposable disposable = chatService.chatStream(req.getMessage(), req.getClassData())
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            if (finished.get()) return;
                            int no = chunkCounter.incrementAndGet();
                            if (no <= 3 || no % 20 == 0) {
                                log.info("[ChatController] stream chunk#{} len={}", no, chunk == null ? 0 : chunk.length());
                            }
                            sendChunkEvent(emitter, chunk);
                        },
                        error -> {
                            if (!finished.compareAndSet(false, true)) return;
                            log.warn("[ChatController] stream error after {} chunks, cost={}ms",
                                    chunkCounter.get(),
                                    System.currentTimeMillis() - startedAt,
                                    error);
                            sendNamedEvent(emitter, "error", Map.of(
                                    "message", "流式对话失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage())
                            ));
                            emitter.complete();
                        },
                        () -> {
                            if (!finished.compareAndSet(false, true)) return;
                            sendNamedEvent(emitter, "done", Map.of("done", true));
                            log.info("[ChatController] stream done, chunks={}, cost={}ms",
                                    chunkCounter.get(),
                                    System.currentTimeMillis() - startedAt);
                            emitter.complete();
                        }
                );

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            if (finished.compareAndSet(false, true)) {
                sendNamedEvent(emitter, "error", Map.of("message", "流式响应超时"));
                log.warn("[ChatController] stream timeout after {} chunks, cost={}ms",
                        chunkCounter.get(),
                        System.currentTimeMillis() - startedAt);
                emitter.complete();
            }
        });
        emitter.onError(ex -> {
            disposable.dispose();
            if (finished.compareAndSet(false, true)) {
                log.warn("[ChatController] stream emitter error after {} chunks, cost={}ms",
                        chunkCounter.get(),
                        System.currentTimeMillis() - startedAt,
                        ex);
            }
        });

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-transform")
                .body(emitter);
    }

    private void sendChunkEvent(SseEmitter emitter, String chunk) {
        String safe = chunk == null ? "" : chunk;
        if (safe.isEmpty()) return;
        boolean sent = sendNamedEvent(emitter, "chunk", Map.of("content", safe));
        if (!sent) {
            throw new RuntimeException("sse_send_failed");
        }
    }

    private boolean sendNamedEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON)
            );
            return true;
        } catch (Exception e) {
            log.debug("[ChatController] SSE send failed, event={}", eventName, e);
            return false;
        }
    }
}
