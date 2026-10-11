-- Spendable, free-only points are separate from growth and all money balances.
CREATE TABLE IF NOT EXISTS boost_points_account (
 member_id BIGINT PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_points_entry (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL,
 event_key VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 delta BIGINT NOT NULL, balance BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
 note VARCHAR(200) NOT NULL, admin_id BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(member_id,event_key), FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_reward_media (
 name VARCHAR(40) PRIMARY KEY, admin_id BIGINT NOT NULL, size_bytes BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS boost_reward_goods (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(60) NOT NULL, kind VARCHAR(20) NOT NULL,
 badge_code VARCHAR(32), image VARCHAR(100) NOT NULL DEFAULT '', description VARCHAR(1000) NOT NULL,
 points_cost BIGINT NOT NULL, stock INT NOT NULL, revision INT NOT NULL DEFAULT 1, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(badge_code)
);
CREATE TABLE IF NOT EXISTS boost_reward_activity (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(60) NOT NULL, cover VARCHAR(100) NOT NULL DEFAULT '',
 description VARCHAR(1000) NOT NULL, points_cost BIGINT NOT NULL,
 starts_at TIMESTAMP NOT NULL, ends_at TIMESTAMP NOT NULL, revision INT NOT NULL DEFAULT 1, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS boost_reward_prize (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, activity_id BIGINT NOT NULL, goods_id BIGINT,
 weight INT NOT NULL, position INT NOT NULL,
 FOREIGN KEY(activity_id) REFERENCES boost_reward_activity(id), FOREIGN KEY(goods_id) REFERENCES boost_reward_goods(id)
);
CREATE TABLE IF NOT EXISTS boost_reward_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, goods_id BIGINT NOT NULL,
 title VARCHAR(60) NOT NULL, kind VARCHAR(20) NOT NULL, image VARCHAR(100) NOT NULL, badge_code VARCHAR(32),
 source VARCHAR(20) NOT NULL, points_spent BIGINT NOT NULL, request_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, status VARCHAR(30) NOT NULL,
 recipient VARCHAR(50) NOT NULL DEFAULT '', phone VARCHAR(20) NOT NULL DEFAULT '', address VARCHAR(300) NOT NULL DEFAULT '',
 carrier VARCHAR(60) NOT NULL DEFAULT '', tracking_no VARCHAR(60) NOT NULL DEFAULT '',
 shipped_at TIMESTAMP NULL, received_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(member_id,request_key), FOREIGN KEY(member_id) REFERENCES boost_member(id), FOREIGN KEY(goods_id) REFERENCES boost_reward_goods(id)
);
CREATE TABLE IF NOT EXISTS boost_member_badge (
 member_id BIGINT NOT NULL, badge_code VARCHAR(32) NOT NULL, title VARCHAR(60) NOT NULL, image VARCHAR(100) NOT NULL,
 earned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(member_id,badge_code),
 FOREIGN KEY(member_id) REFERENCES boost_member(id)
);
CREATE TABLE IF NOT EXISTS boost_reward_draw (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL, draw_count INT NOT NULL,
 points_spent BIGINT NOT NULL, results TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(member_id,request_key), FOREIGN KEY(member_id) REFERENCES boost_member(id), FOREIGN KEY(activity_id) REFERENCES boost_reward_activity(id)
);
CREATE INDEX idx_reward_order_member ON boost_reward_order(member_id,id);
CREATE INDEX idx_points_entry_member ON boost_points_entry(member_id,id);
