"""Read-only checks of the assigned domains, certificate renewal and staged package."""
import json, os
from pathlib import Path
import re
import shlex
import sys
import paramiko

root=Path(__file__).resolve().parent.parent
package=json.loads((root/'test-results/deployment-preflight-20261002/release-package.json').read_text(encoding='utf-8'))
assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',package['version'])
remote_path='/srv/kunkun/releases/'+package['version']+'/release.zip'
c=json.load(sys.stdin)
ssh=paramiko.SSHClient()
ssh.load_host_keys(str(root/'.runtime/server-known-hosts'))
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
def run(command):
    _,stdout,stderr=ssh.exec_command(command,timeout=40)
    output=stdout.read().decode('utf-8','replace')
    error=stderr.read().decode('utf-8','replace')
    return stdout.channel.recv_exit_status(),output,error
result={'readOnly':True,'paymentIntegrationAttempted':False}
try:
    ssh.connect(c['host'],username=c['user'],password=c['password'],timeout=15,auth_timeout=15,allow_agent=False,look_for_keys=False)
    code,output,error=run('nginx -t')
    result['nginxValid']=code==0
    code,output,error=run('sha256sum '+shlex.quote(remote_path))
    result['packageHashMatches']=code==0 and output.split()[0]==package['archiveSha256']
    code,output,error=run("systemctl list-timers --all --no-pager | grep -E 'certbot-renew.timer' || true")
    result['renewalTimerScheduled']='certbot-renew.timer' in output
    script="""
import json,urllib.request,urllib.error
result=[]
for domain in ['ceshisss.oksja.cn','ceshijjht.oksja.cn']:
    r=urllib.request.urlopen('https://'+domain+'/',timeout=15)
    result.append({'domain':domain,'httpsStatus':r.status,'certificateVerified':True})
try:
    r=urllib.request.urlopen('https://ceshisss.oksja.cn/boost/config',timeout=15)
    data=json.load(r)
    api={'available':data.get('code')==200,'httpStatus':r.status}
except urllib.error.HTTPError as exc:api={'available':False,'httpStatus':exc.code}
except (ValueError,TypeError):api={'available':False,'httpStatus':200}
print(json.dumps({'domains':result,'businessApi':api}))
"""
    code,output,error=run('python3 -c '+shlex.quote(script))
    if code:raise RuntimeError('HTTPS verification failed: '+error.splitlines()[-1])
    result.update(json.loads(output))
    result['passed']=result['nginxValid'] and result['packageHashMatches'] and result['renewalTimerScheduled'] and all(d['httpsStatus']==200 for d in result['domains'])
finally:
    ssh.close()
    (Path(os.environ.get('QA_OUTPUT_DIR',root/'test-results/nonpayment-20261002'))/'server-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if not result.get('passed'):sys.exit(1)
