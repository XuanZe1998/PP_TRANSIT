-- Payment and commerce tables are created and repaired by SchemaRepairService
-- after Flyway for compatibility with existing installations. This marker
-- records the provider migration without dropping historical data.
CREATE TABLE IF NOT EXISTS mapay_payment_provider_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO mapay_payment_provider_schema_marker(id)
SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM mapay_payment_provider_schema_marker WHERE id = 1
);
