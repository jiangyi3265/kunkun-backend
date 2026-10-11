-- Separate verified contact details from public profiles. Existing submissions stay locked.
ALTER TABLE boost_player_profile ADD COLUMN shop_submitted_at TIMESTAMP NULL;
ALTER TABLE boost_player_profile ADD COLUMN shop_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS boost_member_phone (
 member_id BIGINT PRIMARY KEY, phone VARCHAR(11) NOT NULL UNIQUE,
 verified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_phone_challenge (
 id VARCHAR(36) PRIMARY KEY, member_id BIGINT NOT NULL, purpose VARCHAR(20) NOT NULL,
 phone VARCHAR(11) NOT NULL, code_hash VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'SENDING', attempts INT NOT NULL DEFAULT 0,
 expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE INDEX idx_boost_phone_member ON boost_phone_challenge(member_id,created_at);
CREATE TABLE IF NOT EXISTS boost_shop_revision (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, revision BIGINT NOT NULL,
 shop_name VARCHAR(40) NOT NULL, shop_intro VARCHAR(500) NOT NULL, shop_proof TEXT,
 phone VARCHAR(11), phone_verified_at TIMESTAMP NULL, shop_status VARCHAR(20) NOT NULL,
 admin_id BIGINT NULL, note VARCHAR(500) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(member_id,revision), FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
