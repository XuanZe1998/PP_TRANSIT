CREATE TABLE IF NOT EXISTS provider_models (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_code VARCHAR(80) NOT NULL,
    source_name VARCHAR(160) NOT NULL,
    upstream_model_name VARCHAR(160) NOT NULL,
    public_model_name VARCHAR(160) NOT NULL,
    vendor VARCHAR(80) NOT NULL DEFAULT 'unknown',
    capability VARCHAR(40) NOT NULL DEFAULT 'text',
    input_modalities VARCHAR(255) NOT NULL DEFAULT 'text',
    output_modalities VARCHAR(255) NOT NULL DEFAULT 'text',
    protocols VARCHAR(255) NOT NULL DEFAULT 'chat-completions',
    pricing_unit VARCHAR(40) NOT NULL DEFAULT 'TOKEN',
    endpoint_path VARCHAR(500) NULL,
    task_query_path VARCHAR(500) NULL,
    task_query_method VARCHAR(8) NOT NULL DEFAULT 'POST',
    verification_status VARCHAR(24) NOT NULL DEFAULT 'DISCOVERED',
    verification_message VARCHAR(1000) NULL,
    verified_at DATETIME NULL,
    last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    missing_sync_count INT NOT NULL DEFAULT 0,
    raw_metadata TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_code, upstream_model_name)
);

CREATE INDEX idx_provider_models_public_status
    ON provider_models(public_model_name, verification_status);
CREATE INDEX idx_provider_models_source_status
    ON provider_models(source_code, verification_status, capability, vendor);
