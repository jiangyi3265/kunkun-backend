"""Check built application artifacts against the specified immutable release archive."""
import hashlib,json,os,sys,zipfile
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
out=Path(os.environ['PLAYER_ACCEPTANCE_OUT']).resolve();out.mkdir(parents=True,exist_ok=True)
metadata_arg=sys.argv[1] if len(sys.argv)>1 else os.environ.get('PLAYER_RELEASE_METADATA')
if not metadata_arg:
    raise SystemExit('Pass the release-package.json to verify, or set PLAYER_RELEASE_METADATA; an old release must not be silently selected.')
metadata=Path(metadata_arg).resolve()
m=json.loads(metadata.read_text('utf-8'));archive=Path(m['archive'])
digest=lambda b:hashlib.sha256(b).hexdigest()
assert digest(archive.read_bytes())==m['archiveSha256'],'Immutable release archive changed'
checks=[]
archive_only='--archive-only' in sys.argv
with zipfile.ZipFile(archive) as z:
    roots={'miniprogram/':ROOT/'代练/dist/build/mp-weixin','h5/':ROOT/'代练/dist/build/h5','admin/':ROOT/'RuoYi-Vue3/dist'}
    for prefix,directory in roots.items():
        files=[name for name in z.namelist() if name.startswith(prefix) and not name.endswith('/')]
        assert files,'Missing packaged application '+prefix
        if archive_only:
            checks.append({'application':prefix,'files':len(files),'status':'已实测通过','scope':'SHA-256 verified immutable archive; current local build is a separate candidate'})
            continue
        expected={name[len(prefix):] for name in files}
        actual={p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file()}
        assert actual==expected,'Built file set differs: '+prefix+' extra='+str(sorted(actual-expected))+' missing='+str(sorted(expected-actual))
        for name in files:
            p=directory/Path(name[len(prefix):]);assert p.is_file(),str(p)+' missing'
            assert digest(p.read_bytes())==digest(z.read(name)),'Built artifact differs from released artifact: '+name
        checks.append({'application':prefix,'files':len(files),'status':'已实测通过'})
jar=ROOT/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'
with zipfile.ZipFile(archive) as z:
    assert digest(z.read('ruoyi-admin.jar'))==m['backendSha256'],'Packaged JAR differs from release metadata'
if not archive_only:
    assert digest(jar.read_bytes())==m['backendSha256'],'JAR differs from deployed release'
checks.append({'application':'backend','sha256':m['backendSha256'],'status':'已实测通过'})
report={'at':datetime.now(timezone.utc).isoformat(),'status':'已实测通过','exitCode':0,'version':m['version'],'checks':checks,'scope':'Immutable deployed archive integrity; local candidate not compared' if archive_only else 'Byte identity of current built artifacts with specified immutable release archive; not source compilation or business test'}
(out/'artifact-identity.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False))
