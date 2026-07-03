-- V1__init_schema.sql
-- ShiXiaoYuan backend schema baseline v0.1
-- MySQL 8.x / InnoDB / utf8mb4

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =========================
-- 1. 课堂会话表 class_session
-- =========================
CREATE TABLE IF NOT EXISTS class_session (
                                             id            BIGINT NOT NULL AUTO_INCREMENT,
                                             class_id      VARCHAR(128) NOT NULL,
    subject       VARCHAR(64) NULL,
    grade         VARCHAR(64) NULL,
    lesson_type   VARCHAR(64) NULL,
    duration_min  INT NULL,
    created_at    TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_class_id (class_id),
    KEY idx_class_id (class_id)
    ) ENGINE=InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;

-- =========================
-- 2. 任务表 job
-- =========================
CREATE TABLE IF NOT EXISTS job (
                                   id          BIGINT NOT NULL AUTO_INCREMENT,
                                   type        VARCHAR(64) NOT NULL,     -- 任务类型：VIDEO_ANALYSIS 等
    status      VARCHAR(32) NOT NULL,     -- PENDING / RUNNING / SUCCESS / FAILED
    session_id  BIGINT NULL,              -- 关联课堂会话ID，可为空
    payload     TEXT NULL,                -- 入参快照 JSON
    result      MEDIUMTEXT NULL,          -- 结果快照 JSON
    created_at  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_job_session (session_id),
    CONSTRAINT fk_job_session
    FOREIGN KEY (session_id) REFERENCES class_session(id)
                                                         ON DELETE SET NULL
    ) ENGINE=InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;

-- =========================
-- 3. 分段分析表 analysis_segment
-- =========================
CREATE TABLE IF NOT EXISTS analysis_segment (
                                                id                          BIGINT NOT NULL AUTO_INCREMENT,
                                                session_id                  BIGINT NOT NULL,     -- 归属课堂
                                                start_s                     INT NOT NULL,        -- 起始时间（秒）
                                                end_s                       INT NOT NULL,        -- 结束时间（秒）

                                                focus_rate                  DECIMAL(5,4) NULL,   -- 专注比例 0~1
    phone_rate                  DECIMAL(5,4) NULL,   -- 手机使用比例 0~1
    hand_raise_cnt              INT NULL,            -- 举手次数
    teacher_move_freq_per_min   DECIMAL(6,2) NULL,   -- 教师移动频率 次/分钟
    stability_std               DECIMAL(6,4) NULL,   -- 稳定性（标准差）
    conf                        DECIMAL(5,4) NULL,   -- 置信度 0~1
    clip_url                    VARCHAR(512) NULL,   -- 对应视频片段 URL
    created_at                  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_segment_session_time (session_id, start_s),
    CONSTRAINT fk_segment_session
    FOREIGN KEY (session_id) REFERENCES class_session(id)
    ON DELETE CASCADE
    ) ENGINE=InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;



