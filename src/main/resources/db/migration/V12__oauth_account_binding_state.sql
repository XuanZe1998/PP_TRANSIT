-- OAuth tables in legacy installations were originally created by the
-- idempotent schema repair runner. Fresh installations need the complete table
-- before that runner executes; existing installations are extended by the
-- same runner immediately after Flyway completes.
CREATE TABLE IF NOT EXISTS oauth_login_states (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    state_hash VARCHAR(255) NOT NULL UNIQUE,
    provider VARCHAR(40) NOT NULL,
    target_user_id BIGINT NULL,
    flow_type VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
