-- V5__add_realtime_window_event.sql
-- Realtime ingestion table for edge device 3-second behavior windows

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS realtime_window_event (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    schema_version        VARCHAR(32) NOT NULL,
    event_type            VARCHAR(64) NOT NULL,
    generated_at          TIMESTAMP NULL,

    device_id             VARCHAR(128) NOT NULL,
    class_id              VARCHAR(128) NOT NULL,

    window_index          INT NOT NULL,
    window_start_ts       DECIMAL(16,3) NOT NULL,
    window_end_ts         DECIMAL(16,3) NOT NULL,
    window_start_at       TIMESTAMP NULL,
    window_end_at         TIMESTAMP NOT NULL,
    elapsed_s             DECIMAL(10,3) NULL,

    frame_count           INT NOT NULL,
    det_frame_count       INT NOT NULL,
    avg_conf              DECIMAL(8,6) NULL,
    dominant_behavior     VARCHAR(128) NULL,

    behavior_counts_json  MEDIUMTEXT NULL,
    behavior_rates_json   MEDIUMTEXT NULL,
    detection_counts_json MEDIUMTEXT NULL,

    backend_name          VARCHAR(32) NULL,
    input_mode            VARCHAR(64) NULL,
    camera_source         VARCHAR(64) NULL,
    camera_width          INT NULL,
    camera_height         INT NULL,
    camera_fps            DECIMAL(10,3) NULL,

    client_ip             VARCHAR(64) NULL,
    user_agent            VARCHAR(255) NULL,
    payload_json          MEDIUMTEXT NOT NULL,

    created_at            TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_realtime_idempotent (device_id, class_id, window_index, window_start_ts, window_end_ts),
    KEY idx_realtime_end_at (window_end_at),
    KEY idx_realtime_class_end (class_id, window_end_at),
    KEY idx_realtime_device_end (device_id, window_end_at)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
