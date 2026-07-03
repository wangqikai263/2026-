// src/main/java/com/shixiaoyuan/backend/config/WebClientConfig.java
package com.shixiaoyuan.backend.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * 统一配置 WebClient 的缓冲上限及超时，解决大文件处理和超时问题。
 *
 * Spring Boot 默认会自动注入一个 WebClient.Builder，
 * 我们在这里显式定义一个 builder bean，设置更大的 maxInMemorySize，
 * 以及更长的连接超时、读取超时，以支持大文件处理（如视频分析）。
 * 所有通过 WebClient.Builder 注入的 WebClient（包括 LlmClient / 各个小模型 client）
 * 都会自动使用这个配置。
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        // 缓冲上限：设为 100MB（足够大文件 JSON 返回）
        int maxInMemorySize = 100 * 1024 * 1024; // 100MB

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemorySize)
                )
                .build();

        // 配置 Netty HttpClient：增加超时时间以支持大文件处理
        // 连接超时：60 秒
        // 读取超时：20 分钟（1200 秒，足以处理大视频）
        // 写入超时：20 分钟
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000)  // connect timeout: 60s
                .responseTimeout(java.time.Duration.ofMinutes(20))     // read timeout: 20m
                .doOnConnected(conn -> 
                        conn.addHandlerLast(new ReadTimeoutHandler(20, TimeUnit.MINUTES))
                                .addHandlerLast(new WriteTimeoutHandler(20, TimeUnit.MINUTES))
                );

        return WebClient.builder()
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
