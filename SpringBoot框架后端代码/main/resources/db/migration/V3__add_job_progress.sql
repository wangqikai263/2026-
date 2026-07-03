-- V3__add_job_progress.sql
-- Add progress column for async job tracking

ALTER TABLE job
    ADD COLUMN progress INT NULL DEFAULT 0 AFTER status;
