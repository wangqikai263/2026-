package com.shixiaoyuan.backend.dto.response;

public class ChatResponse {

    private String content;
    private boolean mock;
    private Long jobId;

    public ChatResponse() {
    }

    public ChatResponse(String content, boolean mock, Long jobId) {
        this.content = content;
        this.mock = mock;
        this.jobId = jobId;
    }

    // 一定要有 getter / setter，Jackson 才能把这些字段序列化成 JSON

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isMock() {
        return mock;
    }

    public void setMock(boolean mock) {
        this.mock = mock;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }
}
