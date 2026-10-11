"""Read-only regression against captured owned proxy and both supported revisions."""
import ast,json,hashlib,datetime
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'deploy/upgrade-player-test.py').read_text('utf-8')
tree=ast.parse(source);function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='nginx_candidate')
scope={'OWNER':'kunkun-internal-test-20261006'}
exec(compile(ast.Module(body=[function],type_ignores=[]),'<actual-upgrade-function>','exec'),scope)
f=scope['nginx_candidate'];before=json.loads((root/'test-results/market-content-20261009/server-before.json').read_text('utf-8'))
checks=[]
for domain,record in before['nginx'].items():
    text=record['config'];current=f(text,domain);assert f(current,domain)==current
    checks.append({'name':domain+' current proxy and repeat application','status':'已实测通过'})
    if domain=='ceshisss.oksja.cn':
        original=text.replace('boost-media(?:/|$)|boost-reward-media(?:/|$)|health','boost-media(?:/|$)|health')
        assert f(original,domain)==current
        try:f(original.replace('boost-media(?:/|$)|health','UNKNOWN_ROUTE'),domain)
        except AssertionError:pass
        else:raise AssertionError('Unknown route must fail before maintenance')
        checks.append({'name':'Legacy conversion and unknown route rejection','status':'已实测通过'})
report={'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Pure function from actual deployment script; captured read-only server proxy, no server mutation','sourceSha256':hashlib.sha256(source.encode()).hexdigest(),'checks':checks,'exitCode':0}
(root/'test-results/market-content-20261009/upgrade-proxy-regression.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
print('PASS owned current / prior / repeated / unsupported proxy configuration')
