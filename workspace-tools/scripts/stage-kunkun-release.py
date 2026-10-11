"""Upload a checksummed package to a private, inactive release directory."""
import hashlib
import json
from pathlib import Path
import re
import shlex
import stat
import sys
import paramiko

root=Path(__file__).resolve().parent.parent
out=root/'test-results/deployment-preflight-20261002'
release=json.loads((out/'release-package.json').read_text(encoding='utf-8'))
version=release['version']
assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',version)
archive=Path(release['archive'])
assert hashlib.sha256(archive.read_bytes()).hexdigest()==release['archiveSha256']
c=json.load(sys.stdin)
ssh=paramiko.SSHClient()
ssh.load_host_keys(str(root/'.runtime/server-known-hosts'))
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
result={'version':version,'staged':False,'activated':False}
def mkdir(path):
    try:
        entry=sftp.lstat(path)
        if not stat.S_ISDIR(entry.st_mode):raise RuntimeError('Expected a real directory: '+path)
    except FileNotFoundError:sftp.mkdir(path,0o700)
def remote_hash(path):
    _,stdout,stderr=ssh.exec_command('sha256sum '+shlex.quote(path),timeout=40)
    value=stdout.read().decode()
    if stdout.channel.recv_exit_status()!=0:raise RuntimeError('Remote package hash could not be read')
    return value.split()[0]
try:
    ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,auth_timeout=15,allow_agent=False,look_for_keys=False)
    sftp=paramiko.SFTPClient.from_transport(ssh.get_transport(),window_size=16*1024*1024)
    for folder in ['/srv/kunkun','/srv/kunkun/releases','/srv/kunkun/releases/'+version]:mkdir(folder)
    destination='/srv/kunkun/releases/'+version+'/release.zip'
    try:
        sftp.stat(destination)
        if remote_hash(destination)!=release['archiveSha256']:raise RuntimeError('A different package already occupies this release version')
    except FileNotFoundError:
        last=[-1]
        def progress(sent,total):
            step=int(sent*4/total)
            if step>last[0]:
                last[0]=step
                print('Release upload: '+str(min(step*25,100))+'%',flush=True)
        temporary=destination+'.uploading'
        sftp.put(str(archive),temporary,callback=progress)
        sftp.chmod(temporary,0o600)
        if remote_hash(temporary)!=release['archiveSha256']:raise RuntimeError('Uploaded package checksum mismatch')
        sftp.posix_rename(temporary,destination)
    result.update(staged=True,remotePath=destination,sha256=remote_hash(destination))
    _,stdout,stderr=ssh.exec_command('nginx -t',timeout=20)
    validation=stdout.read().decode()+stderr.read().decode()
    result['nginxValid']=stdout.channel.recv_exit_status()==0
    (out/'nginx-final.log').write_text(validation,encoding='utf-8')
    _,stdout,stderr=ssh.exec_command("systemctl list-timers --all --no-pager | grep -Ei 'certbot|letsencrypt' || true",timeout=20)
    result['certificateRenewalTimers']=stdout.read().decode().strip()
finally:
    ssh.close()
    (out/'staged-release.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
