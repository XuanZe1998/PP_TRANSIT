-- The additive, cross-database DDL is installed idempotently by
-- LinknuxEvolutionSchemaService after Flyway starts. This marker keeps the
-- migration history explicit for both upgraded and newly provisioned MySQL
-- installations without deleting or rewriting legacy data.
CREATE TABLE IF NOT EXISTS gateway_schema_versions (
    version_name VARCHAR(120) PRIMARY KEY,
    applied_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(500) NULL
);

INSERT INTO gateway_schema_versions(version_name, description)
SELECT 'linknux-platform-evolution-v1', 'Brand configuration, single-level distribution, upstream accounts and operations foundations'
WHERE NOT EXISTS (SELECT 1 FROM gateway_schema_versions WHERE version_name='linknux-platform-evolution-v1');
