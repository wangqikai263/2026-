// client/llm/LlmClient.java
package com.shixiaoyuan.backend.client.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LlmClient {

    private final WebClient llmWebClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(@Qualifier("llmWebClient") WebClient llmWebClient) {
        // 在原有 WebClient 配置的基础上，单独调大这个 LLM 客户端的内存缓冲上限
        // 默认是 256KB（262144），这里改成 20MB，避免 DataBufferLimitException
        int maxInMemorySize = 20 * 1024 * 1024; // 10MB

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemorySize)
                )
                .build();

        this.llmWebClient = llmWebClient.mutate()
                .exchangeStrategies(strategies)
                .build();
    }

    public Mono<String> chat(String model, String systemPrompt, String userContent) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.3,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                )
        );

        return llmWebClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class); // 上层再解析 JSON / content
    }

    public Flux<String> chatStream(String model, String systemPrompt, String userContent) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.3,
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                )
        );

        return llmWebClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(data -> !data.isEmpty())
                .takeUntil(data -> "[DONE]".equals(data))
                .filter(data -> !"[DONE]".equals(data))
                .map(this::extractDeltaContent)
                .filter(piece -> piece != null && !piece.isEmpty());
    }

    /**
     * 从流式 chunk 中提取增量文本（OpenAI / DeepSeek 风格）
     */
    private String extractDeltaContent(String rawData) {
        String data = rawData == null ? "" : rawData.trim();
        if (data.isEmpty()) return "";
        try {
            JsonNode root = mapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode first = choices.get(0);
                JsonNode delta = first.path("delta");
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    if (contentNode.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode item : contentNode) {
                            String text = item.path("text").asText("");
                            if (!text.isEmpty()) {
                                sb.append(text);
                            }
                        }
                        if (!sb.isEmpty()) {
                            return sb.toString();
                        }
                    }
                    String content = contentNode.asText("");
                    if (!content.isEmpty()) {
                        return content;
                    }
                }
                String fallbackMsg = first.path("message").path("content").asText("");
                if (!fallbackMsg.isEmpty()) {
                    return fallbackMsg;
                }
            }
            String direct = root.path("content").asText("");
            if (!direct.isEmpty()) {
                return direct;
            }
            return "";
        } catch (Exception ignore) {
            // 如果上游不是 JSON（极少数兼容场景），按纯文本透传。
            return data.startsWith("{") || data.startsWith("[") ? "" : data;
        }
    }
}
