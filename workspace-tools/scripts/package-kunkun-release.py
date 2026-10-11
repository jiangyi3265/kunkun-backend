"""Package verified release assets; refuse any supplied deployment secret in the bundle."""
import hashlib
import json
from pathlib import Path
import sys, os
import zipfile, io, datetime

root=Path(__file__).resolve().parent.parent
private=json.load(sys.stdin)
mini=root/'代练/dist/build/mp-weixin'
release=json.loads((mini/'RELEASE.json').read_text(encoding='utf-8'))
project=json.loads((mini/'project.config.json').read_text(encoding='utf-8'))
assert release['appid']==project['appid']=='wxd31a958f0fc66a19'
assert project['projectname']=='困困' and project['setting']['urlCheck'] is True
assert release['apiOrigin']=='https://ceshisss.oksja.cn'
version='kunkun-'+datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z').replace('-','').replace(':','').replace('.','')
files=[]
for directory,prefix in [('代练/dist/build/mp-weixin','miniprogram'),('代练/dist/build/h5','h5'),('RuoYi-Vue3/dist','admin'),('deploy','deploy')]:
    folder=root/directory
    assert folder.is_dir()
    for file in folder.rglob('*'):
        if file.is_file() and '__pycache__' not in file.parts and file.suffix not in ('.env','.pem','.key','.pyc'):
            files.append((file,prefix+'/'+file.relative_to(folder).as_posix()))
jar=Path(os.environ['BOOST_RELEASE_JAR']) if os.environ.get('BOOST_RELEASE_JAR') else root/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'
assert jar.is_file()
files.append((jar,'ruoyi-admin.jar'))
for file in (root/'RuoYi-Vue/sql').glob('boost*-menu.sql'):
    files.append((file,'menus/'+file.name))
secret_bytes=[s.encode() for s in private['secrets'] if s]
for file,name in files:
    data=file.read_bytes()
    if any(secret in data for secret in secret_bytes):
        raise RuntimeError('Deployment secret found in release file: '+name)
    if name=='ruoyi-admin.jar':
        with zipfile.ZipFile(io.BytesIO(data)) as nested:
            for entry in nested.namelist():
                if entry.startswith('BOOT-INF/classes/') and not entry.endswith('/'):
                    if any(secret in nested.read(entry) for secret in secret_bytes):
                        raise RuntimeError('Deployment secret found in unpacked application JAR: '+entry)
metadata={'name':'困困','version':version,**release,'adminOrigin':'https://ceshijjht.oksja.cn','backendSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'secretScanPassed':True,'businessServiceActivated':False,'fileCount':len(files)}
target=root/'.runtime/releases'/version
target.mkdir(parents=True,exist_ok=True)
archive=target/'release.zip'
assert not archive.exists(), 'Immutable release already exists; rebuild with a new version'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for file,name in files:z.write(file,name)
    z.writestr('RELEASE.json',json.dumps(metadata,ensure_ascii=False,indent=2))
metadata.update(archive=str(archive),archiveBytes=archive.stat().st_size,archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest())
(Path(os.environ.get('QA_OUTPUT_DIR',root/'test-results/deployment-preflight-20261002'))/'release-package.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(metadata,ensure_ascii=False,indent=2))
