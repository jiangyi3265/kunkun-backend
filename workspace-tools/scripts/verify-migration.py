"""Back up only boost tables, then rehearse the migration twice in a new isolated database."""
from pathlib import Path
import json,os,subprocess,time,re,hashlib,shutil
from local_db import ROOT,configuration,mysql
c=configuration();stamp=str(time.time_ns()//1000000);target='boost_verify_'+stamp
evidence=Path(os.environ.get('QA_OUTPUT_DIR',ROOT/'test-results/remediation-20261002'));evidence.mkdir(parents=True,exist_ok=True)
out=ROOT/'.runtime/migration-backups';out.mkdir(parents=True,exist_ok=True)
tables=mysql("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'boost\\_%' ORDER BY table_name").strip().splitlines()[1:]
assert tables and all(re.fullmatch(r'boost_[a-z_]+',t) for t in tables)
env=os.environ.copy();env['MYSQL_PWD']=c['password']
backup=out/('boost-before-'+stamp+'.sql')
with backup.open('wb') as f:
 p=subprocess.run(['mysqldump','--host='+c['host'],'--port='+c['port'],'--user='+c['user'],'--single-transaction','--skip-lock-tables','--set-gtid-purged=OFF','--no-tablespaces','--default-character-set=utf8mb4',c['database'],*tables],stdout=f,stderr=subprocess.PIPE,env=env)
if p.returncode:raise RuntimeError(p.stderr.decode('utf-8','replace'))
mysql('CREATE DATABASE `'+target+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
mysql(backup.read_text(encoding='utf-8'),target)
source_hashes={}
restore_counts={}
for table in tables:
 columns=mysql("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+table+"' ORDER BY ordinal_position").strip().splitlines()[1:]
 assert all(re.fullmatch(r'[a-z_]+',column) for column in columns)
 query='SELECT * FROM `'+table+'` ORDER BY '+','.join('`'+column+'`' for column in columns)
 source_hashes[table]=hashlib.sha256(mysql(query,target).encode()).hexdigest()
 restore_counts[table]=int(mysql('SELECT COUNT(*) AS n FROM `'+table+'`',target).strip().splitlines()[-1])
private=json.loads((ROOT/'.runtime/local-dev.json').read_text(encoding='utf-8'));env.update(private)
env['BOOST_DB_URL']=re.sub(r'/[^/?]+\?', '/'+target+'?',private['BOOST_DB_URL'])
env['BOOST_MIGRATION_DATABASE']=target
jar=ROOT/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'
java=os.environ.get('JAVA_HOME',str(Path.home()/'.jdks/azul-17.0.19'))+'/bin/java.exe'
command=[java,'-Dfile.encoding=UTF-8','-Dloader.main=com.ruoyi.boost.BoostMigrationCommand','-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher']
for iteration in (1,2):
 result=subprocess.run(command,cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 (evidence/f'migration-{iteration}.log').write_bytes(result.stdout)
 if result.returncode:raise RuntimeError('Migration rehearsal failed; inspect its log')
for table in tables:
 columns=mysql("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+table+"' ORDER BY ordinal_position",target).strip().splitlines()[1:]
 query='SELECT * FROM `'+table+'` ORDER BY '+','.join('`'+column+'`' for column in columns)
 if hashlib.sha256(mysql(query,target).encode()).hexdigest()!=source_hashes[table]:raise RuntimeError('Migration changed restored content: '+table)
counts=[]
restored_env=os.environ.copy();restored_env.update(private);restored_env['BOOST_DB_URL']=env['BOOST_DB_URL'];restored_env['QA_OUTPUT_DIR']=str(evidence)
audit=subprocess.run([__import__('sys').executable,'-X','utf8',str(ROOT/'scripts/audit-business-data.py')],env=restored_env,capture_output=True)
(evidence/'restored-data-audit.log').write_bytes(audit.stdout+audit.stderr)
if audit.returncode:raise RuntimeError('Restored data invariants failed')
shutil.copy2(evidence/'data-audit.json',evidence/'restored-data-audit.json')
media=ROOT/'.runtime/boost-media';restored_media=out/('media-restore-'+stamp);media_hashes=[]
if not media.is_dir():raise RuntimeError('Private local media source missing')
shutil.copytree(media,restored_media)
for asset in media.rglob('*'):
 if asset.is_symlink():raise RuntimeError('Unexpected symlink in private media')
 if asset.is_file():
  relative=asset.relative_to(media);digest=hashlib.sha256(asset.read_bytes()).hexdigest()
  if hashlib.sha256((restored_media/relative).read_bytes()).hexdigest()!=digest:raise RuntimeError('Restored media content mismatch')
  media_hashes.append({'name':relative.as_posix(),'sha256':digest})

for table in tables:
 before=restore_counts[table];after=mysql('SELECT COUNT(*) AS n FROM '+table,target).strip().splitlines()[-1]
 if table!='boost_config' and int(before)!=int(after):raise RuntimeError('Unexpected row count change: '+table)
 counts.append({'table':table,'before':int(before),'after':int(after)})
report={'passed':True,'source':c['database'],'isolatedDatabase':target,'repeatedRuns':2,'backup':str(backup),'counts':counts,'comparisonScope':'Restored backup before and after two migrations','backupSha256':hashlib.sha256(backup.read_bytes()).hexdigest(),'tableContentSha256':source_hashes,'restoredInvariantsPassed':True,'mediaFiles':len(media_hashes),'mediaRestore':str(restored_media),'mediaContentVerified':True}
(evidence/'migration-rehearsal.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'passed':True,'isolatedDatabase':target,'tablesPreserved':len(tables),'runs':2}))
