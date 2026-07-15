ALTER TABLE users ADD COLUMN avatar_object_key VARCHAR(512);
ALTER TABLE users ADD COLUMN avatar_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE avatar_cleanup_tasks (
    object_key VARCHAR(512) PRIMARY KEY,
    created_at_epoch_millis BIGINT NOT NULL,
    available_at_epoch_millis BIGINT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64),
    lease_until_epoch_millis BIGINT,
    completed_at_epoch_millis BIGINT,
    last_error VARCHAR(1024)
);

CREATE INDEX idx_avatar_cleanup_available
    ON avatar_cleanup_tasks(completed_at_epoch_millis, available_at_epoch_millis);
