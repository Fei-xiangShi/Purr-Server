CREATE TABLE users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(1024)
);

CREATE TABLE auth_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(255) NOT NULL,
    created_at_epoch_millis BIGINT NOT NULL,
    expires_at_epoch_millis BIGINT NOT NULL
);

CREATE TABLE pair_bonds (
    pair_id VARCHAR(64) PRIMARY KEY,
    user_a_id VARCHAR(64) NOT NULL REFERENCES users(id),
    user_b_id VARCHAR(64) NOT NULL REFERENCES users(id),
    bonded_at_epoch_millis BIGINT NOT NULL
);

CREATE TABLE call_sessions (
    call_id VARCHAR(128) PRIMARY KEY,
    pair_id VARCHAR(64) NOT NULL REFERENCES pair_bonds(pair_id),
    room_name VARCHAR(255) NOT NULL,
    created_by_user_id VARCHAR(64) NOT NULL REFERENCES users(id),
    started_at_epoch_millis BIGINT NOT NULL,
    updated_at_epoch_millis BIGINT NOT NULL,
    ended_at_epoch_millis BIGINT,
    call_state VARCHAR(32) NOT NULL,
    recording_status VARCHAR(32) NOT NULL,
    recording_id VARCHAR(255)
);
