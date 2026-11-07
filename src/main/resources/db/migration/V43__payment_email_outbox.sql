CREATE TABLE payment_email_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 business_type VARCHAR(40) NOT NULL,
 business_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL,
 claimed_at DATETIME NULL,
 sent_at DATETIME NULL,
 last_error VARCHAR(120) NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY uq_payment_email_business (business_type,business_id)
);
CREATE INDEX idx_payment_email_pending ON payment_email_outbox(status,next_attempt_at);
