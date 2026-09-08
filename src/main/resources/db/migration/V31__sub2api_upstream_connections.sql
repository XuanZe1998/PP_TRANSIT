CREATE TABLE IF NOT EXISTS sub2api_connections (
    channel_id BIGINT PRIMARY KEY,
    base_url VARCHAR(500) NOT NULL,
    detection_method VARCHAR(32) NOT NULL,
    billing_schema_version INT NULL,
    effective_rate_multiplier DECIMAL(20,8) NULL,
    sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    add_new_models BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    last_message VARCHAR(1000) NULL,
    last_synced_at DATETIME NULL,
    lease_token VARCHAR(40) NULL,
    lease_until DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sub2api_model_state (
    channel_id BIGINT NOT NULL,
    upstream_model_name VARCHAR(160) NOT NULL,
    model_mapping_id BIGINT NULL,
    missing_count INT NOT NULL DEFAULT 0,
    retired BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (channel_id, upstream_model_name)
);
