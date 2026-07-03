package com.shixiaoyuan.backend.repository;

import com.shixiaoyuan.backend.entity.UploadHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadHistoryRepository extends JpaRepository<UploadHistoryEntity, Long> {

    List<UploadHistoryEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    boolean existsByUserIdAndJobId(Long userId, Long jobId);

    Optional<UploadHistoryEntity> findByUserIdAndJobId(Long userId, Long jobId);
}

