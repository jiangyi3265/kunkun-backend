// Actual local H5/HTTP/MySQL account-boundary acceptance. Native picker race is
// covered separately by test-account-ui-races.cjs and is not called a device test.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const { spawnSync } = require('node:child_process');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs'), root = path.resolve(__dirname, '..');
const out = path.join(process.env.QA_OUTPUT_DIR || path.join(root, 'test-results', 'frontend-browser-' + Date.now()), 'account-ui-browser');
const stamp = Date.now(), password = 'UiBoundary2026!';
const accounts = [], tokens = new Set(), checks = [], errors = [], transportFailures = [], expectedTransportCancellations = [], consoleErrors = [], expectedConsoleErrors = [], requests = [], blockedFunds = [], cleanup = [];
let browser, fatal, config;
fs.mkdirSync(out, { recursive: true });
function allowed(route, method, body) {
  const p = route.split('?')[0];
  assert.ok(!/\/(?:simulated-pay|wechat-pay|progress|aftersale|review|withdraw|reconcile)(?:\/|$)/.test(p), 'Funds or paid-state route forbidden: ' + p);
  assert.ok(!p.startsWith('/boost/wechat/'), 'Payment notification route forbidden');
  if (/^\/boost\/orders\/\d+\/action$/.test(p)) assert.equal(body?.action, 'cancel');
}
async function raw(route, token, method = 'GET', body) {
  allowed(route, method, body);
  const response = await fetch(env.api + route, { method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '' }, body: body ? JSON.stringify(body) : undefined });
  const value = await response.json(); requests.push({ source: 'HTTP', path: route.split('?')[0], method, status: response.status, code: value.code }); return value;
}
async function api(...args) { const value = await raw(...args); assert.equal(value.code, 200, args[0] + ': ' + value.msg); return value.data; }
async function account(label) {
  const value = await api('/boost/auth/register', null, 'POST', { username: 'qa_ui_race_' + label + '_' + stamp, nickname: '账号边界' + label + String(stamp).slice(-4), password, acceptedPolicy: config.policyVersion });
  accounts.push(value); tokens.add(value.token); return value;
}
async function nav(page, route) { page.acceptanceNavigating = true; try { await page.goto(env.mini + '/?accountBoundary=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' }); } finally { page.acceptanceNavigating = false; } }
// A triggering click can fail before its pre-created waiter is awaited. Observe
// rejection immediately; awaiting the original promise still fails the check.
function observedWait(promise) { promise.catch(() => {}); return promise; }
async function loginForm(page, owner) {
  await page.locator('#username input').fill(owner.user.username); await page.locator('#password input').fill(password); await page.locator('.check-box').click();
  const response = observedWait(page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/auth/login')));
  await page.getByText('登录', { exact: true }).click(); const value = await (await response).json(); assert.equal(value.code, 200, value.msg); tokens.add(value.data.token);
  await page.locator('#username input').waitFor({ state: 'hidden' }); return value.data.token;
}
async function login(page, owner) { await nav(page, 'auth/index'); await loginForm(page, owner); await page.getByText('找高手，上分更轻松', { exact: true }).waitFor(); }
async function currentToken(page) { const value = await page.evaluate(() => localStorage.getItem('boost_token')); if (value) tokens.add(value); return value; }
async function uploadProof(page) {
  const picker = observedWait(page.waitForEvent('filechooser'));
  // Uni button text has pointer-events:none; click the actual control rather
  // than its span, as a user does. H5 opens its native file input directly.
  await page.locator('.upload-add').click(); const chooser = await picker;
  const response = observedWait(page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/boost/upload')));
  await chooser.setFiles(path.join(root, '代练/static/logo.png'));
  const value = await (await response).json(); assert.equal(value.code, 200, value.msg); await page.locator('.upload-thumb').waitFor(); await page.locator('.upload-add:not([disabled])').waitFor(); return value.data.url;
}
function readDb(sql) {
  assert.match(sql, /^SELECT /); assert.ok(process.env.BOOST_DEV_CONFIG_FILE, 'Owned UI datasource configuration is required');
  const source = "import sys,json,os;sys.path.insert(0,'scripts');from local_db import configuration,mysql;values=json.load(open(sys.argv[1],encoding='utf-8'));os.environ.update({k:str(v) for k,v in values.items() if k.startswith('BOOST_DB_')});c=configuration();assert c['database'].startswith('boost_verify_ui_');q=json.load(sys.stdin);assert q.startswith('SELECT ');lines=mysql(q).splitlines();print(json.dumps([json.loads(x) for x in lines[1:] if x.strip()],ensure_ascii=False))";
  const result = spawnSync(process.env.QA_PYTHON || 'C:/Python314/python.exe', ['-X', 'utf8', '-c', source, process.env.BOOST_DEV_CONFIG_FILE], { cwd: root, input: JSON.stringify(sql), encoding: 'utf8' });
  assert.equal(result.status, 0, 'Read-only owned database evidence failed: ' + result.stderr); return JSON.parse(result.stdout);
}
async function check(name, run) {
  const context = await browser.newContext({ viewport: { width: 390, height: 900 } }), page = await context.newPage(); page.setDefaultTimeout(15000);
  let expectingExpiry = false, expectingProfile503 = false, closing = false; const releases = [];
  page.on('pageerror', e => errors.push({ check: name, error: e.message }));
  page.on('requestfailed', request => { const failure = { check: name, path: new URL(request.url()).pathname, error: request.failure()?.errorText }; if (failure.error === 'net::ERR_ABORTED' && (closing || page.acceptanceNavigating)) expectedTransportCancellations.push({ ...failure, reason: closing ? 'context closed' : 'explicit full-page navigation' }); else transportFailures.push(failure); });
  page.on('console', msg => { if (msg.type() !== 'error') return; const fault = /Failed to load resource.*503/.test(msg.text()) && expectingProfile503 && msg.location().url.includes('/boost/me'); const expiry = expectingExpiry && /Failed to load resource.*(?:401|403)/.test(msg.text()); (fault || expiry ? expectedConsoleErrors : consoleErrors).push({ check: name, error: msg.text(), ...(fault || expiry ? { reason: fault ? 'controlled profile delivery 503' : 'owned real session expiry' } : {}) }); });
  page.on('response', response => { const url = new URL(response.url()); if (url.pathname.includes('/boost/')) requests.push({ source: 'browser', path: url.pathname, method: response.request().method(), status: response.status(), expectedExpiry: expectingExpiry }); });
  await page.route('**/api/boost/**', route => {
    try { const r = route.request(), url = new URL(r.url()), body = r.headers()['content-type']?.includes('application/json') ? r.postDataJSON() : undefined; allowed(url.pathname.replace(/^\/api/, ''), r.method(), body); return route.continue(); }
    catch (e) { blockedFunds.push({ path: new URL(route.request().url()).pathname, error: e.message }); return route.abort(); }
  });
  try { const evidence = await run({ page, releases, expire: () => { expectingExpiry = true; }, profileFault: () => { expectingProfile503 = true; } }); await page.screenshot({ path: path.join(out, 'case-' + (checks.length + 1) + '.png'), fullPage: true }); checks.push({ name, passed: true, evidence }); console.log('PASS ' + name); }
  catch (e) { checks.push({ name, passed: false, error: e.message }); console.error('FAIL ' + name + ': ' + e.message); await page.screenshot({ path: path.join(out, 'failure-' + checks.length + '.png'), fullPage: true }).catch(() => {}); }
  finally { for (const release of releases) release(); closing = true; await context.close(); }
}
(async () => {
  assert.ok(process.env.BOOST_API_URL && process.env.MINIAPP_URL, 'Explicit isolated API/H5 endpoints are required');
  for (const url of [env.api, env.mini]) assert.ok(['localhost', '127.0.0.1'].includes(new URL(url).hostname));
  config = await api('/boost/config'); assert.equal(config.paymentsEnabled, false, 'Run only with payment disabled'); assert.ok(process.env.BOOST_DEV_CONFIG_FILE);
  browser = await chromium.launch();
  await check('slow profile failure blocks default writes and visible retry restores real avatar before save/reload', async ({ page, releases, profileFault }) => {
    const a = await account('profile'); await api('/boost/me', a.token, 'PUT', { nickname: a.user.nickname, avatar: 'anime', notifyEnabled: true }); await login(page, a);
    let release, reached; const held = new Promise(r => { release = r; }), started = new Promise(r => { reached = r; }); releases.push(release);
    let initialFailure = true;
    await page.route('**/boost/me', async route => { if (route.request().method() !== 'GET' || !initialFailure) return route.continue(); await route.fetch(); reached(); await held; await route.fulfill({ status: 503, json: { code: 503, msg: '验收注入个人资料暂时不可用' } }); });
    await nav(page, 'account/index?type=profile'); await started; await page.locator('.text-input input').waitFor();
    assert.equal(await page.locator('.text-input input').isDisabled(), true); assert.equal(await page.getByText('保存资料', { exact: true }).evaluate(el => el.hasAttribute('disabled')), true);
    assert.equal(await page.locator('.avatar-options uni-button:not([disabled])').count(), 0); profileFault(); release();
    await page.getByText('验收注入个人资料暂时不可用', { exact: true }).waitFor(); assert.equal(await page.locator('.text-input input').isDisabled(), true); assert.equal(await page.getByText('保存资料', { exact: true }).evaluate(el => el.hasAttribute('disabled')), true); assert.equal(requests.filter(r => r.source === 'browser' && r.path.endsWith('/boost/me') && r.method === 'PUT').length, 0);
    await page.screenshot({ path: path.join(out, 'profile-failed-load.png'), fullPage: true }); initialFailure = false; await page.getByText('重新加载', { exact: true }).click();
    await page.waitForFunction(expected => document.querySelector('.text-input input')?.value === expected, a.user.nickname);
    assert.match(await page.locator('.avatar-options uni-button').nth(2).getAttribute('class'), /chosen/); const name = '加载完成保存' + String(stamp).slice(-5); await page.locator('.text-input input').fill(name);
    await page.getByText('保存资料', { exact: true }).click(); await page.getByText('已保存', { exact: true }).waitFor(); const token = await currentToken(page); const saved = await api('/boost/me', token); assert.equal(saved.nickname, name); assert.equal(saved.avatar, 'anime');
    await page.reload({ waitUntil: 'domcontentloaded' }); await page.waitForFunction(expected => document.querySelector('.text-input input')?.value === expected, name); return { memberId: a.user.id, realGetHeld: true, first503ResponseDeliveryWasFaultInjection: true, visibleRetryRestoredRealAnimeAvatar: true, realPutPersistedAndReloaded: true };
  });
  await check('A real logout reply cannot clear B after expiry navigation and actual B login', async ({ page, releases, expire }) => {
    const a = await account('logoutA'), b = await account('logoutB'); await login(page, a); await nav(page, 'account/index?type=settings'); await page.locator('uni-button').filter({ hasText: '退出登录' }).and(page.locator(':not([disabled])')).waitFor();
    let release, reached; const held = new Promise(r => { release = r; }), started = new Promise(r => { reached = r; }); releases.push(release);
    await page.route('**/boost/auth/logout', async route => { const response = await route.fetch(); const value = await response.json(); assert.equal(value.code, 200); reached(); await held; await route.fulfill({ response }); });
    await page.getByText('退出登录', { exact: true }).click(); await page.locator('.uni-modal__btn_primary').click(); await started; const old = await currentToken(page); expire();
    assert.equal((await raw('/boost/me', old)).code, 401); await page.getByText('个人资料', { exact: true }).click(); await page.locator('#username input').waitFor(); await loginForm(page, b);
    await page.waitForFunction(expected => document.querySelector('.text-input input')?.value === expected, b.user.nickname); const reply = observedWait(page.waitForResponse(r => r.url().endsWith('/boost/auth/logout'))); release(); await reply;
    await page.waitForFunction(expected => document.querySelector('.text-input input')?.value === expected, b.user.nickname); const active = await currentToken(page); assert.ok(active); assert.equal((await api('/boost/me', active)).id, b.user.id);
    await page.reload({ waitUntil: 'domcontentloaded' }); await page.waitForFunction(expected => document.querySelector('.text-input input')?.value === expected, b.user.nickname); return { a: a.user.id, b: b.user.id, aServerSessionRevoked: true, bActualLoginRetainedAfterOldReplyAndReload: true };
  });
  await check('cached certification switches real accounts and preserves same-account retry draft', async ({ page, expire }) => {
    const a = await account('certA'), b = await account('certB'); await login(page, a); await nav(page, 'certification/index'); await page.locator('.textarea textarea').fill('甲账号未提交能力草稿'); await uploadProof(page);
    expire(); await api('/boost/auth/logout', await currentToken(page), 'POST'); await page.getByText('提交认证申请', { exact: true }).click(); await page.locator('#username input').waitFor(); await loginForm(page, b); await page.locator('.textarea textarea').waitFor();
    assert.equal(await page.locator('.textarea textarea').inputValue(), ''); assert.equal(await page.locator('.upload-thumb').count(), 0); assert.equal((await api('/boost/me', await currentToken(page))).id, b.user.id);
    const skill = '乙账号同账号保留能力草稿'; await page.locator('.textarea textarea').fill(skill); const proof = await uploadProof(page); await api('/boost/auth/logout', await currentToken(page), 'POST'); await page.getByText('提交认证申请', { exact: true }).click(); await page.locator('#username input').waitFor(); await loginForm(page, b); await page.locator('.textarea textarea').waitFor();
    assert.equal(await page.locator('.textarea textarea').inputValue(), skill); assert.equal(await page.locator('.upload-thumb').count(), 1); await page.getByText('提交认证申请', { exact: true }).click(); await page.getByText('材料正在审核', { exact: true }).waitFor();
    const saved = await api('/boost/me', await currentToken(page)); assert.equal(saved.id, b.user.id); assert.equal(saved.skill, skill); assert.equal(saved.certification, 'PENDING');
    const stored = readDb(`SELECT JSON_OBJECT('id',id,'skill',skill,'proof',proof,'certification',certification) FROM boost_member WHERE id=${b.user.id}`)[0]; assert.equal(stored.skill, skill); assert.deepEqual(JSON.parse(stored.proof), [proof]); return { a: a.user.id, b: b.user.id, crossAccountDraftCleared: true, sameAccountDraftPreserved: true, realCertificationSavedInMysql: true };
  });
  await check('draft cleanup open modal survives real session expiry without deleting drafts or throwing', async ({ page, expire }) => {
    const a = await account('draft'); await login(page, a); await nav(page, 'account/index?type=settings'); await page.locator('uni-button').filter({ hasText: '退出登录' }).and(page.locator(':not([disabled])')).waitFor();
    await page.evaluate(id => { localStorage.setItem(`boost_draft_${id}_REQUEST`, 'own-draft'); localStorage.setItem('boost_draft_999999_REQUEST', 'foreign-draft'); }, a.user.id);
    await page.getByText('清理本机草稿', { exact: true }).click(); await page.locator('.uni-modal__btn_primary').waitFor(); expire(); await api('/boost/auth/logout', await currentToken(page), 'POST');
    await page.waitForFunction(() => location.hash.includes('/pages/auth/index'), null, { timeout: 30000 }); await page.locator('.uni-modal__btn_primary').click();
    assert.equal(await page.evaluate(id => localStorage.getItem(`boost_draft_${id}_REQUEST`), a.user.id), 'own-draft'); assert.equal(await page.evaluate(() => localStorage.getItem('boost_draft_999999_REQUEST')), 'foreign-draft');
    await loginForm(page, a); await page.locator('uni-button').filter({ hasText: '退出登录' }).and(page.locator(':not([disabled])')).waitFor(); await page.getByText('清理本机草稿', { exact: true }).click(); await page.locator('.uni-modal__btn_primary').click(); await page.getByText('草稿已清理', { exact: true }).waitFor();
    assert.equal(await page.evaluate(id => localStorage.getItem(`boost_draft_${id}_REQUEST`), a.user.id), null); assert.equal(await page.evaluate(() => localStorage.getItem('boost_draft_999999_REQUEST')), 'foreign-draft'); return { memberId: a.user.id, automaticReminder401NavigatedToLogin: true, expiredModalDidNotDelete: true, sameAccountCleanupPassed: true };
  });
})().catch(e => { fatal = e.message; console.error(e); }).finally(async () => {
  try { await browser?.close(); } catch (e) { fatal = [fatal, 'Browser cleanup: ' + e.message].filter(Boolean).join('; '); }
  for (const token of tokens) { try { const value = await raw('/boost/auth/logout', token, 'POST'); assert.ok([200, 401].includes(value.code)); cleanup.push({ code: value.code }); } catch (e) { cleanup.push({ error: e.message }); } }
  let persisted = [];
  try { if (accounts.length) { const ids = accounts.map(a => { assert.ok(Number.isSafeInteger(a.user.id)); return a.user.id; }).join(','); persisted = readDb(`SELECT JSON_OBJECT('id',m.id,'username',m.username,'sessions',(SELECT COUNT(*) FROM boost_session s WHERE s.member_id=m.id),'balanceCents',m.balance_cents,'ledgerRows',(SELECT COUNT(*) FROM boost_ledger l WHERE l.member_id=m.id),'paidOrders',(SELECT COUNT(*) FROM boost_order o WHERE (o.buyer_id=m.id OR o.worker_id=m.id) AND o.paid_at IS NOT NULL),'withdrawals',(SELECT COUNT(*) FROM boost_withdrawal w WHERE w.member_id=m.id)) FROM boost_member m WHERE m.id IN (${ids}) ORDER BY m.id`); assert.equal(persisted.length, accounts.length); for (const row of persisted) { assert.ok(accounts.some(a => a.user.username === row.username)); assert.equal(row.sessions, 0); assert.equal(row.balanceCents, 0); assert.equal(row.ledgerRows, 0); assert.equal(row.paidOrders, 0); assert.equal(row.withdrawals, 0); } } }
  catch (e) { fatal = [fatal, e.message].filter(Boolean).join('; '); }
  const passed = !fatal && checks.length === 4 && checks.every(c => c.passed) && !errors.length && !transportFailures.length && !consoleErrors.length && !blockedFunds.length && cleanup.every(c => !c.error);
  const report = { passed, time: new Date().toISOString(), scope: 'Actual local H5/HTTP/MySQL; controlled response delay and first-profile-delivery 503 fault injection. No device picker race, no actual WeChat, no payment.', endpoints: { api: env.api, mini: env.mini }, checks, errors, transportFailures, expectedTransportCancellations, consoleErrors, expectedConsoleErrors, blockedFunds, requests, cleanup, persisted, fatal };
  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify({ passed, checks: checks.map(c => ({ name: c.name, passed: c.passed })), report: path.join(out, 'results.json'), fatal })); if (!passed) process.exitCode = 1;
});
