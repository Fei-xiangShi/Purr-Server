CREATE TABLE push_devices (
    installation_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL REFERENCES auth_sessions(session_id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    token VARCHAR(4096) NOT NULL,
    created_at_epoch_millis BIGINT NOT NULL,
    updated_at_epoch_millis BIGINT NOT NULL,
    disabled_at_epoch_millis BIGINT,
    CONSTRAINT ck_push_devices_provider CHECK (provider IN ('FCM')),
    CONSTRAINT ck_push_devices_timestamps CHECK (updated_at_epoch_millis >= created_at_epoch_millis),
    CONSTRAINT uq_push_devices_provider_token UNIQUE (provider, token)
);

CREATE INDEX idx_push_devices_user_active
    ON push_devices(user_id, disabled_at_epoch_millis, updated_at_epoch_millis DESC);
