"""Read-only consistency audit of local boost tables; never prints credentials."""
from pathlib import Path
import json, os, re, subprocess

root = Path(__file__).resolve().parent.parent
from local_db import configuration
c = configuration()
host,port,database = c['host'],c['port'],c['database']
def value(key):return c.get({'username':'user'}.get(key,key),'')
checks = {
    'negative_wallet_balances': "SELECT COUNT(*) FROM boost_member WHERE balance_cents<0",
    'wallet_ledger_mismatch': "SELECT COUNT(*) FROM boost_member m LEFT JOIN (SELECT member_id,SUM(amount_cents) total FROM boost_ledger WHERE kind IN ('SETTLEMENT','WITHDRAWAL','WITHDRAWAL_RETURN') GROUP BY member_id) l ON l.member_id=m.id WHERE m.balance_cents<>COALESCE(l.total,0)",
    'completed_order_missing_or_incorrect_settlement': "SELECT COUNT(*) FROM boost_order o LEFT JOIN boost_ledger l ON l.event_key=CONCAT('settle:',o.id) WHERE o.status='COMPLETED' AND (l.id IS NULL OR l.member_id<>o.worker_id OR l.amount_cents<>o.original_cents)",
    'settlement_before_completion': "SELECT COUNT(*) FROM boost_ledger l JOIN boost_order o ON o.id=l.order_id WHERE l.kind='SETTLEMENT' AND o.status<>'COMPLETED'",
    'withdrawal_missing_or_incorrect_freeze': "SELECT COUNT(*) FROM boost_withdrawal w LEFT JOIN boost_ledger l ON l.event_key=CONCAT('withdraw:',w.id) WHERE l.id IS NULL OR l.member_id<>w.member_id OR l.amount_cents<>-w.amount_cents",
    'rejected_withdrawal_missing_or_incorrect_return': "SELECT COUNT(*) FROM boost_withdrawal w LEFT JOIN boost_ledger l ON l.event_key=CONCAT('withdraw-return:',w.id) WHERE w.status IN ('REJECTED','FAILED') AND (l.id IS NULL OR l.member_id<>w.member_id OR l.amount_cents<>w.amount_cents)",
    'coupon_points_to_closed_or_wrong_order': "SELECT COUNT(*) FROM boost_coupon c LEFT JOIN boost_order o ON o.id=c.used_order_id WHERE c.used_order_id IS NOT NULL AND (o.id IS NULL OR o.status IN ('CANCELLED','REFUNDED') OR o.buyer_id<>c.member_id OR o.coupon_id<>c.id)",
    'duplicate_request_key': "SELECT COUNT(*) FROM (SELECT buyer_id,request_key FROM boost_order GROUP BY buyer_id,request_key HAVING COUNT(*)>1) x",
    'multiple_live_orders_for_one_request': "SELECT COUNT(*) FROM (SELECT o.listing_id FROM boost_order o JOIN boost_listing l ON l.id=o.listing_id WHERE l.kind='REQUEST' AND o.status NOT IN ('CANCELLED','REFUNDED') GROUP BY o.listing_id HAVING COUNT(*)>1) x",
    'order_amount_arithmetic_mismatch': "SELECT COUNT(*) FROM boost_order WHERE original_cents-discount_cents<>amount_cents OR amount_cents<=0 OR discount_cents<0",
    'refund_state_disagrees_with_aftersale': "SELECT COUNT(*) FROM boost_order o LEFT JOIN boost_aftersale a ON a.order_id=o.id WHERE o.status IN ('AFTERSALE','REFUNDING','REFUND_EXCEPTION','REFUNDED') AND (a.id IS NULL OR a.status<>CASE o.status WHEN 'AFTERSALE' THEN 'PENDING' ELSE o.status END)",
}
sql = 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;\nSTART TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n'
sql += '\n'.join(query + ';' for query in checks.values()) + '\nCOMMIT;\n'
env = os.environ.copy()
env['MYSQL_PWD'] = value('password')
result = subprocess.run(['mysql', '--host=' + host, '--port=' + (port or '3306'), '--user=' + value('username'), '--default-character-set=utf8mb4', '--batch', '--skip-column-names', database], input=sql.encode(), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
if result.returncode:
    raise SystemExit(result.stderr.decode('utf-8', 'replace'))
counts = [int(line) for line in result.stdout.decode().splitlines() if line.strip()]
if len(counts) != len(checks):
    raise SystemExit('Incomplete audit output')
report = {'passed': all(n == 0 for n in counts), 'readOnly': True, 'violations': dict(zip(checks, counts))}
(Path(os.environ['QA_OUTPUT_DIR'])/'data-audit.json' if os.environ.get('QA_OUTPUT_DIR') else root/'test-results/deep-data-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
raise SystemExit(0 if report['passed'] else 1)
