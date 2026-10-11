"""Read-only local aggregate snapshot proving the non-payment run leaves money untouched."""
import json
from pathlib import Path
import sys, os
from local_db import mysql

target=Path(os.environ.get('QA_OUTPUT_DIR',Path(__file__).resolve().parent.parent/'test-results/nonpayment-20261002'))
target.mkdir(parents=True,exist_ok=True)
phase=sys.argv[1]
assert phase in ('before','after')
queries={
    'walletTotalCents':'SELECT COALESCE(SUM(balance_cents),0) FROM boost_member',
    'ledgerRows':'SELECT COUNT(*) FROM boost_ledger',
    'ledgerTotalCents':'SELECT COALESCE(SUM(amount_cents),0) FROM boost_ledger',
    'withdrawals':'SELECT COUNT(*) FROM boost_withdrawal',
    'aftersales':'SELECT COUNT(*) FROM boost_aftersale',
    'paidOrders':"SELECT COUNT(*) FROM boost_order WHERE status IN ('WAIT_START','IN_PROGRESS','REVIEW','COMPLETED','AFTERSALE','REFUNDING','REFUND_EXCEPTION','REFUNDED')",
}
sql='SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;'
sql+='SELECT '+','.join('('+q+') AS '+k for k,q in queries.items())+';COMMIT;'
lines=[s for s in mysql(sql).splitlines() if s.strip()]
result=dict(zip(queries,map(int,lines[1].split('\t'))))
(target/('funds-'+phase+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
if phase=='after':
    before=json.loads((target/'funds-before.json').read_text(encoding='utf-8'))
    comparison={'passed':before==result,'before':before,'after':result}
    (target/'funds-unchanged.json').write_text(json.dumps(comparison,indent=2),encoding='utf-8')
    print(json.dumps(comparison))
    if before!=result:sys.exit(1)
else:print('Read-only money snapshot recorded.')
