// Price changes and lost responses, exercised with isolated QA orders and simulated funds.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs'), root = path.resolve(__dirname, '..');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'))).token;
const stamp = Date.now(), password = 'BoundaryQa2026!', checks = [], errors = [], tokens = [], orderIds = [];
let browser, page, buyer, worker, service, withdrawalId;
async function raw(route, token, method = 'GET', body) { body=require('./test-contract.cjs').bodyFor(route,method,body);
  const r = await fetch(env.api + route, { method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + adminToken } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return r.json();
}
async function api(...args) { const r = await raw(...args); assert.equal(r.code, 200, args[0] + ': ' + r.msg); return r.data; }
async function approve() { await api('/boost-admin/moderate/listings/' + service.id, null, 'POST', { action: 'approve', note: '交易边界回归审核' }); }
async function nav(p, route) { await p.goto(env.mini + '/?boundary=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' }); }
async function click(p, text) { await p.getByText(text, { exact: true }).last().click(); }
async function login(p, username, pass) {
  await nav(p, 'auth/index'); await p.locator('.text-input input').nth(0).fill(username); await p.locator('.text-input input').nth(1).fill(pass); await p.locator('.check-box').click(); await click(p, '登录');
  await p.getByText('找高手，上分更轻松', { exact: true }).waitFor(); tokens.push(await p.evaluate(() => localStorage.getItem('boost_token')));
}
(async () => {
  assert.equal((await api('/boost/config')).simulatedPayment, true);
  buyer = await api('/boost/auth/register', null, 'POST', { username: 'qa_boundary_' + stamp, password, nickname: '交易边界买家' });
  worker = await api('/boost/auth/login', null, 'POST', { username: 'demo_worker', password: 'Demo2026!' }); tokens.push(buyer.token, worker.token);
  const body = { kind: 'SERVICE', title: '交易边界回归 ' + stamp, game: '王者荣耀', zone: '微信区', platform: '安卓', currentRank: '星耀 III', targetRank: '星耀 II', durationHours: 24, priceCents: 12000, description: '验证改价确认和网络故障后安全重试。', images: [] };
  service = await api('/boost/listings', worker.token, 'POST', body); await approve();
  browser = await chromium.launch(); page = await browser.newPage({ viewport: { width: 390, height: 900 } }); page.setDefaultTimeout(15000); page.on('pageerror', e => errors.push(e.message));
  await login(page, buyer.user.username, password); await nav(page, 'checkout/index?id=' + service.id);
  await page.locator('.fixed-footer .price').filter({ hasText: '120' }).waitFor();
  body.priceCents = 24000; await api('/boost/listings/' + service.id, worker.token, 'PUT', body); await approve();
  await page.locator('.check-box').click();
  const changedResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/orders'));
  await click(page, '确认下单并支付'); const changed = await (await changedResponse).json();
  if (changed.code === 200) orderIds.push(changed.data.id);
  assert.equal(changed.code, 409, 'Displayed ¥120 must not silently create a ¥240 order');
  await page.getByText('服务内容或价格已更新，请重新确认后下单', { exact: true }).waitFor();
  await page.locator('.fixed-footer .price').filter({ hasText: '240' }).waitFor();
  assert.equal(await page.locator('.check-box.checked').count(), 0); assert.equal((await api('/boost/orders', buyer.token)).total, 0);
  checks.push('旧报价提交被拒绝，不产生订单，刷新金额后必须重新勾选确认');
  await click(page, '确认下单并支付'); await page.getByText('请先确认服务范围并同意交易规则', { exact: true }).waitFor();
  let dropCreate = true, createdId;
  await page.route('**/api/boost/orders', async route => {
    if (route.request().method() === 'POST' && dropCreate) {
      dropCreate = false; const result = await (await route.fetch()).json(); assert.equal(result.code, 200); createdId = result.data.id; orderIds.push(createdId); await route.abort();
    } else await route.continue();
  });
  await page.locator('.check-box').click(); await click(page, '确认下单并支付');
  await page.getByText('连接失败，请检查网络后重试', { exact: true }).waitFor();
  body.priceCents = 36000; body.targetRank = '荣耀王者'; await api('/boost/listings/' + service.id, worker.token, 'PUT', body); await approve();
  await click(page, '交易规则'); await page.getByText('用户协议与隐私说明', { exact: true }).first().waitFor(); await page.locator('[aria-label="返回"]').click();
  await page.locator('.fixed-footer .price').filter({ hasText: '360' }).waitFor(); assert.equal(await page.locator('.check-box.checked').count(), 0);
  await page.locator('.check-box').click(); await click(page, '确认下单并支付');
  await page.getByText('付款后，打手即可开始服务', { exact: true }).waitFor();
  await page.getByText('订单金额', { exact: true }).waitFor();
  assert.equal(await page.getByText('实付', { exact: true }).count(), 0);
  assert.equal(await page.locator('.uni-modal').count(), 0, 'A recovered old order must be reviewed before payment');
  assert.equal((await api('/boost/orders', buyer.token)).total, 1); assert.equal((await api('/boost/orders/' + createdId, buyer.token)).originalCents, 24000);
  await page.getByText('星耀 III → 星耀 II', { exact: true }).waitFor();
  await click(page, '去付款'); await page.locator('.uni-modal__btn_default').click();
  checks.push('下单响应丢失后即使服务再次改价，仍恢复原订单并先核对，不重复下单或自动付款');
  let dropCancel = true;
  await page.route('**/api/boost/orders/' + createdId + '/action', async route => {
    if (dropCancel) { dropCancel = false; assert.equal((await (await route.fetch()).json()).code, 200); await route.abort(); } else await route.continue();
  });
  await click(page, '取消未付款订单'); await page.locator('.uni-modal__btn_primary').click();
  await page.locator('.field-error').filter({ hasText: '连接失败' }).waitFor();
  await click(page, '取消未付款订单'); await page.locator('.uni-modal__btn_primary').click();
  await page.getByText('订单已关闭', { exact: true }).waitFor();
  await page.getByText('订单金额', { exact: true }).waitFor();
  await page.getByText('连接失败，请检查网络后重试', { exact: true }).waitFor({ state: 'hidden' });
  checks.push('取消已入库但响应丢失，再次取消正常恢复已关闭状态');
  await page.screenshot({ path: path.join(root, 'test-results/deep-cancel-retry.png'), fullPage: true });
  await page.close(); page = await browser.newPage({ viewport: { width: 390, height: 900 } }); page.setDefaultTimeout(15000); page.on('pageerror', e => errors.push(e.message));
  await login(page, 'demo_worker', 'Demo2026!'); const before = await api('/boost/wallet', worker.token); assert.ok(before.balanceCents >= 200);
  await nav(page, 'wallet/index'); await click(page, '提现'); const fields = page.locator('.field-row input');
  await fields.nth(0).fill('1'); await fields.nth(1).fill('测试打手'); await fields.nth(2).fill('boundary-qa');
  let dropWithdraw = true;
  await page.route('**/api/boost/wallet/withdraw', async route => {
    if (dropWithdraw) { dropWithdraw = false; const result = await (await route.fetch()).json(); assert.equal(result.code, 200); withdrawalId = result.data.id; await route.abort(); } else await route.continue();
  });
  await click(page, '提交提现申请'); await page.locator('.uni-modal__btn_primary').click(); await page.getByText('连接失败，请检查网络后重试', { exact: true }).waitFor();
  await fields.nth(0).fill('2'); const mismatchResponse = page.waitForResponse(r => r.url().endsWith('/boost/wallet/withdraw'));
  await click(page, '提交提现申请'); await page.locator('.uni-modal__btn_primary').click();
  assert.equal((await (await mismatchResponse).json()).code, 409); assert.equal((await api('/boost/wallet', worker.token)).balanceCents, before.balanceCents - 100);
  await fields.nth(0).fill('1'); await click(page, '提交提现申请'); await page.locator('.uni-modal__btn_primary').click();
  await page.getByText('提现 ¥1', { exact: true }).first().waitFor();
  const after = await api('/boost/wallet', worker.token); assert.equal(after.balanceCents, before.balanceCents - 100); assert.equal(after.withdrawals.filter(w => w.id === withdrawalId).length, 1);
  checks.push('提现响应丢失后更改金额明确报冲突，原金额重试仅冻结一次');
  await page.screenshot({ path: path.join(root, 'test-results/deep-withdrawal-retry.png'), fullPage: true });
  assert.deepEqual(errors, []); const result = { passed: true, checks, errors };
  fs.writeFileSync(path.join(root, 'test-results/deep-boundaries.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
})().catch(async e => {
  console.error(e); process.exitCode = 1;
  if (page && !page.isClosed()) { console.error((await page.locator('body').innerText()).slice(-1600)); await page.screenshot({ path: path.join(root, 'test-results/deep-boundaries-failure.png'), fullPage: true }); }
}).finally(async () => {
  await browser?.close();
  if (withdrawalId) await api('/boost-admin/withdrawals/' + withdrawalId, null, 'POST', { action: 'reject', note: '交易边界测试完成，退回测试冻结余额' });
  for (const id of orderIds) if ((await api('/boost/orders/' + id, buyer.token)).status === 'WAIT_PAY') await api('/boost/orders/' + id + '/action', buyer.token, 'POST', { action: 'cancel' });
  if (service) await api('/boost/listings/' + service.id + '/action', worker.token, 'POST', { action: 'offline' });
  await Promise.allSettled(tokens.filter(Boolean).map(t => api('/boost/auth/logout', t, 'POST')));
});
