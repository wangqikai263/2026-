package com.shixiaoyuan.backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "analysis_segment",
        indexes = {
                @Index(name = "idx_segment_session_time", columnList = "session_id,start_s")
        }
)
public class AnalysisSegmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属课堂会话ID */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    // 如果你想做关联对象，可以改为：
    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "session_id", nullable = false)
    // private ClassSessionEntity session;

    /** 分段开始时间（秒） */
    @Column(name = "start_s", nullable = false)
    private Integer startS;

    /** 分段结束时间（秒） */
    @Column(name = "end_s", nullable = false)
    private Integer endS;

    /** 专注度 0~1 */
    @Column(name = "focus_rate", precision = 5, scale = 4)
    private BigDecimal focusRate;

    /** 手机使用率 0~1 */
    @Column(name = "phone_rate", precision = 5, scale = 4)
    private BigDecimal phoneRate;

    /** 举手次数 */
    @Column(name = "hand_raise_cnt")
    private Integer handRaiseCnt;

    /** 教师移动频率（次/分钟） */
    @Column(name = "teacher_move_freq_per_min", precision = 6, scale = 2)
    private BigDecimal teacherMoveFreqPerMin;

    /** 稳定性（标准差） */
    @Column(name = "stability_std", precision = 6, scale = 4)
    private BigDecimal stabilityStd;

    /** 本段结果综合置信度 0~1 */
    @Column(name = "conf", precision = 5, scale = 4)
    private BigDecimal conf;

    /** 该分段对应的视频片段URL（可选） */
    @Column(name = "clip_url", length = 512)
    private String clipUrl;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // ====== getter / setter ======

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public Integer getStartS() { return startS; }
    public void setStartS(Integer startS) { this.startS = startS; }

    public Integer getEndS() { return endS; }
    public void setEndS(Integer endS) { this.endS = endS; }

    public BigDecimal getFocusRate() { return focusRate; }
    public void setFocusRate(BigDecimal focusRate) { this.focusRate = focusRate; }

    public BigDecimal getPhoneRate() { return phoneRate; }
    public void setPhoneRate(BigDecimal phoneRate) { this.phoneRate = phoneRate; }

    public Integer getHandRaiseCnt() { return handRaiseCnt; }
    public void setHandRaiseCnt(Integer handRaiseCnt) { this.handRaiseCnt = handRaiseCnt; }

    public BigDecimal getTeacherMoveFreqPerMin() { return teacherMoveFreqPerMin; }
    public void setTeacherMoveFreqPerMin(BigDecimal teacherMoveFreqPerMin) { this.teacherMoveFreqPerMin = teacherMoveFreqPerMin; }

    public BigDecimal getStabilityStd() { return stabilityStd; }
    public void setStabilityStd(BigDecimal stabilityStd) { this.stabilityStd = stabilityStd; }

    public BigDecimal getConf() { return conf; }
    public void setConf(BigDecimal conf) { this.conf = conf; }

    public String getClipUrl() { return clipUrl; }
    public void setClipUrl(String clipUrl) { this.clipUrl = clipUrl; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

