-- The legacy users table is created by the additive schema bootstrap runner.
-- The runner adds users.invoice_enabled idempotently on both fresh and upgraded
-- databases; this marker keeps the rollout visible in Flyway history.
CREATE TABLE IF NOT EXISTS user_invoice_access_schema_marker (
    id INT PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO user_invoice_access_schema_marker(id) SELECT 1 WHERE NOT EXISTS (
    SELECT 1 FROM user_invoice_access_schema_marker WHERE id=1
);
