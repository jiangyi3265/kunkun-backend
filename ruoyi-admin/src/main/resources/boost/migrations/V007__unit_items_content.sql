-- Existing lots and historical orders keep their original pricing and quantities.
ALTER TABLE boost_listing ADD COLUMN pricing_mode VARCHAR(12) NOT NULL DEFAULT 'LOT';
ALTER TABLE boost_listing ADD COLUMN reserved_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE boost_listing ADD COLUMN sold_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE boost_listing ADD COLUMN detail_images TEXT;
ALTER TABLE boost_order ADD COLUMN pricing_mode VARCHAR(12) NOT NULL DEFAULT 'LOT';
ALTER TABLE boost_order ADD COLUMN unit_price_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE boost_order ADD COLUMN detail_images TEXT;
ALTER TABLE boost_order ADD COLUMN purchase_hash VARCHAR(64) NOT NULL DEFAULT '';
CREATE TABLE IF NOT EXISTS boost_site_content (
 id BIGINT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0, payload TEXT NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS boost_content_activity (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(60) NOT NULL,
 summary VARCHAR(200) NOT NULL DEFAULT '', cover VARCHAR(200) NOT NULL DEFAULT '',
 body VARCHAR(5000) NOT NULL DEFAULT '', detail_images TEXT,
 starts_at TIMESTAMP NOT NULL, ends_at TIMESTAMP NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', reward_activity_id BIGINT NULL,
 revision BIGINT NOT NULL DEFAULT 0, create_key VARCHAR(64) UNIQUE, request_hash VARCHAR(64),
 published_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_boost_content_activity_status ON boost_content_activity(status,starts_at,id);
