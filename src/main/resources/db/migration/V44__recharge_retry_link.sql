-- Wallet tables are initialized by SchemaRepairService after Flyway on a fresh database.
-- That service installs retry_order_id on both fresh and existing schemas.
CREATE TABLE IF NOT EXISTS recharge_retry_schema_marker (id INT PRIMARY KEY);
INSERT INTO recharge_retry_schema_marker(id) SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM recharge_retry_schema_marker WHERE id=1
);
