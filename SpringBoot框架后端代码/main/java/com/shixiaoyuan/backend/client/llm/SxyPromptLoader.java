package com.shixiaoyuan.backend.client.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 加载师小元的 System Prompt（Markdown），启动时读一次，后面直接复用。
 */
@Component
public class SxyPromptLoader {

    private final String systemPrompt;

    public SxyPromptLoader(
            // 可以通过配置覆盖路径，没配就用默认的 classpath:prompts/system_prompt_shixiaoyuan_v1.2.md
            @Value("${sxy.prompt.system-path:classpath:prompts/system_prompt_shixiaoyuan_v1.2.md}")
            Resource promptResource
    ) throws IOException {
        byte[] bytes = promptResource.getInputStream().readAllBytes();
        this.systemPrompt = new String(bytes, StandardCharsets.UTF_8);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
}
