package com.shixiaoyuan.backend.service.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixiaoyuan.backend.client.module.BehaviorModuleClient;
import com.shixiaoyuan.backend.entity.JobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class VideoAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisWorker.class);

    private final JobService jobService;
    private final BehaviorModuleClient behaviorModuleClient;
    private final AnalysisResultService analysisResultService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Value("${app.jobs.dir}")
    private String jobsDir;

    @Value("${app.modules.behavior.result-dir:}")
    private String behaviorResultDir;

    @Value("${app.modules.behavior.recovery.wait-seconds:7200}")
    private long recoveryWaitSeconds;

    @Value("${app.modules.behavior.recovery.poll-interval-seconds:10}")
    private long recoveryPollIntervalSeconds;

    @Value("${app.jobs.worker.enabled:true}")
    private boolean workerEnabled;

    @Value("${app.jobs.worker.stuck-reset-minutes:10}")
    private long stuckResetMinutes;

    public VideoAnalysisWorker(
            JobService jobService,
            BehaviorModuleClient behaviorModuleClient,
            AnalysisResultService analysisResultService
    ) {
        this.jobService = jobService;
        this.behaviorModuleClient = behaviorModuleClient;
        this.analysisResultService = analysisResultService;
    }

    @Scheduled(fixedDelay = 2000)
    public void runOnce() {
        if (!workerEnabled) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            // 自动续跑：将长时间 RUNNING 的任务回滚为 PENDING
            long boundedStuckResetMinutes = Math.max(10L, stuckResetMinutes);
            jobService.resetStuckRunningJobs(Duration.ofMinutes(boundedStuckResetMinutes));

            Optional<JobEntity> claimed = jobService.claimNextPendingJob();
            if (claimed.isEmpty()) {
                return;
            }
            JobEntity job = claimed.get();
            processJob(job);
        } finally {
            busy.set(false);
        }
    }

    private void processJob(JobEntity job) {
        Map<String, Object> payload = null;
        try {
            try {
                payload = objectMapper.readValue(job.getPayload(), new TypeReference<>() {});
            } catch (Exception e) {
                Map<String, Object> invalidPayload = Map.of(
                        "schema_version", "0.1.0",
                        "module_name", "behavior_device_usage",
                        "error", "invalid_payload"
                );
                String resultPath = writeJobResult(job.getId(), invalidPayload, Map.of("raw_payload", job.getPayload()));
                safeMarkJobFailed(job.getId(), resultPath, invalidPayload);
                return;
            }

            String videoPath = stringValue(payload.get("video_path"));
            String classId = stringValue(payload.get("class_id"));
            Integer durationMin = intValue(payload.get("duration_min"));
            String trackingMode = stringValue(payload.getOrDefault("tracking_mode", "fast"));

            Map<String, Object> analysis;
            try {
                analysis = behaviorModuleClient
                        .analyze(videoPath, classId, durationMin, trackingMode, job.getId())
                        .block();
                if (analysis == null) {
                    analysis = Map.of(
                            "schema_version", "0.1.0",
                            "module_name", "behavior_device_usage",
                            "error", "行为小模型返回为空"
                    );
                }
            } catch (Exception e) {
                String reason = describeThrowable(e);
                log.warn("Behavior module analyze failed. jobId={}, videoPath={}, reason={}",
                        job.getId(), videoPath, reason, e);
                analysis = Map.of(
                        "schema_version", "0.1.0",
                        "module_name", "behavior_device_usage",
                        "error", "调用行为小模型失败: " + reason
                );
            }
            analysis = hydrateAnalysisFromSourcePath(job.getId(), analysis);
            RecoveryResult recovery = tryRecoverAnalysisFromLegacyResult(analysis, payload);
            if (recovery == null && hasAnalysisError(analysis)) {
                recovery = tryRecoverAnalysisFromProgress(job.getId(), payload);
            }
            if (recovery != null) {
                analysis = recovery.analysis;
            }
            String recoveredSourceJsonPath = recovery == null ? null : recovery.sourceJsonPath;

            Long sessionId = job.getSessionId();
            if (sessionId != null) {
                try {
                    analysisResultService.persistSegments(sessionId, analysis);
                    analysisResultService.persistTracks(sessionId, analysis);
                } catch (Exception persistError) {
                    // 分段/轨迹入库失败不应阻断主任务收尾，保底先让结果可见
                    log.warn("Failed to persist analysis aggregates. jobId={}, sessionId={}, reason={}",
                            job.getId(), sessionId, describeThrowable(persistError), persistError);
                }
            }
            try {
                jobService.updateJobProgress(job.getId(), 90);
            } catch (Exception progressError) {
                log.warn("Failed to update job progress to 90. jobId={}, reason={}",
                        job.getId(), describeThrowable(progressError));
            }

            // 写入结果文件
            String resultPath = writeJobResult(job.getId(), analysis, payload);
            String sourceJsonPath = firstNonBlank(extractSourceJsonPath(analysis), recoveredSourceJsonPath);
            boolean hasAnalysisError = hasAnalysisError(analysis);
            boolean hasReadableResult = hasReadableFile(resultPath) || hasReadableFile(sourceJsonPath);
            String status = hasAnalysisError ? "FAILED" : (hasReadableResult ? "SUCCESS" : "FAILED");

            Map<String, Object> resultSummary = new HashMap<>();
            resultSummary.put("result_path", resultPath);
            resultSummary.put("source_json_path", sourceJsonPath);
            resultSummary.put("schema_version", analysis.get("schema_version"));
            resultSummary.put("module_name", analysis.get("module_name"));
            resultSummary.put("summary", analysis.get("summary"));
            if (hasAnalysisError) {
                resultSummary.put("error", nonBlankString(analysis.get("error")));
            } else if (!hasReadableResult) {
                resultSummary.put("error", "result_file_missing");
            }

            try {
                String resultJson = objectMapper.writeValueAsString(resultSummary);
                jobService.updateJobResult(job.getId(), status, resultJson);
            } catch (Exception e) {
                log.warn("Failed to serialize/update job result summary. jobId={}", job.getId(), e);
                safeMarkJobFailed(job.getId(), resultPath, Map.of(
                        "schema_version", "0.1.0",
                        "module_name", "behavior_device_usage",
                        "error", "write_result_failed"
                ));
            }
        } catch (Exception unexpected) {
            String reason = describeThrowable(unexpected);
            log.error("Unhandled job processing failure. jobId={}, reason={}", job.getId(), reason, unexpected);
            Map<String, Object> failureAnalysis = Map.of(
                    "schema_version", "0.1.0",
                    "module_name", "behavior_device_usage",
                    "error", "分析任务内部异常: " + reason
            );
            String resultPath = writeJobResult(
                    job.getId(),
                    failureAnalysis,
                    payload == null ? Map.of("job_id", job.getId()) : payload
            );
            safeMarkJobFailed(job.getId(), resultPath, failureAnalysis);
        }
    }

    private void safeMarkJobFailed(Long jobId, String resultPath, Map<String, Object> analysis) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("result_path", resultPath);
        summary.put("schema_version", analysis == null ? null : analysis.get("schema_version"));
        summary.put("module_name", analysis == null ? null : analysis.get("module_name"));
        String errorText = nonBlankString(analysis == null ? null : analysis.get("error"));
        summary.put("error", errorText == null ? "unexpected_worker_failure" : errorText);
        try {
            String json = objectMapper.writeValueAsString(summary);
            jobService.updateJobResult(jobId, "FAILED", json);
        } catch (Exception e) {
            log.warn("Failed to mark job as FAILED with summary. jobId={}", jobId, e);
            try {
                jobService.updateJobResult(jobId, "FAILED", "{\"error\":\"unexpected_worker_failure\"}");
            } catch (Exception ignored) {
                log.warn("Failed to mark job as FAILED with fallback summary. jobId={}", jobId);
            }
        }
    }

    private String writeJobResult(Long jobId, Map<String, Object> analysis, Map<String, Object> payload) {
        try {
            Path jobRoot = Path.of(jobsDir, String.valueOf(jobId));
            Files.createDirectories(jobRoot);

            Path requestFile = jobRoot.resolve("request.json");
            Path resultFile = jobRoot.resolve("result.json");

            if (!Files.exists(requestFile)) {
                Files.writeString(requestFile, objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8);
            }
            Files.writeString(resultFile, objectMapper.writeValueAsString(analysis), StandardCharsets.UTF_8);
            return resultFile.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Failed to persist result.json. jobId={}, jobsDir={}", jobId, jobsDir, e);
            return null;
        }
    }

    private String extractSourceJsonPath(Map<String, Object> analysis) {
        Object metadata = analysis.get("metadata");
        if (!(metadata instanceof Map<?, ?> metaMap)) {
            return null;
        }
        Object jsonPath = metaMap.get("json_path");
        if (jsonPath == null) {
            return null;
        }
        String text = String.valueOf(jsonPath).trim();
        return text.isEmpty() ? null : text;
    }

    private Map<String, Object> hydrateAnalysisFromSourcePath(Long jobId, Map<String, Object> analysis) {
        if (analysis == null || hasAnalysisError(analysis) || containsAnalysisPayload(analysis)) {
            return analysis;
        }
        String sourceJsonPath = extractSourceJsonPath(analysis);
        if (sourceJsonPath == null) {
            return analysis;
        }
        try {
            String json = Files.readString(Path.of(sourceJsonPath), StandardCharsets.UTF_8);
            Map<String, Object> loaded = objectMapper.readValue(json, new TypeReference<>() {});
            if (loaded == null) {
                return analysis;
            }
            Map<String, Object> normalized = new HashMap<>(loaded);
            Map<String, Object> metadata = new HashMap<>();
            Object metadataRaw = loaded.get("metadata");
            if (metadataRaw instanceof Map<?, ?> rawMap) {
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    metadata.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            metadata.putIfAbsent("json_path", sourceJsonPath);
            normalized.put("metadata", metadata);
            log.info("Loaded analysis payload from module result file. jobId={}, source={}", jobId, sourceJsonPath);
            return normalized;
        } catch (Exception e) {
            String reason = describeThrowable(e);
            log.warn("Failed to load analysis payload from source json path. jobId={}, source={}, reason={}",
                    jobId, sourceJsonPath, reason);
            return Map.of(
                    "schema_version", "0.1.0",
                    "module_name", "behavior_device_usage",
                    "error", "读取行为模块结果文件失败: " + reason,
                    "metadata", Map.of("json_path", sourceJsonPath)
            );
        }
    }

    private boolean containsAnalysisPayload(Map<String, Object> analysis) {
        if (analysis == null) {
            return false;
        }
        return analysis.containsKey("device_usage_segments")
                || analysis.containsKey("timeline")
                || analysis.containsKey("detections")
                || analysis.containsKey("tracks");
    }

    private RecoveryResult tryRecoverAnalysisFromLegacyResult(Map<String, Object> analysis, Map<String, Object> payload) {
        String errorText = nonBlankString(analysis.get("error"));
        if (errorText == null) {
            return null;
        }
        String lowerError = errorText.toLowerCase();
        if (!lowerError.contains("databufferlimitexception") && !lowerError.contains("max bytes to buffer")) {
            return null;
        }

        String videoPath = stringValue(payload.get("video_path"));
        String videoStem = toFileStemFromPath(videoPath);
        if (videoStem == null) {
            return null;
        }

        RecoveryResult recovered = tryRecoverFromResultFile(videoStem, videoPath);
        if (recovered == null) {
            return null;
        }
        log.warn("Recovered analysis from result file for DataBuffer overflow. source={}", recovered.sourceJsonPath);
        return recovered;
    }

    private RecoveryResult tryRecoverAnalysisFromProgress(Long jobId, Map<String, Object> payload) {
        if (jobId == null) {
            return null;
        }
        String videoPath = stringValue(payload.get("video_path"));
        String videoStem = toFileStemFromPath(videoPath);
        if (videoStem == null) {
            return null;
        }

        long waitSeconds = Math.max(7200L, recoveryWaitSeconds);
        long pollIntervalSeconds = Math.max(2L, recoveryPollIntervalSeconds);
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        int failedStatusStreak = 0;

        while (System.currentTimeMillis() <= deadline) {
            RecoveryResult recovered = tryRecoverFromResultFile(videoStem, videoPath);
            if (recovered != null) {
                log.warn("Recovered analysis by polling module progress. jobId={}, source={}",
                        jobId, recovered.sourceJsonPath);
                return recovered;
            }

            String status = null;
            Integer progress = null;
            try {
                Map<String, Object> progressPayload = behaviorModuleClient.getProgress(jobId).block();
                status = nonBlankString(progressPayload == null ? null : progressPayload.get("status"));
                progress = intValue(progressPayload == null ? null : progressPayload.get("progress"));
                if (progress != null) {
                    int normalizedProgress = Math.max(1, Math.min(progress, 89));
                    jobService.updateJobProgress(jobId, normalizedProgress);
                }
            } catch (Exception progressError) {
                log.debug("Failed to poll module progress. jobId={}, reason={}",
                        jobId, describeThrowable(progressError));
            }

            if ("FAILED".equalsIgnoreCase(status)) {
                failedStatusStreak += 1;
                if (failedStatusStreak >= 6) {
                    log.warn("Module reported FAILED repeatedly during recovery polling. jobId={}, progress={}",
                            jobId, progress);
                    return null;
                }
            } else if (status != null) {
                failedStatusStreak = 0;
            }

            safeSleep(pollIntervalSeconds * 1000L);
        }
        log.warn("Recovery polling timed out without result file. jobId={}, waitSeconds={}", jobId, waitSeconds);
        return null;
    }

    private RecoveryResult tryRecoverFromResultFile(String videoStem, String videoPath) {
        Path legacyPath = findLatestLegacyResultByStem(videoStem, buildLegacySearchDirs(videoPath));
        if (legacyPath == null) {
            return null;
        }
        try {
            String json = Files.readString(legacyPath, StandardCharsets.UTF_8);
            Map<String, Object> recovered = objectMapper.readValue(json, new TypeReference<>() {});
            if (hasAnalysisError(recovered)) {
                return null;
            }
            return new RecoveryResult(recovered, legacyPath.toAbsolutePath().toString());
        } catch (Exception ignored) {
            return null;
        }
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
                // ignore invalid path
            }
        }
        return List.copyOf(dirs);
    }

    private void addExistingDir(Set<Path> dirs, String rawPath) {
        String value = nonBlankString(rawPath);
        if (value == null) {
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
                // ignore this directory
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

    private String toFileStemFromPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path fileName = Path.of(filePath).getFileName();
            if (fileName == null) {
                return null;
            }
            return toFileStem(fileName.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toFileStem(String fileName) {
        String text = nonBlankString(fileName);
        if (text == null) {
            return null;
        }
        int dot = text.lastIndexOf('.');
        return dot > 0 ? text.substring(0, dot) : text;
    }

    private boolean hasReadableFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(filePath);
            return Files.exists(path) && Files.isRegularFile(path);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasAnalysisError(Map<String, Object> analysis) {
        return nonBlankString(analysis.get("error")) != null;
    }

    private String firstNonBlank(String first, String second) {
        String text = nonBlankString(first);
        if (text != null) {
            return text;
        }
        return nonBlankString(second);
    }

    private String nonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void safeSleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = nonBlankString(cursor.getMessage());
            if (message != null && !"null".equalsIgnoreCase(message)) {
                return cursor.getClass().getSimpleName() + ": " + message;
            }
            Throwable cause = cursor.getCause();
            if (cause == null || cause == cursor) {
                break;
            }
            cursor = cause;
        }
        String simpleName = nonBlankString(throwable.getClass().getSimpleName());
        return simpleName == null ? throwable.getClass().getName() : simpleName;
    }

    private static class RecoveryResult {
        private final Map<String, Object> analysis;
        private final String sourceJsonPath;

        private RecoveryResult(Map<String, Object> analysis, String sourceJsonPath) {
            this.analysis = analysis;
            this.sourceJsonPath = sourceJsonPath;
        }
    }
}
