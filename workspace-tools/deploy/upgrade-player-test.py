"""Versioned upgrade of the already-owned test deployment; never first-install it again.

prepare: immutable candidate, live snapshot restore to a fresh private schema, migrations twice.
activate: short maintenance, stop only owned API, fresh backup, additive migration, switch, check,
then reopen. Before reopening, failure restores the old pointer/environment/nginx. Additive DB
columns may remain but old rows must match; after reopening there is no automatic data rollback.
"""
import datetime,hashlib,json,os,re,shutil,stat,subprocess,sys,time,urllib.request,zipfile
from pathlib import Path
BASE=Path('/srv/kunkun/test');OWNER='kunkun-internal-test-20261006'
action,version=sys.argv[1:3];assert action in ('prepare','activate','inspect')
assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',version)
c=json.loads((BASE/'config/private.json').read_text());assert c['owner']==OWNER
deployment=json.loads((BASE/'deployment-state.json').read_text());assert deployment['owner']==OWNER
assert BASE.resolve()==BASE and (BASE/'current').is_symlink()
WORK=BASE/'upgrades'/version;RELEASE=BASE/'releases'/version
WORK.parent.mkdir(mode=0o700,exist_ok=True);assert not WORK.parent.is_symlink()
WORK.mkdir(mode=0o700,exist_ok=True);assert not WORK.is_symlink();WORK.chmod(0o700)
STATE=WORK/'state.json';state=json.loads(STATE.read_text()) if STATE.exists() else {'owner':OWNER,'version':version}
assert state['owner']==OWNER and state['version']==version
secret=[v for k,v in c.items() if isinstance(v,str) and any(s in k.lower() for s in ('password','secret','key'))]
def safe(s):
 for v in secret:
  if v:s=s.replace(v,'[REDACTED]')
 return s
def write(p,s,mode=0o600):
 assert not p.is_symlink();p.write_text(s);p.chmod(mode)
def persist():write(STATE,json.dumps(state,ensure_ascii=False,indent=2))
def run(args,input=None,env=None,timeout=120):
 r=subprocess.run(args,input=input,text=True,capture_output=True,env=env,timeout=timeout)
 if r.returncode:raise RuntimeError(safe('Command failed: '+args[0]+' '+r.stderr[-1500:]+r.stdout[-1500:]))
 return r.stdout
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def sql(q,db=None):
 assert db is None or db=='kunkun_test' or re.fullmatch(r'kunkun_upgrade_\d{8}T\d{9}Z(?:_activation)?',db)
 env=os.environ.copy();env['MYSQL_PWD']=c['mysqlRootPassword']
 return run(['/www/server/mysql/bin/mysql','--no-defaults','--protocol=socket','--socket='+str(BASE/'mysql/mysql.sock'),'--user=root','--batch','--raw','--skip-column-names','--default-character-set=utf8mb4']+([db] if db else []),q,env).strip()
def tables(db):
 names=sql('SHOW TABLES',db).splitlines();assert all(re.fullmatch(r'[a-zA-Z0-9_]+',t) for t in names);return names
def fingerprint(db,original=None):
 out={}
 for t in (original or tables(db)):
  columns=sql("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+t+"' ORDER BY ordinal_position",db).splitlines()
  if original:columns=original[t]['columns']
  assert all(re.fullmatch(r'[a-zA-Z0-9_]+',x) for x in columns)
  keys=(original[t].get('keys') if original else None) if t=='boost_config' else None
  if t=='boost_config' and keys is None:keys=sql('SELECT HEX(config_key) FROM boost_config ORDER BY config_key',db).splitlines()
  if keys:assert all(re.fullmatch('[A-Fa-f0-9]+',k) for k in keys)
  where=(' WHERE HEX(config_key) IN ('+','.join("'"+k+"'" for k in keys)+')') if keys else ''
  expr=','.join("IF(`"+x+"` IS NULL,'N',CONCAT('V',HEX(`"+x+"`)))" for x in columns)
  hashes=sql("SELECT SHA2(CONCAT_WS('|',"+expr+"),256) AS h FROM `"+t+'`'+where+' ORDER BY h',db)
  out[t]={'columns':columns,'rows':int(sql('SELECT COUNT(*) FROM `'+t+'`'+where,db)),'sha256':hashlib.sha256(hashes.encode()).hexdigest()}
  if keys is not None:out[t]['keys']=keys
 return out
def envfile():
 result={}
 for line in (BASE/'config/production.env').read_text().splitlines():
  if '=' in line:
   k,v=line.split('=',1);result[k]=json.loads(v) if v.startswith('"') else v
 return result
def migration(db):
 env=os.environ.copy();env.update(envfile());env.update(BOOST_DB_URL='jdbc:mysql://127.0.0.1:'+str(c['mysqlPort'])+'/'+db+'?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',BOOST_DB_USER='kunkun_migrate',BOOST_DB_PASSWORD=c['migrationPassword'],BOOST_MIGRATION_DATABASE=db,BOOST_LOG_DIR=str(WORK/'migration-logs'))
 result=run(['/www/server/java/jdk-17.0.8/bin/java','-Dloader.main=com.ruoyi.boost.BoostMigrationCommand','-cp',str(RELEASE/'ruoyi-admin.jar'),'org.springframework.boot.loader.launch.PropertiesLauncher'],env=env,timeout=120)
 assert 'Migration verified:' in result;return result.strip().splitlines()[-1]
MENU={87000:('boost',None,'M',''),87003:('members','boost/console','C','boost:members:read'),87008:('shops','boost/console','C','boost:shops:read'),87009:('rewards','boost/rewards','C','boost:rewards:read'),87026:('#',None,'F','boost:shops:review'),87027:('#',None,'F','boost:rewards:manage'),87028:('#',None,'F','boost:rewards:grant'),87029:('#',None,'F','boost:rewards:fulfill'),87030:('#',None,'F','boost:benefits:manage'),87031:('#',None,'F','boost:benefits:rules'),87010:('content','boost/content','C','boost:content:read'),87011:('activities','boost/content','C','boost:content:read'),87032:('#',None,'F','boost:content:manage')}
def nginx_candidate(text,domain):
 assert OWNER in text and text.count('server {')==2,'Unexpected owned proxy structure'
 if domain=='ceshisss.oksja.cn':
  old='boost-media(?:/|$)|health';new='boost-media(?:/|$)|boost-reward-media(?:/|$)|health'
  if new in text:
   assert text.count(new)==1 and old not in text,'Duplicate media proxy rule'
  else:
   assert text.count(old)==1,'Unknown media proxy rule';text=text.replace(old,new)
 return text

def menus(db):
 for id,(path,component,kind,permission) in MENU.items():
  rows=sql('SELECT path,COALESCE(component,\'\'),menu_type,COALESCE(perms,\'\'),parent_id FROM sys_menu WHERE menu_id='+str(id),db)
  if not rows:assert id not in (87000,87003),'Required base menu missing';continue
  fields=rows.split('\t');assert fields[0]==path and (component is None or fields[1]==component) and fields[2]==kind and fields[3]==permission,'Menu ID belongs to another feature: '+str(id)
  parent={87000:0,87003:87000,87008:87000,87009:87000,87026:87008,87027:87009,87028:87009,87029:87009,87030:87003,87031:87003,87010:87000,87011:87000,87032:87010}[id];assert int(fields[4])==parent
 roles=sql('SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id',db)
 for name in ('boost-player-menu.sql','boost-rewards-menu.sql','boost-benefits-menu.sql','boost-content-menu.sql'):sql((RELEASE/'menus'/name).read_text(),db)
 assert roles==sql('SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id',db),'Role rights unexpectedly changed'
 assert int(sql('SELECT COUNT(*) FROM sys_menu WHERE menu_id IN ('+','.join(map(str,MENU))+')',db))==len(MENU)
def archive_backup(label):
 plain=WORK/(label+'.sql');assert not plain.exists()
 env=os.environ.copy();env['MYSQL_PWD']=c['mysqlRootPassword']
 data=run(['/www/server/mysql/bin/mysqldump','--no-defaults','--protocol=socket','--socket='+str(BASE/'mysql/mysql.sock'),'--user=root','--single-transaction','--routines','--triggers','--hex-blob','--set-gtid-purged=OFF','kunkun_test'],env=env)
 write(plain,data);env['KUNKUN_BACKUP_KEY']=c['backupKey'];encrypted=WORK/(label+'.sql.enc')
 run(['openssl','enc','-aes-256-cbc','-salt','-pbkdf2','-iter','200000','-in',str(plain),'-out',str(encrypted),'-pass','env:KUNKUN_BACKUP_KEY'],env=env);encrypted.chmod(0o600)
 decoded=WORK/(label+'.decoded.sql');run(['openssl','enc','-d','-aes-256-cbc','-pbkdf2','-iter','200000','-in',str(encrypted),'-out',str(decoded),'-pass','env:KUNKUN_BACKUP_KEY'],env=env);decoded.chmod(0o600);assert digest(decoded)==digest(plain)
 decoded.unlink();state[label]={'plainSha256':digest(plain),'encryptedSha256':digest(encrypted),'encryptedFile':str(encrypted)};persist();return plain
def media_backup(label):
 output={}
 for name in ('private-media','public-uploads'):
  source=BASE/name;assert source.resolve()==source;dest=WORK/(label+'-'+name);assert not dest.exists()
  for f in source.rglob('*'):assert not f.is_symlink() and (f.is_file() or f.is_dir())
  shutil.copytree(source,dest);dest.chmod(0o700)
  a={str(f.relative_to(source)):digest(f) for f in source.rglob('*') if f.is_file()};b={str(f.relative_to(dest)):digest(f) for f in dest.rglob('*') if f.is_file()};assert a==b
  output[name]={'fileCount':len(a),'inventorySha256':hashlib.sha256(json.dumps(a,sort_keys=True).encode()).hexdigest()}
 return output
def validate_release():
 assert RELEASE.is_dir() and not RELEASE.is_symlink();metadata=json.loads((RELEASE/'RELEASE.json').read_text())
 assert metadata['version']==version and metadata['backendSha256']==digest(RELEASE/'ruoyi-admin.jar')
 for file,sha in state['inventory'].items():assert digest(RELEASE/file)==sha,'Candidate changed: '+file
 return metadata
def health():
 end=time.monotonic()+100
 while time.monotonic()<end:
  try:
   ready=json.load(urllib.request.urlopen('http://127.0.0.1:'+str(c['apiPort'])+'/health/ready',timeout=3))
   if ready.get('status')=='UP':return ready
  except Exception:pass
  time.sleep(1)
 raise RuntimeError('Owned API did not become ready')
NGINX=Path('/www/server/panel/vhost/nginx');nginxbin='/www/server/nginx/sbin/nginx'
def reload_nginx():run([nginxbin,'-t']);run([nginxbin,'-s','reload'])
result={'action':action,'version':version,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
try:
 if action=='prepare':
  assert not state.get('prepared') and not state.get('activationStarted')
  archive=BASE/'config'/(version+'.delta.zip');assert digest(archive)==sys.argv[3]
  assert not RELEASE.exists();RELEASE.mkdir(mode=0o755)
  with zipfile.ZipFile(archive) as z:
   assert z.testzip() is None;manifest=json.loads(z.read('delta-manifest.json'));assert manifest['version']==version
   assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',manifest['baseVersion'])
   basis=BASE/'releases'/manifest['baseVersion'];assert (BASE/'current').resolve()==basis and not basis.is_symlink()
   assert digest(basis/'ruoyi-admin.jar')==manifest['baseJarSha256'];inventory=manifest['files']
   for name,expected in inventory.items():
    p=Path(name);assert not p.is_absolute() and '..' not in p.parts and re.fullmatch('[a-f0-9]{64}',expected)
    target=RELEASE/p;target.parent.mkdir(parents=True,exist_ok=True)
    if name=='ruoyi-admin.jar':
     with (basis/name).open('rb') as oldjar,target.open('wb') as newjar:
      for piece in manifest['jarPieces']:
       if 'file' in piece:
        assert re.fullmatch('jar-patch/[0-9]+',piece['file']);newjar.write(z.read(piece['file']))
       else:
        a,n=piece['offset'],piece['length'];assert isinstance(a,int) and isinstance(n,int) and 0<=a<=a+n<=(basis/name).stat().st_size
        oldjar.seek(a);data=oldjar.read(n);assert len(data)==n;newjar.write(data)
     assert target.stat().st_size==manifest['jarBytes']
    elif name in manifest['changed']:target.write_bytes(z.read('files/'+name))
    else:
     source=basis/p;assert not source.is_symlink() and source.is_file() and os.path.commonpath([str(source.resolve()),str(basis)])==str(basis);shutil.copyfile(source,target)
    target.chmod(0o644);assert digest(target)==expected,'Reconstructed candidate differs: '+name
  state['inventory']=inventory;meta=validate_release();assert meta['secretScanPassed']
  state['oldPointer']=str((BASE/'current').resolve());assert state['oldPointer']!=str(RELEASE)
  for name in ('production.env','private.json'):shutil.copy2(BASE/'config'/name,WORK/(name+'.before'));(WORK/(name+'.before')).chmod(0o600)
  for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):shutil.copy2(NGINX/(domain+'.conf'),WORK/(domain+'.before'));assert OWNER in (WORK/(domain+'.before')).read_text()
  for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):write(WORK/(domain+'.candidate'),nginx_candidate((WORK/(domain+'.before')).read_text(),domain))
  clone='kunkun_upgrade_'+version[len('kunkun-'):];assert sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+clone+"'")=='0'
  sql('CREATE DATABASE `'+clone+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');state['clone']=clone;persist()
  source=fingerprint('kunkun_test');plain=archive_backup('prepare');sql(plain.read_text(),clone);plain.unlink();before=fingerprint(clone)
  assert source==before==fingerprint('kunkun_test'),'Source changed while taking snapshot; prepare a new candidate snapshot'
  result['restoredSourceFingerprintMatched']=True
  sql('GRANT ALL PRIVILEGES ON `'+clone+"`.* TO 'kunkun_migrate'@'127.0.0.1'")
  try:
   result['migrationRuns']=[migration(clone),migration(clone)]
   preserved=before
   assert fingerprint(clone,preserved)==preserved,'Migration modified original columns';menus(clone)
  finally:sql('REVOKE ALL PRIVILEGES ON `'+clone+"`.* FROM 'kunkun_migrate'@'127.0.0.1'")
  result['restoredRows']={t:x['rows'] for t,x in before.items()};result['mediaBackup']=media_backup('prepare');state['prepared']=True;state['oldDataFingerprint']=before;persist()
 elif action=='activate':
  assert state.get('prepared') and not state.get('activationStarted');meta=validate_release()
  assert str((BASE/'current').resolve())==state['oldPointer']
  for name in ('production.env','private.json'):assert digest(BASE/'config'/name)==digest(WORK/(name+'.before'))
  for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):assert digest(NGINX/(domain+'.conf'))==digest(WORK/(domain+'.before'))
  assert sql("SELECT COUNT(*) FROM boost_member WHERE balance_cents<>0",'kunkun_test')=='0'
  assert sql("SELECT COUNT(*) FROM boost_order WHERE status IN ('WAIT_DELIVERY','WAIT_CONFIRM','WAIT_START','IN_PROGRESS','REVIEW','AFTERSALE','REFUNDING','REFUND_EXCEPTION') OR (status='WAIT_PAY' AND payment_mode='WECHAT')",'kunkun_test')=='0'
  assert sql("SELECT COUNT(*) FROM boost_withdrawal WHERE status IN ('PENDING','PROCESSING','WAIT_USER_CONFIRM')",'kunkun_test')=='0'
  assert sql("SELECT COUNT(*) FROM boost_order WHERE status='WAIT_PAY'",'kunkun_test')=='0','Pending orders require a separate reconciliation plan before updating'
  candidates={domain:nginx_candidate((WORK/(domain+'.before')).read_text(),domain) for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn')}
  for domain,text in candidates.items():assert text==(WORK/(domain+'.candidate')).read_text(),'Proxy candidate changed since preparation'
  state['activationStarted']=True;persist()
  for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
   text=(WORK/(domain+'.before')).read_text();assert text.count('server {')==2
   write(NGINX/(domain+'.conf'),text.replace('server {','server {\n    if ($remote_addr !~ "^(127\\.0\\.0\\.1|::1)$") { return 503; }'),0o644)
  reload_nginx();run(['systemctl','stop','kunkun-test-api'])
  before=fingerprint('kunkun_test');plain=archive_backup('activation');state['activationFingerprint']=before;result['mediaBackup']=media_backup('activation');persist()
  clone=state['clone']+'_activation';assert sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+clone+"'")=='0'
  sql('CREATE DATABASE `'+clone+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');sql(plain.read_text(),clone)
  assert fingerprint(clone)==before==fingerprint('kunkun_test'),'Fresh stopped-service restore mismatch'
  result['activationRestoreMatches']=True
  state['oldMenus']=sql('SELECT menu_id,HEX(menu_name) FROM sys_menu WHERE menu_id IN ('+','.join(map(str,MENU))+')','kunkun_test').splitlines()
  state['dbMigrationStarted']=True;persist()
  result['migrationRuns']=[migration('kunkun_test'),migration('kunkun_test')]
  preserved=before
  assert fingerprint('kunkun_test',preserved)==preserved,'Original data changed before menu upgrade';menus('kunkun_test')
  state['rollbackFingerprint']=fingerprint('kunkun_test');persist()
  plain.unlink();os.symlink(str(RELEASE),str(BASE/'candidate-current'));os.replace(BASE/'candidate-current',BASE/'current')
  values=envfile();values['BOOST_FREE_POINTS_DRAW_ENABLED']='true';write(BASE/'config/production.env','\n'.join(k+'='+json.dumps(v,ensure_ascii=False) for k,v in values.items())+'\n')
  run(['systemctl','start','kunkun-test-api']);result['health']=health()
  config=json.load(urllib.request.urlopen('http://127.0.0.1:'+str(c['apiPort'])+'/boost/config'))['data'];assert config['businessMode']=='PLAYER_ITEM' and config['games']==['和平精英'] and config['paymentsEnabled'] is False;result['businessConfig']=config
  # Another owner may reload shared Nginx; the no-rollback boundary precedes any
  # public configuration write, not only our own final reload command.
  state['reopeningStarted']=True;persist()
  for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):
   write(NGINX/(domain+'.conf'),candidates[domain],0o644)
  # Set the boundary before reopening: never auto-restore data after users can write.
  reload_nginx();state['activated']=True
  c.update(version=version,jarSha256=meta['backendSha256']);write(BASE/'config/private.json',json.dumps(c,ensure_ascii=False,indent=2))
  deployment.update(migratedVersion=version,lastUpgrade=str(WORK),apiReady=True);write(BASE/'deployment-state.json',json.dumps(deployment,indent=2));persist()
 elif action=='inspect':result.update(state={k:v for k,v in state.items() if k not in ('inventory','oldDataFingerprint','activationFingerprint')},current=str((BASE/'current').resolve()),jarSha256=digest(BASE/'current/ruoyi-admin.jar'),health=health())
 result['status']='已实测通过'
except Exception as e:
 result.update(status='失败',error=safe(str(e)))
 if action=='activate' and state.get('activationStarted') and not state.get('reopeningStarted'):
  try:
   run(['systemctl','stop','kunkun-test-api'])
   # Additive migration does not overwrite old row columns. No new business traffic was admitted.
   if state.get('dbMigrationStarted'):
    baseline=state.get('rollbackFingerprint') or {t:v for t,v in state['activationFingerprint'].items() if t not in ('boost_config','sys_menu')}
    assert fingerprint('kunkun_test',baseline)==baseline, 'Original data needs manual recovery from encrypted snapshot'
    original={int(line.split('\t')[0]):line.split('\t')[1] for line in state['oldMenus']};added=set(MENU)-set(original)
    if added:sql('DELETE FROM sys_menu WHERE menu_id IN ('+','.join(map(str,added))+')','kunkun_test')
    for id,name in original.items():sql('UPDATE sys_menu SET menu_name=CONVERT(0x'+name+' USING utf8mb4) WHERE menu_id='+str(id),'kunkun_test')
    oldMenus={t:state['activationFingerprint'][t] for t in ('sys_menu','sys_role_menu')};assert fingerprint('kunkun_test',oldMenus)==oldMenus
   temp=BASE/'rollback-current';assert not temp.exists();os.symlink(state['oldPointer'],temp);os.replace(temp,BASE/'current')
   shutil.copy2(WORK/'production.env.before',BASE/'config/production.env');run(['systemctl','start','kunkun-test-api']);health()
   for domain in ('ceshisss.oksja.cn','ceshijjht.oksja.cn'):shutil.copy2(WORK/(domain+'.before'),NGINX/(domain+'.conf'))
   reload_nginx();result['recovery']='Previous app/environment/proxy restored before reopening; additive schema retained. No business rows reverted.'
  except Exception as recovery:result['recoveryError']=safe(str(recovery))
 state['failure']=result;persist()
finally:
 result['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();write(WORK/(action+'-result.json'),json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));sys.exit(0 if result.get('status')=='已实测通过' else 1)
