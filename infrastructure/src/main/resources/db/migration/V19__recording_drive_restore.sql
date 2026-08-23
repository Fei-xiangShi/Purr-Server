ALTER TABLE call_recordings ADD COLUMN restore_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE call_recordings ADD COLUMN restore_lease_owner VARCHAR(128);
ALTER TABLE call_recordings ADD COLUMN restore_lease_until_epoch_millis BIGINT;
ALTER TABLE call_recordings ADD COLUMN restore_error_message VARCHAR(2048);

CREATE INDEX idx_call_recordings_restore_lease
    ON call_recordings(restore_lease_until_epoch_millis);
