CREATE TABLE creative_xyq_credentials (
 user_id BIGINT PRIMARY KEY,
 access_key TEXT NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE creative_xyq_assets (
 id VARCHAR(160) NOT NULL,
 user_id BIGINT NOT NULL,
 key_fingerprint VARCHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (user_id, id)
);
CREATE TABLE creative_xyq_runs (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 access_key TEXT NOT NULL,
 message TEXT NOT NULL,
 thread_id VARCHAR(160),
 run_id VARCHAR(160),
 state VARCHAR(32) NOT NULL,
 result_json MEDIUMTEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_xyq_user_created (user_id, created_at, id)
);
