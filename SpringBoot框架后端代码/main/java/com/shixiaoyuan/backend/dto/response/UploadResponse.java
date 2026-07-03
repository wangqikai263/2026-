// dto/response/UploadResponse.java
package com.shixiaoyuan.backend.dto.response;

import java.util.Map;


public class UploadResponse {
    private boolean ok;
    private Long sessionId;     // 先可以返回 null，将来关联课堂会话
    private Long jobId;         // 先 null，将来关联异步任务
    private String fileUrl;     // /uploads/xxx.mp4
    private Map<String,Object> analysis; // 占位 analysis

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

    public Map<String, Object> getAnalysis() { return analysis; }
    public void setAnalysis(Map<String, Object> analysis) { this.analysis = analysis; }
}

