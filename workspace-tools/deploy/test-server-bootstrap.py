"""Restricted internal test deployment. Root-only private input, owned resources only."""
import datetime,hashlib,ipaddress,json,os,re,shutil,stat,subprocess,sys,time,urllib.request,zipfile
from pathlib import Path
BASE=Path('/srv/kunkun/test');CFG=BASE/'config/private.json';OWNER='kunkun-internal-test-20261006'
assert str(BASE.resolve())==str(BASE) and sys.argv[1] in ('prepare','migrate','start','backup-restore','activate','rollback','verify')
c=json.loads(CFG.read_text());assert c['owner']==OWNER
SECRETS=[v for k,v in c.items() if any(t in k.lower() for t in ('password','secret','key')) and isinstance(v,str)]
statefile=BASE/'deployment-state.json';state=json.loads(statefile.read_text()) if statefile.exists() else {'owner':OWNER}
assert state['owner']==OWNER
def redact(text):
    for value in sorted(SECRETS,key=len,reverse=True):
        if value:text=text.replace(value,'[REDACTED]')
    return text
def persist():statefile.write_text(json.dumps(state,indent=2));statefile.chmod(0o600)
def execute(args,input=None,env=None,timeout=60):
    result=subprocess.run(args,input=input,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=timeout)
    if result.returncode:raise RuntimeError(redact(result.stderr[-1800:] or result.stdout[-1800:]))
    return result.stdout
def mysql(sql,database=None,blank=False):
    env=os.environ.copy()
    if not blank:env['MYSQL_PWD']=c['mysqlRootPassword']
    command=['/www/server/mysql/bin/mysql','--no-defaults','--protocol=socket','--socket='+str(BASE/'mysql/mysql.sock'),'--user=root','--batch','--raw','--default-character-set=utf8mb4']
    if database:assert database in ('kunkun_test','kunkun_restore_test');command.append(database)
    return execute(command,input=sql,env=env)
def literal(value):return 'CONVERT(0x'+str(value).encode().hex()+' USING utf8mb4)'
def password(value):
    assert re.fullmatch(r'[A-Za-z0-9_-]{20,100}',value)
    return "'"+value+"'"
def write(path,text,mode=0o600):
    assert not path.is_symlink();path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);path.chmod(mode)
def service(name,text):
    assert name in ('kunkun-test-mysql','kunkun-test-redis','kunkun-test-api')
    path=Path('/etc/systemd/system')/(name+'.service')
    assert not path.exists() or OWNER in path.read_text(),'An unrelated unit occupies this name'
    write(path,'# '+OWNER+'\n'+text,0o644)
def wait(predicate,seconds=40):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=predicate()
            if value:return value
        except (OSError,RuntimeError,ValueError):pass
        time.sleep(1)
    raise RuntimeError('Owned service did not become ready before timeout')
def require_safe_release():
    release=BASE/'releases'/c['version'];assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',c['version'])
    assert release.is_dir() and not release.is_symlink()
    assert hashlib.sha256((release/'ruoyi-admin.jar').read_bytes()).hexdigest()==c['jarSha256']
    return release

def redis_runtime():
    # The panel's Redis binary lives below a root-only directory. Use an exact
    # verified copy in the owned runtime rather than changing shared permissions.
    source=Path('/www/server/redis/src/redis-server');assert source.is_file()
    bindir=BASE/'bin';assert not bindir.is_symlink();bindir.mkdir(exist_ok=True);bindir.chmod(0o755)
    binary=bindir/'redis-server';assert not binary.is_symlink()
    if binary.exists():assert hashlib.sha256(binary.read_bytes()).digest()==hashlib.sha256(source.read_bytes()).digest()
    else:shutil.copyfile(source,binary)
    execute(['chown','root:root',str(bindir),str(binary)]);binary.chmod(0o755)
    redisconf=BASE/'config/redis.conf'
    service('kunkun-test-redis',f"[Unit]\nDescription=Kunkun isolated test Redis\nAfter=network.target\n[Service]\nUser=kunkun-test\nGroup=kunkun-test\nExecStart={binary} {redisconf}\nRestart=on-failure\nRestartSec=5\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\nReadWritePaths={BASE}/redis\nUMask=0077\n[Install]\nWantedBy=multi-user.target\n")
    execute(['systemctl','daemon-reload']);execute(['systemctl','start','kunkun-test-redis'])
    env=os.environ.copy();env['REDISCLI_AUTH']=c['redisPassword']
    assert 'PONG' in wait(lambda:execute(['/www/server/redis/src/redis-cli','-h','127.0.0.1','-p',str(c['redisPort']),'PING'],env=env))
def environment(database='kunkun_test',migration=False):
    values={'SPRING_PROFILES_ACTIVE':'prod','SERVER_PORT':str(c['apiPort']),
      'BOOST_PAYMENTS_ENABLED':'false','BOOST_DB_URL':f"jdbc:mysql://127.0.0.1:{c['mysqlPort']}/{database}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
      'BOOST_DB_USER':'kunkun_migrate' if migration else 'kunkun_app','BOOST_DB_PASSWORD':c['migrationPassword'] if migration else c['databasePassword'],
      'BOOST_REDIS_HOST':'127.0.0.1','SPRING_DATA_REDIS_PORT':str(c['redisPort']),'BOOST_REDIS_DATABASE':'0','BOOST_REDIS_PASSWORD':c['redisPassword'],
      'BOOST_JWT_SECRET':c['jwtSecret'],'BOOST_MEDIA_SIGNING_KEY':c['mediaKey'],'BOOST_MEDIA_DIR':str(BASE/'private-media'),
      'BOOST_UPLOAD_DIR':str(BASE/'public-uploads'),'BOOST_LOG_DIR':str(BASE/'logs'),
      'BOOST_PUBLIC_API_ORIGIN':'https://ceshisss.oksja.cn','BOOST_WEB_ORIGINS':'https://ceshisss.oksja.cn,https://ceshijjht.oksja.cn',
      'BOOST_OPERATOR':'困困内部测试（协议草案，真实运营主体待确认）','BOOST_PRIVACY_EMAIL':'not-configured@example.com',
      'BOOST_RETENTION':'仅供限制访问的内部测试；请勿提交真实身份、账号密码或支付资料。测试数据在本次测试结束后由项目负责人清理；正式保留期限与联系人待确认。邮箱是未启用的占位值。',
      'BOOST_POLICY_VERSION':'internal-test-draft-20261006','BOOST_POLICY_DATE':'2026-10-06',
      'BOOST_WX_APP_ID':c['appId'],'BOOST_WX_APP_SECRET':c['appSecret'],'BOOST_CAMPAIGN_DAILY_CENTS':'0','BOOST_CAMPAIGN_TOTAL_CENTS':'0'}
    if migration:values['BOOST_MIGRATION_DATABASE']=database
    return values

action=sys.argv[1];result={'action':action,'startedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'internalTestOnly':True,'paymentsEnabled':False}
try:
  backups=BASE/'backups';assert not backups.is_symlink()
  backups.mkdir(exist_ok=True);execute(['chown','root:root',str(backups)]);backups.chmod(0o700)
  if action=='prepare':
    if not state.get('resourcesPrepared'):
      # The allocated ports must not be owned by an existing project.
      listeners=execute(['ss','-H','-lnt'])
      for port in (c['mysqlPort'],c['redisPort'],c['apiPort']):
        occupied=bool(re.search(r':'+str(port)+r'\s',listeners))
        known=(port==c['mysqlPort'] and state.get('mysqlInitialized')) or (port==c['redisPort'] and state.get('redisConfigured'))
        assert not occupied or known,'Allocated port is occupied'
      if subprocess.run(['id','kunkun-test'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        execute(['useradd','--system','--home-dir',str(BASE),'--shell','/sbin/nologin','kunkun-test'])
      else:assert execute(['getent','passwd','kunkun-test']).split(':')[5]==str(BASE),'Unrelated Unix account'
      execute(['chown','root:kunkun-test',str(BASE/'config')]);(BASE/'config').chmod(0o750)
      for name in ('mysql','redis','private-media','public-uploads','logs'):
        p=BASE/name;assert not p.is_symlink();p.mkdir(exist_ok=True);p.chmod(0o700);execute(['chown','kunkun-test:kunkun-test',str(p)])
      mysqlconf=BASE/'config/mysql.cnf'
      settings=f"[mysqld]\nbasedir=/www/server/mysql\ndatadir={BASE}/mysql\nsocket={BASE}/mysql/mysql.sock\npid-file={BASE}/mysql/mysql.pid\nport={c['mysqlPort']}\nbind-address=127.0.0.1\nmysqlx=0\nskip-name-resolve\nmax_connections=35\ninnodb_buffer_pool_size=128M\ncharacter-set-server=utf8mb4\nlog-error={BASE}/mysql/error.log\nskip-networking=ON\n"
      write(mysqlconf,settings,0o640);execute(['chown','root:kunkun-test',str(mysqlconf)])
      if not state.get('mysqlInitialized'):
        assert not (BASE/'mysql/auto.cnf').exists(),'Refuse to reinitialize any MySQL data'
        execute(['/www/server/mysql/bin/mysqld','--defaults-file='+str(mysqlconf),'--initialize-insecure','--user=kunkun-test'],timeout=120)
        state['mysqlInitialized']=True;persist()
      service('kunkun-test-mysql',f"[Unit]\nDescription=Kunkun isolated test MySQL\nAfter=network.target\n[Service]\nUser=kunkun-test\nGroup=kunkun-test\nExecStart=/www/server/mysql/bin/mysqld --defaults-file={mysqlconf}\nRestart=on-failure\nRestartSec=5\nTimeoutStartSec=120\nTimeoutStopSec=60\nUMask=0077\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\nReadWritePaths={BASE}/mysql\n[Install]\nWantedBy=multi-user.target\n")
      execute(['systemctl','daemon-reload']);execute(['systemctl','start','kunkun-test-mysql'])
      if not state.get('mysqlRootConfigured'):
        wait(lambda:'1' in mysql('SELECT 1;',blank=True))
        mysql("ALTER USER 'root'@'localhost' IDENTIFIED BY "+password(c['mysqlRootPassword'])+';',blank=True)
        state['mysqlRootConfigured']=True;persist()
      # Private new instance only. Both target schemas are new and dedicated to this project.
      if not state.get('baseSchemaInitialized'):
        assert not state.get('apiReady') and not state.get('publicActivated')
        mysql("CREATE DATABASE IF NOT EXISTS kunkun_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS kunkun_restore_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'kunkun_app'@'127.0.0.1' IDENTIFIED BY "+password(c['databasePassword'])+"; GRANT SELECT,INSERT,UPDATE,DELETE ON kunkun_test.* TO 'kunkun_app'@'127.0.0.1'; CREATE USER IF NOT EXISTS 'kunkun_migrate'@'127.0.0.1' IDENTIFIED BY "+password(c['migrationPassword'])+"; GRANT ALL PRIVILEGES ON kunkun_test.* TO 'kunkun_migrate'@'127.0.0.1';")
        mysql((BASE/'config/ry-base.sql').read_text(), 'kunkun_test')
        mysql("DELETE FROM sys_user WHERE user_id<>1; UPDATE sys_user SET user_name='admin',password="+literal(c['adminHash'])+",nick_name='困困测试管理员',email='',phonenumber='' WHERE user_id=1;",'kunkun_test')
        state['baseSchemaInitialized']=True;persist()
      write(mysqlconf,settings.replace('skip-networking=ON\n',''),0o640);execute(['chown','root:kunkun-test',str(mysqlconf)]);execute(['systemctl','restart','kunkun-test-mysql'])
      wait(lambda:'1' in mysql('SELECT 1;'))
      redisconf=BASE/'config/redis.conf'
      write(redisconf,f"bind 127.0.0.1\nprotected-mode yes\nport {c['redisPort']}\nrequirepass {c['redisPassword']}\ndir {BASE}/redis\nappendonly yes\nappendfsync everysec\nmaxmemory 128mb\nmaxmemory-policy noeviction\nlogfile {BASE}/redis/redis.log\n",0o640);execute(['chown','root:kunkun-test',str(redisconf)])
      service('kunkun-test-redis',f"[Unit]\nDescription=Kunkun isolated test Redis\nAfter=network.target\n[Service]\nUser=kunkun-test\nGroup=kunkun-test\nExecStart=/www/server/redis/src/redis-server {redisconf}\nRestart=on-failure\nRestartSec=5\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\nReadWritePaths={BASE}/redis\nUMask=0077\n[Install]\nWantedBy=multi-user.target\n")
      state['redisConfigured']=True;persist();execute(['systemctl','daemon-reload']);execute(['systemctl','start','kunkun-test-redis'])
      state['resourcesPrepared']=True;persist()
    redis_runtime()
    result['resourcesPrepared']=True;result['isolatedSchemas']=['kunkun_test','kunkun_restore_test']
  elif action=='migrate':
    assert state.get('resourcesPrepared');release=require_safe_release()
    outputs=[]
    for _ in range(2):
      env=os.environ.copy();env.update(environment(migration=True))
      outputs.append(execute(['/www/server/java/jdk-17.0.8/bin/java','-Dloader.main=com.ruoyi.boost.BoostMigrationCommand','-cp',str(release/'ruoyi-admin.jar'),'org.springframework.boot.loader.launch.PropertiesLauncher'],env=env,timeout=90)[-1500:])
    mysql((BASE/'config/boost-menu.sql').read_text(),'kunkun_test');state['migratedVersion']=c['version'];persist();result['doubleMigration']=outputs
  elif action=='start':
    release=require_safe_release();assert state.get('migratedVersion')==c['version']
    assert not state.get('publicActivated'),'Do not change a live test deployment without another versioned deployment plan'
    # Offline migration runs as root and initializes the same log appender.
    # Only regular files in this project's fixed log directory may be handed
    # to its unprivileged application; never follow a service-created symlink.
    logs=BASE/'logs';assert not logs.is_symlink() and logs.is_dir()
    import pwd
    appaccount=pwd.getpwnam('kunkun-test')
    for logfile in logs.iterdir():
      # Hold the checked inode open: the application may replace a path inside
      # its writable logs directory while root prepares the next startup.
      descriptor=os.open(logfile,os.O_RDONLY|os.O_NOFOLLOW|(os.O_DIRECTORY if logfile.name=='access' else 0))
      try:
        metadata=os.fstat(descriptor)
        if logfile.name=='access':assert stat.S_ISDIR(metadata.st_mode) and metadata.st_uid==appaccount.pw_uid
        else:
          assert stat.S_ISREG(metadata.st_mode) and metadata.st_uid in (0,appaccount.pw_uid),'Unexpected owned log inode'
          os.fchown(descriptor,appaccount.pw_uid,appaccount.pw_gid);os.fchmod(descriptor,0o600)
      finally:os.close(descriptor)
    env=environment();text='\n'.join(k+'='+json.dumps(v,ensure_ascii=False) for k,v in env.items())+'\n';write(BASE/'config/production.env',text)
    pointer=BASE/'current';assert not pointer.exists() or pointer.is_symlink()
    if pointer.is_symlink():
      if 'previousPointer' not in state:state['previousPointer']=os.readlink(pointer) if pointer.resolve()!=release.resolve() else None
      pointer.unlink()
    pointer.symlink_to(release,target_is_directory=True)
    service('kunkun-test-api',f"[Unit]\nDescription=Kunkun restricted nonpayment test API\nAfter=network-online.target kunkun-test-mysql.service kunkun-test-redis.service\nRequires=kunkun-test-mysql.service kunkun-test-redis.service\n[Service]\nUser=kunkun-test\nGroup=kunkun-test\nWorkingDirectory={BASE}/current\nEnvironmentFile={BASE}/config/production.env\nExecStart=/www/server/java/jdk-17.0.8/bin/java -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -jar {BASE}/current/ruoyi-admin.jar\nRestart=on-failure\nRestartSec=10\nTimeoutStopSec=45\nSuccessExitStatus=143\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\nReadWritePaths={BASE}/logs {BASE}/private-media {BASE}/public-uploads\nUMask=0077\n[Install]\nWantedBy=multi-user.target\n")
    redis_runtime()
    execute(['systemctl','daemon-reload']);execute(['systemctl','restart','kunkun-test-api']);persist()
    ready=wait(lambda:json.load(urllib.request.urlopen(f"http://127.0.0.1:{c['apiPort']}/health/ready",timeout=3)),90)
    assert ready.get('status')=='UP';result['readiness']=ready;state['apiReady']=True;persist()
  elif action=='backup-restore':
    assert state.get('apiReady');assert not state.get('publicActivated'),'Run baseline restore before opening the test site'
    dump=BASE/'backups/pre-activation.sql'
    env=os.environ.copy();env['MYSQL_PWD']=c['mysqlRootPassword']
    encrypted=BASE/'backups/pre-activation.sql.enc';decoded=BASE/'backups/restore-validation.sql'
    for file in (dump,encrypted,decoded):
      assert not file.is_symlink()
      if file.exists():assert stat.S_ISREG(file.stat().st_mode) and file.stat().st_uid==0 and stat.S_IMODE(file.stat().st_mode)==0o600
    if not dump.exists():
      assert not encrypted.exists() and not decoded.exists()
      data=execute(['/www/server/mysql/bin/mysqldump','--no-defaults','--protocol=socket','--socket='+str(BASE/'mysql/mysql.sock'),'--user=root','--single-transaction','--routines','--triggers','--hex-blob','--set-gtid-purged=OFF','kunkun_test'],env=env,timeout=90)
      write(dump,data)
    env['KUNKUN_BACKUP_KEY']=c['backupKey']
    if not encrypted.exists():execute(['openssl','enc','-aes-256-cbc','-salt','-pbkdf2','-iter','200000','-in',str(dump),'-out',str(encrypted),'-pass','env:KUNKUN_BACKUP_KEY'],env=env);encrypted.chmod(0o600)
    execute(['openssl','enc','-d','-aes-256-cbc','-pbkdf2','-iter','200000','-in',str(encrypted),'-out',str(decoded),'-pass','env:KUNKUN_BACKUP_KEY'],env=env);decoded.chmod(0o600)
    assert hashlib.sha256(dump.read_bytes()).digest()==hashlib.sha256(decoded.read_bytes()).digest()
    mysql(decoded.read_text(),'kunkun_restore_test')
    tables=mysql('SHOW TABLES;','kunkun_test').splitlines()[1:];counts={};restored={}
    for table in tables:
      assert re.fullmatch(r'[a-zA-Z0-9_]+',table)
      counts[table]=int(mysql('SELECT COUNT(*) FROM `'+table+'`;','kunkun_test').splitlines()[-1]);restored[table]=int(mysql('SELECT COUNT(*) FROM `'+table+'`;','kunkun_restore_test').splitlines()[-1])
    assert counts==restored
    for table in ('boost_member','boost_order','boost_ledger','boost_withdrawal','boost_session'):assert counts[table]==0
    media=list((BASE/'private-media').iterdir());assert not media
    for table in tables:
      sourceSql=mysql('SHOW CREATE TABLE `'+table+'`;','kunkun_test').splitlines()[1:]
      restoreSql=mysql('SHOW CREATE TABLE `'+table+'`;','kunkun_restore_test').splitlines()[1:]
      # MySQL's dump expands inherited utf8mb4 into explicit per-column syntax.
      # Compare every column property first, then normalize only that redundant
      # spelling; keys, foreign keys, collation and all remaining DDL stay exact.
      columns="SELECT ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,CHARACTER_SET_NAME,COLLATION_NAME,EXTRA,GENERATION_EXPRESSION,COLUMN_COMMENT FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' ORDER BY ORDINAL_POSITION;"
      assert mysql(columns,'kunkun_test')==mysql(columns,'kunkun_restore_test'),'Restored column metadata differs: '+table
      if sourceSql!=restoreSql:
        assert 'DEFAULT CHARSET=utf8mb4' in sourceSql[-1] and 'DEFAULT CHARSET=utf8mb4' in restoreSql[-1]
        assert [line.replace(' CHARACTER SET utf8mb4','') for line in sourceSql]==[line.replace(' CHARACTER SET utf8mb4','') for line in restoreSql],'Restored table definition differs: '+table
    sqlHash=hashlib.sha256(dump.read_bytes()).hexdigest();dump.unlink();decoded.unlink()
    state['backupRestorePassed']=True;persist();result.update(restoredTableCounts=restored,backupSha256=sqlHash,encryptedBackupSha256=hashlib.sha256(encrypted.read_bytes()).hexdigest(),decryptionMatches=True,schemaDefinitionsMatch=True,columnMetadataExactMatch=True,ddlNormalization='Only redundant explicit CHARACTER SET utf8mb4 on utf8mb4 tables; all column metadata is compared exactly',plaintextCopiesRemoved=True,privateMediaFiles=0)
  elif action=='activate':
    assert state.get('apiReady') and state.get('backupRestorePassed');require_safe_release()
    assert not state.get('publicActivated'),'Already activated; use verify without blindly changing sites again'
    sites=Path('/www/server/panel/vhost/nginx');backup=BASE/'backups/nginx';assert not backup.is_symlink();backup.mkdir(exist_ok=True);backup.chmod(0o700)
    for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
      current=sites/(domain+'.conf');expected=c['siteHashes'][domain]
      assert current.is_file() and not current.is_symlink() and hashlib.sha256(current.read_bytes()).hexdigest()==expected,'Site changed after preflight'
      target=backup/current.name
      assert not target.is_symlink()
      if target.exists():assert state.get('sitesBackupPrepared') and hashlib.sha256(target.read_bytes()).hexdigest()==expected,'Never overwrite an existing original-site backup'
      else:shutil.copy2(current,target)
    state['sitesBackupPrepared']=True;persist()
    try:
      for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
        candidate=BASE/'config'/('nginx-'+domain+'.conf');text=candidate.read_text()
        assert OWNER in text and 'deny all;' in text
        write(sites/(domain+'.conf'),text,0o644)
      execute(['nginx','-t']);execute(['nginx','-s','reload'])
    except Exception:
      for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):shutil.copy2(backup/(domain+'.conf'),sites/(domain+'.conf'))
      execute(['nginx','-t']);execute(['nginx','-s','reload']);raise
    state['publicActivated']=True;persist()
    execute(['systemctl','enable','kunkun-test-mysql','kunkun-test-redis','kunkun-test-api']);result['accessIps']=c['accessIps'];result['publicActivated']=True
  elif action=='rollback':
    assert state.get('sitesBackupPrepared')
    sites=Path('/www/server/panel/vhost/nginx')
    backup=BASE/'backups/nginx';assert not backup.is_symlink() and backup.stat().st_uid==0 and backup.stat().st_mode&0o077==0
    for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
      original=backup/(domain+'.conf');assert original.is_file() and not original.is_symlink() and original.stat().st_uid==0
      assert hashlib.sha256(original.read_bytes()).hexdigest()==c['siteHashes'][domain],'Original site backup differs from preflight'
    for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
      current=sites/(domain+'.conf');assert OWNER in current.read_text(),'Current site no longer belongs to this test deployment'
      shutil.copy2(BASE/'backups/nginx'/current.name,current)
    execute(['nginx','-t']);execute(['nginx','-s','reload'])
    execute(['systemctl','disable','--now','kunkun-test-api','kunkun-test-redis','kunkun-test-mysql'])
    pointer=BASE/'current'
    if pointer.is_symlink():pointer.unlink()
    if state.get('previousPointer'):pointer.symlink_to(state['previousPointer'],target_is_directory=True)
    state['publicActivated']=False;persist();result['ownedServicesStopped']=True
  elif action=='verify':
    result['readiness']=json.load(urllib.request.urlopen(f"http://127.0.0.1:{c['apiPort']}/health/ready",timeout=5))
    assert result['readiness'].get('status')=='UP'
    listeners=execute(['ss','-H','-lnt']);selected=[]
    for port in (c['mysqlPort'],c['redisPort'],c['apiPort']):
      found=[line for line in listeners.splitlines() if line.split()[3].endswith(':'+str(port))]
      assert found,'Owned service listener missing'
      for line in found:
        address=ipaddress.ip_address(line.split()[3].rsplit(':',1)[0].strip('[]'))
        effective=address.ipv4_mapped if isinstance(address,ipaddress.IPv6Address) and address.ipv4_mapped else address
        assert effective==ipaddress.ip_address('127.0.0.1'),'An owned service is not restricted to loopback'
      selected.extend(found)
    result['ownedListeners']=selected
    result['runtimeGrants']=mysql("SHOW GRANTS FOR 'kunkun_app'@'127.0.0.1';")
    for grant in [line for line in result['runtimeGrants'].splitlines() if line.startswith('GRANT ')]:
      match=re.match(r'GRANT (.+) ON (.+) TO ',grant);assert match and 'WITH GRANT OPTION' not in grant
      assert (match[1]=='USAGE' and match[2]=='*.*') or (match[2]=='`kunkun_test`.*' and set(match[1].split(', ')).issubset({'SELECT','INSERT','UPDATE','DELETE'}))
    assert state.get('publicActivated')
    denied=[]
    for domain,routes in {'ceshisss.oksja.cn':['/','/boost/config','/boost/policy','/health/ready','/boost-media/blocked-probe.png'], 'ceshijjht.oksja.cn':['/','/prod-api/boost/config','/prod-api/getInfo','/assets/blocked-probe.js']}.items():
      for scheme in ('http','https'):
        port=443 if scheme=='https' else 80
        for route in routes:
          code=execute(['curl','--silent','--show-error','--noproxy','*','--max-time','8','--resolve',f'{domain}:{port}:127.0.0.1','--interface','127.0.0.2','-H','X-Forwarded-For: '+c['accessIps'][-1],'-H','X-Real-IP: '+c['accessIps'][-1],'-o','/dev/null','-w','%{http_code}',scheme+'://'+domain+route])
          assert code=='403','Non-allowlisted source or spoofed proxy header bypassed test restrictions'
          denied.append({'scheme':scheme,'domain':domain,'path':route,'source':'127.0.0.2','forwardedAddressSpoofed':True,'httpStatus':int(code)})
    result['deniedSourceCases']=denied
    result['realIpDirectives']=execute(['sh','-c',"nginx -T 2>/dev/null | grep -E '^[[:space:]]*(real_ip_header|set_real_ip_from|real_ip_recursive)' || true"])
    result['guardProfile']=environment()['SPRING_PROFILES_ACTIVE'];result['state']={k:v for k,v in state.items() if 'password' not in k.lower()}
  result['passed']=True
except Exception as exc:
  result['passed']=False;result['error']=redact(str(exc));raise
finally:
  result['finishedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
  print(json.dumps(result,ensure_ascii=False))
