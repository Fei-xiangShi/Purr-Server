CREATE TABLE outbox_events (
    event_id VARCHAR(64) PRIMARY KEY,
    recipient_user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at_epoch_millis BIGINT NOT NULL,
    available_at_epoch_millis BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until_epoch_millis BIGINT,
    published_at_epoch_millis BIGINT,
    last_error VARCHAR(2048)
);

CREATE INDEX idx_outbox_dispatch
    ON outbox_events(published_at_epoch_millis, available_at_epoch_millis, occurred_at_epoch_millis);

CREATE INDEX idx_outbox_lease
    ON outbox_events(lease_until_epoch_millis);

CREATE TABLE outbox_dispatch_locks (
    lock_name VARCHAR(64) PRIMARY KEY,
    lease_owner VARCHAR(128),
    lease_until_epoch_millis BIGINT
);

INSERT INTO outbox_dispatch_locks(lock_name, lease_owner, lease_until_epoch_millis)
VALUES ('realtime', NULL, NULL);
