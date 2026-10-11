"""Local acceptance fixtures and evidence. Secrets never go to stdout."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from local_db import ROOT, configuration, mysql

OUT = Path(os.environ['QA_OUTPUT_DIR']).resolve()
OUT.mkdir(parents=True, exist_ok=True)

def save(name, data):
    (OUT/name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def demo_sessions():
    # Only an aggregate digest is retained; token hashes are never logged or persisted.
    rows=[line for line in mysql("SELECT s.member_id,s.token_hash FROM boost_session s JOIN boost_member m ON m.id=s.member_id WHERE m.username IN ('demo_buyer','demo_worker') ORDER BY s.member_id,s.token_hash;").splitlines() if line.strip()][1:]
    return {'count':len(rows),'sha256':hashlib.sha256('\n'.join(rows).encode()).hexdigest()}

def api(route, body=None, token=None):
    origin = os.environ.get('BOOST_API_URL', read(ROOT/'.runtime/dev-endpoints.json')['api'])
    if not re.fullmatch(r'http://(?:127\.0\.0\.1|localhost):\d+', origin):
        raise RuntimeError('Acceptance authentication only supports local services')
    headers = {'Content-Type':'application/json'}
    if token: headers['Authorization'] = 'Bearer '+token
    request = urllib.request.Request(origin+route, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response: return json.load(response)

def login(username, password):
    captcha = api('/captchaImage')
    private = read(ROOT/'.runtime/local-dev.json')
    redis_host = private.get('BOOST_REDIS_HOST', '127.0.0.1')
    if redis_host not in ('127.0.0.1', 'localhost'): raise RuntimeError('Local CAPTCHA fixture requires local Redis')
    env = os.environ.copy()
    if private.get('BOOST_REDIS_PASSWORD'): env['REDISCLI_AUTH'] = private['BOOST_REDIS_PASSWORD']
    code = ''
    if captcha.get('captchaEnabled', True):
        uuid = captcha['uuid']
        if not re.fullmatch(r'[a-zA-Z0-9-]+', uuid): raise RuntimeError('Invalid CAPTCHA identifier')
        redis = os.environ.get('QA_REDIS_CLI', r'C:\Program Files\Redis\redis-cli.exe')
        result = subprocess.run([redis, '-h', redis_host, '-p', '6379', '-n', str(private.get('BOOST_REDIS_DATABASE', 0)), '--raw', 'GET', 'captcha_codes:'+uuid], capture_output=True, env=env, check=True)
        code = json.loads(result.stdout.decode('utf-8').strip())
    result = api('/login', {'username':username, 'password':password, 'code':str(code), 'uuid':captcha.get('uuid','')})
    if result.get('code') != 200: raise RuntimeError('Local QA login failed: '+str(result.get('msg')))
    return result['token']

action = sys.argv[1]
if action == 'prepare-db':
    database = 'boost_verify_'+str(time.time_ns()//1000000)
    mysql('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    # Contains only an isolated database name; credentials remain in local-dev.json.
    save('isolated-db.json', {'database':database, 'retainedForInspection':True})
    print('Created isolated local test database')
elif action in ('login-admin', 'login-readonly'):
    readonly = action == 'login-readonly'
    destination = ROOT/('.runtime/readonly-qa.json' if readonly else '.runtime/admin-session.json')
    if not readonly and destination.exists(): raise RuntimeError('Existing administrator session file must be handled before this run')
    state = read(destination) if readonly else {}
    username = state['name'] if readonly else os.environ.get('QA_ADMIN_USERNAME', 'admin')
    password = state['password'] if readonly else os.environ.get('QA_ADMIN_PASSWORD', 'admin123')
    state['token'] = login(username, password)
    destination.write_text(json.dumps(state), encoding='utf-8')
    print('Local QA session prepared using its own CAPTCHA fixture')
elif action == 'cleanup-admin':
    destination = ROOT/'.runtime/admin-session.json'
    if destination.exists():
        state = read(destination)
        if api('/logout', {}, state['token']).get('code') != 200: raise RuntimeError('Administrator logout failed')
        try: verified = api('/getInfo', token=state['token']).get('code') == 401
        except urllib.error.HTTPError as error: verified = error.code == 401
        if not verified: raise RuntimeError('Revoked administrator session remained usable')
        destination.unlink()
    save('admin-cleanup.json', {'passed':True, 'sessionRevoked':True, 'sessionFileRemoved':True})
    print('Temporary administrator session revoked')
elif action == 'capture-qa':
    baseline=int([line for line in mysql('SELECT COALESCE(MAX(id),0) FROM boost_member;').splitlines() if line.strip()][1])
    save('qa-baseline.json', {'maxMemberId':baseline,'demoSessions':demo_sessions(),'scope':'New qa_ members plus preexisting demo-session aggregate fingerprint; read-only capture'})
    print('Captured member ID boundary for this acceptance run')
elif action == 'verify-qa':
    captured=read(OUT/'qa-baseline.json')
    baseline=captured['maxMemberId']
    rows=mysql("SELECT m.id,m.username,(SELECT COUNT(*) FROM boost_session s WHERE s.member_id=m.id) AS sessions,(SELECT COUNT(*) FROM boost_listing l WHERE l.owner_id=m.id AND l.status IN ('ACTIVE','PENDING')) AS visible_or_pending_publications,(SELECT COUNT(*) FROM boost_order o WHERE (o.buyer_id=m.id OR o.worker_id=m.id) AND o.status='WAIT_PAY') AS unpaid_orders FROM boost_member m WHERE m.id>"+str(int(baseline))+" AND LEFT(m.username,3)='qa_' ORDER BY m.id;")
    records=[]
    for line in [line for line in rows.splitlines() if line.strip()][1:]:
        if not line.strip():continue
        member,username,sessions,publications,orders=line.split('\t')
        records.append({'memberId':int(member),'username':username,'sessions':int(sessions),'visibleOrPendingPublications':int(publications),'unpaidOrders':int(orders)})
    demo_after=demo_sessions()
    demos_unchanged=captured['demoSessions']==demo_after
    passed=demos_unchanged and all(not any(r[k] for k in ('sessions','visibleOrPendingPublications','unpaidOrders')) for r in records)
    save('qa-cleanup-verification.json', {'passed':passed,'baselineMemberId':baseline,'members':records,'demoSessionsUnchanged':demos_unchanged,'demoSessionsBefore':captured['demoSessions'],'demoSessionsAfter':demo_after,'scope':'Read-only exact new qa_ member session/publication/unpaid-order verification and unchanged preexisting demo sessions. Retains offline history; does not infer cleanup of prior reused suites.'})
    print(json.dumps({'passed':passed,'membersChecked':len(records)}))
    if not passed:sys.exit(1)
elif action == 'collect-tests':
    from surefire_integrity import collect
    report = collect(ROOT, OUT)
    print(json.dumps({'passed':report['passed'], **report['totals'], 'expectedSuites':report['completeness']['expectedSuites'], 'expectedTests':report['completeness']['expectedTests'], 'integrityErrors':len(report['completeness']['errors'])}))
    if not report['passed']: sys.exit(1)
elif action == 'verify-resume':
    previous=Path(os.environ['QA_RESUME_FROM']).resolve()
    if not previous.is_relative_to(ROOT/'test-results') or previous==OUT:raise RuntimeError('Resume source must be another project evidence directory')
    # This helper prepares fresh owned runtime data only; it does not participate
    # in compiled code, backend tests, migration sources or any reusable UI suite.
    controls={'scripts/acceptance.cjs','scripts/acceptance-support.py','scripts/验收.ps1','scripts/prepare-ui-acceptance.py','scripts/prepare-wallet-readonly-fixture.py'}
    def relevant(record):
        p=record['path']
        return 'logs' not in Path(p).parts and p not in controls and p!='scripts/run-nonpayment-checks.cjs' and not (p.startswith('scripts/e2e-') and p.endswith('.cjs'))
    def index(name,folder):return {x['path']:x['sha256'] for x in read(folder/name)['files'] if relevant(x)}
    prior_before=index('source-before.json',previous)
    prior_after=index('source-final.json',previous)
    current=index('source-before.json',OUT)
    if prior_before!=prior_after or prior_after!=current:raise RuntimeError('Business, tests, dependency or deployment sources changed; earlier evidence cannot be reused')
    package=read(previous/'release-package.json')
    archive=Path(package['archive'])
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=package['archiveSha256']:raise RuntimeError('Earlier archive changed')
    import zipfile
    prefixes={'miniprogram/':'代练/dist/build/mp-weixin/','h5/':'代练/dist/build/h5/','admin/':'RuoYi-Vue3/dist/','deploy/':'deploy/'}
    checked=0
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if name=='RELEASE.json':continue
            local=ROOT/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar' if name=='ruoyi-admin.jar' else None
            for prefix,destination in prefixes.items():
                if name.startswith(prefix):local=ROOT/(destination+name[len(prefix):]);break
            if local is None or not local.is_file() or hashlib.sha256(local.read_bytes()).digest()!=hashlib.sha256(z.read(name)).digest():raise RuntimeError('A verified build artifact changed: '+name)
            checked+=1
    def all_index(name,folder):return {x['path']:x['sha256'] for x in read(folder/name)['files']}
    old=all_index('source-final.json',previous);now=all_index('source-before.json',OUT)
    changed=[p for p in sorted(old.keys()|now.keys()) if old.get(p)!=now.get(p)]
    save('resume-validation.json',{'passed':True,'previous':str(previous),'sourceComparison':'All application sources, Java tests, dependency locks, build and deployment sources identical; exact named QA controls and fresh owned UI fixture preparation excluded; browser suites always execute on the new fixture','allowedControlFiles':sorted(controls),'changedFiles':changed,'browserRunner':'Every original and supplementary browser suite executes fresh; no old UI results copied','matchingBuildFiles':checked,'archiveSha256':package['archiveSha256']})
    print('Verified earlier source snapshots and every packaged build file before evidence reuse')
elif action == 'source':
    files=[]
    for directory in ('代练','RuoYi-Vue3','RuoYi-Vue','scripts','deploy','patches'):
        for current, directories, names in os.walk(ROOT/directory):
            directories[:] = [d for d in directories if d not in ('node_modules','.git','target','dist','.runtime','__pycache__','logs')]
            for name in names:
                p=Path(current)/name
                if p.suffix.lower() in ('.jar','.zip','.class','.pyc','.log'): continue
                files.append({'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
    files.sort(key=lambda x:x['path'])
    digest=hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()
    save('source-final.json', {'sha256':digest,'files':files})
    print('Source SHA-256: '+digest)
else:
    raise RuntimeError('Unknown acceptance support action')
