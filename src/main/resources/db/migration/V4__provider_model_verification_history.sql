CREATE TABLE IF NOT EXISTS provider_model_verifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_model_id BIGINT NOT NULL,
    source_code VARCHAR(80) NOT NULL,
    upstream_model_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    message VARCHAR(1000) NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL
);

CREATE INDEX idx_provider_verification_model_started
    ON provider_model_verifications(provider_model_id, started_at);
CREATE INDEX idx_provider_verification_source_status
    ON provider_model_verifications(source_code, status, started_at);
