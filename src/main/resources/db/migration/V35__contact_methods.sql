CREATE TABLE IF NOT EXISTS contact_methods (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel VARCHAR(80) NOT NULL,
    contact_number VARCHAR(240) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_contact_methods_created
    ON contact_methods(created_at, id);
