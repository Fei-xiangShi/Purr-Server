ALTER TABLE call_sessions
    ADD COLUMN connected_at_epoch_millis BIGINT;

UPDATE call_sessions
SET connected_at_epoch_millis = started_at_epoch_millis
WHERE call_state IN ('active', 'ended');

CREATE INDEX idx_call_sessions_pair_connected_history
    ON call_sessions(pair_id, connected_at_epoch_millis DESC, call_id DESC);
