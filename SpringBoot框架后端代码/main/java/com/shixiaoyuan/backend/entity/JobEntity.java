package com.shixiaoyuan.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "job")
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务类型：例如 "VIDEO_ANALYSIS" / "AGGREGATION" 等 */
    @Column(nullable = false, length = 64)
    private String type;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED */
    @Column(nullable = false, length = 32)
    private String status;

    /** 进度百分比：0-100 */
    @Column
    private Integer progress;

    /** 关联的课堂会话ID（可以为 null） */
    @Column(name = "session_id")
    private Long sessionId;

    /** 调用小模型/聚合的入参快照（JSON 字符串） */
    @Lob
    @Column
    private String payload;

    /** 任务的结果快照（JSON 字符串） */
    @Lob
    @Column
    private String result;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ====== getter / setter ======

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
