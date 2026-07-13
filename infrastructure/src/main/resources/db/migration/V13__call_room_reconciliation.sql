ALTER TABLE call_sessions
    ADD COLUMN room_empty_since_epoch_millis BIGINT;

CREATE INDEX idx_call_sessions_open_reconciliation
    ON call_sessions(call_state, started_at_epoch_millis, room_empty_since_epoch_millis);
