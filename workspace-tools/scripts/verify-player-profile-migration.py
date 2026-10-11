"""Real V003 profile -> additive V004 check, using a newly owned localhost database."""
import json,os,re,time,hashlib
from datetime import datetime,timezone
from pathlib import Path
from local_db import ROOT,configuration,mysql

out=Path(os.environ.get('PLAYER_ACCEPTANCE_OUT',ROOT/'test-results/player-profile-20261008/final'));out.mkdir(parents=True,exist_ok=True)
started=datetime.now(timezone.utc).isoformat();database='boost_verify_profile_'+str(time.time_ns()//1000000)
assert re.fullmatch(r'boost_verify_profile_[0-9]+',database)
c=configuration();assert c['host'] in ('localhost','127.0.0.1') and database!=c['database']
mysql('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
resources=ROOT/'RuoYi-Vue/ruoyi-admin/src/main/resources/boost'
files=[resources/'schema.sql',resources/'migrations/V002__commercial_hardening.sql',resources/'migrations/V003__player_items.sql']
for file in files:mysql(file.read_text(encoding='utf-8'),database)
mysql("INSERT INTO boost_member(username,password_hash,nickname) VALUES ('v003profile','fixture-only','既有玩家'); INSERT INTO boost_player_profile(member_id,shop_name,shop_intro,game_uid,shop_status,realname_status,membership_type,membership_expires_at,growth_points) VALUES (LAST_INSERT_ID(),'既有认证店铺','既有资料','123456','APPROVED','VERIFIED','MEMBER','2030-01-01',150)",database)
query='SELECT member_id,shop_name,shop_intro,game_uid,shop_status,realname_status,membership_type,membership_expires_at,growth_points FROM boost_player_profile'
before=mysql(query,database)
v4=resources/'migrations/V004__player_public_profiles.sql';mysql(v4.read_text(encoding='utf-8'),database)
assert mysql(query,database)==before,'Preexisting V003 profile was changed'
defaults=mysql("SELECT bio='',ip_province='未知',ip_observed_at IS NULL FROM boost_player_profile",database).strip().splitlines()[-1]
assert defaults=='1\t1\t1',defaults
report={'startedAt':started,'finishedAt':datetime.now(timezone.utc).isoformat(),'status':'已实测通过','exitCode':0,'database':database,'preservedProfileFingerprint':hashlib.sha256(before.encode()).hexdigest(),'checks':['Existing shop, identity, membership and growth values preserved','Empty bio / unknown province / NULL observation timestamp applied'],'migrationSha256':hashlib.sha256(v4.read_bytes()).hexdigest(),'scope':'Actual local MySQL V003 preexisting profile -> V004 SQL. Certification/member values are explicit fixtures; not real provider or membership activation. Migration-runner repetition/checksum checks remain in Java suite.','cleanup':'Owned isolated database retained; no production/shared data changed.'}
(out/'profile-migration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS real MySQL V003 existing player profile -> V004: preserved values and new defaults')
