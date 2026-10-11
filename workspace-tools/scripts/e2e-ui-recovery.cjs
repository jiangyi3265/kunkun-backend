const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs'), root = path.resolve(__dirname, '..');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'))).token;
const phase = process.env.QA_PHASE === 'before' ? 'before' : 'after', stamp = Date.now(), password = 'UiRecovery2026!';
const tokens = [], listings = [], orders = [], checks = [], errors = []; let browser, buyer, worker, service, requestIds = [];
async function raw(route, token, method = 'GET', body) { body=require('./test-contract.cjs').bodyFor(route,method,body);
  return (await fetch(env.api + route, { method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + adminToken } : {}) }, body: body ? JSON.stringify(body) : undefined })).json();
}
async function api(...args) { const r = await raw(...args); assert.equal(r.code, 200, args[0] + ': ' + r.msg); return r.data; }
async function publish(owner, kind, title) {
  const item = await api('/boost/listings', owner.token, 'POST', { kind, title, game: '王者荣耀', zone: '微信区', platform: '安卓', currentRank: '星耀 III', targetRank: '星耀 I', durationHours: 24, priceCents: 12000, description: '页面恢复回归记录，全部使用本机测试数据。', images: [] });
  listings.push({ id: item.id, token: owner.token }); await api('/boost-admin/moderate/listings/' + item.id, null, 'POST', { action: 'approve', note: '页面恢复回归审核' }); return item.id;
}
async function nav(page, route) { await page.goto(env.mini + '/?uiRecovery=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' }); }
async function submitLogin(page, user) {
  await page.locator('.text-input input').nth(0).fill(user.user.username); await page.locator('.text-input input').nth(1).fill(password); await page.locator('.check-box').click();
  const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/auth/login'));
  await page.getByText('登录', { exact: true }).click(); const r = await (await response).json(); assert.equal(r.code, 200); tokens.push(r.data.token); await page.locator('.text-input input').first().waitFor({ state: 'hidden' });
}
async function login(page, user) { await nav(page, 'auth/index'); await submitLogin(page, user); await page.getByText('找高手，上分更轻松', { exact: true }).waitFor(); }
async function check(name, test) {
  const page = await browser.newPage({ viewport: { width: 390, height: 900 } }); page.setDefaultTimeout(12000); page.on('pageerror', e => errors.push(e.message));
  try { await test(page); checks.push({ name, passed: true }); console.log('PASS ' + name); }
  catch (e) { checks.push({ name, passed: false, error: e.message }); console.error('FAIL ' + name + ': ' + e.message); await page.screenshot({ path: require('./test-output.cjs').file('ui-recovery-' + phase + '-' + checks.length + '.png'), fullPage: true }); }
  finally { await page.close(); }
}
(async () => {
  assert.ok(['localhost', '127.0.0.1'].includes(new URL(env.api).hostname)); assert.equal((await api('/boost/config')).simulatedPayment, true);
  buyer = await api('/boost/auth/register', null, 'POST', { username: 'qa_ui_b_' + stamp, password, nickname: '页面恢复买家' });
  worker = await api('/boost/auth/register', null, 'POST', { username: 'qa_ui_w_' + stamp, password, nickname: '页面恢复打手' }); tokens.push(buyer.token, worker.token);
  const form = new FormData(); form.append('purpose','CERTIFICATION'); form.append('file', new Blob([fs.readFileSync(path.join(root, '代练/static/logo.png'))], { type: 'image/png' }), 'qa-proof.png');
  const upload = await (await fetch(env.api + '/boost/upload', { method: 'POST', headers: { 'X-Boost-Token': worker.token }, body: form })).json(); assert.equal(upload.code, 200);
  await api('/boost/me/certification', worker.token, 'POST', { skill: '页面回归测试的游戏能力证明', images: [upload.data.url] });
  await api('/boost-admin/moderate/members/' + worker.user.id, null, 'POST', { action: 'approve', note: '页面恢复回归认证' });
  service = await publish(worker, 'SERVICE', '页面恢复服务 ' + stamp);
  for (const entry of ['详情', '大厅']) requestIds.push(await publish(buyer, 'REQUEST', '页面恢复需求' + entry + ' ' + stamp));
  await api('/boost/listings/' + service + '/favorite', buyer.token, 'POST'); await api('/boost/shops/' + worker.user.id + '/follow', buyer.token, 'POST');
  browser = await chromium.launch();
  await check('从登录页返回服务详情刷新已收藏状态', async page => {
    await nav(page, 'service/detail?id=' + service); await page.locator('.favorite-btn:not([disabled])').click(); await submitLogin(page, buyer);
    await page.getByText('已收藏', { exact: true }).waitFor({ timeout: 3500 }); assert.equal((await api('/boost/listings/' + service, buyer.token)).favorited, true);
    await page.screenshot({ path: require('./test-output.cjs').file('ui-favorite-return.png'), fullPage: true });
  });
  await check('从登录页返回打手主页刷新已关注状态', async page => {
    await nav(page, 'shop/index?id=' + worker.user.id); await page.getByText('＋ 关注', { exact: true }).click(); await submitLogin(page, buyer);
    await page.getByText('已关注', { exact: true }).waitFor({ timeout: 3500 }); assert.equal((await api('/boost/shops/' + worker.user.id, buyer.token)).followed, true);
  });
  await check('聊天发送等待期间新输入的下一条消息不丢失', async page => {
    const conversation = await api('/boost/conversations', buyer.token, 'POST', { peerId: worker.user.id }); await login(page, buyer); await nav(page, 'chat/index?id=' + conversation.id);
    await page.getByText('先打个招呼，确认服务需求吧', { exact: true }).waitFor();
    let release, reached; const delayed = new Promise(r => { release = r; }), sent = new Promise(r => { reached = r; }); let first = true;
    await page.route('**/api/boost/conversations/' + conversation.id + '/messages', async route => {
      if (!first) return route.continue(); first = false; const response = await route.fetch(); reached(); await delayed; await route.fulfill({ response });
    });
    try {
      const input = page.locator('.chat-input input'); await input.fill('第一条消息已经发送'); await input.press('Tab');
      await page.locator('.fixed-footer .btn:not([disabled])').waitFor(); await page.getByText('发送', { exact: true }).click();
      let timer; try { await Promise.race([sent, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Message request did not start')), 12000); })]); } finally { clearTimeout(timer); }
      await input.fill('第二条正在编辑，不能丢失'); await input.press('Tab'); release();
      await page.waitForFunction(() => document.querySelector('.chat-input input').value === '' || !document.querySelector('.fixed-footer .btn').hasAttribute('disabled'));
      await page.getByText('第一条消息已经发送', { exact: true }).waitFor(); assert.equal(await input.inputValue(), '第二条正在编辑，不能丢失');
      await page.locator('.fixed-footer .btn:not([disabled])').waitFor(); await page.getByText('发送', { exact: true }).click(); await page.getByText('第二条正在编辑，不能丢失', { exact: true }).waitFor();
      await page.waitForFunction(() => document.querySelector('.chat-input input').value === ''); const messages = (await api('/boost/conversations/' + conversation.id, buyer.token)).items;
      assert.deepEqual(messages.map(m => m.content), ['第一条消息已经发送', '第二条正在编辑，不能丢失']);
      await page.screenshot({ path: require('./test-output.cjs').file('ui-chat-draft.png'), fullPage: true });
    } finally { release(); }
  });
  for (const [i, entry] of ['详情', '大厅'].entries()) await check(entry + '接单响应丢失后重试恢复原订单', async page => {
    const listing = requestIds[i]; await login(page, worker); await nav(page, i === 0 ? 'service/detail?id=' + listing : 'hall/index');
    const button = i === 0 ? page.getByText('立即接单', { exact: true }) : page.locator('.card').filter({ hasText: '页面恢复需求大厅 ' + stamp }).getByText('立即接单', { exact: true });
    let dropped = false, createdId;
    await page.route('**/api/boost/orders/accept', async route => {
      if (dropped) return route.continue(); dropped = true; const r = await (await route.fetch()).json(); assert.equal(r.code, 200); createdId = r.data.id; orders.push(createdId); await route.abort();
    });
    await button.click(); await page.locator('.uni-modal__btn_primary').click(); await page.getByText('连接失败，请检查网络后重试', { exact: true }).waitFor();
    await button.click(); const retry = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/orders/accept')); await page.locator('.uni-modal__btn_primary').click();
    const result = await (await retry).json(); assert.equal(result.code, 200, result.msg); assert.equal(result.data.id, createdId);
    await page.getByText('订单编号', { exact: true }).waitFor(); const own = await api('/boost/orders?role=worker', worker.token); assert.equal(own.items.filter(o => o.listingId === listing).length, 1);
    await api('/boost/orders/' + createdId + '/action', buyer.token, 'POST', { action: 'cancel' });
    await page.locator('[aria-label="返回"]').last().click(); await button.click();
    const nextResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/orders/accept'));
    await page.locator('.uni-modal__btn_primary').click(); const next = await (await nextResponse).json(); assert.equal(next.code, 200, next.msg); orders.push(next.data.id);
    assert.notEqual(next.data.id, createdId, 'After a successful recovery and cancellation, a new acceptance must create a new order');
    await page.getByText('订单编号', { exact: true }).waitFor();
  });
  if (phase !== 'before') await check('返回详情时服务已下架，不展示旧内容或继续下单入口', async page => {
    const hidden = await publish(worker, 'SERVICE', '返回页面下架回归 ' + stamp);
    await login(page, buyer); await nav(page, 'service/detail?id=' + hidden);
    await page.locator('.pad .card.row').first().click(); await page.getByText('打手主页', { exact: true }).waitFor();
    await api('/boost/listings/' + hidden + '/action', worker.token, 'POST', { action: 'offline' });
    await page.locator('[aria-label="返回"]').last().click(); await page.getByText('此服务已下架或正在审核', { exact: true }).waitFor();
    assert.equal(await page.locator('.detail-title').count(), 0);
    assert.equal(await page.locator('.fixed-footer .btn:not([disabled])').count(), 0);
    await page.screenshot({ path: require('./test-output.cjs').file('ui-offline-return.png'), fullPage: true });
  });
  assert.deepEqual(errors, []);
  const report = { passed: checks.every(c => c.passed), checks, errors }; fs.writeFileSync(require('./test-output.cjs').file('ui-recovery-' + phase + '.json'), JSON.stringify(report, null, 2)); if (!report.passed) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => {
  await browser?.close();
  for (const id of orders) await api('/boost/orders/' + id + '/action', buyer.token, 'POST', { action: 'cancel' });
  for (const item of listings) { const own = await api('/boost/listings/' + item.id, item.token); if (['ACTIVE', 'PENDING'].includes(own.status)) await api('/boost/listings/' + item.id + '/action', item.token, 'POST', { action: 'offline' }); }
  await Promise.allSettled(tokens.map(t => raw('/boost/auth/logout', t, 'POST')));
});
