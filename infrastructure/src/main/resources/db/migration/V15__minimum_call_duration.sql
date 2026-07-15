ALTER TABLE call_sessions
    ADD COLUMN duration_millis BIGINT;

UPDATE call_sessions
SET duration_millis = CASE
    WHEN ended_at_epoch_millis >= connected_at_epoch_millis
        THEN ended_at_epoch_millis - connected_at_epoch_millis
    ELSE 0
END
WHERE connected_at_epoch_millis IS NOT NULL
  AND ended_at_epoch_millis IS NOT NULL;

ALTER TABLE call_sessions
    ADD CONSTRAINT ck_call_sessions_duration_nonnegative
        CHECK (duration_millis IS NULL OR duration_millis >= 0);

ALTER TABLE call_sessions
    ADD CONSTRAINT ck_call_sessions_duration_has_timestamps
        CHECK (
            duration_millis IS NULL OR
            (connected_at_epoch_millis IS NOT NULL AND ended_at_epoch_millis IS NOT NULL)
        );

CREATE INDEX idx_call_sessions_pair_valid_history
    ON call_sessions(pair_id, call_state, duration_millis, started_at_epoch_millis DESC, call_id DESC);
