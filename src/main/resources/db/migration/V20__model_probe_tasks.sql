-- The additive model_probe_tasks table is installed idempotently by
-- EnterpriseSchemaService after Flyway starts (same pattern as the other
-- enterprise additive schema). This marker keeps the migration history
-- explicit for both upgraded and newly provisioned MySQL installations.
CREATE TABLE IF NOT EXISTS model_probe_tasks_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO model_probe_tasks_schema_marker(id) SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM model_probe_tasks_schema_marker WHERE id=1
);