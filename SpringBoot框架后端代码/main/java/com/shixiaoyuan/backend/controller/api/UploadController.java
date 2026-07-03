// controller/api/UploadController.java
package com.shixiaoyuan.backend.controller.api;

import com.shixiaoyuan.backend.dto.response.UploadResponse;
import com.shixiaoyuan.backend.auth.AuthSessionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixiaoyuan.backend.entity.ClassSessionEntity;
import com.shixiaoyuan.backend.entity.JobEntity;
import com.shixiaoyuan.backend.entity.UploadHistoryEntity;
import com.shixiaoyuan.backend.repository.ClassSessionRepository;
import com.shixiaoyuan.backend.repository.UploadHistoryRepository;
import com.shixiaoyuan.backend.service.job.JobService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "上传接口", description = "上传课堂视频并创建分析任务（行为小模型接入版）")
public class UploadController {
    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    @Value("${app.upload.dir}")
    private String uploadDir;

    @Value("${app.jobs.dir}")
    private String jobsDir;

    private final ClassSessionRepository classSessionRepository;
    private final UploadHistoryRepository uploadHistoryRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UploadController(
            ClassSessionRepository classSessionRepository,
            UploadHistoryRepository uploadHistoryRepository,
            JobService jobService
    ) {
        this.classSessionRepository = classSessionRepository;
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.jobService = jobService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(
            @RequestPart("video") MultipartFile video,
            @RequestParam(value = "classId", required = false) String classId,
            @RequestParam(value = "durationMin", required = false) Integer durationMin,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "grade", required = false) String grade,
            @RequestParam(value = "lessonType", required = false) String lessonType,
            @RequestParam(value = "trackingMode", required = false, defaultValue = "fast") String trackingMode,
            HttpSession httpSession
    ) throws Exception {
        Long userId = AuthSessionUtil.requireUserId(httpSession);
        UploadResponse resp = new UploadResponse();

        // 1. 基础校验
        if (video == null || video.isEmpty()) {
            resp.setOk(false);
            resp.setAnalysis(Map.of("error", "no_file"));
            return resp;
        }

        // 2. 确保目录存在
        Path root = Path.of(uploadDir);
        Files.createDirectories(root);

        // 3. 生成不重复的文件名并保存
        String original = StringUtils.cleanPath(video.getOriginalFilename());
        String filename = System.currentTimeMillis() + "_" + original;
        Path dest = root.resolve(filename);

        long saveStartNs = System.nanoTime();
        video.transferTo(dest);
        long saveCostMs = (System.nanoTime() - saveStartNs) / 1_000_000L;
        log.info("Upload saved. originalName={}, sizeBytes={}, dest={}, saveCostMs={}",
                original, video.getSize(), dest.toAbsolutePath(), saveCostMs);

        String resolvedClassId = StringUtils.hasText(classId) ? classId : "demo-001";
        Integer resolvedDuration = durationMin != null ? durationMin : 40;

        ClassSessionEntity classSession = classSessionRepository.findByClassId(resolvedClassId)
                .orElseGet(() -> {
                    ClassSessionEntity created = new ClassSessionEntity();
                    created.setClassId(resolvedClassId);
                    created.setSubject(subject);
                    created.setGrade(grade);
                    created.setLessonType(lessonType);
                    created.setDurationMin(resolvedDuration);
                    return classSessionRepository.save(created);
                });
        boolean sessionUpdated = false;
        if (resolvedDuration != null && classSession.getDurationMin() == null) {
            classSession.setDurationMin(resolvedDuration);
            sessionUpdated = true;
        }
        if (StringUtils.hasText(subject) && classSession.getSubject() == null) {
            classSession.setSubject(subject);
            sessionUpdated = true;
        }
        if (StringUtils.hasText(grade) && classSession.getGrade() == null) {
            classSession.setGrade(grade);
            sessionUpdated = true;
        }
        if (StringUtils.hasText(lessonType) && classSession.getLessonType() == null) {
            classSession.setLessonType(lessonType);
            sessionUpdated = true;
        }
        if (sessionUpdated) {
            classSession = classSessionRepository.save(classSession);
        }

        Map<String, Object> jobPayload = new HashMap<>();
        jobPayload.put("video_path", dest.toAbsolutePath().toString());
        jobPayload.put("class_id", resolvedClassId);
        jobPayload.put("duration_min", resolvedDuration);
        jobPayload.put("file_url", "/uploads/" + filename);
        jobPayload.put("tracking_mode", trackingMode);
        JobEntity job = jobService.createVideoAnalysisJob(classSession.getId(), objectMapper.writeValueAsString(jobPayload));
        saveUploadHistory(userId, job, classSession.getId(), filename, video.getSize(), trackingMode);

        // 4. 异步任务：这里只创建任务并返回，不阻塞等待
        writeRequestSnapshot(job.getId(), jobPayload);

        // 5. 组装返回结果
        resp.setOk(true);
        resp.setFileUrl("/uploads/" + filename); // 前端可用于回放或后续扩展
        resp.setSessionId(classSession.getId());
        resp.setJobId(job.getId());
        resp.setAnalysis(Map.of("status", "PENDING", "progress", 0));

        return resp;
    }

    private void writeRequestSnapshot(Long jobId, Map<String, Object> payload) {
        try {
            Path jobRoot = Path.of(jobsDir, String.valueOf(jobId));
            Files.createDirectories(jobRoot);
            Path requestFile = jobRoot.resolve("request.json");
            Files.writeString(requestFile, objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            // 忽略快照失败，不影响任务创建
        }
    }

    private void saveUploadHistory(Long userId, JobEntity job, Long sessionId, String filename, long fileSize, String trackingMode) {
        try {
            UploadHistoryEntity history = new UploadHistoryEntity();
            history.setUserId(userId);
            history.setJobId(job.getId());
            history.setSessionId(sessionId);
            history.setFileName(filename);
            history.setFileUrl("/uploads/" + filename);
            history.setFileSizeBytes(fileSize);
            history.setTrackingMode(trackingMode);
            uploadHistoryRepository.save(history);
        } catch (Exception ignored) {
            // 忽略历史写入失败，不影响主流程
        }
    }
}
