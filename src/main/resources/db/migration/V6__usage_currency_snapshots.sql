-- Core operational tables are created and upgraded by SchemaRepairService
-- after Flyway. This marker keeps the versioned registry explicit without
-- attempting to alter tables that may not exist on a first installation.
CREATE TABLE IF NOT EXISTS usage_currency_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO usage_currency_schema_marker(id) SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM usage_currency_schema_marker WHERE id=1
);
