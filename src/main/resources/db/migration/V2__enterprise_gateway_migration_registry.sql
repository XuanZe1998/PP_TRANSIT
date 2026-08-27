CREATE TABLE IF NOT EXISTS gateway_schema_versions (
    version_name VARCHAR(120) PRIMARY KEY,
    applied_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(500) NULL
);

INSERT INTO gateway_schema_versions(version_name, description)
SELECT 'enterprise-gateway-v2', 'Organizations, wallet ledger, model source metadata, credential pools, idempotency and task APIs'
WHERE NOT EXISTS (SELECT 1 FROM gateway_schema_versions WHERE version_name='enterprise-gateway-v2');
