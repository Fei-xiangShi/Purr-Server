CREATE TABLE webhook_inbox (
    provider VARCHAR(64) NOT NULL,
    event_id VARCHAR(256) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    received_at_epoch_millis BIGINT NOT NULL,
    available_at_epoch_millis BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    processing_state VARCHAR(16) NOT NULL,
    lease_owner VARCHAR(128),
    lease_until_epoch_millis BIGINT,
    processed_at_epoch_millis BIGINT,
    last_error VARCHAR(2048),
    PRIMARY KEY (provider, event_id)
);

CREATE INDEX idx_webhook_inbox_dispatch
    ON webhook_inbox(processing_state, available_at_epoch_millis, lease_until_epoch_millis);
