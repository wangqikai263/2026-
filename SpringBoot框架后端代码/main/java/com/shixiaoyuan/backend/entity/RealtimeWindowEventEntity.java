package com.shixiaoyuan.backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "realtime_window_event",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_realtime_idempotent",
                        columnNames = {"device_id", "class_id", "window_index", "window_start_ts", "window_end_ts"}
                )
        },
        indexes = {
                @Index(name = "idx_realtime_end_at", columnList = "window_end_at"),
                @Index(name = "idx_realtime_class_end", columnList = "class_id,window_end_at"),
                @Index(name = "idx_realtime_device_end", columnList = "device_id,window_end_at")
        }
)
public class RealtimeWindowEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schema_version", nullable = false, length = 32)
    private String schemaVersion;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "class_id", nullable = false, length = 128)
    private String classId;

    @Column(name = "window_index", nullable = false)
    private Integer windowIndex;

    @Column(name = "window_start_ts", nullable = false, precision = 16, scale = 3)
    private BigDecimal windowStartTs;

    @Column(name = "window_end_ts", nullable = false, precision = 16, scale = 3)
    private BigDecimal windowEndTs;

    @Column(name = "window_start_at")
    private Instant windowStartAt;

    @Column(name = "window_end_at", nullable = false)
    private Instant windowEndAt;

    @Column(name = "elapsed_s", precision = 10, scale = 3)
    private BigDecimal elapsedS;

    @Column(name = "frame_count", nullable = false)
    private Integer frameCount;

    @Column(name = "det_frame_count", nullable = false)
    private Integer detFrameCount;

    @Column(name = "avg_conf", precision = 8, scale = 6)
    private BigDecimal avgConf;

    @Column(name = "dominant_behavior", length = 128)
    private String dominantBehavior;

    @Lob
    @Column(name = "behavior_counts_json")
    private String behaviorCountsJson;

    @Lob
    @Column(name = "behavior_rates_json")
    private String behaviorRatesJson;

    @Lob
    @Column(name = "detection_counts_json")
    private String detectionCountsJson;

    @Column(name = "backend_name", length = 32)
    private String backendName;

    @Column(name = "input_mode", length = 64)
    private String inputMode;

    @Column(name = "camera_source", length = 64)
    private String cameraSource;

    @Column(name = "camera_width")
    private Integer cameraWidth;

    @Column(name = "camera_height")
    private Integer cameraHeight;

    @Column(name = "camera_fps", precision = 10, scale = 3)
    private BigDecimal cameraFps;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }
    public Integer getWindowIndex() { return windowIndex; }
    public void setWindowIndex(Integer windowIndex) { this.windowIndex = windowIndex; }
    public BigDecimal getWindowStartTs() { return windowStartTs; }
    public void setWindowStartTs(BigDecimal windowStartTs) { this.windowStartTs = windowStartTs; }
    public BigDecimal getWindowEndTs() { return windowEndTs; }
    public void setWindowEndTs(BigDecimal windowEndTs) { this.windowEndTs = windowEndTs; }
    public Instant getWindowStartAt() { return windowStartAt; }
    public void setWindowStartAt(Instant windowStartAt) { this.windowStartAt = windowStartAt; }
    public Instant getWindowEndAt() { return windowEndAt; }
    public void setWindowEndAt(Instant windowEndAt) { this.windowEndAt = windowEndAt; }
    public BigDecimal getElapsedS() { return elapsedS; }
    public void setElapsedS(BigDecimal elapsedS) { this.elapsedS = elapsedS; }
    public Integer getFrameCount() { return frameCount; }
    public void setFrameCount(Integer frameCount) { this.frameCount = frameCount; }
    public Integer getDetFrameCount() { return detFrameCount; }
    public void setDetFrameCount(Integer detFrameCount) { this.detFrameCount = detFrameCount; }
    public BigDecimal getAvgConf() { return avgConf; }
    public void setAvgConf(BigDecimal avgConf) { this.avgConf = avgConf; }
    public String getDominantBehavior() { return dominantBehavior; }
    public void setDominantBehavior(String dominantBehavior) { this.dominantBehavior = dominantBehavior; }
    public String getBehaviorCountsJson() { return behaviorCountsJson; }
    public void setBehaviorCountsJson(String behaviorCountsJson) { this.behaviorCountsJson = behaviorCountsJson; }
    public String getBehaviorRatesJson() { return behaviorRatesJson; }
    public void setBehaviorRatesJson(String behaviorRatesJson) { this.behaviorRatesJson = behaviorRatesJson; }
    public String getDetectionCountsJson() { return detectionCountsJson; }
    public void setDetectionCountsJson(String detectionCountsJson) { this.detectionCountsJson = detectionCountsJson; }
    public String getBackendName() { return backendName; }
    public void setBackendName(String backendName) { this.backendName = backendName; }
    public String getInputMode() { return inputMode; }
    public void setInputMode(String inputMode) { this.inputMode = inputMode; }
    public String getCameraSource() { return cameraSource; }
    public void setCameraSource(String cameraSource) { this.cameraSource = cameraSource; }
    public Integer getCameraWidth() { return cameraWidth; }
    public void setCameraWidth(Integer cameraWidth) { this.cameraWidth = cameraWidth; }
    public Integer getCameraHeight() { return cameraHeight; }
    public void setCameraHeight(Integer cameraHeight) { this.cameraHeight = cameraHeight; }
    public BigDecimal getCameraFps() { return cameraFps; }
    public void setCameraFps(BigDecimal cameraFps) { this.cameraFps = cameraFps; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
