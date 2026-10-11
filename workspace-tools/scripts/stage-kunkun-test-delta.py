"""Upload only changed files while verifying an entire immutable candidate."""
import datetime,hashlib,json,re,shlex,stat,sys,zipfile
from pathlib import Path
import paramiko
ROOT=Path(__file__).resolve().parent.parent
PRIVATE=ROOT/'.runtime/deploy-test-20261006';OUT=ROOT/'test-results/deployment-test-20261006'
new=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
old=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8'))
cfg=json.loads((PRIVATE/'server.private.json').read_text(encoding='utf-8'))
assert cfg['version']==old['version'] and cfg['jarSha256']==old['backendSha256']==new['backendSha256']
assert new['secretScanPassed'] and new['appid']=='wxd31a958f0fc66a19' and new['apiOrigin']=='https://ceshisss.oksja.cn'
for release in (old,new):
    assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',release['version'])
    assert hashlib.sha256(Path(release['archive']).read_bytes()).hexdigest()==release['archiveSha256']
manifest={'baseVersion':old['version'],'version':new['version'],'jarSha256':new['backendSha256'],'files':{},'changed':[]}
delta=PRIVATE/'candidate-delta.zip'
with zipfile.ZipFile(old['archive']) as previous,zipfile.ZipFile(new['archive']) as current,zipfile.ZipFile(delta,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
    assert previous.testzip() is None and current.testzip() is None
    for item in current.infolist():
        assert not item.is_dir() and not stat.S_ISLNK(item.external_attr>>16)
        data=current.read(item);digest=hashlib.sha256(data).hexdigest();manifest['files'][item.filename]=digest
        if item.filename not in previous.namelist() or hashlib.sha256(previous.read(item.filename)).hexdigest()!=digest:
            manifest['changed'].append(item.filename);output.writestr(item.filename,data)
    output.writestr('delta-manifest.json',json.dumps(manifest))
(OUT/'candidate-file-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
connection=json.loads((PRIVATE/'connection.private.json').read_text(encoding='utf-8-sig'))
ssh=paramiko.SSHClient();ssh.load_host_keys(str(ROOT/'.runtime/server-known-hosts'));ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
record={'startedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':'python scripts/stage-kunkun-test-delta.py [new release record] [verified base release record]','deltaBytes':delta.stat().st_size,'localArchiveSha256':new['archiveSha256']}
try:
    ssh.connect(connection['host'],username=connection['user'],password=connection['password'],timeout=15,allow_agent=False,look_for_keys=False)
    sftp=ssh.open_sftp();base='/srv/kunkun/test/config/'
    for local,name in ((delta,'candidate-delta.zip'),(ROOT/'deploy/test-stage-delta.py','test-stage-delta.py')):
        try:assert stat.S_ISREG(sftp.lstat(base+name).st_mode)
        except FileNotFoundError:pass
        sftp.put(str(local),base+name);sftp.chmod(base+name,0o600)
        assert hashlib.sha256(sftp.open(base+name,'rb').read()).digest()==hashlib.sha256(local.read_bytes()).digest()
    digest=hashlib.sha256(delta.read_bytes()).hexdigest()
    command='python3 '+shlex.quote(base+'test-stage-delta.py')+' '+shlex.quote(digest)
    _,stdout,stderr=ssh.exec_command(command,timeout=120)
    data=stdout.read().decode();error=stderr.read().decode();record['exitCode']=stdout.channel.recv_exit_status()
    for value in (connection['password'],connection['appSecret'],cfg['adminPassword']):assert value not in data+error
    record['stdout']=data;record['stderr']=error
    assert record['exitCode']==0,'Remote candidate validation failed'
    record['remote']=json.loads(data);record['passed']=True
except Exception as error:
    record['passed']=False;record['errorType']=type(error).__name__;raise
finally:
    ssh.close();record['finishedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    (OUT/'stage-delta.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in record.items() if k not in ('stdout','stderr')}))
