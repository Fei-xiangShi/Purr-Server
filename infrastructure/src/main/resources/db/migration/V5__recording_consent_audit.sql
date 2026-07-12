CREATE TABLE call_recording_consents (
    call_id VARCHAR(128) NOT NULL REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_version VARCHAR(64) NOT NULL,
    consented_at_epoch_millis BIGINT NOT NULL,
    PRIMARY KEY (call_id, user_id, policy_version)
);

CREATE INDEX idx_call_recording_consents_user
    ON call_recording_consents(user_id, consented_at_epoch_millis);
