//前后端契约（Swagger / OpenAPI）
package com.shixiaoyuan.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI apiInfo() {
        return new OpenAPI()
                .info(new Info()
                        .title("师小元 · 课堂分析后端 API")
                        .version("v0.1.0")
                        .description("前后端 & A组 小模型 统一契约"));
    }
}
