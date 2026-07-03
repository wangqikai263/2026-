package com.shixiaoyuan.backend.repository;

import com.shixiaoyuan.backend.entity.AnalysisSegmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisSegmentRepository extends JpaRepository<AnalysisSegmentEntity, Long> {

    /** 按课堂会话ID取出所有分段，按时间排序，聚合器会用到 */
    List<AnalysisSegmentEntity> findBySessionIdOrderByStartSAsc(Long sessionId);
}
