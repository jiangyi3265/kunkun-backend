"""Synthetic gate regressions, never application/business execution or public audit."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from local_db import ROOT
from surefire_integrity import build_inventory

out=Path(os.environ['QA_OUTPUT_DIR'])/'gate-tests'
out.mkdir(parents=True,exist_ok=True)
probe='''
import io,json,os,runpy,urllib.request
mode=os.environ['QA_OSV_FIXTURE_MODE']
def response(request,timeout=None):
    size=len(json.loads(request.data)['queries'])
    items=[{} for _ in range(size)]
    if mode=='advisory':items[0]={'vulns':[{'id':'QA-ONLY-SYNTHETIC-ADVISORY'}]}
    if mode=='incomplete':items=items[:-1]
    return io.BytesIO(json.dumps({'results':items}).encode())
urllib.request.urlopen=response
runpy.run_path('scripts/security-scan-backend.py',run_name='__main__')
'''
checks=[]
for mode,expected in [('clean',0),('advisory',1),('incomplete',1)]:
    folder=out/mode;folder.mkdir(exist_ok=True)
    env={**os.environ,'QA_OUTPUT_DIR':str(folder),'QA_OSV_FIXTURE_MODE':mode}
    result=subprocess.run([sys.executable,'-X','utf8','-c',probe],cwd=ROOT,env=env,capture_output=True)
    (folder/'command.log').write_bytes(result.stdout+result.stderr)
    checks.append({'case':'security-'+mode,'expectedExitCode':expected,'actualExitCode':result.returncode,'passed':result.returncode==expected})

# Dedicated synthetic reactor and XML; real target/surefire-reports is never altered.
fixture=out/'report-integrity-fixture'
module=fixture/'RuoYi-Vue/app'
source=module/'src/test/java/qa'
source.mkdir(parents=True,exist_ok=True)
namespace='http://maven.apache.org/POM/4.0.0'
(fixture/'RuoYi-Vue/pom.xml').write_text('<project xmlns="'+namespace+'"><modules><module>app</module></modules></project>',encoding='utf-8')
(module/'pom.xml').write_text('<project xmlns="'+namespace+'"><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin></plugins></build></project>',encoding='utf-8')
(source/'AlphaTest.java').write_text('''package qa;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class AlphaTest {
  // @Test void commentedOut() {} is not an executable method.
  String text = "@Test void stringOnly() {}";
  @BeforeEach void setup() {}
  @Test void first() {}
  @org.junit.jupiter.api.Test
  void second() {}
  @ParameterizedTest
  @ValueSource(strings = {"comma,inside", "other"})
  void variants(String value) {}
  static class Helper { void unused() {} }
}
''',encoding='utf-8')
(source/'TestBeta.java').write_text('''package qa;
import org.junit.jupiter.api.Test;
class TestBeta { @Test void multiline
(
) {} }
''',encoding='utf-8')
inventory=build_inventory(fixture,out/'inventory-control')
inventory_ok=not inventory['problems'] and inventory['expectedSuites']==2 and inventory['expectedTests']==5 and {s['name'] for s in inventory['suites']}=={'qa.AlphaTest','qa.TestBeta'}
checks.append({'case':'ast-source-count-comments-strings-and-parameter-values','passed':inventory_ok,'scope':'Parse-only synthetic Java, not JUnit execution','actualProblems':inventory['problems'],'actualSuites':inventory['expectedSuites'],'actualTests':inventory['expectedTests']})

def make_reports(folder):
    paths=[]
    for suite in inventory['suites']:
        expected=sum(m['invocations'] for m in suite['methods'])
        xml=ET.Element('testsuite',{'name':suite['name'],'tests':str(expected),'failures':'0','errors':'0','skipped':'0'})
        ET.SubElement(xml,'properties')
        ET.SubElement(xml,'system-out').text='Synthetic output must not enter sanitized results'
        for method in suite['methods']:
            for index in range(1,method['invocations']+1):
                name=method['name'] if method['kind']=='test' else method['name']+'(String)['+str(index)+']'
                ET.SubElement(xml,'testcase',{'name':name,'classname':suite['name']})
        paths.append((suite['module'],folder/('TEST-'+suite['name']+'.xml'),xml))
    return paths

validator_probe='''
import json,os,sys
from pathlib import Path
sys.path.insert(0,str(Path.cwd()/'scripts'))
from surefire_integrity import validate_reports
control=json.loads(Path(os.environ['QA_INTEGRITY_CONTROL']).read_text(encoding='utf-8'))
report=validate_reports(control['inventory'],[(m,Path(p)) for m,p in control['paths']],Path(os.environ['QA_OUTPUT_DIR']))
print(json.dumps({'passed':report['passed'],'errors':report['completeness']['errors']}))
sys.exit(0 if report['passed'] else 1)
'''
mutations=[
 ('complete',0,None),('missing-suite',1,'Missing required suite'),
 ('zero-cases',1,'zero executable cases'),('inconsistent-counter',1,'tests counter'),
 ('hidden-skip',1,'skipped counter'),('reported-skip',1,'skipped test cases'),
 ('reported-failure',1,'failed/errored/skipped'),('reported-error',1,'failed/errored/skipped'),
 ('missing-method',1,'#first:'),('missing-parameter',1,'#variants:'),
 ('duplicate-parameter-index',1,'parameter invocation indices'),('duplicate-case',1,'duplicate testcase'),
 ('duplicate-suite',1,'Duplicate suite'),('unknown-suite',1,'Unexpected suite'),
 ('wrong-module',1,'wrong reactor module'),('wrong-classname',1,'mismatched classname'),
 ('unknown-method',1,'#unexpected:'),('negative-counter',1,'nonnegative-integer'),
 ('missing-counter',1,'nonnegative-integer'),('malformed-xml',1,'malformed report'),
]
if inventory_ok:
    for mode,expected,reason in mutations:
        folder=out/('reports-'+mode);folder.mkdir(exist_ok=True)
        paths=make_reports(folder)
        alpha=next(x[2] for x in paths if x[2].get('name')=='qa.AlphaTest')
        if mode=='missing-suite':paths=paths[:1]
        elif mode=='zero-cases':
            for _,_,xml in paths:
                for case in xml.findall('testcase'):xml.remove(case)
                xml.set('tests','0')
        elif mode=='inconsistent-counter':alpha.set('tests','99')
        elif mode in ('hidden-skip','reported-skip','reported-failure','reported-error'):
            tag={'hidden-skip':'skipped','reported-skip':'skipped','reported-failure':'failure','reported-error':'error'}[mode]
            ET.SubElement(alpha.find('testcase'),tag)
            if mode!='hidden-skip':alpha.set({'skipped':'skipped','failure':'failures','error':'errors'}[tag],'1')
        elif mode=='missing-method':alpha.remove(alpha.find('testcase'));alpha.set('tests','3')
        elif mode=='missing-parameter':alpha.remove(alpha.findall('testcase')[-1]);alpha.set('tests','3')
        elif mode=='duplicate-parameter-index':alpha.findall('testcase')[-1].set('name','variants(Object)[1]')
        elif mode=='duplicate-case':alpha.append(copy.deepcopy(alpha.find('testcase')));alpha.set('tests','5')
        elif mode=='duplicate-suite':paths.append((paths[0][0],folder/'TEST-duplicate.xml',copy.deepcopy(alpha)))
        elif mode=='unknown-suite':alpha.set('name','qa.UnknownTest')
        elif mode=='wrong-module':paths[0]=('RuoYi-Vue/other',paths[0][1],paths[0][2])
        elif mode=='wrong-classname':alpha.find('testcase').set('classname','qa.Stranger')
        elif mode=='unknown-method':alpha.find('testcase').set('name','unexpected')
        elif mode=='negative-counter':alpha.set('errors','-1')
        elif mode=='missing-counter':del alpha.attrib['skipped']
        for _,path,xml in paths:ET.ElementTree(xml).write(path,encoding='utf-8',xml_declaration=True)
        if mode=='malformed-xml':paths[0][1].write_text('<testsuite',encoding='utf-8')
        control=folder/'control.json'
        control.write_text(json.dumps({'inventory':inventory,'paths':[(m,str(p)) for m,p,_ in paths]}),encoding='utf-8')
        destination=folder/'output'
        env={**os.environ,'QA_OUTPUT_DIR':str(destination),'QA_INTEGRITY_CONTROL':str(control)}
        result=subprocess.run([sys.executable,'-X','utf8','-c',validator_probe],cwd=ROOT,env=env,capture_output=True)
        (folder/'command.log').write_bytes(result.stdout+result.stderr)
        evidence=destination/'backend-tests.json'
        recorded=json.loads(evidence.read_text(encoding='utf-8')) if evidence.is_file() else None
        reason_ok=recorded is not None and (reason is None or any(reason in problem for problem in recorded['completeness']['errors']))
        sanitized_ok=recorded is not None and all('system-out' not in p.read_text(encoding='utf-8') and '<properties' not in p.read_text(encoding='utf-8') for p in (destination/'surefire').glob('*.xml'))
        checks.append({'case':'reports-'+mode,'expectedExitCode':expected,'actualExitCode':result.returncode,'expectedReason':reason,'passed':result.returncode==expected and reason_ok and sanitized_ok,'evidence':str(evidence),'sanitizedOutputsVerified':sanitized_ok})

    # Verify the actual support entry point with its ROOT set to this isolated fixture.
    # No environment option can redirect reports during normal real acceptance.
    support_probe='''
import os,runpy,sys
from pathlib import Path
sys.path.insert(0,str(Path.cwd()/'scripts'))
import local_db
local_db.ROOT=Path(os.environ['QA_SYNTHETIC_ROOT'])
sys.argv=['scripts/acceptance-support.py','collect-tests']
runpy.run_path('scripts/acceptance-support.py',run_name='__main__')
'''
    for mode,expected in [('complete',0),('missing-suite',1),('zero-cases',1),('hidden-skip',1)]:
        folder=out/('entrypoint-'+mode);folder.mkdir(exist_ok=True)
        reports=module/'target/surefire-reports';reports.mkdir(parents=True,exist_ok=True)
        for old in reports.glob('TEST-*.xml'):old.unlink()  # Only this exact synthetic reactor.
        for p in (out/('reports-'+mode)).glob('TEST-*.xml'):
            (reports/p.name).write_bytes(p.read_bytes())
        env={**os.environ,'QA_OUTPUT_DIR':str(folder),'QA_SYNTHETIC_ROOT':str(fixture)}
        result=subprocess.run([sys.executable,'-X','utf8','-c',support_probe],cwd=ROOT,env=env,capture_output=True)
        (folder/'command.log').write_bytes(result.stdout+result.stderr)
        evidence=folder/'backend-tests.json'
        recorded=json.loads(evidence.read_text(encoding='utf-8')) if evidence.is_file() else None
        checks.append({'case':'entrypoint-'+mode,'expectedExitCode':expected,'actualExitCode':result.returncode,'passed':result.returncode==expected and recorded is not None and recorded['passed']==(expected==0),'evidence':str(evidence)})

    # Unknown discovery semantics produce policy failure rather than guessed success.
    original=(source/'TestBeta.java').read_text(encoding='utf-8')
    variants=[('dynamic-source','import org.junit.jupiter.api.TestFactory; class TestBeta { @TestFactory Object dynamic() {return null;} }','dynamic/combined'),
              ('composed-test','class TestBeta { @CustomTest void custom() {} }','possible composed test'),
              ('inherited-tests','class TestBeta extends Base { }','unresolved superclass'),
              ('syntax-error','class TestBeta { void broken( }','Java syntax error')]
    for mode,text,reason in variants:
        try:
            (source/'TestBeta.java').write_text('package qa;\n'+text,encoding='utf-8')
            current=build_inventory(fixture,out/('policy-'+mode))
            checks.append({'case':'policy-'+mode,'passed':any(reason in p for p in current['problems']),'expectedReason':reason,'problems':current['problems'],'scope':'Synthetic parse-only source'})
        finally:
            (source/'TestBeta.java').write_text(original,encoding='utf-8')
    # Same-package inheritance is statically supported, including suppressed overrides.
    (source/'Base.java').write_text('package qa; import org.junit.jupiter.api.Test; abstract class Base { @Test void inherited() {} @Test void suppressed() {} }',encoding='utf-8')
    try:
        (source/'TestBeta.java').write_text('package qa; import org.junit.jupiter.api.Test; import org.springframework.transaction.annotation.*; class TestBeta extends Base { @Override void suppressed() {} @Test @Transactional void local() {} }',encoding='utf-8')
        inherited=build_inventory(fixture,out/'inventory-inheritance')
        beta=next(s for s in inherited['suites'] if s['name']=='qa.TestBeta')
        checks.append({'case':'inherited-tests-and-nontest-overrides','passed':not inherited['problems'] and {m['name'] for m in beta['methods']}=={'inherited','local'},'problems':inherited['problems']})
    finally:
        (source/'TestBeta.java').write_text(original,encoding='utf-8')
        (source/'Base.java').unlink()
    pom_original=(module/'pom.xml').read_text(encoding='utf-8')
    try:
        (module/'pom.xml').write_text(pom_original.replace('</plugin>','<configuration><includes><include>**/AlphaTest.java</include></includes></configuration></plugin>'),encoding='utf-8')
        current=build_inventory(fixture,out/'policy-surefire-selection')
        checks.append({'case':'policy-surefire-selection','passed':any('configured Surefire' in p for p in current['problems']),'problems':current['problems']})
    finally:
        (module/'pom.xml').write_text(pom_original,encoding='utf-8')

report={'passed':all(c['passed'] for c in checks),'mode':'Synthetic OSV responses, Java source and Surefire XML only; no business tests, Maven, real database or public advisory requests. Real audit and clean verify remain separately required.','checks':checks}
(out/'results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
sys.exit(0 if report['passed'] else 1)
