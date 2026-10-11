"""Stage an immutable internal-test candidate; verify every file, including reused bytes."""
import hashlib,json,os,re,shutil,stat,sys,zipfile
from pathlib import Path
BASE=Path('/srv/kunkun/test')
assert BASE.resolve()==BASE
cfg=json.loads((BASE/'config/private.json').read_text())
state=json.loads((BASE/'deployment-state.json').read_text())
assert cfg['owner']==state['owner']=='kunkun-internal-test-20261006'
delta=BASE/'config/candidate-delta.zip'
assert delta.is_file() and not delta.is_symlink()
assert hashlib.sha256(delta.read_bytes()).hexdigest()==sys.argv[1]
with zipfile.ZipFile(delta) as z:
    assert z.testzip() is None
    manifest=json.loads(z.read('delta-manifest.json'))
    for version in (manifest['baseVersion'],manifest['version']):
        assert re.fullmatch(r'kunkun-\d{8}T\d{9}Z',version)
    assert cfg['version']==manifest['baseVersion'] and manifest['version']!=cfg['version']
    original=BASE/'releases'/cfg['version'];candidate=BASE/'releases'/manifest['version']
    assert original.is_dir() and not original.is_symlink()
    assert not candidate.is_symlink()
    assert hashlib.sha256((original/'ruoyi-admin.jar').read_bytes()).hexdigest()==cfg['jarSha256']==manifest['jarSha256']
    expected=manifest['files'];assert len(expected)>500 and 'ruoyi-admin.jar' in expected
    assert set(z.namelist())==set(manifest['changed'])|{'delta-manifest.json'}
    candidate.mkdir(exist_ok=True)
    reused=0;uploaded=0
    for name,digest in expected.items():
        relative=Path(name)
        assert not relative.is_absolute() and '..' not in relative.parts
        assert relative.parts[0] in ('h5','admin','miniprogram','deploy','ruoyi-admin.jar','RELEASE.json')
        assert re.fullmatch(r'[a-f0-9]{64}',digest)
        target=candidate/relative
        assert target.resolve().is_relative_to(candidate) and not target.is_symlink()
        for ancestor in target.parents:
            if ancestor==BASE:break
            assert not ancestor.is_symlink()
        target.parent.mkdir(parents=True,exist_ok=True)
        if name in manifest['changed']:
            payload=z.read(name);assert hashlib.sha256(payload).hexdigest()==digest
            if target.exists():assert target.is_file() and target.read_bytes()==payload
            else:target.write_bytes(payload)
            uploaded+=1
        else:
            source=original/relative
            assert source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(original)
            assert hashlib.sha256(source.read_bytes()).hexdigest()==digest
            if target.exists():assert target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==digest
            else:shutil.copyfile(source,target)
            reused+=1
        assert hashlib.sha256(target.read_bytes()).hexdigest()==digest
        target.chmod(0o644 if relative.parts[0] in ('h5','admin') else 0o640)
    actual={p.relative_to(candidate).as_posix() for p in candidate.rglob('*') if p.is_file()}
    assert actual==set(expected),'Candidate has unexpected or missing files'
    for directory in [candidate]+[p for p in candidate.rglob('*') if p.is_dir()]:directory.chmod(0o755)
    import pwd
    group=pwd.getpwnam('kunkun-test').pw_gid
    os.chown(candidate/'ruoyi-admin.jar',0,group)
    print(json.dumps({'passed':True,'version':manifest['version'],'baseVersion':manifest['baseVersion'],'filesVerified':len(expected),'filesCopiedFromVerifiedBase':reused,'filesUploaded':uploaded,'jarSha256':manifest['jarSha256'],'deltaSha256':sys.argv[1],'method':'Verified per-entry delta; full local archive was not transferred or hashed on the server','pointerChanged':False}))
