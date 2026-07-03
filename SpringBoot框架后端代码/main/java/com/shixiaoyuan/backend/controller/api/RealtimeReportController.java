package com.shixiaoyuan.backend.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixiaoyuan.backend.auth.AuthSessionUtil;
import com.shixiaoyuan.backend.dto.request.RealtimeWindowReportRequest;
import com.shixiaoyuan.backend.entity.ClassSessionEntity;
import com.shixiaoyuan.backend.entity.JobEntity;
import com.shixiaoyuan.backend.entity.RealtimeWindowEventEntity;
import com.shixiaoyuan.backend.entity.UploadHistoryEntity;
import com.shixiaoyuan.backend.repository.ClassSessionRepository;
import com.shixiaoyuan.backend.repository.UploadHistoryRepository;
import com.shixiaoyuan.backend.service.job.JobService;
import com.shixiaoyuan.backend.service.realtime.RealtimeWindowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/realtime")
@Tag(name = "实时上报接口", description = "接收板端3秒行为窗口并提供实时查询")
public class RealtimeReportController {

    private final RealtimeWindowService realtimeWindowService;
    private final ClassSessionRepository classSessionRepository;
    private final UploadHistoryRepository uploadHistoryRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.realtime.enabled:true}")
    private boolean realtimeEnabled;

    @Value("${app.jobs.dir}")
    private String jobsDir;

    @Value("${app.realtime.auth.device-token:}")
    private String expectedDeviceToken;

    @Value("${app.realtime.ingest.max-payload-bytes:262144}")
    private int maxPayloadBytes;

    public RealtimeReportController(
            RealtimeWindowService realtimeWindowService,
            ClassSessionRepository classSessionRepository,
            UploadHistoryRepository uploadHistoryRepository,
            JobService jobService
    ) {
        this.realtimeWindowService = realtimeWindowService;
        this.classSessionRepository = classSessionRepository;
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.jobService = jobService;
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> report(
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
            @RequestBody RealtimeWindowReportRequest request,
            HttpServletRequest servletRequest
    ) {
        if (!realtimeEnabled) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "realtime_disabled", "Realtime endpoint is disabled.");
        }
        String expected = trim(expectedDeviceToken);
        if (expected != null && !expected.equals(trim(deviceToken))) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_device_token", "Device token invalid.");
        }

        final String raw;
        try {
            raw = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_json", "Invalid JSON payload.");
        }

        if (raw.getBytes().length > Math.max(1024, maxPayloadBytes)) {
            return error(HttpStatusCode.valueOf(413), "payload_too_large", "Payload too large.");
        }

        try {
            RealtimeWindowEventEntity saved = realtimeWindowService.ingest(
                    request,
                    extractClientIp(servletRequest),
                    servletRequest.getHeader("User-Agent"),
                    raw
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("eventId", saved.getId());
            body.put("deviceId", saved.getDeviceId());
            body.put("classId", saved.getClassId());
            body.put("windowIndex", saved.getWindowIndex());
            body.put("serverTime", java.time.Instant.now().toString());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_payload", e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "ingest_failed", "Failed to ingest realtime window.");
        }
    }

    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live(
            @RequestParam(value = "classId", required = false) String classId,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "lookbackSec", required = false, defaultValue = "300") Integer lookbackSec,
            @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit
    ) {
        if (!realtimeEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "message", "Realtime endpoint is disabled."));
        }
        return ResponseEntity.ok(realtimeWindowService.getLive(classId, deviceId, lookbackSec, limit));
    }

    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> trend(
            @RequestParam(value = "classId", required = false) String classId,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "minutes", required = false, defaultValue = "30") Integer minutes,
            @RequestParam(value = "bucketSec", required = false, defaultValue = "3") Integer bucketSec
    ) {
        if (!realtimeEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "message", "Realtime endpoint is disabled."));
        }
        return ResponseEntity.ok(realtimeWindowService.getTrend(classId, deviceId, minutes, bucketSec));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "enabled", realtimeEnabled,
                "serverTime", java.time.Instant.now().toString()
        );
    }

    @PostMapping("/snapshot/finalize")
    public ResponseEntity<Map<String, Object>> finalizeSnapshot(
            @RequestBody Map<String, Object> request,
            HttpSession session
    ) {
        if (!realtimeEnabled) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "realtime_disabled", "Realtime endpoint is disabled.");
        }

        Long userId = AuthSessionUtil.requireUserId(session);
        String classId = trim(stringValue(request.get("classId")));
        if (classId == null) {
            classId = "demo-001";
        }

        Object classData = request.get("classData");
        if (classData == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_payload", "classData is required.");
        }

        String fileName = trim(stringValue(request.get("fileName")));
        if (fileName == null) {
            fileName = "realtime_" + Instant.now().toEpochMilli() + ".json";
        }
        String trackingMode = trim(stringValue(request.get("trackingMode")));
        if (trackingMode == null) {
            trackingMode = "realtime";
        }
        Integer durationMin = intValue(request.get("durationMin"));

        try {
            String snapshotJson = objectMapper.writeValueAsString(classData);
            ClassSessionEntity classSession = findOrCreateClassSession(classId, durationMin);

            Map<String, Object> payload = new HashMap<>();
            payload.put("source", "realtime");
            payload.put("class_id", classId);
            payload.put("file_name", fileName);
            payload.put("tracking_mode", trackingMode);
            payload.put("finalized_at", Instant.now().toString());

            JobEntity job = jobService.createRealtimeSnapshotJob(
                    classSession.getId(),
                    objectMapper.writeValueAsString(payload)
            );

            Path jobRoot = Path.of(jobsDir, String.valueOf(job.getId()));
            Files.createDirectories(jobRoot);
            Path resultFile = jobRoot.resolve("result.json");
            Files.writeString(resultFile, snapshotJson, StandardCharsets.UTF_8);

            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("source", "realtime_snapshot");
            resultSummary.put("result_path", resultFile.toAbsolutePath().toString());
            resultSummary.put("saved_at", Instant.now().toString());
            resultSummary.put("file_name", fileName);
            jobService.updateJobResult(job.getId(), "SUCCESS", objectMapper.writeValueAsString(resultSummary));

            saveUploadHistory(userId, classSession.getId(), job.getId(), fileName, trackingMode, snapshotJson);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("sourceType", "history");
            body.put("jobId", job.getId());
            body.put("sessionId", classSession.getId());
            body.put("fileName", fileName);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "finalize_failed", "Failed to persist realtime snapshot.");
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatusCode status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = trim(request.getHeader("X-Forwarded-For"));
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff;
        }
        return trim(request.getRemoteAddr());
    }

    private String trim(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private String stringValue(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Integer intValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private ClassSessionEntity findOrCreateClassSession(String classId, Integer durationMin) {
        ClassSessionEntity session = classSessionRepository.findByClassId(classId)
                .orElseGet(() -> {
                    ClassSessionEntity created = new ClassSessionEntity();
                    created.setClassId(classId);
                    created.setDurationMin(durationMin == null ? 40 : durationMin);
                    return classSessionRepository.save(created);
                });
        if (session.getDurationMin() == null && durationMin != null) {
            session.setDurationMin(durationMin);
            session = classSessionRepository.save(session);
        }
        return session;
    }

    private void saveUploadHistory(Long userId, Long sessionId, Long jobId, String fileName, String trackingMode, String snapshotJson) {
        UploadHistoryEntity history = new UploadHistoryEntity();
        history.setUserId(userId);
        history.setSessionId(sessionId);
        history.setJobId(jobId);
        history.setFileName(StringUtils.hasText(fileName) ? fileName : ("realtime_" + jobId + ".json"));
        history.setFileUrl(null);
        history.setTrackingMode(StringUtils.hasText(trackingMode) ? trackingMode : "realtime");
        history.setFileSizeBytes(snapshotJson == null ? 0L : (long) snapshotJson.getBytes(StandardCharsets.UTF_8).length);
        uploadHistoryRepository.save(history);
    }
}
