const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs');
const contract = require('./test-contract.cjs');
const root = path.resolve(__dirname, '..');
const out = process.env.QA_OUTPUT_DIR || path.join(root, 'test-results/acceptance-20261004/frontend');
const phase = process.env.QA_PHASE === 'before' ? 'before' : 'after';
const password = 'FrontendQa2026!';
const tokens = [], checks = [], traffic = [], consoleErrors = [], pageErrors = [];
let browser, account;
fs.mkdirSync(out, { recursive: true });
async function raw(route, token, method = 'GET', body) {
  const response = await fetch(env.api + route, { method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '' }, body: body ? JSON.stringify(contract.bodyFor(route, method, body)) : undefined });
  return response.json();
}
async function api(...args) {
  const result = await raw(...args);
  assert.equal(result.code, 200, args[0] + ': ' + result.msg);
  return result.data;
}
async function nav(page, route) {
  await page.goto(env.mini + '/?frontendAcceptance=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' });
}
async function login(page) {
  await nav(page, 'auth/index');
  await page.locator('.text-input input').nth(0).fill(account.user.username);
  await page.locator('.text-input input').nth(1).fill(password);
  await page.locator('.check-box').click();
  const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/auth/login'));
  await page.getByText('登录', { exact: true }).click();
  const result = await (await response).json();
  assert.equal(result.code, 200); tokens.push(result.data.token);
  await page.getByText('找高手，上分更轻松', { exact: true }).waitFor();
}
async function check(id, name, mode, run) {
  const context = await browser.newContext({ viewport: { width: 390, height: 900 } });
  const page = await context.newPage(); page.setDefaultTimeout(8000);
  page.on('pageerror', e => pageErrors.push({ check: id, message: e.message }));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push({ check: id, message: m.text().replace(/https?:\/\/[^\s]+/g, '[URL]') }); });
  page.on('response', r => { const url = new URL(r.url()); if (url.pathname.includes('/boost')) traffic.push({ check: id, method: r.request().method(), path: url.pathname, status: r.status() }); });
  try {
    await run(page);
    checks.push({ id, name, mode, status: '已实测通过' }); console.log('PASS ' + id + ' ' + name);
  } catch (e) {
    checks.push({ id, name, mode, status: '失败', error: e.message }); console.error('FAIL ' + id + ': ' + e.message);
  } finally {
    await page.screenshot({ path: path.join(out, phase + '-' + id + '.png'), fullPage: true }).catch(() => {});
    await context.close();
  }
}
(async () => {
  assert.ok(['127.0.0.1', 'localhost'].includes(new URL(env.api).hostname));
  account = await api('/boost/auth/register', null, 'POST', { username: 'qa_front_' + Date.now(), password, nickname: '前端验收初始昵称' }); tokens.push(account.token);
  browser = await chromium.launch();
  await check('F01', '发布者查看待审核私有发布图片，签名过期后预览获得有效图片', '真实QA登录；本人待审核发布和媒体授权响应为 Mock，不写发布数据', async page => {
    await login(page);
    await page.waitForLoadState('networkidle');
    const asset = '/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png';
    const picture = fs.readFileSync(path.join(root, '代练/static/logo.png'));
    let grants = 0, expired = false;
    await page.route('**/boost/listings/900001', route => route.fulfill({ json: { code: 200, data: { id: 900001, kind: 'REQUEST', status: 'PENDING', title: '授权过期图片预览回归', game: '王者荣耀', priceCents: 12000, sales: 0, ownerId: account.user.id, nickname: account.user.nickname, certification: 'NONE', images: JSON.stringify([asset]), reviews: [], reviewTotal: 0, durationHours: 24, currentRank: '星耀', targetRank: '王者', description: '本人可见的待审核私有发布图片，浏览器故障注入', zone: '微信区', platform: '安卓' } } }));
    await page.route('**/boost/media/access', route => {
      if (!route.request().postDataJSON()?.paths?.includes(asset)) return route.continue();
      grants++; return route.fulfill({ json: { code: 200, data: { urls: { [asset]: asset + '?grant=' + grants } } } });
    });
    await page.route('**/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png?*', route => route.fulfill({ status: expired && new URL(route.request().url()).searchParams.get('grant') === '1' ? 403 : 200, contentType: 'image/png', headers: { 'Cache-Control': 'no-store' }, body: picture }));
    await nav(page, 'service/detail?id=900001');
    await page.getByText('授权过期图片预览回归', { exact: true }).waitFor();
    await page.locator('.detail-swiper img').evaluate(img => img.complete && img.naturalWidth > 0 ? true : new Promise((resolve, reject) => { img.onload = resolve; img.onerror = () => reject(new Error('Initial thumbnail did not load')); }));
    assert.equal(grants, 1);
    expired = true; await page.clock.setFixedTime(new Date(Date.now() + 180000));
    await page.locator('.detail-swiper uni-image').click();
    await page.locator('#u-a-p img').waitFor();
    const source = await page.locator('#u-a-p img').getAttribute('src');
    assert.notEqual(new URL(source, env.mini).searchParams.get('grant'), '1', '预览层仍使用过期签名 grant=1');
    assert.ok(grants >= 2, '预览前必须等待新授权');
    await page.locator('#u-a-p img').evaluate(img => img.complete && img.naturalWidth > 0 ? true : new Promise((resolve, reject) => { img.onload = resolve; img.onerror = () => reject(new Error('Fresh preview image did not load')); }));
  });
  await check('F02', '资料校验失败后修正并保存，错误清除且刷新后持久化', '真实本地接口、数据库与浏览器操作', async page => {
    await login(page); await nav(page, 'account/index?type=profile');
    const input = page.locator('.text-input input'); await input.waitFor();
    await page.waitForFunction(() => document.querySelector('.text-input input').value === '前端验收初始昵称');
    await input.fill('甲'); await input.press('Tab');
    await page.getByText('保存资料', { exact: true }).click(); await page.locator('.field-error').waitFor();
    assert.equal((await api('/boost/me', account.token)).nickname, '前端验收初始昵称');
    await input.fill('前端验收保存成功'); await input.press('Tab');
    await page.getByText('保存资料', { exact: true }).click(); await page.getByText('已保存', { exact: true }).waitFor();
    assert.equal((await api('/boost/me', account.token)).nickname, '前端验收保存成功');
    assert.equal(await page.locator('.field-error').count(), 0, '成功保存后仍残留前一次校验错误');
    await page.reload(); await page.waitForFunction(() => document.querySelector('.text-input input')?.value === '前端验收保存成功');
  });
  const proof = await contract.upload(account.token, 'CERTIFICATION');
  await api('/boost/me/certification', account.token, 'POST', { skill: '前端验收游戏能力证明', images: [proof] });
  await check('F03', '认证状态刷新失败可见并能重新加载，返回真实待审核状态', '真实本地认证提交与持久化；单次 GET 网络错误由浏览器注入', async page => {
    await login(page); await nav(page, 'certification/index');
    await page.getByText('材料正在审核', { exact: true }).waitFor();
    let fail = true;
    await page.route('**/api/boost/me', route => fail ? route.fulfill({ status: 503, json: { code: 503, msg: '验收注入：认证状态暂不可用' } }) : route.continue());
    await page.getByText('刷新审核状态', { exact: true }).click();
    await page.getByText('验收注入：认证状态暂不可用', { exact: true }).waitFor({ timeout: 3000 });
    assert.equal(await page.getByText('提交认证申请', { exact: true }).count(), 0, '失败时不能错误展示新的申请表单');
    await page.screenshot({ path: path.join(out, phase + '-F03-error.png'), fullPage: true });
    fail = false; await page.getByText('重新加载', { exact: true }).click();
    await page.getByText('材料正在审核', { exact: true }).waitFor();
    assert.equal(await page.getByText('验收注入：认证状态暂不可用', { exact: true }).count(), 0);
    await page.reload(); await page.getByText('材料正在审核', { exact: true }).waitFor();
    assert.equal((await api('/boost/me', account.token)).certification, 'PENDING');
  });
})().catch(e => { checks.push({ id: 'SETUP', status: '失败', error: e.message }); console.error(e.stack); }).finally(async () => {
  await browser?.close();
  const adminSessionFile = path.join(root, '.runtime/admin-session.json');
  let certificationCleanup = '未配置后台会话，保留本次QA待审核证明', cleanupError = '';
  try {
    const admin = process.env.ADMIN_TOKEN || (fs.existsSync(adminSessionFile) ? JSON.parse(fs.readFileSync(adminSessionFile, 'utf8')).token : '');
    if (account && admin) {
      const me = await api('/boost/me', account.token);
      if (me.certification === 'PENDING') {
        const result = await (await fetch(env.api + '/boost-admin/moderate/members/' + account.user.id, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + admin }, body: JSON.stringify({ action: 'reject', note: '自动验收数据清理：本次前端测试材料，无需业务审核' }) })).json();
        assert.equal(result.code, 200, 'QA认证清理失败: ' + result.msg);
        assert.equal((await api('/boost/me', account.token)).certification, 'REJECTED');
      }
      certificationCleanup = '本次QA认证已结束待审核状态';
    }
  } catch (e) { cleanupError = e.message; }
  const cleanup = await Promise.all(tokens.map(async token => { try { const logout = await raw('/boost/auth/logout', token, 'POST'); const revoked = await raw('/boost/me', token); return logout.code === 200 && revoked.code === 401; } catch { return false; } }));
  const unexpectedConsoleErrors = consoleErrors.filter(e => !((e.check === 'F02' && /status of 400/.test(e.message)) || (e.check === 'F03' && /status of 503/.test(e.message))));
  const passed = checks.length === 3 && checks.every(c => c.status === '已实测通过') && pageErrors.length === 0 && unexpectedConsoleErrors.length === 0 && cleanup.every(Boolean) && !cleanupError;
  const report = { passed, phase, completedAt: new Date().toISOString(), environment: env, checks, pageErrors, consoleErrors, unexpectedConsoleErrors, traffic, cleanup: { accountId: account?.user.id, sessionsRevoked: cleanup.every(Boolean), certificationCleanup, error: cleanupError, fixture: '保留本次QA账号与测试记录；没有创建订单或资金写入' } };
  fs.writeFileSync(path.join(out, phase + '.json'), JSON.stringify(report, null, 2));
  process.exitCode = passed ? 0 : 1;
});
