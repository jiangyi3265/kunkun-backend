"""Independent SQL observation after actual built UI/HTTP flows, in the marked fixture."""
import argparse,json,re,os
from pathlib import Path
from datetime import datetime,timezone
from local_db import ROOT,configuration,mysql
p=argparse.ArgumentParser();p.add_argument('--fixture',required=True);a=p.parse_args();f=Path(a.fixture).resolve();assert f.is_relative_to(ROOT/'.runtime');fixture=json.loads(f.read_text(encoding='utf-8'));db=fixture['database'];assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',db) and db!=configuration()['database'];assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
out=Path(os.environ['PLAYER_ACCEPTANCE_OUT']);r=json.loads((out/'rewards-real-browser.json').read_text(encoding='utf-8'));assert r['exitCode']==0;ids=r['fixture'];assert ids['database']==db
for name in ['memberId','goodsId','badgeId','activityId','orderId']:assert isinstance(ids[name],int) and ids[name]>0
member=str(ids['memberId']);goods=str(ids['goodsId']);order=str(ids['orderId'])
count=lambda sql:int(mysql(sql,db).strip().splitlines()[-1])
balance=count('SELECT balance FROM boost_points_account WHERE member_id='+member);assert balance==158
assert count('SELECT SUM(delta) FROM boost_points_entry WHERE member_id='+member)==balance
ledger=count('SELECT COUNT(*) FROM boost_points_entry WHERE member_id='+member);assert ledger==5
assert count('SELECT COUNT(*) FROM boost_member_badge WHERE member_id='+member)==1
assert count("SELECT COUNT(*) FROM boost_reward_order WHERE id="+order+" AND member_id="+member+" AND status='COMPLETED' AND recipient='测试收件人' AND phone='13800000000' AND address='测试省测试市测试区测试街道1号' AND carrier='测试物流' AND tracking_no='TESTREWARD001'")==1
assert count('SELECT COUNT(*) FROM boost_reward_draw WHERE member_id='+member)==2
assert count('SELECT SUM(draw_count) FROM boost_reward_draw WHERE member_id='+member)==6
assert count('SELECT SUM(points_spent) FROM boost_reward_draw WHERE member_id='+member)==12
wins=count("SELECT COUNT(*) FROM boost_reward_order WHERE member_id="+member+" AND goods_id="+goods+" AND source='DRAW'")
stock=count('SELECT stock FROM boost_reward_goods WHERE id='+goods);assert stock==2-wins and stock>=0
assert count('SELECT balance_cents FROM boost_member WHERE id='+member)==0
assert count('SELECT COALESCE(MAX(growth_points),0) FROM boost_player_profile WHERE member_id='+member)==0
for row in mysql('SELECT HEX(results) AS snapshot FROM boost_reward_draw WHERE member_id='+member,db).splitlines()[1:]:
 for result in json.loads(bytes.fromhex(row).decode()):
  assert result['kind'] in ['NONE','PHYSICAL']
  for probability in result['odds']:assert all(key in probability for key in ['goodsId','title','kind','position','weight','probability'])
report={'status':'已实测通过','exitCode':0,'time':datetime.now(timezone.utc).isoformat(),'database':db,'observed':{'memberId':ids['memberId'],'balance':balance,'ledgerRows':ledger,'badgeCount':1,'completedOrderId':ids['orderId'],'physicalDrawWins':wins,'stock':stock,'drawRecords':2,'drawCount':6,'drawPointsSpent':12,'moneyAndGrowthUnchanged':True,'shippingFieldsMatched':True},'scope':'Direct actual MySQL after built admin/H5 and real HTTP. Generated shipping data masked in report; no real parcel/provider.'}
(out/'rewards-http-persistence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS direct MySQL: points, stock, draw snapshots, public badge and completed physical order match actual UI/API')
