"""Read-only current owned deployment, without printing data rows or secrets."""
import json, shlex, os
from pathlib import Path
from datetime import datetime, timezone
import paramiko
ROOT=Path(__file__).resolve().parent.parent
OUT=Path(os.environ.get('QA_OUTPUT_DIR',ROOT/'test-results/interconnect-20261008'));OUT.mkdir(parents=True,exist_ok=True)
c=json.loads((ROOT/'.runtime/deploy-test-20261006/connection.private.json').read_text(encoding='utf-8-sig'))
remote=r'''
import json,hashlib,re,os,subprocess
from pathlib import Path
b=Path('/srv/kunkun/test');c=json.loads((b/'config/private.json').read_text());s=json.loads((b/'deployment-state.json').read_text());assert c['owner']==s['owner']=='kunkun-internal-test-20261006'
env=os.environ.copy();env['MYSQL_PWD']=c['mysqlRootPassword']
def sql(q):
 p=subprocess.run(['/www/server/mysql/bin/mysql','--no-defaults','--protocol=socket','--socket='+str(b/'mysql/mysql.sock'),'--user=root','--batch','--raw','--skip-column-names','kunkun_test'],input=q,text=True,capture_output=True,env=env);assert p.returncode==0;return p.stdout.strip()
tables=sql('SHOW TABLES').splitlines();assert all(re.fullmatch('[A-Za-z0-9_]+',t) for t in tables)
counts={t:int(sql('SELECT COUNT(*) FROM `'+t+'`')) for t in tables}
active=b/'current';config={}
for line in (b/'config/production.env').read_text().splitlines():
 if '=' in line:
  k,v=line.split('=',1);config[k]=v
safe={k:v for k,v in config.items() if k in ['BOOST_PAYMENTS_ENABLED','BOOST_FREE_POINTS_DRAW_ENABLED','BOOST_POLICY_VERSION','BOOST_POLICY_DATE','BOOST_PUBLIC_API_ORIGIN','BOOST_WEB_ORIGINS','BOOST_MEDIA_DIR','BOOST_UPLOAD_DIR','SERVER_PORT']}
money={'positiveWallets':int(sql('SELECT COUNT(*) FROM boost_member WHERE balance_cents<>0')),'activePaidOrders':int(sql("SELECT COUNT(*) FROM boost_order WHERE status IN ('WAIT_START','IN_PROGRESS','REVIEW','WAIT_DELIVERY','WAIT_CONFIRM','AFTERSALE','REFUNDING','REFUND_EXCEPTION') OR (status='WAIT_PAY' AND payment_mode='WECHAT')")),'pendingWithdrawals':int(sql("SELECT COUNT(*) FROM boost_withdrawal WHERE status IN ('PENDING','PROCESSING','WAIT_USER_CONFIRM')"))}
menus=sql('SELECT menu_id,parent_id,path,COALESCE(component,\'\'),perms FROM sys_menu WHERE menu_id BETWEEN 87000 AND 87099 ORDER BY menu_id').splitlines()
nginx={}
for domain in ['ceshisss.oksja.cn','ceshijjht.oksja.cn']:
 p=Path('/www/server/panel/vhost/nginx')/(domain+'.conf');nginx[domain]={'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'config':p.read_text()}
sizes={}
for name in ['private-media','public-uploads']:
 p=b/name;files=[x for x in p.rglob('*') if x.is_file()];sizes[name]={'files':len(files),'bytes':sum(x.stat().st_size for x in files),'symlinks':sum(x.is_symlink() for x in p.rglob('*'))}
print(json.dumps({'current':str(active.resolve()),'jarSha256':hashlib.sha256((active/'ruoyi-admin.jar').read_bytes()).hexdigest(),'stateKeys':list(s),'config':safe,'credentialPresence':{k:bool(v and v.strip('"')) for k,v in config.items() if k.startswith(('BOOST_IDENTITY','BOOST_SMS'))},'counts':counts,'financialObligations':money,'menus':menus,'media':sizes,'nginx':nginx,'appUnit':subprocess.run(['systemctl','cat','kunkun-test-api'],capture_output=True,text=True).stdout,'disk':subprocess.run(['df','-B1','/srv/kunkun/test'],capture_output=True,text=True).stdout}))
'''
ssh=paramiko.SSHClient();ssh.load_host_keys(str(ROOT/'.runtime/server-known-hosts'));ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
 ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,allow_agent=False,look_for_keys=False)
 _,stdout,stderr=ssh.exec_command('python3 -c '+shlex.quote(remote),timeout=45);data=stdout.read().decode();err=stderr.read().decode();code=stdout.channel.recv_exit_status();assert code==0,'Owned inspection failed: '+str(code)
 for secret in [c['password'],c['appSecret']]:assert secret not in data+err
 result=json.loads(data);result.update(checkedAt=datetime.now(timezone.utc).isoformat(),exitCode=code)
 (OUT/'server-before.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps({k:result[k] for k in ['current','counts','financialObligations','media','credentialPresence','disk']},ensure_ascii=False))
finally:ssh.close()
