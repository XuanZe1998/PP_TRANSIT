-- Core operational tables are created and upgraded by SchemaRepairService and
-- EnterpriseSchemaService after Flyway. This marker versions the rollout while
-- keeping first-install databases compatible when those tables do not yet exist.
CREATE TABLE IF NOT EXISTS multi_unit_pricing_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO multi_unit_pricing_schema_marker(id) SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM multi_unit_pricing_schema_marker WHERE id=1
);
