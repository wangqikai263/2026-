package com.shixiaoyuan.backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 追踪摘要实体（v0.2 新增）
 * 每条记录对应一个 track_id 的生命周期汇总。
 */
@Entity
@Table(
        name = "track_summary",
        indexes = {
                @Index(name = "idx_track_session", columnList = "session_id"),
                @Index(name = "idx_track_session_tid", columnList = "session_id,track_id")
        }
)
public class TrackSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** BoxMOT 分配的追踪 ID */
    @Column(name = "track_id", nullable = false)
    private Integer trackId;

    /** 主类别名 */
    @Column(name = "cls_name", nullable = false, length = 64)
    private String clsName;

    /** 主类别索引 */
    @Column(name = "cls_id")
    private Integer clsId;

    @Column(name = "first_frame")
    private Integer firstFrame;

    @Column(name = "last_frame")
    private Integer lastFrame;

    @Column(name = "first_ts_s", precision = 10, scale = 3)
    private BigDecimal firstTsS;

    @Column(name = "last_ts_s", precision = 10, scale = 3)
    private BigDecimal lastTsS;

    @Column(name = "duration_s", precision = 10, scale = 3)
    private BigDecimal durationS;

    @Column(name = "total_frames")
    private Integer totalFrames;

    @Column(name = "avg_conf", precision = 5, scale = 4)
    private BigDecimal avgConf;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public Integer getTrackId() { return trackId; }
    public void setTrackId(Integer trackId) { this.trackId = trackId; }

    public String getClsName() { return clsName; }
    public void setClsName(String clsName) { this.clsName = clsName; }

    public Integer getClsId() { return clsId; }
    public void setClsId(Integer clsId) { this.clsId = clsId; }

    public Integer getFirstFrame() { return firstFrame; }
    public void setFirstFrame(Integer firstFrame) { this.firstFrame = firstFrame; }

    public Integer getLastFrame() { return lastFrame; }
    public void setLastFrame(Integer lastFrame) { this.lastFrame = lastFrame; }

    public BigDecimal getFirstTsS() { return firstTsS; }
    public void setFirstTsS(BigDecimal firstTsS) { this.firstTsS = firstTsS; }

    public BigDecimal getLastTsS() { return lastTsS; }
    public void setLastTsS(BigDecimal lastTsS) { this.lastTsS = lastTsS; }

    public BigDecimal getDurationS() { return durationS; }
    public void setDurationS(BigDecimal durationS) { this.durationS = durationS; }

    public Integer getTotalFrames() { return totalFrames; }
    public void setTotalFrames(Integer totalFrames) { this.totalFrames = totalFrames; }

    public BigDecimal getAvgConf() { return avgConf; }
    public void setAvgConf(BigDecimal avgConf) { this.avgConf = avgConf; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
