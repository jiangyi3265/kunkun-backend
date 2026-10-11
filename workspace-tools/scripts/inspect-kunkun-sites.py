"""Inspect only the assigned sites before preparing HTTPS changes."""
import json
from pathlib import Path
import paramiko
import sys

root=Path(__file__).resolve().parent.parent
c=json.load(sys.stdin)
ssh=paramiko.SSHClient()
ssh.load_host_keys(str(root/'.runtime/server-known-hosts'))
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
    ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,auth_timeout=15,allow_agent=False,look_for_keys=False)
    sftp=ssh.open_sftp()
    data={}
    backup=root/'.runtime/kunkun-server-original'
    backup.mkdir(parents=True,exist_ok=True)
    for domain in ['ceshisss.oksja.cn','ceshijjht.oksja.cn']:
        file='/www/server/panel/vhost/nginx/'+domain+'.conf'
        text=sftp.open(file).read().decode()
        (backup/(domain+'.conf')).write_text(text,encoding='utf-8')
        data[domain]={'nginxConfig':text}
        file='/www/server/panel/vhost/nginx/well-known/'+domain+'.conf'
        try:data[domain]['acmeConfig']=sftp.open(file).read().decode()
        except FileNotFoundError:data[domain]['acmeConfig']=''
    account_path='/etc/letsencrypt/accounts/acme-v02.api.letsencrypt.org/directory'
    try:data['acmeAccounts']=sftp.listdir(account_path)
    except FileNotFoundError:data['acmeAccounts']=[]
    _,stdout,stderr=ssh.exec_command('nginx -t',timeout=20)
    data['nginxTest']={'stdout':stdout.read().decode(),'stderr':stderr.read().decode(),'exitCode':stdout.channel.recv_exit_status()}
    (root/'test-results/deployment-preflight-20261002/assigned-sites.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(data,ensure_ascii=False,indent=2))
finally:ssh.close()
