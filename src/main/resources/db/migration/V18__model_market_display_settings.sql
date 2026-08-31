CREATE TABLE IF NOT EXISTS model_market_display_settings (
    public_model_name VARCHAR(160) PRIMARY KEY,
    display_priority INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_model_market_display_priority
    ON model_market_display_settings(display_priority, public_model_name);
