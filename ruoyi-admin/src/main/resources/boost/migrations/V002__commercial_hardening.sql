-- Additive, restartable migration. BoostMigrations checks existing columns/indexes.
ALTER TABLE boost_listing ADD COLUMN request_key VARCHAR(64);
ALTER TABLE boost_listing ADD COLUMN request_hash VARCHAR(64);
CREATE UNIQUE INDEX uq_boost_listing_request ON boost_listing(owner_id,request_key);
ALTER TABLE boost_message ADD COLUMN request_key VARCHAR(64);
ALTER TABLE boost_message ADD COLUMN request_hash VARCHAR(64);
CREATE UNIQUE INDEX uq_boost_message_request ON boost_message(sender_id,request_key);
ALTER TABLE boost_order ADD COLUMN provider_state VARCHAR(32);
ALTER TABLE boost_order ADD COLUMN provider_error VARCHAR(500);
ALTER TABLE boost_order ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE boost_order ADD COLUMN refund_attempt INT NOT NULL DEFAULT 1;
ALTER TABLE boost_order ADD COLUMN refund_no VARCHAR(64);
ALTER TABLE boost_member ADD COLUMN accepted_policy VARCHAR(64);
ALTER TABLE boost_member ADD COLUMN accepted_at TIMESTAMP NULL;
CREATE INDEX ix_boost_listing_market ON boost_listing(status,kind,game,price_cents,id);
CREATE INDEX ix_boost_listing_newest ON boost_listing(status,kind,id);
CREATE INDEX ix_boost_session_expiry ON boost_session(expires_at);
CREATE INDEX ix_boost_order_reconcile ON boost_order(status,payment_mode,provider_checked_at,id);
CREATE INDEX ix_boost_order_expiry ON boost_order(status,expires_at,id);
CREATE INDEX ix_boost_withdrawal_reconcile ON boost_withdrawal(status,provider_checked_at,id);
CREATE INDEX ix_boost_notification_unread ON boost_notification(member_id,is_read,id);
CREATE INDEX ix_boost_message_unread ON boost_message(conversation_id,is_read,sender_id,id);
CREATE TABLE IF NOT EXISTS boost_media (
 name VARCHAR(40) PRIMARY KEY, owner_id BIGINT NOT NULL, purpose VARCHAR(20) NOT NULL,
 size_bytes BIGINT NOT NULL, gc_checked_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(owner_id) REFERENCES boost_member(id)
);
CREATE INDEX ix_boost_media_owner ON boost_media(owner_id,created_at);
CREATE TABLE IF NOT EXISTS boost_rate_limit (
 bucket_key VARCHAR(100) PRIMARY KEY, hits INT NOT NULL, resets_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_boost_rate_expiry ON boost_rate_limit(resets_at);
CREATE TABLE IF NOT EXISTS boost_campaign_claim (
 identity_hash VARCHAR(64) PRIMARY KEY, member_id BIGINT NOT NULL, coupon_id BIGINT,
 amount_cents BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_campaign_budget (
 budget_day VARCHAR(10) PRIMARY KEY, used_cents BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS boost_provider_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT, event_type VARCHAR(40) NOT NULL,
 provider_state VARCHAR(32), note VARCHAR(500), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_boost_provider_order ON boost_provider_event(order_id,id);

CREATE INDEX ix_boost_media_gc ON boost_media(gc_checked_at,created_at);
CREATE UNIQUE INDEX uq_boost_campaign_member ON boost_campaign_claim(member_id);
INSERT INTO boost_config(config_key,config_value) SELECT 'rate:lock','1' WHERE NOT EXISTS (SELECT 1 FROM boost_config WHERE config_key='rate:lock');
INSERT INTO boost_config(config_key,config_value) SELECT 'campaign:lock','1' WHERE NOT EXISTS (SELECT 1 FROM boost_config WHERE config_key='campaign:lock');
UPDATE boost_order SET expires_at=TIMESTAMPADD(MINUTE,30,created_at) WHERE expires_at IS NULL;
