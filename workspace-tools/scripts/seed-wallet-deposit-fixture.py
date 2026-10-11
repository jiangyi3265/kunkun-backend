"""Synthetic display balances in an owned test schema, never a deposit/payment operation."""
import json,os,re,sys
from pathlib import Path
from local_db import ROOT,configuration,mysql
fixture_path=Path(sys.argv[1]).resolve();assert fixture_path.is_relative_to(ROOT/'.runtime')
fixture=json.loads(fixture_path.read_text(encoding='utf-8'))
private=json.loads(Path(fixture['environmentFile']).read_text(encoding='utf-8'))
os.environ.update({k:str(v) for k,v in private.items() if k.startswith('BOOST_DB_')})
cfg=configuration();database=cfg['database']
assert cfg['host'] in ('localhost','127.0.0.1') and database==fixture['database'] and re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',database)
assert mysql('SELECT marker FROM qa_ui_fixture_marker',database).strip().splitlines()[1]==fixture['marker']
member=int(sys.argv[2]);assert member>0
row=mysql('SELECT username FROM boost_member WHERE id='+str(member),database).strip().splitlines()
assert len(row)==2 and row[1].startswith('qa_deposit_'),'Only a fresh deposit acceptance account can be seeded'
assert mysql('SELECT COUNT(*) FROM boost_ledger WHERE member_id='+str(member),database).strip().splitlines()[-1]=='0'
mysql('UPDATE boost_member SET balance_cents=1234,deposit_cents=5678 WHERE id='+str(member),database)
print(json.dumps({'memberId':member,'balanceCents':1234,'depositCents':5678,'scope':'Explicit synthetic SQL fixture; no deposit, withdrawal, or real payment was performed'}))
