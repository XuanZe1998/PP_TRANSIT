CREATE TABLE IF NOT EXISTS aiapibank_provider_groups (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    external_group_id BIGINT NOT NULL,
    channel_id BIGINT NULL,
    group_slug VARCHAR(96) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NULL,
    platform VARCHAR(80) NOT NULL,
    subscription_type VARCHAR(80) NULL,
    base_rate_multiplier DECIMAL(20,10) NOT NULL DEFAULT 1,
    group_rate_multiplier DECIMAL(20,10) NOT NULL DEFAULT 1,
    user_rate_multiplier DECIMAL(20,10) NOT NULL DEFAULT 1,
    resolved_rate_multiplier DECIMAL(20,10) NOT NULL DEFAULT 1,
    peak_rate_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    peak_start VARCHAR(16) NULL,
    peak_end VARCHAR(16) NULL,
    peak_rate_multiplier DECIMAL(20,10) NOT NULL DEFAULT 1,
    billing_timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai',
    exclusive_group BOOLEAN NOT NULL DEFAULT FALSE,
    image_rate_independent BOOLEAN NOT NULL DEFAULT FALSE,
    image_rate_multiplier DECIMAL(20,10) NOT NULL DEFAULT 1,
    long_context_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    credential_status VARCHAR(40) NOT NULL DEFAULT 'CREDENTIAL_MISSING',
    sync_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    model_count INT NOT NULL DEFAULT 0,
    missing_sync_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    raw_json TEXT NULL,
    last_synced_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (external_group_id),
    UNIQUE (group_slug),
    UNIQUE (channel_id)
);

CREATE TABLE IF NOT EXISTS aiapibank_model_offers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_group_id BIGINT NOT NULL,
    model_mapping_id BIGINT NULL,
    upstream_model_name VARCHAR(160) NOT NULL,
    public_model_name VARCHAR(320) NOT NULL,
    platform VARCHAR(80) NOT NULL,
    billing_mode VARCHAR(40) NOT NULL DEFAULT 'token',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    missing_sync_count INT NOT NULL DEFAULT 0,
    pricing_json TEXT NULL,
    official_pricing_json TEXT NULL,
    raw_json TEXT NULL,
    last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider_group_id, upstream_model_name),
    UNIQUE (public_model_name),
    UNIQUE (model_mapping_id)
);

CREATE TABLE IF NOT EXISTS aiapibank_image_price_variants (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    model_offer_id BIGINT NOT NULL,
    resolution_tier VARCHAR(24) NOT NULL,
    max_edge_pixels INT NOT NULL,
    unit VARCHAR(24) NOT NULL DEFAULT 'IMAGE',
    official_unit_price DECIMAL(20,10) NOT NULL DEFAULT 0,
    source_unit_price DECIMAL(20,10) NOT NULL DEFAULT 0,
    sale_unit_price DECIMAL(20,10) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (model_offer_id, resolution_tier)
);

CREATE TABLE IF NOT EXISTS aiapibank_sync_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(40) NOT NULL,
    groups_seen INT NOT NULL DEFAULT 0,
    groups_applied INT NOT NULL DEFAULT 0,
    models_seen INT NOT NULL DEFAULT 0,
    models_applied INT NOT NULL DEFAULT 0,
    credentials_missing INT NOT NULL DEFAULT 0,
    disabled_routes INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    summary_json TEXT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL
);

CREATE INDEX idx_aiapibank_group_status ON aiapibank_provider_groups(sync_status, credential_status);
CREATE INDEX idx_aiapibank_offer_group_status ON aiapibank_model_offers(provider_group_id, enabled, missing_sync_count);
CREATE INDEX idx_aiapibank_sync_started ON aiapibank_sync_runs(started_at);

