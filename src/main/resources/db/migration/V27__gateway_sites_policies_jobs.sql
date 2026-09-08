CREATE TABLE IF NOT EXISTS upstream_sites (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, site_key VARCHAR(160) NOT NULL UNIQUE,
 name VARCHAR(120) NOT NULL, adapter VARCHAR(40) NOT NULL,
 public_code VARCHAR(80) NULL, public_name VARCHAR(120) NULL,
 badge_text VARCHAR(40) NULL, badge_color VARCHAR(16) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS upstream_site_channels (
 channel_id BIGINT PRIMARY KEY, site_id BIGINT NOT NULL
);
CREATE INDEX idx_site_channels_site ON upstream_site_channels(site_id);
CREATE TABLE IF NOT EXISTS gateway_price_rules (
 scope_type VARCHAR(20) NOT NULL, scope_id BIGINT NOT NULL,
 mode VARCHAR(20) NOT NULL, amount DECIMAL(20,8) NOT NULL DEFAULT 20,
 fixed_amounts TEXT NULL, version BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(scope_type,scope_id)
);
CREATE TABLE IF NOT EXISTS gateway_price_overrides (
 model_mapping_id BIGINT PRIMARY KEY, snapshot TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS gateway_price_previews (
 id VARCHAR(80) PRIMARY KEY, payload TEXT NOT NULL, fingerprint VARCHAR(80) NOT NULL,
 created_at DATETIME NOT NULL, applied BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS gateway_sync_jobs (
 id VARCHAR(80) PRIMARY KEY, channel_id BIGINT NOT NULL, site_id BIGINT NULL,
 status VARCHAR(40) NOT NULL, phase VARCHAR(40) NULL, error_code VARCHAR(60) NULL,
 http_status INT NULL, message VARCHAR(1000) NULL, suggestion VARCHAR(1000) NULL,
 created_at DATETIME NOT NULL, started_at DATETIME NULL, finished_at DATETIME NULL
);
CREATE INDEX idx_gateway_jobs_channel ON gateway_sync_jobs(channel_id,created_at);
CREATE TABLE IF NOT EXISTS gateway_sync_locks (channel_id BIGINT PRIMARY KEY, job_id VARCHAR(80) NOT NULL);
