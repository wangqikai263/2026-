// WebClient（LLM & 小模型）
package com.shixiaoyuan.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@Configuration
public class HttpClientConfig {
    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    /**
     * LLM 调用专用 WebClient
     * - baseUrl 从 app.llm.base-url 读取
     * - apiKey 从 app.llm.api-key 读取
     * - 超时时间（响应超时）从 app.llm.timeout-seconds 读取，默认 120 秒
     */
    @Bean
    public WebClient llmWebClient(
            @Value("${app.llm.base-url}") String baseUrl,
            @Value("${app.llm.api-key:}") String apiKey,
            @Value("${app.llm.timeout-seconds:120}") long timeoutSeconds
    ) {
        HttpClient httpClient = HttpClient.create()
                // 整个响应最长等待时间（包括请求发送 + 服务器处理 + 响应返回）
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));

        String token = apiKey == null ? "" : apiKey.trim();
        if (token.isBlank()) {
            log.warn("[HttpClientConfig] app.llm.api-key 为空，调用 LLM 会返回 401。请设置 LLM_API_KEY 或 OPENAI_API_KEY。");
        }

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        if (!token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }

        return builder.build();
    }

    /**
     * 小模型（FastAPI 等）调用专用 WebClient
     * - 不预设 baseUrl，具体模块在调用时通过 uri(...) 传入
     * - 超时时间从 app.modules.http-timeout-seconds 读取，默认 10800 秒（3 小时）
     * - 响应内存缓冲从 app.modules.max-in-memory-mb 读取，默认 256MB
     */
    @Bean
    public WebClient moduleWebClient(
            @Value("${app.modules.http-timeout-seconds:10800}") long timeoutSeconds,
            @Value("${app.modules.max-in-memory-mb:256}") int maxInMemoryMb
    ) {
        long boundedTimeoutSeconds = Math.max(7200L, Math.min(timeoutSeconds, 86400L));
        if (boundedTimeoutSeconds != timeoutSeconds) {
            log.warn("[HttpClientConfig] app.modules.http-timeout-seconds={} 不在建议范围，已自动调整为 {} 秒。",
                    timeoutSeconds, boundedTimeoutSeconds);
        }

        int boundedMaxInMemoryMb = Math.max(16, Math.min(maxInMemoryMb, 1024));
        if (boundedMaxInMemoryMb != maxInMemoryMb) {
            log.warn("[HttpClientConfig] app.modules.max-in-memory-mb={} 超出建议范围，已自动调整为 {}MB。",
                    maxInMemoryMb, boundedMaxInMemoryMb);
        }

        int maxInMemoryBytes = boundedMaxInMemoryMb * 1024 * 1024;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemoryBytes))
                .build();

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(boundedTimeoutSeconds));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
