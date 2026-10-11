"""Own marked local production-mode fixture; never writes shared boost business rows."""
import argparse,json,os,re,secrets,subprocess,sys
from pathlib import Path
from local_db import ROOT,configuration,mysql

def literal(v):return 'CONVERT(0x'+str(v).encode().hex()+' USING utf8mb4)'
p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','snapshot','hide-order','restore-order']);p.add_argument('--runtime',required=True);args=p.parse_args()
runtime=Path(args.runtime).resolve();assert runtime.is_relative_to(ROOT/'.runtime') and runtime.name.startswith('nonpayment-prod-')
fixture=runtime/'fixture.json';private=runtime/'production.private.json'
if args.action=='prepare':
 assert not fixture.exists() and not private.exists();runtime.mkdir(parents=True,exist_ok=True)
 r=subprocess.run([sys.executable,'-X','utf8',str(ROOT/'scripts/prepare-admin-media-fixture.py'),'prepare','--output',str(fixture)],capture_output=True,text=True)
 if r.returncode:raise RuntimeError(r.stderr)
 f=json.loads(fixture.read_text(encoding='utf-8'));db=f['database'];user='qa_np_'+secrets.token_hex(8);password=secrets.token_urlsafe(32)
 assert re.fullmatch(r'boost_verify_media_[0-9]+',db)
 assert re.fullmatch(r'qa_np_[a-f0-9]{16}',user) and re.fullmatch(r'[A-Za-z0-9_-]{40,64}',password)
 mysql("CREATE USER '"+user+"'@'localhost' IDENTIFIED BY '"+password+"'; GRANT SELECT, INSERT, UPDATE, DELETE ON `"+db+"`.* TO '"+user+"'@'localhost';")
 c=configuration();values={
  'SPRING_PROFILES_ACTIVE':'prod','BOOST_PAYMENTS_ENABLED':'false',
  'BOOST_DB_URL':f"jdbc:mysql://127.0.0.1:{c['port']}/{db}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",'BOOST_DB_USER':user,'BOOST_DB_PASSWORD':password,
  'BOOST_REDIS_HOST':'127.0.0.1','BOOST_REDIS_PASSWORD':secrets.token_urlsafe(32),'BOOST_REDIS_DATABASE':'0',
  'BOOST_JWT_SECRET':secrets.token_urlsafe(64),'BOOST_MEDIA_SIGNING_KEY':secrets.token_urlsafe(48),
  'BOOST_MEDIA_DIR':f['mediaDirectory'],'BOOST_UPLOAD_DIR':str(runtime/'public-uploads'),'BOOST_LOG_DIR':str(runtime/'logs'),
  'BOOST_PUBLIC_API_ORIGIN':'https://ceshisss.oksja.cn','BOOST_WEB_ORIGINS':'https://ceshijjht.oksja.cn',
  'BOOST_OPERATOR':'隔离测试主体（不代表真实运营主体）','BOOST_PRIVACY_EMAIL':'qa@example.com','BOOST_RETENTION':'隔离测试夹具，不代表实际数据保存承诺',
  'BOOST_POLICY_VERSION':'nonpayment-isolated-20261004','BOOST_POLICY_DATE':'2026-10-04',
  'BOOST_WX_APP_ID':'wxd31a958f0fc66a19','BOOST_WX_APP_SECRET':'isolated-test-secret-no-wechat-request',
  'BOOST_CAMPAIGN_DAILY_CENTS':'0','BOOST_CAMPAIGN_TOTAL_CENTS':'0'}
 private.write_text(json.dumps(values,ensure_ascii=False),encoding='utf-8')
 print(json.dumps({'prepared':True,'database':db,'account':user,'merchantFieldsConfigured':False,'policy':'explicit isolated fixture, not legal production readiness'}))
else:
 f=json.loads(fixture.read_text(encoding='utf-8'));db=f['database'];assert re.fullmatch(r'boost_verify_media_[0-9]+',db) and db!=configuration()['database']
 actual=mysql('SELECT marker FROM qa_media_fixture_marker',db).strip().splitlines()[-1];assert actual==f['marker']
 if args.action=='hide-order':mysql('RENAME TABLE boost_order TO qa_np_hidden_order',db);print('Owned isolated order table hidden')
 elif args.action=='restore-order':
  if 'qa_np_hidden_order' in mysql('SHOW TABLES',db).splitlines():mysql('RENAME TABLE qa_np_hidden_order TO boost_order',db)
  print('Owned isolated order table restored')
 else:
  result={'database':db,'markerValid':True}
  for name in ['boost_ledger','boost_withdrawal','boost_aftersale','boost_progress','boost_review']:
   result[name]=int(mysql('SELECT COUNT(*) FROM '+name,db).strip().splitlines()[-1])
  result['walletCents']=int(mysql('SELECT COALESCE(SUM(balance_cents),0) FROM boost_member',db).strip().splitlines()[-1])
  result['paidOrders']=int(mysql("SELECT COUNT(*) FROM boost_order WHERE paid_at IS NOT NULL OR payment_mode IS NOT NULL OR status NOT IN ('WAIT_PAY','CANCELLED')",db).strip().splitlines()[-1])
  print(json.dumps(result))
