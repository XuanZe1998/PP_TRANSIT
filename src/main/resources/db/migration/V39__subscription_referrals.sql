CREATE TABLE referral_settings (
 id INT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 freeze_days INT NOT NULL DEFAULT 7, minimum_cents BIGINT NOT NULL DEFAULT 2000,
 service_notice VARCHAR(500) NOT NULL, version BIGINT NOT NULL DEFAULT 1
);
INSERT INTO referral_settings VALUES (1,FALSE,7,2000,'申请后 2 个工作日内审核，审核通过后 2 个工作日内人工打款。',1);
CREATE TABLE referral_members (
 user_id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE referral_rules (
 service_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 mode VARCHAR(20) NOT NULL, fixed_cents BIGINT NOT NULL DEFAULT 0,
 rate_bps INT NOT NULL DEFAULT 0, cost_cents BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE referral_previews (
 token VARCHAR(64) PRIMARY KEY, admin_id BIGINT NOT NULL, service_id BIGINT NOT NULL,
 version BIGINT NOT NULL, payload TEXT NOT NULL, expires_at DATETIME NOT NULL
);
CREATE TABLE referral_orders (
 order_id BIGINT PRIMARY KEY, inviter_id BIGINT NULL, customer_id BIGINT NOT NULL,
 rule_version BIGINT NOT NULL DEFAULT 0, mode VARCHAR(20) NULL, fixed_cents BIGINT NOT NULL DEFAULT 0,
 rate_bps INT NOT NULL DEFAULT 0, sale_cents BIGINT NOT NULL DEFAULT 0, cost_cents BIGINT NOT NULL DEFAULT 0,
 commission_cents BIGINT NOT NULL DEFAULT 0, reversed_cents BIGINT NOT NULL DEFAULT 0,
 refunded_cents BIGINT NOT NULL DEFAULT 0, freeze_days INT NOT NULL DEFAULT 7,
 status VARCHAR(24) NOT NULL DEFAULT 'INELIGIBLE', available_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_referral_order_status ON referral_orders(status,order_id);
CREATE INDEX idx_referral_order_inviter ON referral_orders(inviter_id,order_id);
CREATE TABLE referral_accounts (
 user_id BIGINT PRIMARY KEY, channel VARCHAR(16) NOT NULL, encrypted_details TEXT NOT NULL,
 account_hash VARCHAR(96) NOT NULL, masked_account VARCHAR(160) NOT NULL
);
CREATE INDEX idx_referral_account_hash ON referral_accounts(account_hash);
CREATE TABLE referral_withdrawals (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, request_key VARCHAR(80) NOT NULL,
 user_id BIGINT NOT NULL, amount_cents BIGINT NOT NULL, channel VARCHAR(16) NOT NULL,
 encrypted_details TEXT NOT NULL, account_hash VARCHAR(96) NOT NULL, masked_account VARCHAR(160) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', note VARCHAR(500) NULL,
 claimed_by BIGINT NULL, reviewed_by BIGINT NULL,
 payment_reference VARCHAR(160) NULL, paid_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,request_key), UNIQUE(channel,payment_reference)
);
CREATE INDEX idx_referral_withdrawal_user ON referral_withdrawals(user_id,id);
CREATE INDEX idx_referral_withdrawal_status ON referral_withdrawals(status,id);
CREATE INDEX idx_referral_withdrawal_account ON referral_withdrawals(account_hash,user_id);
CREATE TABLE referral_refunds (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, amount_cents BIGINT NOT NULL,
 merchandise_cents BIGINT NOT NULL,
 reference VARCHAR(160) NOT NULL UNIQUE, refunded_at DATETIME NOT NULL,
 admin_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_referral_refund_order ON referral_refunds(order_id);
