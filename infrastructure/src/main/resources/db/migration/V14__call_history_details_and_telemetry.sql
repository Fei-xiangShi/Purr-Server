CREATE TABLE call_telemetry_samples (
    call_id VARCHAR(128) NOT NULL REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sampled_at_epoch_millis BIGINT NOT NULL,
    round_trip_time_ms DOUBLE PRECISION,
    jitter_ms DOUBLE PRECISION,
    uplink_packet_loss_percent DOUBLE PRECISION,
    downlink_packet_loss_percent DOUBLE PRECISION,
    uplink_bitrate_kbps DOUBLE PRECISION,
    downlink_bitrate_kbps DOUBLE PRECISION,
    network_transport VARCHAR(128),
    send_codec VARCHAR(128),
    receive_codec VARCHAR(128),
    network_validated BOOLEAN NOT NULL,
    network_metered BOOLEAN NOT NULL,
    PRIMARY KEY (call_id, user_id, sampled_at_epoch_millis)
);

CREATE INDEX idx_call_sessions_pair_requested_history
    ON call_sessions(pair_id, started_at_epoch_millis DESC, call_id DESC);

CREATE INDEX idx_call_telemetry_call_time
    ON call_telemetry_samples(call_id, sampled_at_epoch_millis ASC);
