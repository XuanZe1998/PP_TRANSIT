CREATE TABLE IF NOT EXISTS optional_recharge_invoice_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO optional_recharge_invoice_schema_marker(id) SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM optional_recharge_invoice_schema_marker WHERE id=1
);
