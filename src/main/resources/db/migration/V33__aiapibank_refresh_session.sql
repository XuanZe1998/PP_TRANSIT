ALTER TABLE gateway_account_credentials
 ADD COLUMN encrypted_refresh_token TEXT NULL;
ALTER TABLE gateway_account_credentials
 ADD COLUMN encrypted_pending_token TEXT NULL;
ALTER TABLE gateway_account_credentials
 ADD COLUMN account_email_preview VARCHAR(190) NULL;
ALTER TABLE gateway_account_credentials
 ADD COLUMN auth_status VARCHAR(32) NOT NULL DEFAULT 'ANONYMOUS';
ALTER TABLE gateway_account_credentials
 ADD COLUMN access_expires_at DATETIME NULL;
ALTER TABLE gateway_account_credentials
 ADD COLUMN last_authenticated_at DATETIME NULL;
ALTER TABLE gateway_account_credentials
 ADD COLUMN last_error VARCHAR(1000) NULL;
ALTER TABLE gateway_account_credentials
 ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE gateway_account_credentials SET access_token='';
