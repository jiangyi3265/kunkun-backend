// Isolated build-entry regression. The compiler is a probe, not a real app build.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const out = path.resolve(process.argv[2] || '');
assert(out.startsWith(path.join(root, 'test-results') + path.sep) && !fs.existsSync(out), 'Use a fresh evidence directory');
fs.mkdirSync(out, { recursive: true });
const source = path.join(root, 'patches/braces-3.0.3-depth-limit');
const entry = process.argv[3] ? path.resolve(process.argv[3]) : path.join(root, '代练/scripts/uni.cjs');
assert(entry.startsWith(root + path.sep), 'Entry must be an owned source snapshot');
const report = { startedAt: new Date().toISOString(), scope: 'Isolated dependency fixtures and compiler probe; actual production builds run separately', checks: [] };
const cases = [
  ['unpatched', false], ['tampered', false], ['nested-unpatched', false],
  ['missing', false], ['unknown-version', false], ['tampered-patch', false],
  ['linked-package', false], ['linked-scope-package', false], ['linked-node-modules', false],
  ['patched', true], ['compiler-failure', true]
];
for (const [name, mayLaunch] of cases) {
  const fixture = path.join(out, name), mini = path.join(fixture, '代练');
  fs.mkdirSync(path.join(mini, 'scripts'), { recursive: true });
  fs.mkdirSync(path.join(fixture, 'scripts'), { recursive: true });
  fs.copyFileSync(entry, path.join(mini, 'scripts/uni.cjs'));
  fs.copyFileSync(path.join(root, '代练/scripts/apply-braces-hardening.cjs'), path.join(mini, 'scripts/apply-braces-hardening.cjs'));
  const patch = path.join(mini, 'patches/braces-3.0.3-depth-limit');
  fs.cpSync(source, patch, { recursive: true });
  function dependency(target, patched) {
    fs.cpSync(path.join(source, 'original'), target, { recursive: true });
    fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name: 'braces', version: '3.0.3' }));
    if (patched) for (const file of Object.keys(JSON.parse(fs.readFileSync(path.join(source, 'manifest.json'))).files)) fs.copyFileSync(path.join(source, file), path.join(target, file));
  }
  const braces = path.join(mini, 'node_modules/braces');
  if (name !== 'missing') dependency(braces, name !== 'unpatched');
  if (name === 'nested-unpatched') dependency(path.join(mini, 'node_modules/consumer/node_modules/braces'), false);
  if (name === 'tampered') fs.appendFileSync(path.join(braces, 'lib/parse.js'), '\n// unexpected bytes\n');
  if (name === 'unknown-version') fs.writeFileSync(path.join(braces, 'package.json'), JSON.stringify({ name: 'braces', version: '3.0.99' }));
  if (name === 'tampered-patch') fs.appendFileSync(path.join(patch, 'lib/parse.js'), '\n// unexpected patch bytes\n');
  if (name.startsWith('linked-')) {
    const target = path.join(fixture, 'linked-target');
    dependency(path.join(target, name === 'linked-node-modules' ? 'braces' : 'node_modules/braces'), false);
    const link = path.join(mini, 'node_modules', name === 'linked-node-modules' ? 'consumer/node_modules' : name === 'linked-scope-package' ? '@scope/consumer' : 'consumer');
    fs.mkdirSync(path.dirname(link), { recursive: true });
    fs.symlinkSync(target, link, process.platform === 'win32' ? 'junction' : 'dir');
  }
  const compiler = path.join(mini, 'node_modules/@dcloudio/vite-plugin-uni');
  fs.mkdirSync(path.join(compiler, 'bin'), { recursive: true });
  fs.writeFileSync(path.join(compiler, 'package.json'), JSON.stringify({ name: '@dcloudio/vite-plugin-uni', version: '0.0.0-test' }));
  const compilerExit = name === 'compiler-failure' ? 29 : 0;
  fs.writeFileSync(path.join(compiler, 'bin/uni.js'), `require('node:fs').writeFileSync('compiler-called.json', JSON.stringify({args:process.argv.slice(2),cwd:process.cwd(),input:process.env.UNI_INPUT_DIR,marker:process.env.QA_BUILD_MARKER}));process.exit(${compilerExit});`);
  const args = ['build', '-p', 'mp-weixin'];
  const result = spawnSync(process.execPath, [path.join(mini, 'scripts/uni.cjs'), ...args], {
    cwd: fixture, env: { ...process.env, QA_BUILD_MARKER: 'preserved' }, encoding: 'utf8', windowsHide: true, timeout: 15000
  });
  fs.writeFileSync(path.join(fixture, 'command.log'), (result.stdout || '') + (result.stderr || '') + (result.error?.message || ''));
  const called = path.join(mini, 'compiler-called.json');
  const record = { name, command: [process.execPath, path.join(mini, 'scripts/uni.cjs'), ...args], exitCode: result.status, compilerLaunched: fs.existsSync(called) };
  try {
    assert(!result.error, result.error?.message);
    assert.equal(record.compilerLaunched, mayLaunch, 'Compiler must never start before dependencies are verified');
    if (mayLaunch) {
      assert.equal(result.status, compilerExit, 'Compiler exit must be propagated');
      assert.deepEqual(JSON.parse(fs.readFileSync(called)), { args, cwd: mini, input: mini, marker: 'preserved' });
    } else {
      assert.notEqual(result.status, 0, 'Unsafe dependency must fail closed');
      const reason = name.startsWith('linked-') ? /Linked dependency directories are not supported/ : {
        unpatched: /Backport has not been applied/, tampered: /Unknown dependency bytes/,
        'nested-unpatched': /Backport has not been applied/, missing: /Expected audited braces dependency missing/,
        'unknown-version': /Unknown braces version/, 'tampered-patch': /Backport source fingerprint mismatch/
      }[name];
      assert.match(result.stderr, reason, 'Must fail for the specific unsafe dependency, not a broken fixture');
    }
    record.status = '已实测通过';
  } catch (error) { record.status = '失败'; record.error = error.message; }
  report.checks.push(record);
  console.log(`${record.status}: ${name}`);
}
report.finishedAt = new Date().toISOString();
report.exitCode = report.checks.every(c => c.status === '已实测通过') ? 0 : 1;
fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(report, null, 2));
process.exitCode = report.exitCode;
