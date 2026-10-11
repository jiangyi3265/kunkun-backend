"""Read-only deployment verification. No restart, migration, or data mutation."""
import base64,hashlib,io,json,shlex,sys,urllib.request,urllib.error,zipfile
from datetime import datetime,timezone
from pathlib import Path
import paramiko

ROOT=Path(__file__).resolve().parents[1]
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out=ROOT/'test-results'/('server-deployment-'+stamp);out.mkdir(parents=True)
meta=json.loads((Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'test-results/interconnect-20261008/package-final/release-package.json').read_text('utf-8'))
jar=ROOT/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'
report={'startedAt':datetime.now(timezone.utc).isoformat(),'requestedVersion':meta['version'],'operation':'Verify already deployed latest tested version; read-only','checks':[]}
ssh=paramiko.SSHClient();ssh.load_host_keys(str(ROOT/'.runtime/server-known-hosts'));ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
private=json.loads((ROOT/'.runtime/deploy-test-20261006/connection.private.json').read_text('utf-8-sig'))
remote=r'''
import os,json,hashlib,subprocess,urllib.request
from pathlib import Path
b=Path('/srv/kunkun/test');c=json.loads((b/'config/private.json').read_text());state=json.loads((b/'deployment-state.json').read_text())
assert c['owner']==state['owner']=='kunkun-internal-test-20261006'
current=(b/'current').resolve();assert current.is_relative_to(b/'releases')
p=subprocess.run(['systemctl','show','kunkun-test-api','--property=ActiveState,SubState,MainPID,ActiveEnterTimestamp,ExecMainStartTimestamp,ExecMainStatus,WorkingDirectory'],capture_output=True,text=True,check=True)
service=dict(line.split('=',1) for line in p.stdout.splitlines() if '=' in line)
pid=int(service['MainPID']);assert pid>0
cwd=str(Path('/proc')/str(pid)/'cwd');cwd=str(Path(cwd).resolve())
base='http://127.0.0.1:'+str(c['apiPort'])
health=json.load(urllib.request.urlopen(base+'/health/ready',timeout=10))
config=json.load(urllib.request.urlopen(base+'/boost/config',timeout=10))
env=os.environ.copy();env['MYSQL_PWD']=c['mysqlRootPassword']
p=subprocess.run(['/www/server/mysql/bin/mysql','--no-defaults','--protocol=socket','--socket='+str(b/'mysql/mysql.sock'),'--user=root','--batch','--raw','--skip-column-names','kunkun_test'],input="SELECT config_key,config_value FROM boost_config WHERE config_key LIKE 'schema:%' ORDER BY config_key",text=True,capture_output=True,env=env)
assert p.returncode==0,'Read-only migration query failed'
migrations=dict(line.split('\t',1) for line in p.stdout.splitlines())
upgrade=b/'upgrades'/current.name/'state.json'
backupdir=b/'upgrades'/current.name
backups={name:(backupdir/name).is_file() for name in ['prepare.sql.enc','activation.sql.enc']}
web_files={name:{p.relative_to(current/name).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (current/name).rglob('*') if p.is_file()} for name in ['h5','admin']}
print(json.dumps({'current':str(current),'version':current.name,'jarSha256':hashlib.sha256((current/'ruoyi-admin.jar').read_bytes()).hexdigest(),'service':service,'processWorkingDirectory':cwd,'health':health,'config':config,'migrations':migrations,'encryptedBackupFilesExist':backups,'webFileHashes':web_files}))
'''

def check(name,condition):
    report['checks'].append({'name':name,'status':'已实测通过' if condition else '失败'})
    if not condition:raise AssertionError(name)

try:
    if '--packaged-baseline' in sys.argv:
        archive_bytes=Path(meta['archive']).read_bytes()
        check('Deployed baseline archive matches immutable metadata',hashlib.sha256(archive_bytes).hexdigest()==meta['archiveSha256'])
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            jar_bytes=archive.read('ruoyi-admin.jar')
        check('Packaged baseline JAR matches release metadata',hashlib.sha256(jar_bytes).hexdigest()==meta['backendSha256'])
    else:
        jar_bytes=jar.read_bytes()
        check('Local JAR matches the tested immutable package',hashlib.sha256(jar_bytes).hexdigest()==meta['backendSha256'])
    ssh.connect(private['host'],username=private['user'],password=private['password'],timeout=15,allow_agent=False,look_for_keys=False)
    command='python3 -c '+shlex.quote(remote)
    _,stdout,stderr=ssh.exec_command(command,timeout=45);data=stdout.read().decode();errors=stderr.read().decode();code=stdout.channel.recv_exit_status()
    report['remoteExitCode']=code
    assert code==0,'Read-only remote verification failed'
    for secret in [private['password'],private['appSecret']]:assert secret not in data+errors
    state=json.loads(data);report['server']=state
    check('Server points to current tested release and identical JAR',state['version']==meta['version'] and state['jarSha256']==meta['backendSha256'])
    check('Running process belongs to current release',state['processWorkingDirectory']==state['current'])
    check('API service is active and ready',state['service']['ActiveState']=='active' and state['service']['SubState']=='running' and state['health']['status']=='UP')
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as z:
        migrations={name:base64.b64encode(hashlib.sha256(z.read('BOOT-INF/classes/boost/migrations/'+name+'.sql')).digest()).decode() for name in ['V002__commercial_hardening','V003__player_items','V004__player_public_profiles','V005__free_points_rewards','V006__player_benefits','V007__unit_items_content','V008__shop_disclosure_phone']}
        deposit_migration='V009__wallet_deposit_display'
        deposit_resource='BOOT-INF/classes/boost/migrations/'+deposit_migration+'.sql'
        if deposit_resource in z.namelist():
            migrations[deposit_migration]=base64.b64encode(hashlib.sha256(z.read(deposit_resource)).digest()).decode()
    check('Database migrations through '+sorted(migrations)[-1]+' match current application',all(state['migrations'].get('schema:'+k)==v for k,v in migrations.items()))
    check('Existing encrypted pre-upgrade backups retained',all(state['encryptedBackupFilesExist'].values()))
    with zipfile.ZipFile(meta['archive']) as z:
        for app in ['h5','admin']:
            expected={name[len(app)+1:]:hashlib.sha256(z.read(name)).hexdigest() for name in z.namelist() if name.startswith(app+'/') and not name.endswith('/')}
            check('Deployed '+app+' has exact release file set and bytes',state['webFileHashes'][app]==expected)
    def get(url):
        with urllib.request.urlopen(url,timeout=20) as r:return r.status,r.headers.get('Content-Type',''),r.read()
    code,_,body=get('https://ceshisss.oksja.cn/boost/config');config=json.loads(body)
    check('Public HTTPS API matches local current service',code==200 and config==state['config'])
    data=config['data'];check('Player-item configuration and payment off',data['businessMode']=='PLAYER_ITEM' and data['games']==['和平精英'] and data['accountSalesAllowed'] is False and data['paymentsEnabled'] is False and data['simulatedPayment'] is False)
    check('Separate request and sale certification gates',data['shopCertificationRequired'] is True and data['requestShopCertificationRequired'] is False and data['realnameRequired'] is True)
    for endpoint in ['/boost/rewards/goods','/boost/rewards/activities','/boost/content/site','/boost/content/activities']:
        code,ctype,body=get('https://ceshisss.oksja.cn'+endpoint);result=json.loads(body)
        check('Public '+endpoint+' responds with business JSON',code==200 and result['code']==200 and 'json' in ctype)
    for url,app in [('https://ceshisss.oksja.cn/','h5'),('https://ceshijjht.oksja.cn/','admin')]:
        code,ctype,body=get(url);check('Public '+app+' entry serves current release bytes',code==200 and 'text/html' in ctype and hashlib.sha256(body).hexdigest()==state['webFileHashes'][app]['index.html'])
    report['deploymentStatus']='当前最新已验收后端包已部署并运行，本轮确认一致后未重复停服部署'
    report['remaining']={'realnameReady':data['realnameReady'],'paymentsEnabled':data['paymentsEnabled'],'scope':'Deployment verification only; does not replace real identity/phone/business acceptance.'}
    report['exitCode']=0
except Exception as e:
    report['exitCode']=1;report['errorType']=type(e).__name__;report['error']=str(e)
finally:
    ssh.close();report['finishedAt']=datetime.now(timezone.utc).isoformat()
    (out/'server-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({'exitCode':report['exitCode'],'version':meta['version'],'checks':report['checks'],'error':report.get('error'),'report':str(out/'server-verification.json')},ensure_ascii=False))
sys.exit(report['exitCode'])
