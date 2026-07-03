package com.shixiaoyuan.backend.repository;

import com.shixiaoyuan.backend.entity.ClassSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClassSessionRepository extends JpaRepository<ClassSessionEntity, Long> {

    Optional<ClassSessionEntity> findByClassId(String classId);
}
