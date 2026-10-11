"""Back up and enable HTTPS only for the two explicitly assigned empty sites."""
from datetime import datetime, timezone
import difflib
import hashlib
import json
from pathlib import Path
import shlex
import sys
import paramiko

root=Path(__file__).resolve().parent.parent
out=root/'test-results/deployment-preflight-20261002'
inspection=json.loads((out/'assigned-sites.json').read_text(encoding='utf-8'))
domains=['ceshisss.oksja.cn','ceshijjht.oksja.cn']
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup='/srv/kunkun/backups/'+stamp+'/nginx'
originals={d:inspection[d]['nginxConfig'] for d in domains}
updated={}
for domain,text in originals.items():
    assert 'listen 443' not in text and text.count('#SSL-END')==1
    assert 'server_name '+domain+';' in text
    text=text.replace('    listen [::]:80;', '    listen [::]:80;\n    listen 443 ssl;\n    listen [::]:443 ssl;',1)
    text=text.replace('    #SSL-END', '    ssl_certificate /etc/letsencrypt/live/kunkun/fullchain.pem;\n    ssl_certificate_key /etc/letsencrypt/live/kunkun/privkey.pem;\n    ssl_protocols TLSv1.2 TLSv1.3;\n    ssl_session_timeout 10m;\n    #SSL-END',1)
    updated[domain]=text
(out/'https-config.diff').write_text(''.join(''.join(difflib.unified_diff(originals[d].splitlines(True),updated[d].splitlines(True),fromfile='before/'+d+'.conf',tofile='after/'+d+'.conf')) for d in domains),encoding='utf-8')
c=json.load(sys.stdin)
ssh=paramiko.SSHClient()
ssh.load_host_keys(str(root/'.runtime/server-known-hosts'))
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
result={'domains':domains,'backupDirectory':backup,'configured':False}
changed=[]
def run(command,timeout=30):
    _,stdout,stderr=ssh.exec_command(command,timeout=timeout)
    data=stdout.read().decode('utf-8','replace')+stderr.read().decode('utf-8','replace')
    code=stdout.channel.recv_exit_status()
    return code,data
def write_config(domain,text):
    target='/www/server/panel/vhost/nginx/'+domain+'.conf'
    temporary=target+'.kunkun-staged'
    with sftp.open(temporary,'w') as f:f.write(text)
    sftp.chmod(temporary,0o644)
    sftp.posix_rename(temporary,target)
try:
    ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,auth_timeout=15,allow_agent=False,look_for_keys=False)
    sftp=ssh.open_sftp()
    for domain in domains:
        actual=sftp.open('/www/server/panel/vhost/nginx/'+domain+'.conf').read().decode()
        if actual!=originals[domain]:raise RuntimeError('Site configuration changed since inspection: '+domain)
    code,log=run('nginx -t')
    if code:raise RuntimeError('Existing Nginx configuration does not pass validation')
    code,_=run('umask 077; mkdir -p '+shlex.quote(backup))
    if code:raise RuntimeError('Could not create site backup directory')
    for domain in domains:
        with sftp.open(backup+'/'+domain+'.conf','w') as f:f.write(originals[domain])
        sftp.chmod(backup+'/'+domain+'.conf',0o600)
    accounts=inspection['acmeAccounts']
    if len(accounts)!=1:raise RuntimeError('A unique existing production ACME account is required')
    args=['certbot','certonly','--non-interactive','--webroot','--cert-name','kunkun','--keep-until-expiring','--account',accounts[0],
          '--deploy-hook','/usr/bin/nginx -t && /usr/bin/nginx -s reload']
    for domain in domains:args+=['-w','/www/wwwroot/'+domain,'-d',domain]
    code,log=run(' '.join(map(shlex.quote,args)),180)
    (out/'certificate-issuance.log').write_text(log,encoding='utf-8')
    if code:raise RuntimeError('Certificate issuance failed; see certificate-issuance.log')
    for domain in domains:
        actual=sftp.open('/www/server/panel/vhost/nginx/'+domain+'.conf').read().decode()
        if actual!=originals[domain]:raise RuntimeError('Site configuration changed during certificate issuance: '+domain)
    for domain in domains:
        write_config(domain,updated[domain]);changed.append(domain)
    code,log=run('nginx -t')
    (out/'nginx-https-test.log').write_text(log,encoding='utf-8')
    if code:raise RuntimeError('New HTTPS configuration failed validation')
    code,log=run('nginx -s reload')
    if code:raise RuntimeError('Nginx reload failed')
    result['configured']=True
    result['configSha256']={d:hashlib.sha256(updated[d].encode()).hexdigest() for d in domains}
    code,cert=run("openssl x509 -in /etc/letsencrypt/live/kunkun/fullchain.pem -noout -subject -issuer -dates -ext subjectAltName")
    result['certificate']=cert
except Exception as exc:
    result['error']=type(exc).__name__+': '+str(exc).split('\n')[0]
    if changed:
        for domain in changed:write_config(domain,originals[domain])
        result['rollback']=run('nginx -t && nginx -s reload')[0]==0
finally:
    ssh.close()
    (out/'https-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if not result['configured']:sys.exit(1)
