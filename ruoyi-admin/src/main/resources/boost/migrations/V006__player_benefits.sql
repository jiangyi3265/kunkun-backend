-- Manual, audited membership and growth. No payment or automatic reward rules.
ALTER TABLE boost_player_profile ADD COLUMN benefits_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS boost_benefit_action (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 admin_id BIGINT NOT NULL, revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(member_id,request_key), FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
INSERT INTO boost_config(config_key,config_value) SELECT 'player:growth-thresholds','' WHERE NOT EXISTS (SELECT 1 FROM boost_config WHERE config_key='player:growth-thresholds');
