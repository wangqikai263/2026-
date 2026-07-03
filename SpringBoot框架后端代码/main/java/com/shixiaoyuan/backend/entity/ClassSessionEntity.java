package com.shixiaoyuan.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "class_session",
        indexes = {
                @Index(name = "idx_class_id", columnList = "class_id")
        }
)
public class ClassSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务侧课堂ID，如 "2025-03-01-高一3班-数学" */
    @Column(name = "class_id", nullable = false, length = 128, unique = true)
    private String classId;

    @Column(length = 64)
    private String subject;

    @Column(length = 64)
    private String grade;

    @Column(name = "lesson_type", length = 64)
    private String lessonType;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // ====== getter / setter ======

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getLessonType() { return lessonType; }
    public void setLessonType(String lessonType) { this.lessonType = lessonType; }

    public Integer getDurationMin() { return durationMin; }
    public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
