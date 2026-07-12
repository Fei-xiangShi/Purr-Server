CREATE TABLE presence_connections (
    connection_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_seen_epoch_millis BIGINT NOT NULL
);

CREATE INDEX idx_presence_user_last_seen
    ON presence_connections(user_id, last_seen_epoch_millis);
