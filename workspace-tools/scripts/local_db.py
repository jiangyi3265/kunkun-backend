"""Private local datasource access for development helpers. Never print secrets."""
from pathlib import Path
import json,os,re,subprocess
ROOT=Path(__file__).resolve().parent.parent
def configuration():
    source=ROOT/'.runtime/local-dev.json'
    values=json.loads(source.read_text(encoding='utf-8')) if source.exists() else {}
    values.update({k:v for k,v in os.environ.items() if k.startswith('BOOST_DB_')})
    match=re.search(r'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)',values.get('BOOST_DB_URL',''))
    if not match:raise RuntimeError('Configure BOOST_DB_URL in .runtime/local-dev.json or environment')
    host,port,database=match.groups()
    return dict(host=host,port=port or '3306',database=database,user=values['BOOST_DB_USER'],password=values['BOOST_DB_PASSWORD'])
def mysql(sql,database=None):
    c=configuration()
    if c['host'] not in ('localhost','127.0.0.1'):raise RuntimeError('Development helper only supports local MySQL')
    env=os.environ.copy();env['MYSQL_PWD']=c['password']
    result=subprocess.run(['mysql','--host='+c['host'],'--port='+c['port'],'--user='+c['user'],'--default-character-set=utf8mb4','--batch','--raw',database or c['database']],input=sql.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
    if result.returncode:raise RuntimeError(result.stderr.decode('utf-8','replace'))
    return result.stdout.decode('utf-8')
