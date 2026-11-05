ALTER TABLE referral_settings ADD COLUMN first_purchase_coupon_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE referral_settings ADD COLUMN coupon_started_at DATETIME NULL;

CREATE TABLE agent_api_commission_remainders (
    agent_user_id BIGINT PRIMARY KEY,
    remainder_units INT NOT NULL DEFAULT 0
);
