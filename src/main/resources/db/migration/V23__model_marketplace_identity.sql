CREATE TABLE IF NOT EXISTS model_catalog_identities (
    comparison_key VARCHAR(240) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    publisher_code VARCHAR(80) NOT NULL,
    publisher_name VARCHAR(120) NOT NULL,
    category VARCHAR(40) NOT NULL,
    capability VARCHAR(40) NOT NULL,
    input_modalities VARCHAR(255) NOT NULL,
    output_modalities VARCHAR(255) NOT NULL,
    protocols VARCHAR(255) NOT NULL,
    metadata_rank INT NOT NULL DEFAULT 100,
    metadata_source VARCHAR(80) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS model_identity_aliases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_code VARCHAR(80) NOT NULL,
    upstream_model_name VARCHAR(200) NOT NULL,
    comparison_key VARCHAR(240) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_code, upstream_model_name)
);

CREATE INDEX idx_model_identity_alias_key
    ON model_identity_aliases(comparison_key);
