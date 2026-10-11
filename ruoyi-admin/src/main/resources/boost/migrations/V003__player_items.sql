-- New player-item records are explicit. Historical skills and orders remain historical.
ALTER TABLE boost_listing ADD COLUMN business_type VARCHAR(24) NOT NULL DEFAULT 'LEGACY_BOOST';
ALTER TABLE boost_listing ADD COLUMN item_category VARCHAR(30) NOT NULL DEFAULT '';
ALTER TABLE boost_listing ADD COLUMN quantity INT NOT NULL DEFAULT 1;
ALTER TABLE boost_listing ADD COLUMN item_details VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE boost_listing ADD COLUMN delivery_terms VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE boost_order ADD COLUMN business_type VARCHAR(24) NOT NULL DEFAULT 'LEGACY_BOOST';
ALTER TABLE boost_order ADD COLUMN item_category VARCHAR(30) NOT NULL DEFAULT '';
ALTER TABLE boost_order ADD COLUMN quantity INT NOT NULL DEFAULT 1;
ALTER TABLE boost_order ADD COLUMN item_details VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE boost_order ADD COLUMN delivery_terms VARCHAR(500) NOT NULL DEFAULT '';
CREATE TABLE IF NOT EXISTS boost_player_profile (
 member_id BIGINT PRIMARY KEY, shop_name VARCHAR(40) NOT NULL DEFAULT '',
 shop_intro VARCHAR(500) NOT NULL DEFAULT '', game_uid VARCHAR(40) NOT NULL DEFAULT '',
 shop_status VARCHAR(20) NOT NULL DEFAULT 'NONE', shop_proof TEXT,
 shop_review_note VARCHAR(500) NOT NULL DEFAULT '',
 realname_status VARCHAR(20) NOT NULL DEFAULT 'NONE', realname_provider VARCHAR(40) NOT NULL DEFAULT '',
 identity_subject_hash VARCHAR(64), realname_verified_at TIMESTAMP NULL,
 membership_type VARCHAR(20) NOT NULL DEFAULT 'FREE', membership_expires_at TIMESTAMP NULL,
 growth_points BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_identity_session (
 id VARCHAR(64) PRIMARY KEY, member_id BIGINT NOT NULL, provider VARCHAR(40) NOT NULL,
 provider_token VARCHAR(128) NOT NULL UNIQUE, auth_url VARCHAR(2000) NOT NULL DEFAULT '', status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 expires_at TIMESTAMP NOT NULL, provider_request_id VARCHAR(100) NOT NULL DEFAULT '',
 verified_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_item_delivery (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL UNIQUE, seller_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 note VARCHAR(1000) NOT NULL, images TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(order_id) REFERENCES boost_order(id), FOREIGN KEY(seller_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_growth_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, event_key VARCHAR(100) NOT NULL UNIQUE,
 points BIGINT NOT NULL, reason VARCHAR(200) NOT NULL, rule_version VARCHAR(40) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE INDEX ix_boost_item_market ON boost_listing(business_type,game,item_category,status,id);
