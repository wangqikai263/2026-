package com.shixiaoyuan.backend.service.job;

import com.shixiaoyuan.backend.entity.JobEntity;
import com.shixiaoyuan.backend.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class JobService {

    private final JobRepository repo;

    public JobService(JobRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public JobEntity createVideoAnalysisJob(Long sessionId, String payloadJson) {
        JobEntity job = new JobEntity();
        job.setType("VIDEO_ANALYSIS");
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setSessionId(sessionId);
        job.setPayload(payloadJson);
        return repo.save(job);
    }

    @Transactional
    public JobEntity createRealtimeSnapshotJob(Long sessionId, String payloadJson) {
        JobEntity job = new JobEntity();
        job.setType("REALTIME_ANALYSIS");
        job.setStatus("SUCCESS");
        job.setProgress(100);
        job.setSessionId(sessionId);
        job.setPayload(payloadJson);
        return repo.save(job);
    }

    @Transactional
    public JobEntity updateJobResult(Long jobId, String status, String resultJson) {
        JobEntity job = repo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (status != null) {
            job.setStatus(status);
            if ("SUCCESS".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                job.setProgress(100);
            }
        }
        job.setResult(resultJson);
        return repo.save(job);
    }

    @Transactional
    public JobEntity updateJobProgress(Long jobId, Integer progress) {
        JobEntity job = repo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (progress != null) {
            job.setProgress(Math.max(0, Math.min(100, progress)));
        }
        return repo.save(job);
    }

    @Transactional
    public Optional<JobEntity> claimNextPendingJob() {
        Optional<JobEntity> next = repo.findFirstByStatusOrderByCreatedAtAsc("PENDING");
        if (next.isEmpty()) {
            return Optional.empty();
        }
        JobEntity job = next.get();
        int updated = repo.updateStatusIfMatch(job.getId(), "PENDING", "RUNNING");
        if (updated == 1) {
            return repo.findById(job.getId());
        }
        return Optional.empty();
    }

    @Transactional
    public int resetStuckRunningJobs(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        return repo.resetStatusBefore("RUNNING", "PENDING", cutoff);
    }

    @Transactional
    public boolean cancelPendingJob(Long jobId) {
        int updated = repo.updateStatusIfMatch(jobId, "PENDING", "CANCELLED");
        return updated == 1;
    }
}
