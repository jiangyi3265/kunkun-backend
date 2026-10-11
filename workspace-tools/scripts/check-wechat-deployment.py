"""Validate WeChat credentials on the intended server without recording tokens or secrets."""
import json
from pathlib import Path
import shlex
import sys
import paramiko

root = Path(__file__).resolve().parent.parent
credentials = json.load(sys.stdin)
client = paramiko.SSHClient()
client.load_host_keys(str(root / '.runtime/server-known-hosts'))
client.set_missing_host_key_policy(paramiko.RejectPolicy())
remote = r'''
import json,sys,urllib.request,urllib.error
c=json.load(sys.stdin)
payload=json.dumps({'grant_type':'client_credential','appid':c['appid'],'secret':c['secret'],'force_refresh':False}).encode()
request=urllib.request.Request('https://api.weixin.qq.com/cgi-bin/stable_token',data=payload,headers={'Content-Type':'application/json'},method='POST')
try:
    response=json.load(urllib.request.urlopen(request,timeout=25))
    result={'appId':c['appid'],'verified':bool(response.get('access_token')),'errcode':response.get('errcode',0),'errmsg':response.get('errmsg',''),'expiresIn':response.get('expires_in')}
except Exception as exc:
    result={'appId':c['appid'],'verified':False,'error':type(exc).__name__+': '+str(exc).split('\n')[0]}
print(json.dumps(result,ensure_ascii=False))
'''
try:
    client.connect(credentials['host'],username=credentials['user'],password=credentials['password'],timeout=15,auth_timeout=15,allow_agent=False,look_for_keys=False)
    stdin,stdout,stderr=client.exec_command('python3 -c '+shlex.quote(remote),timeout=40)
    stdin.write(json.dumps({'appid':credentials['appid'],'secret':credentials['secret']}))
    stdin.channel.shutdown_write()
    output=stdout.read().decode('utf-8')
    if stdout.channel.recv_exit_status()!=0:
        raise RuntimeError('Remote credential check did not complete')
    result=json.loads(output)
    target=root/'test-results/deployment-preflight-20261002/wechat-credentials.json'
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
finally:
    client.close()
