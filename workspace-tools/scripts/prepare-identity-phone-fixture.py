"""Synthetic identity/SMS receipt only inside a freshly marked, owned localhost QA database."""
import argparse,json,re,uuid,secrets,bcrypt
from pathlib import Path
from local_db import ROOT,configuration,mysql
p=argparse.ArgumentParser();p.add_argument('--fixture',required=True);p.add_argument('--member',type=int);p.add_argument('--challenge',action='store_true');p.add_argument('--verify',action='store_true');p.add_argument('--expect-report');a=p.parse_args()
f=Path(a.fixture).resolve();assert f.is_relative_to(ROOT/'.runtime');fixture=json.loads(f.read_text('utf-8'));db=fixture['database'];assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',db) and db!=configuration()['database'];assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
quote=lambda x:'CONVERT(0x'+str(x).encode().hex()+' USING utf8mb4)'
if a.verify:
 if a.expect_report:
  evidence=Path(a.expect_report).resolve();assert evidence.is_relative_to(ROOT/'test-results')
  actors=json.loads(evidence.read_text('utf-8'))['fixture'];seller=int(actors['seller']);buyer=int(actors['buyer'])
  checks={
   'shop once-only and admin history': f'SELECT COUNT(*) FROM boost_shop_revision WHERE member_id={seller} AND revision BETWEEN 1 AND 4',
   'current rejected version retained': f"SELECT COUNT(*) FROM boost_player_profile WHERE member_id={seller} AND shop_revision=4 AND shop_status='REJECTED' AND shop_submitted_at IS NOT NULL",
   'verified contact persisted': f'SELECT COUNT(*) FROM boost_member_phone WHERE member_id={seller} AND verified_at IS NOT NULL',
   'growth 150 persisted': f'SELECT COUNT(*) FROM boost_growth_event WHERE member_id={seller} AND points=150',
   'request active without shop': f"SELECT COUNT(*) FROM boost_listing WHERE owner_id={buyer} AND kind='REQUEST' AND status='ACTIVE'",
   'buyer remains without shop': f"SELECT COUNT(*) FROM boost_player_profile WHERE member_id={buyer} AND shop_status='NONE'",
  }
  for name,sql in checks.items(): assert int(mysql(sql,db).strip().splitlines()[-1])==(4 if name=='shop once-only and admin history' else 1),name
  print(json.dumps({'assertions': list(checks), 'status':'已实测通过'},ensure_ascii=False))
 print(json.dumps({'shopRevisions':mysql('SELECT member_id,revision,shop_name,shop_status,admin_id FROM boost_shop_revision ORDER BY id',db),'phones':mysql("SELECT member_id,CONCAT(LEFT(phone,3),'****',RIGHT(phone,4)) AS masked_phone FROM boost_member_phone",db),'growth':mysql('SELECT member_id,points,reason FROM boost_growth_event',db),'listings':mysql('SELECT id,owner_id,kind,title,status FROM boost_listing',db)},ensure_ascii=False))
else:
 member=a.member;assert member and 'qa_id_' in mysql('SELECT username FROM boost_member WHERE id='+str(member),db)
 if a.challenge:
  challenge=str(uuid.uuid4());code=f'{secrets.randbelow(1000000):06}';phone=f'139{member:08}'
  digest=bcrypt.hashpw(code.encode(),bcrypt.gensalt()).decode()
  mysql("INSERT INTO boost_phone_challenge(id,member_id,purpose,phone,code_hash,status,expires_at) VALUES ("+quote(challenge)+f",{member},'MEMBER',"+quote(phone)+','+quote(digest)+",'SENT',DATE_ADD(NOW(),INTERVAL 5 MINUTE))",db)
  (f.parent/f'phone-code-{member}.private.json').write_text(json.dumps({'challengeId':challenge,'code':code}),encoding='utf8')
  print('Controlled SMS receipt seeded; not a real sent SMS. Secret fixture remains private.')
 else:
  mysql(f"INSERT INTO boost_player_profile(member_id,realname_status,realname_provider,identity_subject_hash,ip_province) VALUES ({member},'VERIFIED','TENCENT_FACEID','"+'b'*64+f"','广东'); INSERT INTO boost_identity_session(id,member_id,provider,provider_token,status,expires_at,verified_at) VALUES ('qa_id_{member}',{member},'TENCENT_FACEID','qa_identity_fixture_{member}','VERIFIED','2030-01-01',NOW());",db)
  print('Synthetic authoritative identity fixture installed only in '+db+'; NOT real identity verification')
