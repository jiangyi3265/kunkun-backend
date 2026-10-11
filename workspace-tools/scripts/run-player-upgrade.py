"""Upload an immutable package and run the reviewed updater on the already owned host."""
import sys,json,hashlib,shlex,stat,datetime,re,zipfile
from pathlib import Path
import paramiko
ROOT=Path(__file__).resolve().parent.parent
action,metadata_path=sys.argv[1:3];assert action in ('prepare','activate','inspect')
meta=json.loads(Path(metadata_path).read_text('utf-8'));version=meta['version']
assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',version)
out=ROOT/'test-results/interconnect-20261008/deployment'/version;out.mkdir(parents=True,exist_ok=True)
record={'action':action,'version':version,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
private=ROOT/'.runtime/deploy-test-20261006';c=json.loads((private/'connection.private.json').read_text('utf-8-sig'))
ssh=paramiko.SSHClient();ssh.load_host_keys(str(ROOT/'.runtime/server-known-hosts'));ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
 ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,allow_agent=False,look_for_keys=False);sftp=ssh.open_sftp()
 script=ROOT/'deploy/upgrade-player-test.py';remote='/srv/kunkun/test/config/upgrade-player-'+version+'.py'
 with zipfile.ZipFile(meta['archive']) as full:
  assert full.read('deploy/upgrade-player-test.py')==script.read_bytes(),'Updater differs from immutable reviewed release'
 def remote_digest(target):
  _,verify,errors=ssh.exec_command('sha256sum '+shlex.quote(target),timeout=30);result=verify.read().decode().split();assert verify.channel.recv_exit_status()==0 and result;return result[0]
 def put(local,target):
  try:
   assert stat.S_ISREG(sftp.lstat(target).st_mode)
   assert remote_digest(target)==hashlib.sha256(local.read_bytes()).hexdigest(),'Refuse to overwrite existing versioned artifact'
   return
  except FileNotFoundError:pass
  temporary=target+'.uploading'
  try:assert stat.S_ISREG(sftp.lstat(temporary).st_mode)
  except FileNotFoundError:pass
  sftp.put(str(local),temporary);sftp.chmod(temporary,0o600)
  assert remote_digest(temporary)==hashlib.sha256(local.read_bytes()).hexdigest();sftp.rename(temporary,target)
 if action=='prepare':
  archive=Path(meta['deltaArchive']);assert hashlib.sha256(archive.read_bytes()).hexdigest()==meta['deltaSha256']
  put(archive,'/srv/kunkun/test/config/'+version+'.delta.zip');put(script,remote)
 else:assert hashlib.sha256(sftp.open(remote,'rb').read()).digest()==hashlib.sha256(script.read_bytes()).digest(),'Updater changed since preparation; independently review again before proceeding'
 command='python3 '+shlex.quote(remote)+' '+action+' '+shlex.quote(version)+((' '+meta['deltaSha256']) if action=='prepare' else '')
 record['command']=command
 _,stdout,stderr=ssh.exec_command(command,timeout=600);data=stdout.read().decode();err=stderr.read().decode();record['exitCode']=stdout.channel.recv_exit_status()
 for secret in (c['password'],c['appSecret']):assert secret not in data+err
 record.update(stdout=data,stderr=err)
 if record['exitCode']==0:record['result']=json.loads(data)
 print(json.dumps({'exitCode':record['exitCode'],'action':action,'version':version,'result':record.get('result'),'stderr':err},ensure_ascii=False))
except Exception as e:
 record.update(exitCode=1,errorType=type(e).__name__);print('Upgrade runner failed: '+type(e).__name__)
finally:
 ssh.close();record['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();(out/(action+'.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2),'utf-8')
sys.exit(record.get('exitCode',1))
