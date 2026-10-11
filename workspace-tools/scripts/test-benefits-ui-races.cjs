// Actual compiled Vue SFC; HTTP/session/modal/storage are controlled substitutes.
// No browser, native device, database, or real benefit mutation.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const requireMini = createRequire(path.join(root, '代练/package.json'));
const vue = requireMini('vue'), compiler = requireMini('@vue/compiler-sfc');
const source = process.argv[2] || path.join(root, 'RuoYi-Vue3/src/views/boost/BenefitsEditor.vue');
const output = process.argv[3] || path.join(process.env.QA_OUTPUT_DIR || path.join(root, 'test-results/benefits-ui'), 'benefits-ui-races.json');
const report = { startedAt: new Date().toISOString(), scope: 'Actual Vue SFC/runtime; HTTP, session, modal, permissions and storage substitutes. No actual browser/database/benefit grant.', source, sourceSha256: crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex'), checks: [] };
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function synthetic(context, exports) { return new vm.SyntheticModule(Object.keys(exports), function () { for (const [k, v] of Object.entries(exports)) this.setExport(k, v); }, { context }); }
async function editor(options = {}) {
  const scope = vue.effectScope(), hooks = {}, calls = [], modals = [], messages = [], emitted = [];
  const storage = options.storage || new Map(), store = vue.reactive({ id: 1, token: 'operator-one' });
  const props = vue.reactive({ member: { id: 7, benefitsRevision: options.revision || 0 } });
  const context = vm.createContext({ console, crypto, localStorage: { getItem: k => storage.get(k) || null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) } });
  const request = async config => {
    if (!config.method || config.method === 'get') return { data: { thresholds: '0,100,300' } };
    calls.push({ actor: store.id, token: store.token, url: config.url, data: { ...config.data } });
    if (options.request) return options.request(config);
    return { data: { revision: (config.data.expectedRevision || 0) + 1 } };
  };
  const modules = {
    vue: synthetic(context, { ...vue, onMounted: f => { hooks.mount = f; }, onBeforeUnmount: f => { hooks.unmount = f; } }),
    'element-plus': synthetic(context, { ElMessageBox: { confirm: () => { if (options.autoConfirm) return Promise.resolve(true); const m = deferred(); modals.push(m); return m.promise; } }, ElMessage: { success: x => messages.push(x), error: x => messages.push(x), warning: x => messages.push(x), info: x => messages.push(x) } }),
    '@/plugins/auth': synthetic(context, { default: { hasPermi: () => true } }),
    '@/utils/request': synthetic(context, { default: request }),
    '@/utils/auth': synthetic(context, { getToken: () => store.token }),
    '@/store/modules/user': synthetic(context, { default: () => store })
  };
  const { descriptor } = compiler.parse(fs.readFileSync(source, 'utf8'), { filename: source });
  const m = new vm.SourceTextModule(compiler.compileScript(descriptor, { id: 'independent-benefits' }).content, { context });
  await m.link(specifier => { assert(modules[specifier], 'Unexpected import: ' + specifier); return modules[specifier]; });
  await m.evaluate();
  const state = scope.run(() => m.namespace.default.setup(props, { expose() {}, emit: (...args) => emitted.push(args) }));
  await hooks.mount?.();
  return { state, calls, modals, messages, emitted, storage, store, props, close() { hooks.unmount?.(); scope.stop(); } };
}
function fill(h) { h.state.action.value = 'growth'; h.state.delta.value = 100; h.state.note.value = '隔离成长调整'; }
async function check(name, fn) { try { const evidence = await fn(); report.checks.push({ name, passed: true, evidence }); } catch (e) { report.checks.push({ name, passed: false, error: e.message, evidence: e.evidence }); } }
(async () => {
  await check('Unmounted editor must not submit a late confirmation', async () => {
    const h = await editor(); fill(h); const operation = h.state.saveMember();
    assert.equal(h.modals.length, 1); h.close(); h.modals[0].resolve(true); await operation;
    const evidence = { mutationRequestsAfterUnmount: h.calls.length, requests: h.calls };
    try { assert.equal(h.calls.length, 0, 'A closed/unmounted editor issued a benefit mutation'); } catch (e) { e.evidence = evidence; throw e; }
    return evidence;
  });
  await check('Uncertain intent must retain its key and original revision across editor remount', async () => {
    const storage = new Map();
    const first = await editor({ storage, autoConfirm: true, request: async () => { throw Object.assign(new Error('Uncertain response after possible commit'), { code: 503 }); } });
    fill(first); await first.state.saveMember(); assert.equal(first.calls.length, 1); const original = first.calls[0]; first.close();
    const next = await editor({ storage, revision: 1, autoConfirm: true }); fill(next); await next.state.saveMember();
    const retry = next.calls[0], evidence = { originalPayload: original.data, remountPayload: retry?.data, persistedIntentKeys: [...storage.keys()] }; next.close();
    try { assert.equal(retry?.data.requestKey, original.data.requestKey, 'Remount created a new key for the unresolved original adjustment'); assert.equal(retry?.data.expectedRevision, original.data.expectedRevision); } catch (e) { e.evidence = evidence; throw e; }
    return evidence;
  });
  await check('Control: changed token before confirmation blocks mutation', async () => {
    const h = await editor(); try { fill(h); const p = h.state.saveMember(); h.store.token = 'operator-two'; h.store.id = 2; h.modals[0].resolve(true); await p; assert.equal(h.calls.length, 0); return { requests: 0 }; } finally { h.close(); }
  });
  await check('Control: one mounted editor preserves retry key and confirmation payload', async () => {
    let n = 0; const h = await editor({ request: async () => { if (++n === 1) throw Object.assign(new Error('Uncertain'), { code: 503 }); return { data: { revision: 1 } }; } });
    try { fill(h); let p = h.state.saveMember(); h.state.delta.value = 200; h.modals[0].resolve(true); await p; assert.equal(h.calls[0].data.delta, 100); h.state.delta.value = 100; p = h.state.saveMember(); h.modals[1].resolve(true); await p; assert.equal(h.calls[1].data.requestKey, h.calls[0].data.requestKey); return { sameKeyWithinInstance: true, frozenDelta: 100 }; } finally { h.close(); }
  });
  await check('Definitive HTTP 409 must clear an Axios-coded pending intent so correction can submit', async () => {
    let n = 0;
    const h = await editor({ autoConfirm: true, request: async () => {
      if (++n === 1) throw Object.assign(new Error('Revision rejected by HTTP'), { code: 'ERR_BAD_REQUEST', response: { status: 409, data: { code: 409 } } });
      return { data: { revision: 1 } };
    } });
    try {
      fill(h); await h.state.saveMember();
      h.state.delta.value = 101; await h.state.saveMember();
      const evidence = { mutationRequests: h.calls.length, correctedDelta: h.calls[1]?.data.delta, pendingStorageKeys: [...h.storage.keys()], error: h.state.error.value };
      try { assert.equal(h.calls.length, 2, 'A definitive HTTP rejection permanently prevented corrected input'); assert.equal(h.calls[1].data.delta, 101); } catch (e) { e.evidence = evidence; throw e; }
      return evidence;
    } finally { h.close(); }
  });
  report.finishedAt = new Date().toISOString(); report.counts = { total: report.checks.length, passed: report.checks.filter(c => c.passed).length, failed: report.checks.filter(c => !c.passed).length }; report.exitCode = report.counts.failed ? 1 : 0;
  fs.mkdirSync(path.dirname(output), {recursive: true}); fs.writeFileSync(output, JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2)); process.exitCode = report.exitCode;
})().catch(e => { console.error(e); process.exitCode = 1; });

