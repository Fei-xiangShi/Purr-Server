CREATE TABLE screen_shares (
    share_id VARCHAR(64) PRIMARY KEY,
    call_id VARCHAR(128) NOT NULL REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    active_call_id VARCHAR(128) UNIQUE REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    owner_user_id VARCHAR(64) NOT NULL REFERENCES users(id),
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('obs', 'mobile')),
    media_path VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('authorized', 'live', 'stopping', 'stopped', 'expired', 'failed')),
    created_at_epoch_millis BIGINT NOT NULL,
    updated_at_epoch_millis BIGINT NOT NULL,
    expires_at_epoch_millis BIGINT NOT NULL,
    live_at_epoch_millis BIGINT,
    stopped_at_epoch_millis BIGINT,
    provider_source_type VARCHAR(64),
    provider_source_id VARCHAR(128),
    missing_snapshot_count INTEGER NOT NULL DEFAULT 0 CHECK (missing_snapshot_count >= 0),
    provider_cleaned_at_epoch_millis BIGINT,
    last_error VARCHAR(2048)
);

CREATE INDEX idx_screen_shares_call_created
    ON screen_shares(call_id, created_at_epoch_millis DESC);

CREATE INDEX idx_screen_shares_reconciliation
    ON screen_shares(status, provider_cleaned_at_epoch_millis, updated_at_epoch_millis);
