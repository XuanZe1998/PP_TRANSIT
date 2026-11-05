CREATE TABLE agent_subscription_prices (
    agent_user_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    base_cents BIGINT NOT NULL,
    retail_cents BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(agent_user_id,service_id)
);
ALTER TABLE referral_orders ADD COLUMN resale_agent_id BIGINT NULL;
ALTER TABLE referral_orders ADD COLUMN resale_base_cents BIGINT NULL;
ALTER TABLE referral_orders ADD COLUMN resale_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE referral_orders ADD COLUMN resale_reversed_cents BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_referral_resale_agent ON referral_orders(resale_agent_id,order_id);
