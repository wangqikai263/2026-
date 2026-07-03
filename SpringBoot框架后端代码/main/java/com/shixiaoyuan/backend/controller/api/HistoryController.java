package com.shixiaoyuan.backend.controller.api;

import com.shixiaoyuan.backend.auth.AuthSessionUtil;
import com.shixiaoyuan.backend.entity.JobEntity;
import com.shixiaoyuan.backend.entity.UploadHistoryEntity;
import com.shixiaoyuan.backend.repository.JobRepository;
import com.shixiaoyuan.backend.repository.UploadHistoryRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/history")
@Tag(name = "上传历史接口", description = "查询当前登录用户的导入课堂历史")
public class HistoryController {

    private final UploadHistoryRepository uploadHistoryRepository;
    private final JobRepository jobRepository;

    public HistoryController(
            UploadHistoryRepository uploadHistoryRepository,
            JobRepository jobRepository
    ) {
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.jobRepository = jobRepository;
    }

    @GetMapping("/uploads")
    public Map<String, Object> listUploads(
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
            HttpSession session
    ) {
        Long userId = AuthSessionUtil.requireUserId(session);
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));

        List<UploadHistoryEntity> rows = uploadHistoryRepository.findByUserIdOrderByCreatedAtDesc(
                userId,
                PageRequest.of(0, safeLimit)
        );

        Set<Long> jobIds = new LinkedHashSet<>();
        for (UploadHistoryEntity row : rows) {
            if (row.getJobId() != null) {
                jobIds.add(row.getJobId());
            }
        }
        Map<Long, JobEntity> jobMap = new HashMap<>();
        if (!jobIds.isEmpty()) {
            for (JobEntity job : jobRepository.findAllById(jobIds)) {
                jobMap.put(job.getId(), job);
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (UploadHistoryEntity row : rows) {
            JobEntity job = row.getJobId() == null ? null : jobMap.get(row.getJobId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("jobId", row.getJobId());
            item.put("sessionId", row.getSessionId());
            item.put("fileName", row.getFileName());
            item.put("fileUrl", row.getFileUrl());
            item.put("fileSizeBytes", row.getFileSizeBytes());
            item.put("trackingMode", row.getTrackingMode());
            item.put("status", job == null ? "UNKNOWN" : job.getStatus());
            item.put("progress", job == null ? 0 : (job.getProgress() == null ? 0 : job.getProgress()));
            item.put("createdAt", row.getCreatedAt());
            items.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("items", items);
        return body;
    }
}

