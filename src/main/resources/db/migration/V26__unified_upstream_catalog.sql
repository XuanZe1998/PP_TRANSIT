CREATE TABLE IF NOT EXISTS upstream_catalog_sync (
    channel_id BIGINT PRIMARY KEY,
    sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    last_message VARCHAR(500) NULL,
    last_synced_at DATETIME NULL,
    lease_token VARCHAR(80) NULL,
    lease_until DATETIME NULL
);
