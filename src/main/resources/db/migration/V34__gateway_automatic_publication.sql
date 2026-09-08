CREATE TABLE gateway_publication_requests (
 model_mapping_id BIGINT PRIMARY KEY,
 status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
 reason VARCHAR(1000) NOT NULL DEFAULT '',
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_gateway_publication_due ON gateway_publication_requests(status,next_attempt_at);

-- Legacy core tables are created by SchemaRepairService after Flyway on fresh installs.
-- That runner adds service_tier and queues the existing pending-price routes once.
