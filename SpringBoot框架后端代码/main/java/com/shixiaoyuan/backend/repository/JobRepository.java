package com.shixiaoyuan.backend.repository;

import com.shixiaoyuan.backend.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    List<JobEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    List<JobEntity> findByStatus(String status);

    Optional<JobEntity> findFirstByStatusOrderByCreatedAtAsc(String status);

    @Modifying
    @Query("update JobEntity j set j.status = :newStatus where j.id = :id and j.status = :expectedStatus")
    int updateStatusIfMatch(@Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("newStatus") String newStatus);

    @Modifying
    @Query("update JobEntity j set j.status = :newStatus where j.status = :oldStatus and j.updatedAt < :cutoff")
    int resetStatusBefore(@Param("oldStatus") String oldStatus,
                          @Param("newStatus") String newStatus,
                          @Param("cutoff") Instant cutoff);
}
