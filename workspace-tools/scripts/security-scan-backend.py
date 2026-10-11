"""Scan packaged third-party Maven dependencies against the public OSV advisory database."""
from pathlib import Path
import io,json,zipfile,urllib.request,concurrent.futures,os,sys
root=Path(__file__).resolve().parent.parent
packages={}
with zipfile.ZipFile(root/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar') as jar:
 for entry in jar.namelist():
  if not entry.startswith('BOOT-INF/lib/') or not entry.endswith('.jar'):continue
  with zipfile.ZipFile(io.BytesIO(jar.read(entry))) as lib:
   found=False
   for prop in lib.namelist():
    if prop.startswith('META-INF/maven/') and prop.endswith('/pom.properties'):
     values=dict(line.split('=',1) for line in lib.read(prop).decode('utf-8','replace').splitlines() if '=' in line and not line.startswith('#'))
     if all(k in values for k in ('groupId','artifactId','version')):
      name=values['groupId']+':'+values['artifactId'];packages[name]=values['version'];found=True
   if not found:
    stem=Path(entry).stem
    # Spring and several Gradle-built libraries omit Maven metadata; resolve exact coordinates from the local Maven repository.
    for pom in (Path.home()/'.m2/repository').rglob(stem+'.pom'):
     parts=pom.relative_to(Path.home()/'.m2/repository').parts
     packages['.'.join(parts[:-3])+':'+parts[-3]]=parts[-2];found=True;break
    if not found and stem.startswith('spring-boot-jarmode-tools-'):
     packages['org.springframework.boot:spring-boot-jarmode-tools']=stem.removeprefix('spring-boot-jarmode-tools-');found=True
    if not found:raise RuntimeError('Unresolved packaged library: '+entry)
queries=[{'package':{'ecosystem':'Maven','name':k},'version':v} for k,v in sorted(packages.items()) if not k.startswith('com.ruoyi:')]
def post(url,data):
 req=urllib.request.Request(url,data=json.dumps(data).encode(),headers={'Content-Type':'application/json','User-Agent':'boost-release-audit'})
 with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)
result=[]
for n in range(0,len(queries),50):
 batch=queries[n:n+50];res=post('https://api.osv.dev/v1/querybatch',{'queries':batch})
 if len(res.get('results',[]))!=len(batch):raise RuntimeError('Incomplete OSV batch response')
 result.extend({**q,'advisories':r.get('vulns',[])} for q,r in zip(batch,res['results']))
if len(result)!=len(queries):raise RuntimeError('Incomplete dependency coverage')
out=Path(os.environ.get('QA_OUTPUT_DIR',root/'test-results/remediation-20261002'))/'backend-osv.json';out.write_text(json.dumps(result,indent=2),encoding='utf-8')
findings=[r for r in result if r['advisories']]
print(json.dumps({'packages':len(result),'affected':findings},ensure_ascii=False,indent=2))

sys.exit(1 if findings else 0)
