// src/main/java/com/shixiaoyuan/backend/dto/request/ChatRequest.java
package com.shixiaoyuan.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class ChatRequest {

    /**
     * 老师在前端输入的问题
     */
    private String message;

    /**
     * 会话 ID（目前可为 null，预留给以后用数据库）
     */
    private Long sessionId;

    /**
     * 课堂行为分析 JSON
     * 使用 @JsonProperty 显式指定字段名，避免命名策略影响（如 SNAKE_CASE）
     */
    @JsonProperty("classData")
    private Map<String, Object> classData;
}
