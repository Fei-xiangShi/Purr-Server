ALTER TABLE auth_sessions
    ADD CONSTRAINT uq_auth_sessions_refresh_token_hash UNIQUE (refresh_token_hash);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);

ALTER TABLE call_sessions ADD COLUMN active_pair_id VARCHAR(64);

UPDATE call_sessions
SET active_pair_id = pair_id
WHERE call_state = 'active';

ALTER TABLE call_sessions
    ADD CONSTRAINT uq_call_sessions_active_pair UNIQUE (active_pair_id);

ALTER TABLE call_sessions
    ADD CONSTRAINT uq_call_sessions_room_name UNIQUE (room_name);

CREATE INDEX idx_call_sessions_pair_state ON call_sessions(pair_id, call_state);
CREATE INDEX idx_call_sessions_recording_id ON call_sessions(recording_id);
