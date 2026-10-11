"""Only manipulate/check the explicitly marked fresh UI fixture for number capacity tests."""
import json,re,sys,hashlib
from pathlib import Path
from local_db import mysql
f=json.loads(Path(sys.argv[1]).read_text('utf-8'));db=f['database']
assert re.fullmatch(r'boost_verify_ui_\d+_[a-f0-9]{6}',db)
assert f['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
action=sys.argv[2]
if action=='last-number':
 assert int(mysql('SELECT COALESCE(MAX(id),0) FROM boost_member',db).strip().splitlines()[-1])<9999
 mysql('ALTER TABLE boost_member AUTO_INCREMENT=9999',db)
 # Start a separate simulated time window in this owned fixture; preserve the rate limit policy.
 bucket=hashlib.sha256(b'{"key":"register:127.0.0.1"}').hexdigest()
 mysql("UPDATE boost_rate_limit SET resets_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE bucket_key='"+bucket+"'",db)
 print('Owned fixture next member ID set to 9999')
elif action=='snapshot':
 print(json.dumps({table:int(mysql('SELECT COUNT(*) FROM '+table,db).strip().splitlines()[-1]) for table in ['boost_member','boost_session','boost_notification','boost_coupon']}))
else:raise ValueError('Unknown action')
