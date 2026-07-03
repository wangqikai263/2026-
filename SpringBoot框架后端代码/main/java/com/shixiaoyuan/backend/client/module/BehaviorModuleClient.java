package com.shixiaoyuan.backend.client.module;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 学生设备使用行为小模型客户端（手机 / 电脑 / 其他设备）
 */
@Component
public class BehaviorModuleClient {

    private final WebClient moduleWebClient;
    private final String baseUrl;

    public BehaviorModuleClient(
            @Qualifier("moduleWebClient") WebClient moduleWebClient,
            // 同样加默认值，避免缺配置时报错
            @Value("${app.modules.behavior.base-url:http://localhost:8001}") String baseUrl
    ) {
        this.moduleWebClient = moduleWebClient;
        this.baseUrl = baseUrl;
    }

    /**
     * 调用 behavior_device_usage 模块的 /analyze 接口
     *
     * @param trackingMode "fast" (默认 bytetrack), "precise" (botsort+ReID), "none" (纯检测)
     */
    public Mono<Map<String, Object>> analyze(String videoPath, String classId, Integer durationMin,
                                              String trackingMode, Long jobId) {
        Map<String, Object> body = new HashMap<>();
        body.put("video_path", videoPath);
        body.put("class_id", classId);
        body.put("duration_min", durationMin);
        body.put("segment_size_s", 120);
        body.put("tracking_mode", trackingMode != null ? trackingMode : "fast");
        if (jobId != null) {
            body.put("job_id", String.valueOf(jobId));
        }
        // 避免通过 HTTP 回传超大 JSON，优先只返回结果文件路径
        body.put("response_mode", "path");

        return moduleWebClient.post()
                .uri(baseUrl + "/analyze")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getProgress(Long jobId) {
        if (jobId == null) {
            return Mono.just(Map.of("status", "UNKNOWN", "progress", 0));
        }
        return moduleWebClient.get()
                .uri(baseUrl + "/progress?job_id=" + jobId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15));
    }

    /**
     * 向后兼容的重载（默认 fast 模式）
     */
    public Mono<Map<String, Object>> analyze(String videoPath, String classId, Integer durationMin) {
        return analyze(videoPath, classId, durationMin, "fast", null);
    }

    public Mono<Map<String, Object>> analyze(String videoPath, String classId, Integer durationMin,
                                              String trackingMode) {
        return analyze(videoPath, classId, durationMin, trackingMode, null);
    }
}

