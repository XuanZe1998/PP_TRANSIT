-- These tables have no foreign-key dependency so they can be created before
-- the legacy schema bootstrap runner on both fresh and upgraded databases.
CREATE TABLE IF NOT EXISTS user_verification_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient VARCHAR(255) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS upstream_display_mappings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_id BIGINT NOT NULL,
    public_code VARCHAR(80) NOT NULL,
    public_name VARCHAR(120) NOT NULL,
    badge_text VARCHAR(40) NULL,
    badge_color VARCHAR(16) NULL,
    sort_order INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS model_context_pricing_policies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_model_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    threshold_tokens INT NOT NULL,
    multiplier DECIMAL(10,6) NOT NULL DEFAULT 2.000000,
    verification_note VARCHAR(500) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_refund_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    payment_intent_id BIGINT NOT NULL,
    service_order_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
