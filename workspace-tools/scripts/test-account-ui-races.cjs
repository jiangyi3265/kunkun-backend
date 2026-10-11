// node --experimental-vm-modules scripts/test-account-ui-races.cjs
// Executes the repository SFCs with its Vue compiler/reactivity and the actual API
// wrapper. uni requests, native picker/modal, and administrator transport are
// controlled substitutes; this is neither a browser nor a device test.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const assert = require('node:assert/strict'), crypto = require('node:crypto');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const miniRequire = createRequire(path.join(root, '代练/package.json'));
const adminRequire = createRequire(path.join(root, 'RuoYi-Vue3/package.json'));
const vue = miniRequire('vue'), compiler = miniRequire('@vue/compiler-sfc');
const { renderToString } = miniRequire('@vue/server-renderer');
const files = ['代练/lib/api.js', '代练/pages/account/index.vue', '代练/pages/certification/index.vue', '代练/components/UploadImages.vue', 'RuoYi-Vue3/src/utils/request.js', 'RuoYi-Vue3/src/store/modules/user.js', 'RuoYi-Vue3/src/api/login.js'];
const output = process.env.QA_OUTPUT_DIR ? path.resolve(process.env.QA_OUTPUT_DIR) : path.join(root, 'test-results', 'frontend-races-' + Date.now());
const phase = process.env.QA_PHASE === 'before' ? 'before' : 'after';
const report = { kind: 'Vue SFC compiler + Vue runtime + VM; requests/native picker/modal are substitutes', phase, startedAt: new Date().toISOString(), sources: files.map(file => ({ file, sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex') })), checks: [] };
const tick = () => new Promise(setImmediate);
function deferred() { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; }
function synthetic(context, exports) { return new vm.SyntheticModule(Object.keys(exports), function () { for (const [name, value] of Object.entries(exports)) this.setExport(name, value); }, { context }); }
function ok(data) { return { statusCode: 200, data: { code: 200, data } }; }
function user(id = 1) { return { id, username: id === 1 ? 'A' : 'B', nickname: id === 1 ? '原昵称甲' : '原昵称乙', avatar: 'hero', notifyEnabled: true, certification: 'NONE', skill: '', playerProfile:{shopStatus:'NONE',realnameStatus:'NONE',shopName:'',shopIntro:'',gameUid:''} }; }
async function mini(file, props = {}) {
  const requests = [], uploads = [], modals = [], pickers = [], events = [], redirects = [], hooks = {}, storage = new Map();
  const session = vue.reactive({ token: 'account-a', user: user(), config: null });
  const uni = {
    request: r => requests.push(r), uploadFile: r => uploads.push(r), showModal: r => modals.push(r), chooseImage: r => pickers.push(r), showToast() {},
    reLaunch: r => redirects.push(r.url), navigateTo() {}, getStorageInfoSync: () => ({ keys: [...storage.keys()] }), getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key)
  };
  const context = vm.createContext({ uni, getCurrentPages: () => [{ route: 'pages/test/index' }], console });
  const modules = new Map();
  const scope = vue.effectScope();
  const stub = vue.defineComponent({ inheritAttrs: false, setup: (_, { slots }) => () => vue.h('section', {}, slots.default?.()) });
  const vueExports = { ...vue, onBeforeUnmount: fn => { hooks.unmount = fn; } };
  modules.set('vue', synthetic(context, vueExports));
  modules.set('@dcloudio/uni-app', synthetic(context, { onLoad: fn => { hooks.load = fn; }, onShow: fn => { hooks.show = fn; }, onHide: fn => { hooks.hide = fn; }, onUnload: fn => { hooks.unload = fn; } }));
  modules.set('session', synthetic(context, { session, API_BASE: 'http://test.invalid' }));
  modules.set('media', synthetic(context, { prepareMedia: async () => {}, mediaUrl: value => value, clearMediaCache() {}, setMediaRequest() {}, previewMedia() {} }));
  const api = new vm.SourceTextModule(fs.readFileSync(path.join(root, '代练/lib/api.js'), 'utf8'), { context });
  modules.set('api', api);
  const descriptor = compiler.parse(fs.readFileSync(path.join(root, file), 'utf8'), { filename: file }).descriptor;
  const compiled = compiler.compileScript(descriptor, { id: 'account-ui-race' });
  const script = new vm.SourceTextModule(compiled.content, { context });
  async function link(specifier) {
    if (modules.has(specifier)) return modules.get(specifier);
    if (specifier.endsWith('/StateView.vue')) return modules.get('StateView');
    if (specifier.endsWith('/api')) return api;
    if (specifier === './session') return modules.get('session');
    if (specifier.endsWith('/media')) return modules.get('media');
    if(specifier.endsWith('/playerTrade')){if(!modules.has('playerTrade')){const m=new vm.SourceTextModule(fs.readFileSync(path.join(root,'代练/lib/playerTrade.js'),'utf8'),{context});modules.set('playerTrade',m);}return modules.get('playerTrade');}
    if (specifier.endsWith('.vue')) { const component = synthetic(context, { default: stub }); modules.set(specifier, component); return component; }
    throw new Error('Unexpected SFC import: ' + specifier);
  }
  const stateDescriptor = compiler.parse(fs.readFileSync(path.join(root, '代练/components/StateView.vue'), 'utf8')).descriptor;
  const stateScript = new vm.SourceTextModule(compiler.compileScript(stateDescriptor, { id: 'state-view-race', inlineTemplate: true }).content, { context });
  await stateScript.link(link); await stateScript.evaluate(); modules.set('StateView', synthetic(context, { default: stateScript.namespace.default }));
  await script.link(link); await script.evaluate();
  const component = script.namespace.default;
  const state = scope.run(() => component.setup(props, { expose() {}, emit: (...args) => { events.push(args); if (args[0] === 'update:modelValue') props.modelValue = args[1]; } }));
  async function html() {
    const compiledTemplate = compiler.compileTemplate({ filename: file, id: 'account-ui-race', source: descriptor.template.content, compilerOptions: { bindingMetadata: compiled.bindings } });
    assert.deepEqual(compiledTemplate.errors, []);
    const template = new vm.SourceTextModule(compiledTemplate.code, { context }); await template.link(link); await template.evaluate();
    return renderToString(vue.h({ ...component, setup: () => state, render: template.namespace.render }, props));
  }
  function switchTo(id, token = 'account-' + id) { session.token = token; session.user = id ? user(id) : null; }
  return { state, session, requests, uploads, modals, pickers, events, redirects, storage, props, html, hooks, switchTo, close: () => scope.stop() };
}
async function admin() {
  const requests = [], modals = [], redirects = [], messages = []; let token = 'admin-a';
  const pinia = adminRequire('pinia'), container = pinia.createPinia();
  const context = vm.createContext({ defineStore: pinia.defineStore, console, location: { set href(value) { redirects.push(value); } } });
  const cache = { session: { getJSON: () => null, setJSON() {} } };
  const modules = {
    axios: synthetic(context, { default: adminRequire('axios') }),
    'element-plus': synthetic(context, { ElNotification: { error() {} }, ElMessage: value => messages.push(value), ElMessageBox: { confirm: () => { const d = deferred(); modals.push(d); return d.promise; } }, ElLoading: {} }),
    '@/utils/auth': synthetic(context, { getToken: () => token, setToken: value => { token = value; }, removeToken: () => { token = ''; } }),
    '@/utils/errorCode': synthetic(context, { default: { default: 'error' } }),
    '@/utils/ruoyi': synthetic(context, { tansParams: () => '', blobValidate: () => true }),
    '@/plugins/cache': synthetic(context, { default: cache }),
    'file-saver': synthetic(context, { saveAs() {} }),
    '@/router': synthetic(context, { default: { push() {} } }),
    '@/utils/validate': synthetic(context, { isHttp: value => /^https?:/.test(value), isEmpty: value => !value }),
    '@/assets/images/profile.jpg': synthetic(context, { default: '/profile.jpg' })
  };
  const module = new vm.SourceTextModule(fs.readFileSync(path.join(root, files[4]), 'utf8'), { context, initializeImportMeta: meta => { meta.env = { VITE_APP_BASE_API: 'http://test.invalid' }; } });
  const storeModule = new vm.SourceTextModule(fs.readFileSync(path.join(root, 'RuoYi-Vue3/src/store/modules/user.js'), 'utf8'), { context, initializeImportMeta: meta => { meta.env = { VITE_APP_BASE_API: 'http://test.invalid' }; } });
  modules['@/store/modules/user'] = storeModule;
  modules['@/utils/request'] = module;
  modules['@/api/login'] = new vm.SourceTextModule(fs.readFileSync(path.join(root, 'RuoYi-Vue3/src/api/login.js'), 'utf8'), { context });
  await module.link(specifier => { assert.ok(modules[specifier], specifier); return modules[specifier]; }); await module.evaluate();
  const store = storeModule.namespace.default(container);
  const service = module.namespace.default;
  service.defaults.adapter = config => { const d = deferred(); requests.push({ config, answer: data => d.resolve({ data, config, status: 200, statusText: 'OK', headers: {}, request: { responseType: '' } }), fail: () => d.reject(Object.assign(new Error('Network Error'), { config })) }); return d.promise; };
  return { service, requests, modals, store, redirects, messages, setToken: value => { token = value; store.token = value; }, getToken: () => token };
}
async function check(name, action) {
  const startedAt = new Date().toISOString();
  try { const evidence = await action(); report.checks.push({ name, passed: true, startedAt, evidence }); console.log('PASS ' + name); }
  catch (error) { report.checks.push({ name, passed: false, startedAt, error: error.message }); console.error('FAIL ' + name + ': ' + error.message); }
}
(async () => {
  await check('mini delayed logout cannot clear newly signed-in account', async () => {
    const h = await mini(files[1]); try { const pending = h.state.logout(); h.modals[0].success({ confirm: true }); await tick(); assert.equal(h.requests.length, 1); h.switchTo(2, 'account-b'); await h.requests[0].success(ok({})); await pending; assert.equal(h.session.token, 'account-b'); assert.equal(h.session.user.id, 2); assert.equal(h.redirects.length, 0); return { requests: 1, newAccountRetained: true }; } finally { h.close(); }
  });
  await check('mini logout modal belongs to its initial account', async () => {
    const h = await mini(files[1]); try { const pending = h.state.logout(); h.switchTo(2, 'account-b'); h.modals[0].success({ confirm: true }); await tick(); for (const r of h.requests) await r.success(ok({})); await pending; assert.equal(h.requests.length, 0); assert.equal(h.session.token, 'account-b'); return { requests: 0 }; } finally { h.close(); }
  });
  await check('mini same-account logout still clears local session after success and transport failure', async () => {
    for (const failure of [false, true]) { const h = await mini(files[1]); try { const pending = h.state.logout(); h.modals[0].success({ confirm: true }); await tick(); failure ? h.requests[0].fail() : await h.requests[0].success(ok({})); await pending; assert.equal(h.session.token, ''); assert.equal(h.redirects.at(-1), '/pages/index/index'); } finally { h.close(); } }
    const cancelled = await mini(files[1]); try { const pending = cancelled.state.logout(); cancelled.modals[0].success({ confirm: false }); await pending; assert.equal(cancelled.session.token, 'account-a'); assert.equal(cancelled.requests.length, 0); assert.equal(cancelled.redirects.length, 0); } finally { cancelled.close(); }
    return { successAndFailureExercised: true, cancellationRetainsAccount: true };
  });
  await check('draft cleanup modal cannot delete the next account drafts', async () => {
    const h = await mini(files[1]); try { h.storage.set('boost_draft_1_REQUEST', 'draft-a'); h.storage.set('boost_draft_2_REQUEST', 'draft-b'); const pending = h.state.clearDrafts(); h.switchTo(2, 'account-b'); h.modals[0].success({ confirm: true }); await pending; assert.equal(h.storage.get('boost_draft_2_REQUEST'), 'draft-b'); assert.equal(h.storage.get('boost_draft_1_REQUEST'), 'draft-a'); return { bothDraftsRetained: true }; } finally { h.close(); }
  });
  await check('draft cleanup handles session expiry with real draft keys', async () => {
    const h = await mini(files[1]); try { h.storage.set('boost_draft_1_REQUEST', 'draft-a'); const pending = h.state.clearDrafts(); h.session.token = ''; h.session.user = null; h.modals[0].success({ confirm: true }); await pending; assert.equal(h.storage.get('boost_draft_1_REQUEST'), 'draft-a'); return { noTypeError: true }; } finally { h.close(); }
  });
  await check('same-account draft cleanup removes only its own keys', async () => {
    const h = await mini(files[1]); try { for (const key of ['boost_draft_1_REQUEST', 'boost_draft_1_SERVICE_edit_9', 'boost_draft_2_REQUEST', 'other']) h.storage.set(key, true); const pending = h.state.clearDrafts(); h.modals[0].success({ confirm: true }); await pending; assert.deepEqual([...h.storage.keys()], ['boost_draft_2_REQUEST', 'other']); return { remaining: [...h.storage.keys()] }; } finally { h.close(); }
  });
  await check('profile cannot edit or silently submit before its initial data is ready', async () => {
    const h = await mini(files[1]); try { h.hooks.load({ type: 'profile' }); const pending = h.state.load(); const html = await h.html(); assert.match(html, /<input\b(?=[^>]*class="text-input")(?=[^>]*disabled)/); assert.match(html, /<button\b(?=[^>]*class="ui-button btn wide")(?=[^>]*disabled)[^>]*>保存资料/); for (const tag of html.match(/<button\b[^>]*class="ui-button(?: chosen)?"[^>]*>/g) || []) assert.match(tag, /disabled/); await h.requests[0].success(ok(user())); await pending; return { renderedDisabled: true, method: 'Vue compiler + SSR (no browser)' }; } finally { h.close(); }
  });
  await check('profile load failure releases controls and a retry can save persisted input', async () => {
    const h = await mini(files[1]); try { h.hooks.load({ type: 'profile' }); const first = h.state.load(); h.requests[0].fail(); await first; assert.equal(h.state.loading.value, false); assert.match(h.state.loadError?.value || h.state.error.value, /连接失败/); const retry = h.state.load(); await h.requests[1].success(ok(user())); await retry; h.state.form.nickname = '重试后新昵称'; const saving = h.state.save(); await h.requests[2].success(ok({})); await tick(); await h.requests[3].success(ok({ ...user(), nickname: '重试后新昵称' })); await saving; assert.equal(h.requests[2].method, 'PUT'); assert.equal(h.requests[2].data.nickname, '重试后新昵称'); assert.equal(h.session.user.nickname, '重试后新昵称'); return { failedGetThenRetryThenPut: true }; } finally { h.close(); }
  });
  await check('native image picker binds account before it opens', async () => {
    const h = await mini(files[3], { modelValue: [], limit: 3, purpose: 'CERTIFICATION' }); try { h.state.choose(); h.switchTo(2, 'account-b'); await vue.nextTick(); const pending = h.pickers[0].success({ tempFilePaths: ['a-proof.png'] }); for (const r of h.uploads) r.success({ statusCode: 200, data: JSON.stringify({ code: 200, data: { url: '/boost-media/wrong.png' } }) }); await pending; assert.equal(h.uploads.length, 0); assert.equal(h.props.modelValue.length, 0); assert.equal(h.state.busy.value, false); return { uploads: 0, newAccountUnchanged: true, nativePickerSubstitute: true }; } finally { h.close(); }
  });
  await check('image picker is busy immediately and repeat selection cannot start a second picker', async () => {
    const h = await mini(files[3], { modelValue: [], limit: 3, purpose: 'CERTIFICATION' }); try { h.state.choose(); h.state.choose(); assert.equal(h.state.busy.value, true); assert.equal(h.pickers.length, 1); assert.deepEqual(h.events[0], ['busy', true]); h.pickers[0].fail({ errMsg: 'chooseImage:fail cancel' }); assert.equal(h.state.busy.value, false); assert.deepEqual(h.events.at(-1), ['busy', false]); return { pickers: 1, cancellationReleasesBusy: true }; } finally { h.close(); }
  });
  await check('same-account multi-image selection uploads in order and a failed upload releases busy', async () => {
    const h = await mini(files[3], { modelValue: [], limit: 3, purpose: 'CERTIFICATION' }); try { h.state.choose(); const pending = h.pickers[0].success({ tempFilePaths: ['one.png', 'two.png'] }); assert.equal(h.uploads.length, 1); h.uploads[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, data: { url: '/boost-media/one.png' } }) }); await tick(); assert.equal(h.uploads.length, 2); h.uploads[1].fail(); await pending; assert.deepEqual(Array.from(h.props.modelValue), ['/boost-media/one.png']); assert.equal(h.state.busy.value, false); assert.match(h.state.error.value, /上传失败/); return { firstImagePreserved: true, failedSecondImageReported: true }; } finally { h.close(); }
  });
  await check('certification cached form clears between accounts', async () => {
    const h = await mini(files[2]); try { h.state.form.shopIntro = 'A 的店铺草稿'; h.state.form.images = ['/boost-media/a-proof.png']; h.switchTo(2, 'account-b'); await vue.nextTick(); const pending = h.state.refresh(); await h.requests[0].success(ok(user(2))); await tick(); await h.requests[1].success(ok({policyVersion:'test',realnameReady:false})); await pending; assert.notEqual(h.state.form.shopIntro, 'A 的店铺草稿'); assert.equal(h.state.form.images.length, 0); assert.equal(h.state.ready.value, true); return { readyForNewAccount: true, previousDraftRemoved: true }; } finally { h.close(); }
  });
  await check('certification same-account refresh and re-login preserve unsubmitted draft', async () => {
    const h = await mini(files[2]); try { const initial = h.state.refresh(); await h.requests[0].success(ok(user())); await tick(); await h.requests[1].success(ok({policyVersion:'test',realnameReady:false})); await initial; h.state.form.shopIntro = '同账号店铺草稿'; h.state.form.images = ['/boost-media/own.png']; h.session.token = ''; h.session.user = null; await vue.nextTick(); h.switchTo(1, 'account-a-new-login'); await vue.nextTick(); const pending = h.state.refresh(); await h.requests[2].success(ok(user())); await pending; assert.equal(h.state.form.shopIntro, '同账号店铺草稿'); assert.deepEqual(Array.from(h.state.form.images), ['/boost-media/own.png']); return { sameAccountDraftRetained: true }; } finally { h.close(); }
  });
  await check('shop submission switch resets busy and cannot leak prior result', async()=>{
    const h=await mini(files[2]);try{const loading=h.state.refresh();await h.requests[0].success(ok(user()));await tick();await h.requests[1].success(ok({policyVersion:'test',realnameReady:false}));await loading;Object.assign(h.state.form,{shopName:'测试店铺',shopIntro:'测试介绍资料',gameUid:'123456',images:['/boost-media/a-proof.png']});h.state.accepted.value=true;const submitting=h.state.submit();assert.equal(h.state.busy.value,true);h.switchTo(2,'account-b');await vue.nextTick();assert.equal(h.state.busy.value,false);await h.requests[2].success(ok(true));await submitting;assert.equal(h.state.form.images.length,0);assert.equal(h.state.form.shopIntro,'');assert.equal(h.session.user.id,2);return {oldResponseRejected:true,newAccountNotBusy:true};}finally{h.close();}
  });
  await check('admin late 401 belongs to its request identity and cannot prompt for the next account', async () => {
    const h = await admin(), pending = h.service.get('/boost-admin/overview'); pending.catch(() => {}); await tick(); h.setToken('admin-b'); h.requests[0].answer({ code: 401 }); await assert.rejects(pending); assert.equal(h.modals.length, 0); assert.equal(h.getToken(), 'admin-b'); return { reloginPrompts: 0, newAccountRetained: true };
  });
  await check('admin already-open expiry modal cannot log out a later account', async () => {
    const h = await admin(), pending = h.service.get('/boost-admin/overview'); pending.catch(() => {}); await tick(); h.requests[0].answer({ code: 401 }); await assert.rejects(pending); assert.equal(h.modals.length, 1); h.setToken('admin-b'); h.modals[0].resolve(); await tick(); assert.equal(h.getToken(), 'admin-b'); assert.equal(h.requests.length, 1); assert.equal(h.redirects.length, 0); return { oldModalDoesNotLogout: true };
  });
  await check('admin same-account expiry confirmation still logs out and redirects', async () => {
    const h = await admin(), pending = h.service.get('/boost-admin/overview'); pending.catch(() => {}); await tick(); h.requests[0].answer({ code: 401 }); await assert.rejects(pending); h.modals[0].resolve(); await tick(); assert.equal(h.requests[1].config.url, '/logout'); h.requests[1].answer({ code: 200 }); await tick(); assert.equal(h.getToken(), ''); assert.equal(h.store.token, ''); assert.deepEqual(Array.from(h.store.roles), []); assert.deepEqual(h.redirects, ['/index']); return { normalExpiryRetained: true, actualUserStore: true };
  });
  await check('admin account switch during real user-store logout request has no unhandled rejection or redirect', async () => {
    const unhandled = [], listener = error => unhandled.push(error.message || String(error)); process.on('unhandledRejection', listener);
    try { const h = await admin(), pending = h.service.get('/boost-admin/overview'); pending.catch(() => {}); await tick(); h.requests[0].answer({ code: 401 }); await assert.rejects(pending); h.modals[0].resolve(); await tick(); assert.equal(h.requests[1].config.url, '/logout'); h.setToken('admin-b'); h.requests[1].answer({ code: 200 }); await tick(); await tick(); assert.equal(h.getToken(), 'admin-b'); assert.equal(h.store.token, 'admin-b'); assert.equal(h.redirects.length, 0); assert.deepEqual(unhandled, []); return { actualUserStore: true, delayedActualAxiosLogout: true, unhandledRejections: unhandled }; }
    finally { process.removeListener('unhandledRejection', listener); }
  });
  await check('admin stale successful response cannot populate a later account page', async () => {
    const h = await admin(), pending = h.service.get('/boost-admin/overview'); pending.catch(() => {}); await tick(); h.setToken('admin-b'); h.requests[0].answer({ code: 200, data: { privateRow: 'account-a-data' } }); await assert.rejects(pending, /登录状态已变化/); assert.equal(h.getToken(), 'admin-b'); return { oldSuccessRejected: true };
  });
  await check('admin late transport failure cannot display an old account error to the new account', async () => {
    const h = await admin(), pending = h.service.get('/boost-admin/overview'); pending.catch(() => {}); await tick(); h.setToken('admin-b'); h.requests[0].fail(); await assert.rejects(pending, /登录状态已变化/); assert.equal(h.messages.length, 0); assert.equal(h.getToken(), 'admin-b'); return { staleMessages: 0 };
  });
  for (const pageType of ['profile', 'settings']) await check(pageType + ' initial GET failure cannot write default profile and exposes real retry control', async () => {
    const h = await mini(files[1]); try { h.hooks.load({ type: pageType }); const loading = h.state.load(); h.requests[0].fail(); await loading; h.state.form.nickname = '加载失败后输入'; const saving = h.state.save(); await tick();
      // Settle a regressed PUT/GET so the failing assertion never hangs.
      for (const r of h.requests.slice(1)) await r.success(ok({ ...user(), avatar: 'anime' })); await tick(); for (const r of h.requests.slice(2)) await r.success(ok({ ...user(), avatar: 'anime' })); await saving;
      assert.equal(h.requests.filter(r => r.method === 'PUT').length, 0); const html = await h.html(); assert.match(html, />重新加载<\/button>/); if (pageType === 'profile') assert.match(html, /<input\b(?=[^>]*class="text-input")(?=[^>]*disabled)/); else assert.match(html, /<switch\b(?=[^>]*disabled)/);
      const retry = h.state.load(); await h.requests.at(-1).success(ok({ ...user(), avatar: 'anime' })); await retry; assert.equal(h.state.form.avatar, 'anime'); assert.equal(h.state.ready.value, true); return { failedLoadBlockedPut: true, realStateViewRenderedRetry: true, serverAvatarRecovered: true }; } finally { h.close(); }
  });
  await check('loaded profile PUT failure preserves edits and permits a real save retry', async () => {
    const h = await mini(files[1]); try { h.hooks.load({ type: 'profile' }); const loading = h.state.load(); await h.requests[0].success(ok({ ...user(), avatar: 'anime' })); await loading; h.state.form.nickname = '写入失败可重试'; const first = h.state.save(); h.requests[1].fail(); await first; assert.equal(h.state.form.nickname, '写入失败可重试'); assert.equal(h.state.form.avatar, 'anime'); const retry = h.state.save(); assert.equal(h.requests[2].method, 'PUT'); await h.requests[2].success(ok({})); await tick(); await h.requests[3].success(ok({ ...user(), nickname: '写入失败可重试', avatar: 'anime' })); await retry; assert.equal(h.session.user.nickname, '写入失败可重试'); return { putFailureDidNotTurnIntoLoadFailure: true, sameEditedPayloadRetried: true }; } finally { h.close(); }
  });
  await check('profile avatar picker return cannot reset pending upload; saved avatar and bio reload', async () => {
    const h=await mini(files[1]);try{h.hooks.load({type:'profile'});const loading=h.state.load();await h.requests[0].success(ok(user()));await loading;
      h.state.chooseAvatar();h.state.chooseAvatar();assert.equal(h.pickers.length,1);assert.equal(h.state.avatarBusy.value,true);
      const html=await h.html();for(const tag of html.match(/<button\b[^>]*class="ui-button(?: chosen)?"[^>]*>/g)||[])assert.match(tag,/disabled/,'preset avatar must be disabled while upload is in flight');
      h.hooks.show();assert.equal(h.requests.length,1,'native picker onShow must not reload form');
      const picked=h.pickers[0].success({tempFilePaths:['own-avatar.png']});assert.equal(h.uploads[0].formData.purpose,'AVATAR');
      const image='/boost-media/'+ 'a'.repeat(32)+'.png';h.uploads[0].success({statusCode:200,data:JSON.stringify({code:200,data:{url:image}})});await picked;
      assert.equal(h.state.form.avatar,image);assert.equal(h.state.avatarBusy.value,false);h.state.form.bio='地铁逃生道具玩家';
      const saving=h.state.save();assert.equal(h.requests[1].data.avatar,image);assert.equal(h.requests[1].data.bio,'地铁逃生道具玩家');await h.requests[1].success(ok({}));await tick();await h.requests[2].success(ok({...user(),avatar:image,playerProfile:{...user().playerProfile,bio:'地铁逃生道具玩家'}}));await saving;
      assert.equal(h.session.user.avatar,image);assert.equal(h.session.user.playerProfile.bio,'地铁逃生道具玩家');return {nativePickerSubstitute:true,purpose:'AVATAR',returnPreservesUpload:true,savedFieldsReloaded:true};
    }finally{h.close();}
  });
  await check('profile late avatar callbacks cannot update a switched account or unloaded page', async () => {
    for(const stop of ['account','unload']){const h=await mini(files[1]);try{h.hooks.load({type:'profile'});const loading=h.state.load();await h.requests[0].success(ok(user()));await loading;h.state.chooseAvatar();const picked=h.pickers[0].success({tempFilePaths:['old.png']});assert.equal(h.uploads.length,1);if(stop==='account'){h.switchTo(2,'account-b');await vue.nextTick();}else h.hooks.unload();h.uploads[0].success({statusCode:200,data:JSON.stringify({code:200,data:{url:'/boost-media/'+ 'b'.repeat(32)+'.png'}})});await picked;assert.equal(h.state.avatarBusy.value,false);assert.equal(h.state.form.avatar,'hero');assert.equal(h.requests.length,1);if(stop==='account')assert.equal(h.session.user.id,2);}finally{h.close();}}return {switchAndUnloadRejectLateUpload:true};
  });
  report.finishedAt = new Date().toISOString(); report.passed = report.checks.every(c => c.passed); report.counts = { total: report.checks.length, passed: report.checks.filter(c => c.passed).length, failed: report.checks.filter(c => !c.passed).length };
  fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'account-ui-races-' + phase + '.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify({ passed: report.passed, counts: report.counts, report: path.join(output, 'account-ui-races-' + phase + '.json') })); if (!report.passed) process.exitCode = 1;
})().catch(error => { console.error(error); process.exitCode = 1; });
