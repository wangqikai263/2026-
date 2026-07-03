package com.shixiaoyuan.backend.repository;

import com.shixiaoyuan.backend.entity.TrackSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackSummaryRepository extends JpaRepository<TrackSummaryEntity, Long> {

    /** 按课堂会话ID取出所有追踪摘要，按 track_id 升序 */
    List<TrackSummaryEntity> findBySessionIdOrderByTrackIdAsc(Long sessionId);
}
