"""Owned, access-restricted nonpayment test deployment. Never overwrite another project."""
import argparse,datetime,hashlib,ipaddress,json,re,secrets,shlex,stat,sys,urllib.request,urllib.error,uuid
from pathlib import Path
import paramiko

ROOT=Path(__file__).resolve().parent.parent
PRIVATE=ROOT/'.runtime/deploy-test-20261006'
OUT=ROOT/'test-results/deployment-test-20261006'
OUT.mkdir(parents=True,exist_ok=True)

def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(name,value):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')

parser=argparse.ArgumentParser();parser.add_argument('action',choices=['inspect','prepare','stage','migrate','start','backup-restore','activate','rollback','verify']);parser.add_argument('--release-record');args=parser.parse_args()
c=json.loads((PRIVATE/'connection.private.json').read_text(encoding='utf-8-sig'))
assert c['host']=='124.222.244.144'
ssh=paramiko.SSHClient();ssh.load_host_keys(str(ROOT/'.runtime/server-known-hosts'));ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
record={'action':args.action,'startedAtUtc':now(),'scope':'read-only inventory; no credentials or production business rows' if args.action=='inspect' else 'owned restricted internal-test server action; mutations and validation use only the dedicated instance, files and two authorized domain configurations; no shared business data'}
def run(command,timeout=30):
    _,stdout,stderr=ssh.exec_command(command,timeout=timeout)
    output=stdout.read().decode('utf-8','replace');error=stderr.read().decode('utf-8','replace')
    assert c['password'] not in output+error and c['appSecret'] not in output+error
    return {'command':command,'exitCode':stdout.channel.recv_exit_status(),'stdout':output,'stderr':error}
def mkdir(sftp,path,mode):
    try:assert stat.S_ISDIR(sftp.lstat(path).st_mode),'Expected a non-symlink owned directory'
    except FileNotFoundError:sftp.mkdir(path,mode)
    sftp.chmod(path,mode)
def upload(sftp,local,remote,mode=0o600):
    try:assert not stat.S_ISLNK(sftp.lstat(remote).st_mode),'Refuse a symlink destination'
    except FileNotFoundError:pass
    sftp.put(str(local),remote);sftp.chmod(remote,mode)
def nginx(domain,ips,apiPort):
    assert domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn')
    name='api' if domain=='ceshisss.oksja.cn' else 'admin'
    for ip in ips:ipaddress.ip_address(ip)
    allowed='\n'.join('    allow '+ip+';' for ip in ips)
    mapping='\n'.join('    '+ip+' 1;' for ip in ips)
    proxy=f"proxy_pass http://127.0.0.1:{apiPort}; proxy_http_version 1.1; proxy_set_header Host $host; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header X-Forwarded-Proto https; proxy_set_header Connection ''; proxy_connect_timeout 5s; proxy_read_timeout 35s;"
    locations=f"    location / {{ try_files $uri $uri/ /index.html; }}\n"
    if name=='api':locations+=f"    location ~ ^/(boost(?:/|$)|boost-media(?:/|$)|health(?:/|$)) {{ {proxy} }}\n"
    else:locations+=f"    location ^~ /prod-api/ {{ {proxy.replace('proxy_pass http://127.0.0.1:'+str(apiPort)+';', 'proxy_pass http://127.0.0.1:'+str(apiPort)+'/;')} }}\n"
    challenge=f"    location ^~ /.well-known/acme-challenge/ {{ allow all; root /www/wwwroot/{domain}; default_type text/plain; try_files $uri =404; }}\n"
    return f"""# kunkun-internal-test-20261006: all business access restricted to listed test IPs
map $remote_addr $kunkun_test_{name}_access {{
    default 0;
{mapping}
}}
log_format kunkun_test_{name}_safe '$remote_addr [$time_local] $request_method $uri $status $request_time';
server {{
    listen 80; listen [::]:80; server_name {domain};
    access_log /www/wwwlogs/{domain}.log kunkun_test_{name}_safe;
{challenge}
    location / {{ if ($kunkun_test_{name}_access = 0) {{ return 403; }} return 301 https://{domain}$request_uri; }}
}}
server {{
    listen 443 ssl; listen [::]:443 ssl; server_name {domain};
    ssl_certificate /etc/letsencrypt/live/kunkun/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kunkun/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    root /srv/kunkun/test/current/{'h5' if name=='api' else 'admin'}; index index.html;
    client_max_body_size 10m;
    access_log /www/wwwlogs/{domain}.log kunkun_test_{name}_safe;
    error_log /www/wwwlogs/{domain}.error.log;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;
{allowed}
    deny all;
{challenge}
    location = /RELEASE.json {{ return 404; }}
    location ~ (^|/)\\.(?!well-known/) {{ return 404; }}
    location ~* \\.(sql|log|env|pem|key|zip|jar)$ {{ return 404; }}
{locations}
}}
"""
try:
    ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,auth_timeout=15,allow_agent=False,look_for_keys=False)
    record['connected']=True
    if args.action=='inspect':
        checks={
          'system':'uname -sr; cat /etc/os-release; df -h /; free -m',
          'listeners':'ss -lntp',
          'runtime-paths':"find /opt /srv /usr/lib/jvm /www/server -maxdepth 4 -type f \\( -name java -o -name mysqld -o -name redis-server \\) 2>/dev/null | head -n 80",
          'tools':'command -v java mysql mysqld redis-server nginx python3 tar unzip; java -version 2>&1; mysql --version; redis-server --version',
          'sites':"for domain in ceshisss.oksja.cn ceshijjht.oksja.cn; do cat /www/server/panel/vhost/nginx/$domain.conf; done; nginx -t",
          'owned-layout':"find /srv/kunkun -maxdepth 3 -type d -printf '%p\\n'; systemctl list-unit-files 'kunkun*' --no-pager; ls -ld /root/.my.cnf /etc/my.cnf /www/server/mysql/bin/mysqld 2>/dev/null",
          'peer':'printf "%s" "$SSH_CONNECTION"',
          'db-process-binaries':"for entry in /proc/[0-9]*/exe; do link=$(readlink \"$entry\" 2>/dev/null); case \"$link\" in */mysqld) printf '%s %s\\n' \"$entry\" \"$link\" ;; esac; done",
        }
        record['checks']={k:run(v) for k,v in checks.items()}
        peer=record['checks']['peer']['stdout'].split()[0];ipaddress.ip_address(peer);record['accessIp']=peer
        record['connected']=True
        print(json.dumps({'connected':True,'accessIp':peer,'runtimePaths':record['checks']['runtime-paths']['stdout'],'tools':record['checks']['tools']['stdout'],'ownLayout':record['checks']['owned-layout']['stdout'],'dbBinaries':record['checks']['db-process-binaries']['stdout']},ensure_ascii=False))
    else:
        sftp=ssh.open_sftp();base='/srv/kunkun/test';privatefile=PRIVATE/'server.private.json'
        if args.action=='prepare':
            import bcrypt
            if not privatefile.exists():
                inspection=json.loads((OUT/'inspect.json').read_text(encoding='utf-8'))
                marker='/__kunkun_test_access_probe_'+uuid.uuid4().hex
                try:urllib.request.urlopen('https://ceshisss.oksja.cn'+marker,timeout=15)
                except urllib.error.HTTPError:pass
                probe=run('grep -F '+shlex.quote(marker)+' /www/wwwlogs/ceshisss.oksja.cn.log | tail -n 2')
                webIps=[line.split()[0] for line in probe['stdout'].splitlines() if marker in line]
                assert webIps,'Cannot determine real HTTPS test-client source address'
                ips=sorted(set(['127.0.0.1','::1',inspection['accessIp']]+webIps))
                for ip in ips:ipaddress.ip_address(ip)
                adminPassword=secrets.token_urlsafe(15)
                server={'owner':'kunkun-internal-test-20261006','apiPort':18240,'mysqlPort':13440,'redisPort':16440,'accessIps':ips,
                    'mysqlRootPassword':secrets.token_urlsafe(32),'databasePassword':secrets.token_urlsafe(32),'migrationPassword':secrets.token_urlsafe(32),
                    'redisPassword':secrets.token_urlsafe(32),'jwtSecret':secrets.token_urlsafe(64),'mediaKey':secrets.token_urlsafe(48),'backupKey':secrets.token_urlsafe(48),
                    'adminPassword':adminPassword,'adminHash':bcrypt.hashpw(adminPassword.encode(),bcrypt.gensalt()).decode(),'appId':c['appId'],'appSecret':c['appSecret'],
                    'siteHashes':{domain:hashlib.sha256(sftp.open('/www/server/panel/vhost/nginx/'+domain+'.conf','rb').read()).hexdigest() for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn')}}
                privatefile.write_text(json.dumps(server,ensure_ascii=False,indent=2),encoding='utf-8')
                (PRIVATE/'登录信息.txt').write_text('内部测试版，支付关闭；并非正式商用验收通过。\n后台：https://ceshijjht.oksja.cn\n账号：admin\n密码：'+adminPassword+'\n用户/打手H5：https://ceshisss.oksja.cn\n访问IP：'+', '.join(ips)+'\n测试邮箱未启用，运营协议为待确认草案。\n',encoding='utf-8')
                save('access-source-proof.json',{'marker':marker,'peerIps':ips,'probe':probe,'scope':'Actual SSH/HTTPS source addresses; does not establish a mobile/cellular address'})
            server=json.loads(privatefile.read_text(encoding='utf-8'));mkdir(sftp,'/srv/kunkun',0o755);mkdir(sftp,base,0o751)
            try:
                existing=json.loads(sftp.open(base+'/deployment-state.json').read())
                assert existing['owner']==server['owner'],'Another project owns this directory'
            except FileNotFoundError:
                existingEntries=sftp.listdir(base);assert not existingEntries,'Refuse to claim an occupied unmarked directory'
                with sftp.open(base+'/deployment-state.json','w') as target:target.write(json.dumps({'owner':server['owner']}))
                sftp.chmod(base+'/deployment-state.json',0o600)
            mkdir(sftp,base+'/config',0o750);upload(sftp,privatefile,base+'/config/private.json')
            upload(sftp,ROOT/'RuoYi-Vue/sql/ry_20250522.sql',base+'/config/ry-base.sql');upload(sftp,ROOT/'RuoYi-Vue/sql/boost-menu.sql',base+'/config/boost-menu.sql')
            for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
                path=OUT/('nginx-'+domain+'.conf');path.write_text(nginx(domain,server['accessIps'],server['apiPort']),encoding='utf-8');upload(sftp,path,base+'/config/'+path.name)
        elif args.action=='stage':
            assert args.release_record
            release=json.loads(Path(args.release_record).read_text(encoding='utf-8'));assert release['secretScanPassed']
            assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',release['version'])
            assert release['appid']=='wxd31a958f0fc66a19' and release['apiOrigin']=='https://ceshisss.oksja.cn'
            archive=Path(release['archive']);assert hashlib.sha256(archive.read_bytes()).hexdigest()==release['archiveSha256']
            server=json.loads(privatefile.read_text(encoding='utf-8'));server.update(version=release['version'],jarSha256=release['backendSha256'],archiveSha256=release['archiveSha256'])
            privatefile.write_text(json.dumps(server,ensure_ascii=False,indent=2),encoding='utf-8');upload(sftp,privatefile,base+'/config/private.json')
            mkdir(sftp,base+'/releases',0o755);destination=base+'/releases/'+server['version'];mkdir(sftp,destination,0o755)
            try:
                entry=sftp.lstat(destination+'/release.zip');assert stat.S_ISREG(entry.st_mode),'Archive is not a regular owned file'
                existing=run('sha256sum '+shlex.quote(destination+'/release.zip'));assert existing['exitCode']==0 and existing['stdout'].split()[0]==server['archiveSha256'],'Refuse to overwrite a different release'
            except FileNotFoundError:
                temporary=destination+'/release.zip.uploading';upload(sftp,archive,temporary)
                check=run('sha256sum '+shlex.quote(temporary));assert check['exitCode']==0 and check['stdout'].split()[0]==server['archiveSha256']
                sftp.posix_rename(temporary,destination+'/release.zip')
            extract="""import hashlib,json,stat,zipfile;from pathlib import Path
b=Path('/srv/kunkun/test');c=json.loads((b/'config/private.json').read_text());p=b/'releases'/c['version'];a=p/'release.zip'
assert hashlib.sha256(a.read_bytes()).hexdigest()==c['archiveSha256']
with zipfile.ZipFile(a) as z:
 assert z.testzip() is None
 for item in z.infolist():
  name=Path(item.filename);assert not name.is_absolute() and '..' not in name.parts and not stat.S_ISLNK(item.external_attr>>16)
  assert name.parts[0] in ('h5','admin','miniprogram','deploy','ruoyi-admin.jar','RELEASE.json')
  target=p/name;assert target.resolve().is_relative_to(p)
  if item.is_dir():target.mkdir(parents=True,exist_ok=True);continue
  target.parent.mkdir(parents=True,exist_ok=True)
  if target.exists():assert target.is_file() and not target.is_symlink() and hashlib.sha256(target.read_bytes()).digest()==hashlib.sha256(z.read(item)).digest(),'Refuse to overwrite changed release content'
  else:target.write_bytes(z.read(item))
  target.chmod(0o644 if name.parts[0] in ('h5','admin') else 0o640)
for directory in [p]+[x for x in p.rglob('*') if x.is_dir()]:directory.chmod(0o755)
assert hashlib.sha256((p/'ruoyi-admin.jar').read_bytes()).hexdigest()==c['jarSha256']
print(json.dumps({'version':c['version'],'archiveHashMatches':True,'jarHashMatches':True,'entries':len(z.infolist())}))
"""
            check=run('python3 -c '+shlex.quote(extract),timeout=90);assert check['exitCode']==0
            permissions=run('chown root:kunkun-test '+shlex.quote(destination+'/ruoyi-admin.jar'));assert permissions['exitCode']==0
            record['stage']=check;print(json.dumps({'stage':json.loads(check['stdout'])}));record['passed']=True
        if args.action!='stage':
            upload(sftp,ROOT/'deploy/test-server-bootstrap.py',base+'/config/test-server-bootstrap.py')
            bootstrapHash=hashlib.sha256((ROOT/'deploy/test-server-bootstrap.py').read_bytes()).hexdigest()
            assert hashlib.sha256(sftp.open(base+'/config/test-server-bootstrap.py','rb').read()).hexdigest()==bootstrapHash
            record['runtimeBootstrapSha256']=bootstrapHash
            check=run('python3 '+shlex.quote(base+'/config/test-server-bootstrap.py')+' '+shlex.quote(args.action),timeout=240)
            record['commandResult']=check
            assert check['exitCode']==0,'Owned remote action failed; see redacted evidence'
            record['passed']=True;print(check['stdout'].strip())
except Exception as exc:
    record['errorType']=type(exc).__name__;record['passed']=False
    print(json.dumps({'connected':record.get('connected',False),'actionFailed':True,'errorType':type(exc).__name__}))
    raise
finally:
    ssh.close();record['finishedAtUtc']=now();save(args.action+'.json',record)
