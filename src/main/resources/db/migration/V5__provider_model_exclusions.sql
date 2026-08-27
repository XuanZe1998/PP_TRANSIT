CREATE TABLE IF NOT EXISTS provider_model_exclusions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_code VARCHAR(80) NOT NULL,
    upstream_model_name VARCHAR(160) NOT NULL,
    public_model_name VARCHAR(160) NOT NULL,
    reason VARCHAR(500) NULL,
    excluded_by BIGINT NULL,
    excluded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_code, upstream_model_name)
);

CREATE INDEX idx_provider_model_exclusions_source
    ON provider_model_exclusions(source_code, excluded_at);
