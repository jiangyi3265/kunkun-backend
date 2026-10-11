"""Verify all acceptance references, selected hashes and known private values without printing secrets."""
import csv,hashlib,json,re
from datetime import datetime,timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote,urlparse
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'test-results/rewards-20261008'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
manifest=read(OUT/'最终版本清单.json');rows=read(OUT/'完整验收表.json');report=read(OUT/'验收报告.json');checks=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
assert manifest['sourceId']==report['sourceId']
assert manifest['sourceId']==hashlib.sha256(json.dumps(manifest['sources'],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
for s in manifest['sources']:assert sha(ROOT/s['file'])==s['sha256'],s['file']
checks.append({'name':'selected-source-hashes','count':len(manifest['sources']),'status':'已实测通过'})
for b in manifest['builds']:
 p=ROOT/b['path']
 if p.is_file():assert sha(p)==b['sha256'];continue
 current=[{'file':f.relative_to(p).as_posix(),'sha256':sha(f),'bytes':f.stat().st_size} for f in sorted(p.rglob('*')) if f.is_file()]
 assert current==b['inventory'];assert hashlib.sha256(json.dumps(current,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()==b['sha256']
checks.append({'name':'built-artifact-hashes','count':len(manifest['builds']),'status':'已实测通过'})
allowed={'已实测通过','失败','未测试','环境阻塞','不适用'};refs=[]
for row in rows:
 assert row['当前状态'] in allowed
 if row['当前状态']=='不适用':assert row['边界或说明']
 if row['当前状态']=='已实测通过':assert row['证据位置'] and row['验证方式']
 refs.extend(row['证据位置'].split('；'))
for issue in read(OUT/'问题记录.json'):refs.extend(issue['验证证据'].split('；'))
class Links(HTMLParser):
 def handle_starttag(self,tag,attrs):
  for key,value in attrs:
   if key in ('href','src') and value and not urlparse(value).scheme:refs.append(unquote(value))
Links().feed((OUT/'验收报告.html').read_text(encoding='utf-8'))
# Load only the already-established credential-loading prefix; no scanner or side effects after it.
namespace={};prefix=(ROOT/'scripts/acceptance-secrets.py').read_text(encoding='utf-8').split('findings=[];files=0')[0];exec(compile(prefix,'acceptance-secrets-prefix','exec'),namespace)
secrets=namespace['secrets'];findings=[];files=[]
for p in OUT.rglob('*'):
 if p.is_file() and p.suffix.lower() not in ('.png','.jpg','.jpeg','.zip','.pyc'):
  data=p.read_bytes();files.append(p)
  for name,value in secrets.items():
   if len(value)>=8 and value in data:findings.append({'file':p.relative_to(OUT).as_posix(),'credentialName':name})
assert not findings,'Known credentials detected; inspect protected report without exposing values'
secret_report={'time':datetime.now(timezone.utc).isoformat(),'passed':not findings,'filesScanned':len(files),'findings':findings,'scope':'All textual evidence in rewards-20261008 for previously known private values >=8 bytes; excludes raster images and private runtime. Not general vulnerability detection, not OCR of screenshots.'}
(OUT/'证据敏感信息检查.json').write_text(json.dumps(secret_report,ensure_ascii=False,indent=2),encoding='utf-8')
checks.append({'name':'known-private-evidence-scan','count':len(files),'status':'已实测通过'})
for ref in refs:
 if not ref:continue
 assert (OUT/ref).resolve().is_relative_to(ROOT) and (OUT/ref).exists(),'Missing report evidence '+ref
checks.append({'name':'report-evidence-references','count':len(set(refs)-{''}),'status':'已实测通过'})
assert report['metrics']['java']=={'tests':267,'failures':0,'errors':0,'skipped':0}
assert read(OUT/'after-tab-image/rewards-ui-races.json')['counts']=={'total':4,'passed':4,'failed':0}
assert read(OUT/'after-tab-image/rewards-real-browser.json')['exitCode']==0
checks.append({'name':'report-status-and-counts','status':'已实测通过'})
result={'time':datetime.now(timezone.utc).isoformat(),'sourceId':manifest['sourceId'],'status':'已实测通过','exitCode':0,'checks':checks,'scope':'Self-check of acceptance package links, actual local evidence and version inventory; independent review is a separate file.'}
(OUT/'报告复核.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result,ensure_ascii=False))
