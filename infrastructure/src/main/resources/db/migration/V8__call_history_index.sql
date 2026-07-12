CREATE INDEX idx_call_sessions_history
    ON call_sessions(pair_id, call_state, started_at_epoch_millis DESC, call_id DESC);
