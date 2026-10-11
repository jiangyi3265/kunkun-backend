"""Independent actual MySQL observation of the official Devtools UI flow."""
import json,re,sys
from pathlib import Path
from datetime import datetime,timezone
from local_db import ROOT,configuration,mysql
out=Path(sys.argv[1]).resolve();assert out.is_relative_to(ROOT/'test-results')
report=json.loads((out/'native-integration.json').read_text('utf-8'));assert report['exitCode']==0
fixture=Path(report['server']['fixtureFile']).resolve();assert fixture.is_relative_to(ROOT/'.runtime')
f=json.loads(fixture.read_text('utf-8'));db=f['database'];assert db!=configuration()['database'] and re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',db)
assert f['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
ids=report['fixture'];assert all(isinstance(v,int) and v>0 for v in ids.values())
m=str(ids['memberId']);g=str(ids['goodsId']);order=str(ids['orderId'])
count=lambda q:int(mysql(q,db).strip().splitlines()[-1])
assert count('SELECT balance FROM boost_points_account WHERE member_id='+m)==173
assert count('SELECT SUM(delta) FROM boost_points_entry WHERE member_id='+m)==173
assert count('SELECT COUNT(*) FROM boost_points_entry WHERE member_id='+m)==5
assert count('SELECT COUNT(*) FROM boost_member_badge WHERE member_id='+m)==1
assert count('SELECT COUNT(*) FROM boost_reward_draw WHERE member_id='+m)==2
assert count('SELECT SUM(draw_count) FROM boost_reward_draw WHERE member_id='+m)==6
assert count('SELECT SUM(points_spent) FROM boost_reward_draw WHERE member_id='+m)==12
assert count("SELECT COUNT(*) FROM boost_reward_order WHERE id="+order+" AND member_id="+m+" AND status='COMPLETED' AND recipient='隔离收货人' AND phone='13800000000' AND address='测试省测试市测试街道一号' AND carrier='原生测试物流' AND tracking_no='NATIVE001'")==1
wins=count("SELECT COUNT(*) FROM boost_reward_order WHERE goods_id="+g+" AND member_id="+m+" AND source='DRAW'")
assert count('SELECT stock FROM boost_reward_goods WHERE id='+g)==19-wins
assert count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id="+m+" AND growth_points=150 AND benefits_revision=2 AND membership_type='MEMBER' AND membership_expires_at='2027-01-01 00:00:00' AND realname_status='NONE' AND shop_status='NONE'")==1
assert count('SELECT COUNT(*) FROM boost_growth_event WHERE member_id='+m)==1
assert count('SELECT SUM(points) FROM boost_growth_event WHERE member_id='+m)==150
assert count('SELECT COUNT(*) FROM boost_benefit_action WHERE member_id='+m)==2
assert count('SELECT balance_cents FROM boost_member WHERE id='+m)==0
result={'status':'已实测通过','exitCode':0,'checkedAt':datetime.now(timezone.utc).isoformat(),'database':db,'checks':['173 points equals five ledger entries','One public badge and completed physical shipment with exact synthetic address','Two persisted draws contain 1+5 results and 12-point debit','Physical stock equals 19 minus draw awards','Membership expiry and 150 growth persisted; one growth entry, two benefit journal rows','No money, realname or shop certification manufactured'],'observed':{'memberId':ids['memberId'],'balance':173,'drawAwards':wins,'physicalStock':19-wins,'growth':150},'scope':'Direct SQL on marked isolated DB after actual native Devtools UI; no real device/KYC/payment/parcel claim'}
(out/'native-persistence.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(result,ensure_ascii=False))
