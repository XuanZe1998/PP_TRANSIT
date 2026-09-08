ALTER TABLE gateway_sync_jobs MODIFY channel_id BIGINT NULL;
ALTER TABLE gateway_sync_jobs ADD COLUMN job_type VARCHAR(32) NOT NULL DEFAULT 'MODELS';
CREATE TABLE IF NOT EXISTS gateway_site_sync_locks (
 site_id BIGINT PRIMARY KEY, job_id VARCHAR(80) NOT NULL
);
