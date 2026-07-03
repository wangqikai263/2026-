package com.shixiaoyuan.backend.controller.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixiaoyuan.backend.auth.AuthSessionUtil;
import com.shixiaoyuan.backend.entity.AnalysisSegmentEntity;
import com.shixiaoyuan.backend.entity.ClassSessionEntity;
import com.shixiaoyuan.backend.entity.JobEntity;
import com.shixiaoyuan.backend.entity.UploadHistoryEntity;
import com.shixiaoyuan.backend.repository.AnalysisSegmentRepository;
import com.shixiaoyuan.backend.repository.ClassSessionRepository;
import com.shixiaoyuan.backend.repository.JobRepository;
import com.shixiaoyuan.backend.repository.UploadHistoryRepository;
import com.shixiaoyuan.backend.service.job.JobService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
@Tag(name = "任务查询", description = "读取分析任务与会话数据")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobRepository jobRepository;
    private final ClassSessionRepository classSessionRepository;
    private final AnalysisSegmentRepository analysisSegmentRepository;
    private final UploadHistoryRepository uploadHistoryRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.jobs.dir}")
    private String jobsDir;

    @Value("${app.modules.behavior.result-dir:}")
    private String behaviorResultDir;

    public JobController(
            JobRepository jobRepository,
            ClassSessionRepository classSessionRepository,
            AnalysisSegmentRepository analysisSegmentRepository,
            UploadHistoryRepository uploadHistoryRepository,
            JobService jobService
    ) {
        this.jobRepository = jobRepository;
        this.classSessionRepository = classSessionRepository;
        this.analysisSegmentRepository = analysisSegmentRepository;
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.jobService = jobService;
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Map<String, Object>> getJob(@PathVariable("id") Long id, HttpSession session) {
        Long userId = AuthSessionUtil.requireUserId(session);
        if (!uploadHistoryRepository.existsByUserIdAndJobId(userId, id)) {
            return ResponseEntity.status(403).body(Map.of(
                    "jobId", id,
                    "message", "Forbidden"
            ));
        }
        return jobRepository.findById(id)
                .map(job -> {
                    JobEntity effectiveJob = tryRepairIncompleteJob(userId, job);
                    return ResponseEntity.ok(buildJobDetail(effectiveJob));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable("id") Long id, HttpSession session) {
        Long userId = AuthSessionUtil.requireUserId(session);
        if (!uploadHistoryRepository.existsByUserIdAndJobId(userId, id)) {
            return ResponseEntity.status(403).body(Map.of(
                    "jobId", id,
                    "message", "Forbidden"
            ));
        }
        boolean cancelled = jobService.cancelPendingJob(id);
        if (cancelled) {
            return ResponseEntity.ok(Map.of("jobId", id, "status", "CANCELLED"));
        }
        return ResponseEntity.status(409).body(Map.of(
                "jobId", id,
                "status", "NOT_CANCELLABLE",
                "message", "Only PENDING jobs can be cancelled."
        ));
    }

    @GetMapping("/jobs/{id}/result")
    public ResponseEntity<?> getJobResult(@PathVariable("id") Long id, HttpSession session) {
        Long userId = AuthSessionUtil.requireUserId(session);
        if (!uploadHistoryRepository.existsByUserIdAndJobId(userId, id)) {
            return ResponseEntity.status(403).body(Map.of(
                    "jobId", id,
                    "message", "Forbidden"
            ));
        }
        return jobRepository.findById(id)
                .map(job -> {
                    JobEntity effectiveJob = tryRepairIncompleteJob(userId, job);
                    Path resultPath = Path.of(jobsDir, String.valueOf(id), "result.json");
                    String snapshotJson = readJsonQuietly(resultPath);
                    if (snapshotJson != null) {
                        String snapshotError = extractAnalysisError(snapshotJson);
                        if (snapshotError == null) {
                            if (!"SUCCESS".equalsIgnoreCase(effectiveJob.getStatus())) {
                                JobEntity repaired = persistRecoveredJobSummary(
                                        effectiveJob,
                                        snapshotJson,
                                        resultPath.toAbsolutePath().toString()
                                );
                                if (repaired != null) {
                                    effectiveJob = repaired;
                                }
                            }
                            return ResponseEntity.ok(snapshotJson);
                        }
                    }

                    String fallbackPath = resolveResultPathFromSummary(effectiveJob.getResult());
                    if (fallbackPath != null) {
                        try {
                            String json = readJsonIfExists(Path.of(fallbackPath));
                            if (json != null) {
                                persistResultSnapshotQuietly(resultPath, json);
                                String fallbackError = extractAnalysisError(json);
                                if (fallbackError != null) {
                                    return failedResultResponse(id, effectiveJob.getStatus(), fallbackError);
                                }
                                if (!"SUCCESS".equalsIgnoreCase(effectiveJob.getStatus())) {
                                    JobEntity repaired = persistRecoveredJobSummary(effectiveJob, json, fallbackPath);
                                    if (repaired != null) {
                                        effectiveJob = repaired;
                                    }
                                }
                                return ResponseEntity.ok(json);
                            }
                        } catch (Exception e) {
                            return ResponseEntity.status(500).body(Map.of(
                                    "jobId", id,
                                    "message", "Failed to read fallback result file."
                            ));
                        }
                    }
                    Path legacyPath = resolveLegacyBehaviorResultPath(userId, id, effectiveJob);
                    if (legacyPath != null) {
                        try {
                            String json = readJsonIfExists(legacyPath);
                            if (json != null) {
                                persistResultSnapshotQuietly(resultPath, json);
                                String legacyError = extractAnalysisError(json);
                                if (legacyError != null) {
                                    return failedResultResponse(id, effectiveJob.getStatus(), legacyError);
                                }
                                if (!"SUCCESS".equalsIgnoreCase(effectiveJob.getStatus())) {
                                    JobEntity repaired = persistRecoveredJobSummary(
                                            effectiveJob,
                                            json,
                                            legacyPath.toAbsolutePath().toString()
                                    );
                                    if (repaired != null) {
                                        effectiveJob = repaired;
                                    }
                                }
                                return ResponseEntity.ok(json);
                            }
                        } catch (Exception e) {
                            return ResponseEntity.status(500).body(Map.of(
                                    "jobId", id,
                                    "message", "Failed to read legacy result file."
                            ));
                        }
                    }
                    if (snapshotJson != null) {
                        String snapshotError = extractAnalysisError(snapshotJson);
                        if (snapshotError != null) {
                            return failedResultResponse(id, effectiveJob.getStatus(), snapshotError);
                        }
                        if (!"SUCCESS".equalsIgnoreCase(effectiveJob.getStatus())) {
                            JobEntity repaired = persistRecoveredJobSummary(
                                    effectiveJob,
                                    snapshotJson,
                                    resultPath.toAbsolutePath().toString()
                            );
                            if (repaired != null) {
                                effectiveJob = repaired;
                            }
                        }
                        return ResponseEntity.ok(snapshotJson);
                    }
                    if (!"SUCCESS".equalsIgnoreCase(effectiveJob.getStatus())) {
                        return ResponseEntity.status(409).body(Map.of(
                                "jobId", id,
                                "status", effectiveJob.getStatus(),
                                "message", "Job not finished."
                        ));
                    }
                    return ResponseEntity.status(404).body(Map.of(
                            "jobId", id,
                            "status", effectiveJob.getStatus(),
                            "message", "Result file not found."
                    ));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "jobId", id,
                        "message", "Result file not found."
                )));
    }

    private Map<String, Object> buildJobDetail(JobEntity job) {
        ClassSessionEntity session = null;
        List<AnalysisSegmentEntity> segments = List.of();
        if (job.getSessionId() != null) {
            session = classSessionRepository.findById(job.getSessionId()).orElse(null);
            segments = analysisSegmentRepository.findBySessionIdOrderByStartSAsc(job.getSessionId());
        }
        return Map.of(
                "job", job,
                "session", session,
                "segments", segments
        );
    }

    private String readJsonIfExists(Path path) throws Exception {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            return null;
        }
        return Files.readString(path);
    }

    private String readJsonQuietly(Path path) {
        try {
            return readJsonIfExists(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void persistResultSnapshotQuietly(Path targetPath, String json) {
        if (targetPath == null || json == null) {
            return;
        }
        try {
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(targetPath, json);
        } catch (Exception e) {
            log.warn("Failed to persist repaired result snapshot: {}", targetPath, e);
        }
    }

    private JobEntity tryRepairIncompleteJob(Long userId, JobEntity job) {
        if (job == null) {
            return null;
        }
        String status = nonBlankString(job.getStatus());
        if ("SUCCESS".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
            return job;
        }
        Long jobId = job.getId();
        Path resultPath = Path.of(jobsDir, String.valueOf(jobId), "result.json");

        String snapshotJson = readJsonQuietly(resultPath);
        if (isSuccessfulAnalysisJson(snapshotJson)) {
            JobEntity updated = persistRecoveredJobSummary(job, snapshotJson, resultPath.toAbsolutePath().toString());
            if (updated != null) {
                return updated;
            }
        }

        Path legacyPath = resolveLegacyBehaviorResultPath(userId, jobId, job);
        if (legacyPath != null) {
            String legacyJson = readJsonQuietly(legacyPath);
            if (isSuccessfulAnalysisJson(legacyJson)) {
                persistResultSnapshotQuietly(resultPath, legacyJson);
                JobEntity updated = persistRecoveredJobSummary(job, legacyJson, legacyPath.toAbsolutePath().toString());
                if (updated != null) {
                    log.warn("Auto repaired incomplete job {} using legacy result {}", jobId, legacyPath);
                    return updated;
                }
            }
        }

        String fallbackPath = resolveResultPathFromSummary(job.getResult());
        if (fallbackPath != null) {
            String fallbackJson = readJsonQuietly(Path.of(fallbackPath));
            if (isSuccessfulAnalysisJson(fallbackJson)) {
                persistResultSnapshotQuietly(resultPath, fallbackJson);
                JobEntity updated = persistRecoveredJobSummary(job, fallbackJson, fallbackPath);
                if (updated != null) {
                    log.warn("Auto repaired incomplete job {} using fallback result {}", jobId, fallbackPath);
                    return updated;
                }
            }
        }
        return job;
    }

    private JobEntity persistRecoveredJobSummary(JobEntity job, String analysisJson, String sourceJsonPath) {
        if (job == null || !isSuccessfulAnalysisJson(analysisJson)) {
            return null;
        }
        try {
            Map<String, Object> analysis = objectMapper.readValue(analysisJson, new TypeReference<>() {});
            Path resultPath = Path.of(jobsDir, String.valueOf(job.getId()), "result.json");
            Map<String, Object> summary = new HashMap<>();
            summary.put("result_path", resultPath.toAbsolutePath().toString());
            summary.put("source_json_path", nonBlankString(sourceJsonPath));
            summary.put("schema_version", analysis.get("schema_version"));
            summary.put("module_name", analysis.get("module_name"));
            summary.put("summary", analysis.get("summary"));
            String resultSummaryJson = objectMapper.writeValueAsString(summary);
            return jobService.updateJobResult(job.getId(), "SUCCESS", resultSummaryJson);
        } catch (Exception e) {
            log.warn("Failed to persist auto-repair summary. jobId={}", job.getId(), e);
            return null;
        }
    }

    private boolean isSuccessfulAnalysisJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return nonBlankString(map.get("error")) == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String extractAnalysisError(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return nonBlankString(map.get("error"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> failedResultResponse(Long jobId, String status, String message) {
        String safeStatus = nonBlankString(status);
        if (safeStatus == null) {
            safeStatus = "FAILED";
        }
        String safeMessage = nonBlankString(message);
        if (safeMessage == null) {
            safeMessage = "分析失败，结果不可用。";
        }
        return ResponseEntity.status(409).body(Map.of(
                "jobId", jobId,
                "status", safeStatus,
                "message", safeMessage
        ));
    }


    private String resolveResultPathFromSummary(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> summary = objectMapper.readValue(resultJson, new TypeReference<>() {});
            String resultPath = nonBlankString(summary.get("result_path"));
            if (resultPath != null) {
                return resultPath;
            }
            String sourcePath = nonBlankString(summary.get("source_json_path"));
            if (sourcePath != null) {
                return sourcePath;
            }
            Object metadata = summary.get("metadata");
            if (metadata instanceof Map<?, ?> metadataMap) {
                return nonBlankString(metadataMap.get("json_path"));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Path resolveLegacyBehaviorResultPath(Long userId, Long jobId, JobEntity job) {
        String videoStem = extractVideoStem(job.getPayload());
        if (videoStem == null) {
            videoStem = uploadHistoryRepository.findByUserIdAndJobId(userId, jobId)
                    .map(UploadHistoryEntity::getFileName)
                    .map(this::toFileStem)
                    .orElse(null);
        }
        if (videoStem == null) {
            return null;
        }
        String videoPath = extractVideoPath(job.getPayload());
        return findLatestLegacyResultByStem(videoStem, buildLegacySearchDirs(videoPath));
    }

    private String extractVideoPath(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            return nonBlankString(payload.get("video_path"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractVideoStem(String payloadJson) {
        String videoPath = extractVideoPath(payloadJson);
        if (videoPath == null) {
            return null;
        }
        try {
            Path fileName = Path.of(videoPath).getFileName();
            if (fileName == null) {
                return null;
            }
            return toFileStem(fileName.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toFileStem(String fileName) {
        if (fileName == null) {
            return null;
        }
        String normalized = fileName.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        int dot = normalized.lastIndexOf('.');
        return dot > 0 ? normalized.substring(0, dot) : normalized;
    }

    private List<Path> buildLegacySearchDirs(String videoPath) {
        Set<Path> dirs = new LinkedHashSet<>();
        addExistingDir(dirs, behaviorResultDir);
        addExistingDir(dirs, "./runs/json");
        addExistingDir(dirs, "../behaviorRecognition/runs/json");
        if (videoPath != null) {
            try {
                Path video = Path.of(videoPath).toAbsolutePath().normalize();
                Path uploadsDir = video.getParent();
                Path dataDir = uploadsDir == null ? null : uploadsDir.getParent();
                Path backendDir = dataDir == null ? null : dataDir.getParent();
                Path workspaceDir = backendDir == null ? null : backendDir.getParent();
                if (workspaceDir != null) {
                    addExistingDir(dirs, workspaceDir.resolve("behaviorRecognition").resolve("runs").resolve("json").toString());
                }
            } catch (Exception ignored) {
                // ignore invalid legacy path
            }
        }
        return List.copyOf(dirs);
    }

    private void addExistingDir(Set<Path> dirs, String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.isEmpty()) {
            return;
        }
        try {
            Path dir = Path.of(value).toAbsolutePath().normalize();
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                dirs.add(dir);
            }
        } catch (Exception ignored) {
            // ignore invalid path
        }
    }

    private Path findLatestLegacyResultByStem(String stem, List<Path> dirs) {
        if (stem == null || stem.isBlank() || dirs == null || dirs.isEmpty()) {
            return null;
        }
        String prefix = stem + "_behavior_";
        Path best = null;
        long bestMtime = Long.MIN_VALUE;
        for (Path dir : dirs) {
            try (Stream<Path> stream = Files.list(dir)) {
                Path candidate = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.startsWith(prefix) && name.endsWith(".json");
                        })
                        .max(Comparator.comparingLong(this::safeMtime))
                        .orElse(null);
                if (candidate != null) {
                    long mtime = safeMtime(candidate);
                    if (mtime > bestMtime) {
                        bestMtime = mtime;
                        best = candidate;
                    }
                }
            } catch (Exception ignored) {
                // ignore this directory and continue
            }
        }
        return best;
    }

    private long safeMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String nonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
