"""Read persisted order numbers from only this run's marked local QA database."""
import argparse,json,re
from pathlib import Path
from local_db import ROOT,configuration,mysql
p=argparse.ArgumentParser();p.add_argument('--fixture',required=True);p.add_argument('--report',required=True);a=p.parse_args()
f=Path(a.fixture).resolve();assert f.is_relative_to(ROOT/'.runtime')
fixture=json.loads(f.read_text('utf-8'));db=fixture['database'];assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',db) and db!=configuration()['database'];assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
report=Path(a.report).resolve();assert report.is_relative_to(ROOT/'test-results');r=json.loads(report.read_text('utf-8'));assert r['exitCode']==0
rows=mysql("SELECT JSON_OBJECT('id',id,'orderNo',order_no,'status',status,'paidAt',paid_at) FROM boost_order ORDER BY id",db).strip().splitlines()[1:];orders=[json.loads(row) for row in rows]
expected=[r['order'],r['secondOrder'],r['thirdOrder']];assert len(orders)==3
for i,(actual,wanted) in enumerate(zip(orders,expected)):
 assert actual['id']==wanted['id'] and actual['orderNo']==wanted['orderNo'];assert re.fullmatch(r'G[0-9]{12}',actual['orderNo']);assert actual['paidAt'] is None;assert actual['status']==('CANCELLED' if i==0 else 'WAIT_PAY')
prefix=orders[0]['orderNo'][:9];counter=int(mysql("SELECT config_value FROM boost_config WHERE config_key='sequence:order:"+prefix+"'",db).strip().splitlines()[-1]);assert counter==3
result={'exitCode':0,'status':'已实测通过','orders':orders,'dailyCounter':counter,'scope':'Actual isolated MySQL persisted rows, no real payment or identity verification.'}
report.with_name('persisted-orders.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(result,ensure_ascii=False))
