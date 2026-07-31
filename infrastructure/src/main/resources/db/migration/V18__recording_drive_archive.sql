ALTER TABLE call_recordings ADD COLUMN deletion_lease_owner VARCHAR(128);
ALTER TABLE call_recordings ADD COLUMN deletion_lease_until_epoch_millis BIGINT;

ALTER TABLE call_recordings ADD COLUMN drive_file_id VARCHAR(255);
ALTER TABLE call_recordings ADD COLUMN drive_uploaded_at_epoch_millis BIGINT;
ALTER TABLE call_recordings ADD COLUMN drive_upload_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE call_recordings ADD COLUMN drive_upload_available_at_epoch_millis BIGINT;
ALTER TABLE call_recordings ADD COLUMN drive_upload_lease_owner VARCHAR(128);
ALTER TABLE call_recordings ADD COLUMN drive_upload_lease_until_epoch_millis BIGINT;
ALTER TABLE call_recordings ADD COLUMN drive_upload_error_message VARCHAR(2048);

UPDATE call_recordings
SET drive_upload_available_at_epoch_millis = updated_at_epoch_millis
WHERE recording_status = 'stopped'
  AND object_key IS NOT NULL
  AND deleted_at_epoch_millis IS NULL;

CREATE INDEX idx_call_recordings_drive_upload
    ON call_recordings(drive_uploaded_at_epoch_millis, drive_upload_available_at_epoch_millis);

CREATE INDEX idx_call_recordings_deletion_lease
    ON call_recordings(deletion_lease_until_epoch_millis);
