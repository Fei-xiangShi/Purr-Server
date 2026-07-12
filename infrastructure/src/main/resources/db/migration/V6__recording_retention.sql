ALTER TABLE call_recordings ADD COLUMN deleted_at_epoch_millis BIGINT;
ALTER TABLE call_recordings ADD COLUMN deletion_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE call_recordings ADD COLUMN last_deletion_attempt_at_epoch_millis BIGINT;
ALTER TABLE call_recordings ADD COLUMN deletion_error_message VARCHAR(2048);

CREATE INDEX idx_call_recordings_retention
    ON call_recordings(recording_status, updated_at_epoch_millis, deletion_attempts);
