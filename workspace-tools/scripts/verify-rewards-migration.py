"""Actual MySQL existing V004 profile/wallet -> additive V005, in an owned new DB."""
import hashlib,json,os,re,time
from datetime import datetime,timezone
from pathlib import Path
from local_db import ROOT,configuration,mysql
out=Path(os.environ.get('PLAYER_ACCEPTANCE_OUT',ROOT/'test-results/rewards-20261008/final'));out.mkdir(parents=True,exist_ok=True)
started=datetime.now(timezone.utc).isoformat();db='boost_verify_rewards_migration_'+str(time.time_ns()//1000000);assert re.fullmatch(r'boost_verify_rewards_migration_[0-9]+',db)
c=configuration();assert c['host'] in ('localhost','127.0.0.1') and db!=c['database'];mysql('CREATE DATABASE `'+db+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
r=ROOT/'RuoYi-Vue/ruoyi-admin/src/main/resources/boost'
for f in [r/'schema.sql',*[r/('migrations/'+v+'.sql') for v in ['V002__commercial_hardening','V003__player_items','V004__player_public_profiles']]]:mysql(f.read_text(encoding='utf-8'),db)
mysql("INSERT INTO boost_member(username,password_hash,nickname,balance_cents) VALUES ('v004rewards','fixture-only','既有玩家',26999); INSERT INTO boost_player_profile(member_id,shop_name,shop_intro,game_uid,shop_status,realname_status,membership_type,membership_expires_at,growth_points,bio,ip_province) VALUES (LAST_INSERT_ID(),'既有店铺','既有介绍','123456','APPROVED','VERIFIED','MEMBER','2030-01-01',150,'既有简介','浙江')",db)
query='SELECT m.id,m.username,m.balance_cents,p.shop_name,p.realname_status,p.membership_type,p.growth_points,p.bio,p.ip_province FROM boost_member m JOIN boost_player_profile p ON p.member_id=m.id'
before=mysql(query,db);migration=r/'migrations/V005__free_points_rewards.sql';mysql(migration.read_text(encoding='utf-8'),db);assert mysql(query,db)==before,'V004 business data changed'
tables=['boost_points_account','boost_points_entry','boost_reward_media','boost_reward_goods','boost_reward_activity','boost_reward_prize','boost_reward_order','boost_member_badge','boost_reward_draw']
for table in tables:assert mysql('SELECT COUNT(*) FROM '+table,db).strip().splitlines()[-1]=='0'
report={'startedAt':started,'finishedAt':datetime.now(timezone.utc).isoformat(),'status':'已实测通过','exitCode':0,'database':db,'preservedProfileWalletFingerprint':hashlib.sha256(before.encode()).hexdigest(),'migrationSha256':hashlib.sha256(migration.read_bytes()).hexdigest(),'checks':['Existing V004 profile, identity/member fixture, growth, IP, bio and wallet preserved','9 new tables initially empty; no free points or fake rewards generated automatically'],'scope':'Actual local MySQL V004 -> V005 SQL with synthetic existing records. Java suite separately verifies checksums/repeat migration. No production migration.','cleanup':'Owned DB retained; no shared data modified'}
(out/'rewards-migration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS real MySQL V004 -> V005: old business values preserved, 9 empty reward tables')
