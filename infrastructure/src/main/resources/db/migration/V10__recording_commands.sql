CREATE TABLE recording_commands (
    command_id VARCHAR(128) PRIMARY KEY,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    call_id VARCHAR(128) NOT NULL REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    room_name VARCHAR(255) NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    recording_id VARCHAR(255),
    requested_at_epoch_millis BIGINT NOT NULL,
    available_at_epoch_millis BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until_epoch_millis BIGINT,
    command_state VARCHAR(16) NOT NULL,
    completed_at_epoch_millis BIGINT,
    last_error VARCHAR(2048)
);

CREATE INDEX idx_recording_commands_dispatch
    ON recording_commands(command_state, available_at_epoch_millis, lease_until_epoch_millis);

CREATE INDEX idx_recording_commands_call
    ON recording_commands(call_id, command_type, command_state, requested_at_epoch_millis);
