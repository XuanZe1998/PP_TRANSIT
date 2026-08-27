-- Core operational tables are bootstrapped by SchemaRepairService after
-- Flyway. This marker keeps first-install databases compatible.
CREATE TABLE IF NOT EXISTS account_alias_context_inventory_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO account_alias_context_inventory_schema_marker(id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM account_alias_context_inventory_schema_marker WHERE id=1);
