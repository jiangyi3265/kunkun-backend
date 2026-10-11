-- Additive public profile data. Only province is saved, never raw login IP.
ALTER TABLE boost_player_profile ADD COLUMN bio VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE boost_player_profile ADD COLUMN ip_province VARCHAR(30) NOT NULL DEFAULT '未知';
ALTER TABLE boost_player_profile ADD COLUMN ip_observed_at TIMESTAMP NULL;
