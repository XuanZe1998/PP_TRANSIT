CREATE TABLE service_redemption_hosts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    host VARCHAR(253) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_service_redemption_host UNIQUE (host)
);

-- The legacy YAML list is imported once at startup, then ignored. The flag
-- prevents a deleted host from being resurrected by a later restart.
CREATE TABLE service_redemption_host_bootstrap (
    id INT NOT NULL PRIMARY KEY,
    completed TINYINT NOT NULL DEFAULT 0
);
INSERT INTO service_redemption_host_bootstrap (id, completed) VALUES (1, 0);
