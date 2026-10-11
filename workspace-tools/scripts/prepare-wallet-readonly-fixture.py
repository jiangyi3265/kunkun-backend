"""Owned SQL historical fixture after demo bootstrap, before financial snapshots."""
import argparse,json,os,re,secrets,time,uuid
from pathlib import Path
import bcrypt
from local_db import ROOT,configuration,mysql
parser=argparse.ArgumentParser();parser.add_argument('fixture');args=parser.parse_args()
destination=Path(args.fixture).resolve();assert destination.is_relative_to(ROOT/'.runtime')
fixture=json.loads(destination.read_text(encoding='utf-8'));assert 'walletHistory' not in fixture
private=json.loads(Path(fixture['environmentFile']).read_text(encoding='utf-8'))
os.environ.update({k:str(v) for k,v in private.items() if k.startswith('BOOST_DB_')})
c=configuration();database=c['database']
assert c['host'] in ('127.0.0.1','localhost') and database==fixture['database'] and re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',database)
assert mysql('SELECT marker FROM qa_ui_fixture_marker',database).strip().splitlines()[1]==fixture['marker']
assert int(mysql("SELECT COUNT(*) FROM boost_member WHERE username IN ('demo_buyer','demo_worker')",database).strip().splitlines()[-1])==2,'Seed only after actual owned demo bootstrap'
assert int(mysql("SELECT COUNT(*) FROM boost_listing WHERE id=1 AND status='ACTIVE' AND owner_id=(SELECT id FROM boost_member WHERE username='demo_worker')",database).strip().splitlines()[-1])==1,'Existing navigation fixture id1 must be preserved'
quote=lambda value:'CONVERT(0x'+str(value).encode().hex()+' USING utf8mb4)'
# Deterministic historical rows for read-only UI checks. These are explicit SQL
# fixtures, never proof that a payment/settlement/withdrawal service was executed.
suffix=str(time.time_ns()//1000000)+'_'+uuid.uuid4().hex[:5]
wallet_username='qa_ro_w_'+suffix;buyer_username='qa_ro_b_'+suffix
wallet_password=secrets.token_urlsafe(15)
wallet_hash=bcrypt.hashpw(wallet_password.encode(),bcrypt.gensalt()).decode()
history=[
    'START TRANSACTION',
    "INSERT INTO boost_member(username,password_hash,nickname) VALUES ("+quote(buyer_username)+','+quote(wallet_hash)+",'只读历史买家')",
    'SET @qa_ro_buyer=LAST_INSERT_ID()',
    "INSERT INTO boost_member(username,password_hash,nickname,certification,balance_cents) VALUES ("+quote(wallet_username)+','+quote(wallet_hash)+",'只读历史打手','APPROVED',1000)",
    'SET @qa_ro_worker=LAST_INSERT_ID()',
    "INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,duration_hours,price_cents,description,images,status) VALUES (@qa_ro_worker,'SERVICE','SQL合成历史服务','王者荣耀','微信区','安卓',24,1000,'仅验证只读历史展示，未执行支付或履约服务','[]','OFFLINE')",
    'SET @qa_ro_listing=LAST_INSERT_ID()',
    "INSERT INTO boost_order(order_no,request_key,buyer_id,worker_id,listing_id,title,game,zone,platform,duration_hours,original_cents,discount_cents,amount_cents,status,progress,payment_mode,transaction_id,snapshot_images,paid_at,completed_at) VALUES ("+quote('QA_RO_'+suffix)+','+quote('qa_ro_'+suffix)+",@qa_ro_buyer,@qa_ro_worker,@qa_ro_listing,'SQL合成历史订单','王者荣耀','微信区','安卓',24,1000,0,1000,'COMPLETED',100,'SIMULATED','SQL_FIXTURE_ONLY','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
    'SET @qa_ro_order=LAST_INSERT_ID()',
    "INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents,order_id) VALUES (@qa_ro_worker,CONCAT('settle:',@qa_ro_order),'SETTLEMENT','合成历史结算',1000,1000,@qa_ro_order)",
    "INSERT INTO boost_withdrawal(member_id,request_key,amount_cents,status,account_name,account_number,note) VALUES (@qa_ro_worker,"+quote('qa_ro_'+suffix)+",1000,'REJECTED','测试历史姓名','SQL_FIXTURE_ONLY','SQL合成历史拒绝，无渠道提交')",
    'SET @qa_ro_withdrawal=LAST_INSERT_ID()',
    "INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents) VALUES (@qa_ro_worker,CONCAT('withdraw:',@qa_ro_withdrawal),'WITHDRAWAL','合成历史冻结',-1000,0),(@qa_ro_worker,CONCAT('withdraw-return:',@qa_ro_withdrawal),'WITHDRAWAL_RETURN','合成历史退回',1000,1000)",
    'COMMIT',
    "SELECT JSON_OBJECT('buyerId',@qa_ro_buyer,'workerId',@qa_ro_worker,'listingId',@qa_ro_listing,'orderId',@qa_ro_order,'withdrawalId',@qa_ro_withdrawal,'walletCents',1000,'ledgerRows',3,'withdrawalRows',1,'runningBalances',JSON_ARRAY(1000,0,1000),'scope','SQL synthetic history prepared before the funds baseline; real read-only HTTP/UI/MySQL verification only. No payment, fulfillment, settlement or withdrawal service/channel executed.')"
]
wallet_history=json.loads(mysql(';\n'.join(history)+';',database).strip().splitlines()[-1])
assert wallet_history['runningBalances']==[1000,0,1000]
fixture['walletHistory']=wallet_history
fixture['credentials'].update({'walletUsername':wallet_username,'walletPassword':wallet_password})
destination.write_text(json.dumps(fixture,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'database':database,'walletHistory':wallet_history,'scope':'Owned SQL synthetic historical read-only fixture after actual demo initialization, before funds-before. No payment service or shared business data.'},ensure_ascii=False))
