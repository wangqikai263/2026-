// client/module/FocusModuleClient.java
package com.shixiaoyuan.backend.client.module;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class FocusModuleClient {

    private final WebClient moduleWebClient;
    private final String baseUrl;

    public FocusModuleClient(
            @Qualifier("moduleWebClient") WebClient moduleWebClient,
            @Value("${app.modules.focus.base-url}") String baseUrl
    ) {
        this.moduleWebClient = moduleWebClient;
        this.baseUrl = baseUrl;
    }

    public Mono<Map<String, Object>> analyze(String videoPathOrUrl) {
        return moduleWebClient.post()
                .uri(baseUrl + "/analyze")   // 通过配置保证不写死
                .bodyValue(Map.of("video", videoPathOrUrl))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});      // 上层统一封装 schema_version
    }
}
