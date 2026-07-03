package com.shixiaoyuan.backend.repository;

import com.shixiaoyuan.backend.entity.RealtimeWindowEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RealtimeWindowEventRepository extends JpaRepository<RealtimeWindowEventEntity, Long> {

    Optional<RealtimeWindowEventEntity> findFirstByDeviceIdAndClassIdAndWindowIndexAndWindowStartTsAndWindowEndTs(
            String deviceId,
            String classId,
            Integer windowIndex,
            BigDecimal windowStartTs,
            BigDecimal windowEndTs
    );

    @Query("""
            select e
            from RealtimeWindowEventEntity e
            where (:classId is null or e.classId = :classId)
              and (:deviceId is null or e.deviceId = :deviceId)
              and e.windowEndAt >= :fromTs
            order by e.windowEndAt desc
            """)
    List<RealtimeWindowEventEntity> findRecent(
            @Param("classId") String classId,
            @Param("deviceId") String deviceId,
            @Param("fromTs") Instant fromTs,
            Pageable pageable
    );
}
