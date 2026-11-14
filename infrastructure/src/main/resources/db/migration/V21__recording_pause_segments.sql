ALTER TABLE call_sessions ADD COLUMN recording_paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE call_sessions ADD COLUMN recording_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE recording_commands ADD COLUMN recording_generation BIGINT NOT NULL DEFAULT 0;
