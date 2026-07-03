-- V2__add_track_summary.sql
-- Schema v0.2: 追踪摘要表，存储每个 track_id 的生命周期汇总
-- 与 analysis_segment 同属一次 session，但维度不同：
--   analysis_segment = 时间窗口维度
--   track_summary    = 目标 ID 维度（多目标追踪 v0.2 新增）

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS track_summary (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    session_id      BIGINT      NOT NULL,           -- 归属课堂会话
    track_id        INT         NOT NULL,            -- BoxMOT 分配的追踪 ID
    cls_name        VARCHAR(64) NOT NULL,            -- 主类别名
    cls_id          INT         NULL,                -- 主类别索引
    first_frame     INT         NULL,                -- 首次出现帧号
    last_frame      INT         NULL,                -- 最后出现帧号
    first_ts_s      DECIMAL(10,3) NULL,              -- 首次出现时间戳（秒）
    last_ts_s       DECIMAL(10,3) NULL,              -- 最后出现时间戳（秒）
    duration_s      DECIMAL(10,3) NULL,              -- 持续时间（秒）
    total_frames    INT         NULL,                -- 出现总帧数
    avg_conf        DECIMAL(5,4) NULL,               -- 平均置信度
    created_at      TIMESTAMP   NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_track_session  (session_id),
    KEY idx_track_session_tid (session_id, track_id),
    CONSTRAINT fk_track_session
        FOREIGN KEY (session_id) REFERENCES class_session(id)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
