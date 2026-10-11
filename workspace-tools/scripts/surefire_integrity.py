"""Fail-closed Surefire result completeness. Source inventory is parsed by javac Trees.

This does not execute application tests. collect() reconciles their real XML results
with the current reactor's supported, statically countable JUnit methods.
"""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

COUNTERS = ('tests', 'failures', 'errors', 'skipped')
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
HELPER = Path(__file__).with_name('SurefireSourceInventory.java')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_inventory(root, out):
    """Discover this project's active reactor; unsupported selection fails explicitly."""
    root, out = Path(root).resolve(), Path(out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    reactor = root/'RuoYi-Vue'
    problems, modules, sources, fingerprints = [], [], [], []
    seen = set()

    def visit(folder):
        folder = folder.resolve()
        if not folder.is_relative_to(reactor) or folder in seen:
            problems.append('Unsafe or repeated Maven reactor module')
            return
        seen.add(folder)
        pom = folder/'pom.xml'
        if not pom.is_file():
            problems.append('Missing Maven POM: '+str(pom.relative_to(root)))
            return
        fingerprints.append({'path':pom.relative_to(root).as_posix(), 'sha256':digest(pom)})
        try:
            xml = ET.parse(pom).getroot()
        except ET.ParseError:
            problems.append('Malformed Maven POM: '+str(pom.relative_to(root)))
            return
        # The supported policy is the repository's unconfigured Surefire 3.5.4.
        for element in ('testSourceDirectory', 'directory'):
            if xml.find('m:build/m:'+element, NS) is not None:
                problems.append(str(pom.relative_to(root))+': custom '+element+' requires an explicit inventory policy')
        for profile in xml.findall('m:profiles/m:profile', NS):
            if profile.find('m:modules', NS) is not None or profile.find('m:build', NS) is not None:
                problems.append(str(pom.relative_to(root))+': profile-specific reactor/test configuration is unsupported')
        for properties in xml.findall('m:properties', NS):
            for prop in properties:
                key = prop.tag.rsplit('}', 1)[-1]
                if key in {'test', 'skipTests', 'maven.test.skip', 'surefire.skip', 'groups', 'excludedGroups'} or key.startswith('surefire.'):
                    problems.append(str(pom.relative_to(root))+': test-selection/skip property '+key+' requires an explicit policy')
        for plugin in xml.findall('.//m:plugin', NS):
            if plugin.findtext('m:artifactId', namespaces=NS) == 'maven-surefire-plugin':
                if plugin.findtext('m:version', namespaces=NS) not in (None, '3.5.4'):
                    problems.append(str(pom.relative_to(root))+': Surefire version changed; verify naming/report policy')
                if plugin.find('m:configuration', NS) is not None or plugin.find('m:executions', NS) is not None:
                    problems.append(str(pom.relative_to(root))+': configured Surefire requires an explicit completeness policy')
        source_dir = folder/'src/test/java'
        module_sources = sorted(source_dir.rglob('*.java')) if source_dir.is_dir() else []
        sources.extend(module_sources)
        modules.append({'path':folder.relative_to(root).as_posix(), 'reports':str(folder/'target/surefire-reports'), 'sourceRoot':str(source_dir)})
        for child in xml.findall('m:modules/m:module', NS):
            visit(folder/(child.text or '').strip())

    visit(reactor)
    inventory = {'policy':'Surefire 3.5.4 default names; top-level concrete JUnit @Test and literal single @ValueSource parameterized tests; unsupported semantics fail closed', 'suites':[], 'problems':problems, 'modules':modules, 'sourceFingerprints':fingerprints, 'helperSha256':digest(HELPER)}
    if not sources:
        problems.append('No Java test source files in the active reactor')
    if sources and not problems:
        listing = out/'surefire-source-files.txt'
        listing.write_text('\n'.join(str(p) for p in sources), encoding='utf-8')
        java = os.environ.get('QA_JAVA') or shutil.which('java')
        if not java:
            fallback = Path.home()/'.jdks/azul-17.0.19/bin/java.exe'
            java = str(fallback) if fallback.is_file() else None
        if not java:
            problems.append('A Java 17+ JDK is required for parse-only test inventory')
        else:
            command = [java, '-Dfile.encoding=UTF-8', '-Xms32m', '-Xmx128m', '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=32m', str(HELPER), str(listing)]
            try:
                result = subprocess.run(command, capture_output=True, timeout=60)
                (out/'surefire-inventory-command.log').write_bytes(result.stderr)
                inventory['parserExitCode'] = result.returncode
                if result.returncode:
                    problems.append('Java AST inventory failed; see surefire-inventory-command.log')
                else:
                    parsed = json.loads(result.stdout.decode('utf-8'))
                    problems.extend(parsed['problems'])
                    for suite in parsed['suites']:
                        path = Path(suite['source']).resolve()
                        candidates = [m for m in modules if path.is_relative_to(Path(m['sourceRoot']))]
                        if len(candidates) != 1:
                            problems.append('Cannot map test source to one reactor module: '+suite['name'])
                            continue
                        suite['module'] = candidates[0]['path']
                        suite['source'] = path.relative_to(root).as_posix()
                        suite['sourceSha256'] = digest(path)
                        inventory['suites'].append(suite)
            except (OSError, subprocess.TimeoutExpired, UnicodeError, ValueError, KeyError):
                problems.append('Could not parse complete Java test inventory')
    # Fingerprint every Java source, including helpers/base classes that affect discovery.
    inventory['sourceFingerprints'].extend({'path':p.relative_to(root).as_posix(), 'sha256':digest(p)} for p in sources)
    names = [s['name'] for s in inventory['suites']]
    if len(names) != len(set(names)):
        problems.append('Duplicate test class names across active modules are unsupported')
    if not names and not problems:
        problems.append('No required test suites discovered')
    inventory['expectedSuites'] = len(names)
    inventory['expectedTests'] = sum(m['invocations'] for s in inventory['suites'] for m in s['methods'])
    (out/'backend-test-inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2), encoding='utf-8')
    return inventory


def validate_reports(inventory, report_paths, out):
    """Pure reconciliation used by real collect and clearly labelled synthetic fixtures."""
    out = Path(out)
    target = out/'surefire'
    target.mkdir(parents=True, exist_ok=True)
    expected = {s['name']:s for s in inventory['suites']}
    problems = list(inventory['problems'])
    suites, seen = [], Counter()
    for module, path in sorted(report_paths, key=lambda x: (x[0], str(x[1]))):
        path = Path(path)
        try:
            xml = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            problems.append('Unreadable/malformed report: '+path.name)
            continue
        name = xml.get('name', '')
        if xml.tag != 'testsuite' or not name:
            problems.append('Invalid testsuite root/name: '+path.name)
            continue
        seen[name] += 1
        if seen[name] > 1:
            problems.append('Duplicate suite report: '+name)
        spec = expected.get(name)
        if not spec:
            problems.append('Unexpected suite report (not current source inventory): '+name)
        elif spec['module'] != module:
            problems.append('Suite reported in the wrong reactor module: '+name)
        counts = {}
        for key in COUNTERS:
            value = xml.get(key)
            if value is None or not re.fullmatch(r'[0-9]+', value):
                problems.append(name+': missing/nonnegative-integer counter '+key)
                counts[key] = 0
            else:
                counts[key] = int(value)
        cases = xml.findall('testcase')
        if not cases or counts['tests'] == 0:
            problems.append(name+': zero executable cases')
        if counts['tests'] != len(cases):
            problems.append(name+': tests counter does not equal testcase count')
        actual = {key:sum(bool(c.findall(tag)) for c in cases) for key, tag in (('failures','failure'), ('errors','error'), ('skipped','skipped'))}
        for key in actual:
            if counts[key] != actual[key]:
                problems.append(name+': '+key+' counter contradicts testcase outcomes')
        if any(counts[k] or actual[k] for k in ('failures','errors','skipped')):
            problems.append(name+': failed/errored/skipped test cases are not acceptance passes')
        case_names, methods = [], Counter()
        for case in cases:
            case_name = case.get('name', '')
            case_names.append(case_name)
            if not case_name or case.get('classname') != name:
                problems.append(name+': missing testcase name or mismatched classname')
            if sum(len(case.findall(tag)) for tag in ('failure','error','skipped')) > 1:
                problems.append(name+': testcase has conflicting/multiple result outcomes')
            # Surefire Jupiter stores source method name then optional signature/index.
            method = re.split(r'[\[(]', case_name, maxsplit=1)[0]
            methods[method] += 1
        if len(case_names) != len(set(case_names)):
            problems.append(name+': duplicate testcase identity')
        if spec:
            wanted = {m['name']:m['invocations'] for m in spec['methods']}
            for method in sorted(set(wanted) | set(methods)):
                if methods[method] != wanted.get(method, 0):
                    problems.append(name+'#'+method+': expected '+str(wanted.get(method, 0))+' invocations, reported '+str(methods[method]))
            for method in spec['methods']:
                if method['kind'] == 'parameterized':
                    indices = [int(match.group(1)) for c in case_names if re.split(r'[\[(]', c, maxsplit=1)[0] == method['name'] and (match := re.search(r'\[(\d+)\]$', c))]
                    if sorted(indices) != list(range(1, method['invocations']+1)):
                        problems.append(name+'#'+method['name']+': missing/duplicate parameter invocation indices')
        suites.append({'name':name, **counts, 'actualCases':len(cases), 'cases':case_names, 'reportSha256':digest(path), 'module':module})
        # Environment properties and captured stdout/stderr are deliberately omitted.
        clean = ET.Element('testsuite', {k:xml.attrib[k] for k in ('name', *COUNTERS, 'time') if k in xml.attrib})
        for case in cases:
            copied = ET.SubElement(clean, 'testcase', {k:case.attrib[k] for k in ('name', 'classname', 'time') if k in case.attrib})
            for tag in ('failure', 'error', 'skipped'):
                for item in case.findall(tag):
                    copied.append(item)
        safe_name = re.sub(r'[^a-zA-Z0-9._-]', '_', name)
        suffix = '' if seen[name] == 1 else '-duplicate-'+str(seen[name])
        ET.ElementTree(clean).write(target/('TEST-'+safe_name+suffix+'.xml'), encoding='utf-8', xml_declaration=True)
    missing = sorted(set(expected)-set(seen))
    for name in missing:
        problems.append('Missing required suite report: '+name)
    totals = {key:sum(s[key] for s in suites) for key in COUNTERS}
    if totals['tests'] != inventory['expectedTests']:
        problems.append('Reported total does not match source-derived invocation inventory')
    report = {'passed':not problems, 'totals':totals, 'suites':suites, 'completeness':{'policy':inventory['policy'], 'expectedSuites':inventory['expectedSuites'], 'expectedTests':inventory['expectedTests'], 'reportedSuites':len(suites), 'missingSuites':missing, 'errors':problems, 'inventory':'backend-test-inventory.json'}, 'scope':'Result integrity only; does not substitute for running Maven/business tests'}
    (out/'backend-tests.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report


def collect(root, out):
    inventory = build_inventory(root, out)
    paths = [(m['path'], path) for m in inventory['modules'] for path in Path(m['reports']).glob('TEST-*.xml')]
    return validate_reports(inventory, paths, out)
