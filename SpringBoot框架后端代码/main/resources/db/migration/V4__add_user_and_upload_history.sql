-- V4__add_user_and_upload_history.sql
-- Add user account + upload history for auth and per-user records

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS user_account (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    username       VARCHAR(64) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    created_at     TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS upload_history (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    user_id          BIGINT NOT NULL,
    job_id           BIGINT NOT NULL,
    session_id       BIGINT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_url         VARCHAR(512) NULL,
    file_size_bytes  BIGINT NULL,
    tracking_mode    VARCHAR(32) NULL,
    created_at       TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_history_job (job_id),
    KEY idx_upload_history_user_created (user_id, created_at),
    KEY idx_upload_history_session (session_id),

    CONSTRAINT fk_upload_history_user
        FOREIGN KEY (user_id) REFERENCES user_account(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_upload_history_job
        FOREIGN KEY (job_id) REFERENCES job(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_upload_history_session
        FOREIGN KEY (session_id) REFERENCES class_session(id)
        ON DELETE SET NULL
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;

