"""Scan deliverable source and built artifacts for known private credentials; never print values."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from local_db import ROOT

private=json.loads((ROOT/'.runtime/local-dev.json').read_text(encoding='utf-8'))
secrets={k:str(v).encode() for k,v in private.items() if any(part in k for part in ('PASSWORD','SECRET','SIGNING_KEY')) and v}
server_private=ROOT/'.runtime/deploy-test-20261006/server.private.json'
if server_private.exists():
    secrets.update({'server:'+k:str(v).encode() for k,v in json.loads(server_private.read_text(encoding='utf-8')).items() if isinstance(v,str) and v and any(part in k.lower() for part in ('password','secret','key'))})
encrypted=ROOT/'.runtime/deployment-private.clixml'
if encrypted.exists():
    command="$p=Import-Clixml -LiteralPath '.runtime/deployment-private.clixml'; @{AppSecret=[System.Net.NetworkCredential]::new('',$p.appSecret).Password;SshPassword=[System.Net.NetworkCredential]::new('',$p.sshPassword).Password}|ConvertTo-Json -Compress"
    result=subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',command],cwd=ROOT,capture_output=True,check=True)
    secrets.update({k:str(v).encode() for k,v in json.loads(result.stdout.decode('utf-8-sig')).items() if v})
findings=[];files=0
def scan(label,data):
    global files
    files+=1
    for name,value in secrets.items():
        if len(value)>=8 and value in data:findings.append({'file':label,'credentialName':name})
for directory in ('代练','RuoYi-Vue3','RuoYi-Vue','scripts','deploy','patches'):
    for current,dirs,names in os.walk(ROOT/directory):
        dirs[:]=[d for d in dirs if d not in ('node_modules','.git','target','__pycache__')]
        for name in names:
            p=Path(current)/name
            if p.suffix.lower() in ('.log','.pyc','.zip','.jar'):continue
            scan(p.relative_to(ROOT).as_posix(),p.read_bytes())
jar=ROOT/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'
with zipfile.ZipFile(jar) as z:
    for entry in z.namelist():
        if entry.startswith('BOOT-INF/classes/') and not entry.endswith('/'):scan('ruoyi-admin.jar!'+entry,z.read(entry))
if '--evidence' in sys.argv:
    evidence=Path(sys.argv[sys.argv.index('--evidence')+1]).resolve()
    if not evidence.is_relative_to(ROOT/'test-results'):
        raise ValueError('Evidence scan must stay in workspace test-results')
    for p in evidence.rglob('*'):
        if p.is_file() and p.suffix.lower() in ('.json','.log','.txt','.html','.csv','.vue','.cjs','.py'):
            scan(p.relative_to(ROOT).as_posix(),p.read_bytes())
report={'passed':not findings,'filesScanned':files,'credentialNames':list(secrets),'findings':findings,'scope':'Known private values >=8 bytes in source, H5/mini/admin builds and application JAR resources/classes; excludes private runtime and third-party dependencies'}
(Path(os.environ['QA_OUTPUT_DIR'])/'secret-scan.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
if not findings and '--package' in sys.argv:
    result=subprocess.run([sys.executable,'-X','utf8',str(ROOT/'scripts/package-kunkun-release.py')],input=json.dumps({'secrets':[v.decode() for v in secrets.values() if len(v)>=8]}).encode(),capture_output=True)
    sys.stdout.buffer.write(result.stdout)
    if result.returncode:
        sys.stderr.buffer.write(result.stderr)
        sys.exit(result.returncode)
sys.exit(1 if findings else 0)
