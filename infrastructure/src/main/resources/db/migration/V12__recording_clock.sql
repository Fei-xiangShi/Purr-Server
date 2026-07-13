ALTER TABLE call_sessions
    ADD COLUMN recording_provider_updated_at_epoch_millis BIGINT;

CREATE INDEX idx_call_sessions_recording_clock
    ON call_sessions(recording_provider_updated_at_epoch_millis);
