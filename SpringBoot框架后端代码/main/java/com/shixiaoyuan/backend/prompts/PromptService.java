// service/prompts/PromptService.java
package com.shixiaoyuan.backend.prompts;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;


@Service
public class PromptService {

    public String getSystemPrompt(String name) {
        // 简单版：从 resources/prompts/ 读取
        String path = "prompts/" + name + ".md";
        try {
            var res = new ClassPathResource(path);
            byte[] bytes = res.getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "你是师小元课堂分析助手。"; // fallback
        }
    }
}
