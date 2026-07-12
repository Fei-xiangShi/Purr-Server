ALTER TABLE call_sessions
    ADD COLUMN recording_recovery_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE call_sessions
    ADD COLUMN recording_last_recovery_at_epoch_millis BIGINT;

ALTER TABLE call_sessions
    ADD COLUMN recording_error_message VARCHAR(2048);

CREATE TABLE call_recordings (
    recording_id VARCHAR(255) PRIMARY KEY,
    call_id VARCHAR(128) NOT NULL REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    recording_status VARCHAR(32) NOT NULL,
    object_key VARCHAR(1024),
    location VARCHAR(2048),
    started_at_epoch_millis BIGINT,
    ended_at_epoch_millis BIGINT,
    duration_millis BIGINT,
    size_bytes BIGINT,
    error_code INTEGER,
    error_message VARCHAR(2048),
    created_at_epoch_millis BIGINT NOT NULL,
    updated_at_epoch_millis BIGINT NOT NULL
);

CREATE INDEX idx_call_recordings_call_started
    ON call_recordings(call_id, started_at_epoch_millis);

CREATE INDEX idx_call_recordings_status_updated
    ON call_recordings(recording_status, updated_at_epoch_millis);

INSERT INTO call_recordings (
    recording_id,
    call_id,
    recording_status,
    created_at_epoch_millis,
    updated_at_epoch_millis
)
SELECT
    recording_id,
    call_id,
    recording_status,
    started_at_epoch_millis,
    updated_at_epoch_millis
FROM call_sessions
WHERE recording_id IS NOT NULL;
