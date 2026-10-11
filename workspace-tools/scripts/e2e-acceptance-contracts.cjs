// Non-payment contract acceptance: actual HTTP, MySQL persistence, and browser response-loss recovery.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { spawnSync } = require('node:child_process');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs');
const contract = require('./test-contract.cjs');
const root = path.resolve(__dirname, '..');
const output = path.join(process.env.QA_OUTPUT_DIR || path.join(root, 'test-results/acceptance-20261004'), 'contracts');
fs.mkdirSync(output, { recursive: true });
const startedAt = new Date().toISOString();
const stamp = Date.now();
const password = 'ContractQa2026!';
const checks = [], orders = new Set(), sessions = [], pageErrors = [], networkFailures = [], consoleErrors = [];
const requestEvidence = [], persistence = [], cleanup = { orders: [], listings: [], sessions: [], errors: [] };
let admin, buyer, worker, stranger, listing, browser, page, expectedFailure = false, fatal;

function save(name, data) { fs.writeFileSync(path.join(output, name), JSON.stringify(data, null, 2)); }
function pass(id, name, evidence) { checks.push({ id, name, passed: true, ...evidence }); console.log('PASS ' + id + ' ' + name); }
function restricted(route, method, body) {
  const p = route.split('?')[0];
  assert.ok(!/\/(?:simulated-pay|wechat-pay|progress|aftersale|review|withdraw|reconcile)(?:\/|$)/.test(p), 'Payment or paid-state endpoint is forbidden');
  assert.ok(!p.startsWith('/boost/wechat/'), 'Payment notification endpoint is forbidden');
  if (/^\/boost\/orders\/\d+\/action$/.test(p)) assert.equal(body?.action, 'cancel');
}
async function raw(route, token, method = 'GET', body) {
  restricted(route, method, body);
  const response = await fetch(env.api + route, {
    method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + admin } : {}) },
    body: body ? JSON.stringify(contract.bodyFor(route, method, body)) : undefined
  });
  const result = await response.json();
  requestEvidence.push({ method, path: route.split('?')[0], httpStatus: response.status, code: result.code });
  return result;
}
async function api(...args) { const result = await raw(...args); assert.equal(result.code, 200, args[0] + ': ' + result.msg); return result.data; }
async function register(suffix) {
  const account = await api('/boost/auth/register', null, 'POST', { username: 'qa_contract_' + suffix + '_' + stamp, nickname: '合约验收' + suffix + String(stamp).slice(-5), password });
  sessions.push({ token: account.token, memberId: account.user.id });
  return account;
}
async function moderate(type, id, action) { return api('/boost-admin/moderate/' + type + '/' + id, null, 'POST', { action, note: '非支付契约验收 ' + stamp }); }
function readDb(sql) {
  assert.match(sql, /^SELECT /, 'This helper only issues read-only SELECT statements');
  const code = "import sys,json;sys.path.insert(0,'scripts');from local_db import mysql;sql=json.load(sys.stdin);assert sql.startswith('SELECT ');lines=mysql(sql).splitlines();print(json.dumps([json.loads(line) for line in lines[1:] if line.strip()],ensure_ascii=False))";
  const result = spawnSync(process.env.QA_PYTHON || 'C:/Python314/python.exe', ['-X', 'utf8', '-c', code], { cwd: root, input: JSON.stringify(sql), encoding: 'utf8' });
  assert.equal(result.status, 0, 'Local read-only database evidence failed: ' + result.stderr);
  return JSON.parse(result.stdout);
}
function numeric(value) { assert.ok(Number.isSafeInteger(value) && value > 0); return value; }
function orderDb(id) {
  return readDb(`SELECT JSON_OBJECT('id',id,'buyerId',buyer_id,'workerId',worker_id,'listingId',listing_id,'status',status,'originalCents',original_cents,'amountCents',amount_cents,'discountCents',discount_cents,'couponId',coupon_id,'targetRank',target_rank,'paymentMode',payment_mode,'transactionId',transaction_id,'paidAt',paid_at) FROM boost_order WHERE id=${numeric(id)}`)[0];
}
function couponDb(id) { return readDb(`SELECT JSON_OBJECT('id',id,'memberId',member_id,'usedOrderId',used_order_id) FROM boost_coupon WHERE id=${numeric(id)}`)[0]; }
function noticeDb(id) { return readDb(`SELECT JSON_OBJECT('id',id,'memberId',member_id,'orderId',order_id,'isRead',is_read) FROM boost_notification WHERE id=${numeric(id)}`)[0]; }
function unpaidEvidence(label, id) {
  const row = orderDb(id);
  assert.equal(row.status, 'WAIT_PAY');
  assert.equal(row.paymentMode, null); assert.equal(row.paidAt, null); assert.equal(row.transactionId, null);
  persistence.push({ label, order: row }); return row;
}
async function nav(route) { await page.goto(env.mini + '/?contract=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' }); }
async function click(label) { await page.getByText(label, { exact: true }).last().click(); }

async function run() {
  for (const url of [env.api, env.mini]) assert.ok(['127.0.0.1', 'localhost'].includes(new URL(url).hostname), 'Only the local QA instance is allowed');
  admin = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'), 'utf8')).token;
  const runtimeConfig=await api('/boost/config');
  assert.equal(runtimeConfig.simulatedPayment, true, 'Requires a local QA database; no production execution');
  const orderButton=runtimeConfig.paymentsEnabled===false?'确认下单':'确认下单并支付';
  buyer = await register('b'); worker = await register('w'); stranger = await register('x');
  const proof = await contract.upload(worker.token, 'CERTIFICATION');
  await api('/boost/me/certification', worker.token, 'POST', { skill: '非支付测试能力证明及发布专属服务', images: [proof] });
  await moderate('members', worker.user.id, 'approve');
  assert.equal((await api('/boost/me', worker.token)).certification, 'APPROVED');
  const body = { kind: 'SERVICE', title: '非支付契约服务 ' + stamp, game: '王者荣耀', zone: '微信区', platform: '安卓', currentRank: '星耀 III', targetRank: '星耀 II', durationHours: 24, priceCents: 12000, description: '仅校验未付款下单、优惠券、通知和重试，不发生付款。', images: [] };
  listing = (await api('/boost/listings', worker.token, 'POST', body)).id;
  await moderate('listings', listing, 'approve');
  let quote = await api('/boost/listings/' + listing, buyer.token);
  const coupon = (await api('/boost/coupons', buyer.token)).items.find(c => !c.usedOrderId && c.minimumCents <= 12000);
  assert.ok(coupon, 'Fresh local QA buyer must receive the documented test coupon');
  const couponBody = { listingId: listing, requestKey: randomUUID(), quoteKey: quote.quoteKey, couponId: coupon.id, expectedAmountCents: 12000 - coupon.discountCents, note: '优惠券契约测试' };
  assert.notEqual((await raw('/boost/orders', stranger.token, 'POST', couponBody)).code, 200);
  assert.equal((await api('/boost/orders', stranger.token)).total, 0);
  assert.equal(couponDb(coupon.id).usedOrderId, null);
  const first = await api('/boost/orders', buyer.token, 'POST', couponBody); orders.add(first.id);
  const stored = unpaidEvidence('coupon locked after service order', first.id);
  assert.equal(stored.buyerId, buyer.user.id); assert.equal(stored.workerId, worker.user.id);
  assert.equal(stored.amountCents, 12000 - coupon.discountCents); assert.equal(stored.couponId, coupon.id);
  assert.equal(couponDb(coupon.id).usedOrderId, first.id);
  assert.equal((await api('/boost/orders', buyer.token, 'POST', couponBody)).id, first.id);
  assert.equal((await api('/boost/orders', buyer.token)).total, 1);
  assert.notEqual((await raw('/boost/orders', buyer.token, 'POST', { ...couponBody, requestKey: randomUUID() })).code, 200);
  assert.equal((await raw('/boost/orders/' + first.id, stranger.token)).code, 403);
  assert.equal((await api('/boost/orders?role=worker', worker.token)).items[0].id, first.id);
  pass('A27', '真实服务下单保留待付款快照；重复请求唯一，跨用户优惠券与订单访问被拒绝', { orderId: first.id });
  await api('/boost/orders/' + first.id + '/action', buyer.token, 'POST', { action: 'cancel' });
  await api('/boost/orders/' + first.id + '/action', buyer.token, 'POST', { action: 'cancel' });
  assert.equal(orderDb(first.id).status, 'CANCELLED'); assert.equal(couponDb(coupon.id).usedOrderId, null);
  const second = await api('/boost/orders', buyer.token, 'POST', { ...couponBody, requestKey: randomUUID() }); orders.add(second.id);
  unpaidEvidence('coupon reused by new order', second.id);
  await api('/boost/orders/' + first.id + '/action', buyer.token, 'POST', { action: 'cancel' });
  assert.equal(couponDb(coupon.id).usedOrderId, second.id, 'Retrying cancellation of the old order must preserve the new reservation');
  await api('/boost/orders/' + second.id + '/action', buyer.token, 'POST', { action: 'cancel' });
  assert.equal(couponDb(coupon.id).usedOrderId, null);
  pass('A31', '优惠券锁定、取消退回、重新占用与旧取消重试均在数据库保持正确', { couponId: coupon.id, orderIds: [first.id, second.id] });

  browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 390, height: 900 } });
  await context.addInitScript(token => localStorage.setItem('boost_token', token), buyer.token);
  // A test implementation error must not accidentally initiate a payment.
  await context.route('**/*', async route => {
    const p = new URL(route.request().url()).pathname;
    if (/\/(simulated-pay|wechat-pay|withdraw)(\/|$)/.test(p) || p.includes('/boost/wechat/')) {
      networkFailures.push({ path: p, error: 'Forbidden money request attempted' }); await route.abort(); return;
    }
    await route.fallback();
  });
  page = await context.newPage(); page.setDefaultTimeout(20000);
  page.on('pageerror', e => pageErrors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !expectedFailure) consoleErrors.push(m.text()); });
  page.on('requestfailed', r => { if (!expectedFailure && !String(r.failure()?.errorText).includes('ERR_ABORTED')) networkFailures.push({ path: new URL(r.url()).pathname, error: r.failure()?.errorText }); });
  await nav('account/index?type=coupons');
  await page.getByText(coupon.title, { exact: true }).waitFor(); await page.getByText('待使用', { exact: true }).waitFor();
  await page.screenshot({ path: path.join(output, 'coupon-readonly.png'), fullPage: true });
  await nav('checkout/index?id=' + listing);
  await page.locator('.fixed-footer .price').filter({ hasText: '120' }).waitFor();
  body.priceCents = 24000; await api('/boost/listings/' + listing, worker.token, 'PUT', body); await moderate('listings', listing, 'approve');
  await page.locator('.check-box').click();
  const staleResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/orders'));
  // Keep the waiter rejection observable when awaited, while allowing finally
  // to clean owned data if the preceding UI action fails first.
  staleResponse.catch(()=>{});
  expectedFailure = true; await click(orderButton); const stale = await (await staleResponse).json();
  if (stale.code === 200) orders.add(stale.data.id);
  assert.equal(stale.code, 409);
  await page.getByText('服务内容或价格已更新，请重新确认后下单', { exact: true }).waitFor();
  await page.locator('.fixed-footer .price').filter({ hasText: '240' }).waitFor();
  assert.equal(await page.locator('.check-box.checked').count(), 0);
  assert.equal((await api('/boost/orders', buyer.token)).total, 2);
  expectedFailure = false;
  await click(orderButton); await page.getByText('请先确认服务范围并同意交易规则', { exact: true }).waitFor();
  pass('A28a', '旧页面120元报价被拒绝；无新订单，240元报价必须重新确认');

  let loseCreate = true, createdId; const createBodies = [];
  await page.route('**/api/boost/orders', async route => {
    if (route.request().method() !== 'POST') return route.fallback();
    createBodies.push(route.request().postDataJSON());
    if (loseCreate) {
      loseCreate = false; const result = await (await route.fetch()).json();
      assert.equal(result.code, 200); createdId = result.data.id; orders.add(createdId);
      await route.abort('failed'); return;
    }
    await route.fallback();
  });
  expectedFailure = true;
  await page.locator('.check-box').click(); await click(orderButton);
  await page.getByText('连接失败，请检查网络后重试', { exact: true }).waitFor();
  assert.ok(createdId); const persisted = unpaidEvidence('created order persisted before browser response loss', createdId);
  assert.equal(persisted.amountCents, 24000);
  body.priceCents = 36000; body.targetRank = '荣耀王者';
  await api('/boost/listings/' + listing, worker.token, 'PUT', body); await moderate('listings', listing, 'approve');
  await click('交易规则'); await page.getByText('用户协议与隐私说明', { exact: true }).first().waitFor(); await page.locator('[aria-label="返回"]').click();
  await page.locator('.fixed-footer .price').filter({ hasText: '360' }).waitFor();
  assert.equal(await page.locator('.check-box.checked').count(), 0);
  expectedFailure = false;
  await page.locator('.check-box').click(); await click(orderButton);
  await page.getByText(runtimeConfig.paymentsEnabled===false?'支付暂未开放，可查看或取消订单':'付款后，打手即可开始服务', { exact: true }).waitFor();
  await page.getByText('星耀 III → 星耀 II', { exact: true }).waitFor();
  assert.equal(await page.locator('.uni-modal').count(), 0, 'Recovery must show the existing order before any payment dialog');
  assert.equal(createBodies.length, 2); assert.equal(createBodies[0].requestKey, createBodies[1].requestKey);
  assert.equal((await api('/boost/orders', buyer.token)).total, 3);
  assert.equal(unpaidEvidence('recovered original snapshot', createdId).amountCents, 24000);
  await page.reload({ waitUntil: 'domcontentloaded' }); await page.getByText('星耀 III → 星耀 II', { exact: true }).waitFor();
  await page.screenshot({ path: path.join(output, 'recovered-unpaid-order.png'), fullPage: true });
  pass('A28b', '创建成功响应丢失后恢复原240元订单及原目标，刷新保留且不自动付款', { orderId: createdId, sameRequestKey: true });
  let loseCancel = true;
  await page.route('**/api/boost/orders/' + createdId + '/action', async route => {
    if (loseCancel) { loseCancel = false; assert.equal((await (await route.fetch()).json()).code, 200); await route.abort('failed'); return; }
    await route.fallback();
  });
  expectedFailure = true;
  await click('取消未付款订单'); await page.locator('.uni-modal__btn_primary').click();
  await page.locator('.field-error').filter({ hasText: '连接失败' }).waitFor();
  assert.equal(orderDb(createdId).status, 'CANCELLED');
  await click('取消未付款订单'); await page.locator('.uni-modal__btn_primary').click();
  await page.getByText('订单已关闭', { exact: true }).waitFor();
  await page.getByText('连接失败，请检查网络后重试', { exact: true }).waitFor({ state: 'hidden' });
  expectedFailure = false;
  await page.screenshot({ path: path.join(output, 'cancel-recovered.png'), fullPage: true });
  pass('A28c', '取消已落库但响应丢失，重试恢复关闭状态且仍未付款', { orderId: createdId });

  const notifications = await api('/boost/notifications', buyer.token);
  const selected = notifications.items.find(n => n.orderId === createdId && !n.isRead);
  const otherUnread = notifications.items.filter(n => n.id !== selected?.id && !n.isRead);
  assert.ok(selected); assert.ok(otherUnread.length >= 1);
  const forbidden = await raw('/boost/notifications/' + selected.id + '/read', stranger.token, 'POST');
  assert.ok([400, 403, 404].includes(forbidden.code)); assert.equal(Number(noticeDb(selected.id).isRead), 0);
  await nav('messages/index'); await click('订单与平台通知');
  const selectedRow = page.locator('.notification-item').first();
  assert.equal(notifications.items[0].id, selected.id, 'Newest cancellation notice must be first');
  await selectedRow.click(); await page.getByText('订单已关闭', { exact: true }).waitFor();
  assert.equal(Number(noticeDb(selected.id).isRead), 1);
  for (const n of otherUnread) assert.equal(Number(noticeDb(n.id).isRead), 0, 'Reading one notice must preserve other unread notices');
  const afterRead = await api('/boost/notifications', buyer.token); assert.equal(afterRead.unread, notifications.unread - 1);
  await api('/boost/notifications/' + selected.id + '/read', buyer.token, 'POST');
  assert.equal((await api('/boost/notifications', buyer.token)).unread, afterRead.unread);
  await nav('messages/index'); await click('订单与平台通知');
  assert.equal((await page.locator('.notification-item').first().innerText()).includes('●'), false);
  await page.screenshot({ path: path.join(output, 'single-notice-read.png'), fullPage: true });
  pass('A26', '实际点击单条通知持久化已读；越权拒绝、其他未读不变、重试幂等', { noticeId: selected.id, preservedUnreadIds: otherUnread.map(n => n.id) });
  const money = readDb(`SELECT JSON_OBJECT('memberId',m.id,'balanceCents',m.balance_cents,'ledgerRows',(SELECT COUNT(*) FROM boost_ledger l WHERE l.member_id=m.id),'paidOrders',(SELECT COUNT(*) FROM boost_order o WHERE (o.buyer_id=m.id OR o.worker_id=m.id) AND o.paid_at IS NOT NULL)) FROM boost_member m WHERE m.id IN (${[buyer,worker,stranger].map(a => numeric(a.user.id)).join(',')}) ORDER BY m.id`);
  for (const row of money) { assert.equal(row.balanceCents, 0); assert.equal(row.ledgerRows, 0); assert.equal(row.paidOrders, 0); }
  persistence.push({ label: 'new QA accounts remained without funds or payments', members: money });
  assert.deepEqual(pageErrors, []); assert.deepEqual(networkFailures, []); assert.deepEqual(consoleErrors, []);
}

async function finish() {
  await browser?.close();
  for (const id of orders) try {
    const row = await api('/boost/orders/' + id, buyer.token);
    assert.equal(row.buyerId, buyer.user.id); assert.equal(row.listingId, listing);
    if (row.status === 'WAIT_PAY') await api('/boost/orders/' + id + '/action', buyer.token, 'POST', { action: 'cancel' });
    assert.equal(orderDb(id).status, 'CANCELLED'); cleanup.orders.push({ id, status: 'CANCELLED' });
  } catch (e) { cleanup.errors.push('order ' + id + ': ' + e.message); }
  if (listing) try {
    const row = await api('/boost/listings/' + listing, worker.token);
    assert.equal(row.ownerId, worker.user.id);
    if (['ACTIVE', 'PENDING'].includes(row.status)) await api('/boost/listings/' + listing + '/action', worker.token, 'POST', { action: 'offline' });
    assert.equal((await api('/boost/listings/' + listing, worker.token)).status, 'OFFLINE'); cleanup.listings.push({ id: listing, status: 'OFFLINE' });
  } catch (e) { cleanup.errors.push('listing ' + listing + ': ' + e.message); }
  for (const session of sessions) try {
    await api('/boost/auth/logout', session.token, 'POST'); assert.equal((await raw('/boost/me', session.token)).code, 401);
    cleanup.sessions.push({ memberId: session.memberId, revoked: true });
  } catch (e) { cleanup.errors.push('session ' + session.memberId + ': ' + e.message); }
  if (cleanup.errors.length) { console.error('Cleanup failed: ' + cleanup.errors.join('; ')); process.exitCode = 1; }
  const result = { passed: !fatal && cleanup.errors.length === 0, startedAt, finishedAt: new Date().toISOString(), environment: { ...env, realLocalHttpAndMysql: true, browser: 'Chromium H5', paymentRequestsAllowed: false }, checks, pageErrors, networkFailures, consoleErrors, failure: fatal?.message, cleanup };
  save('results.json', result); save('persistence.json', persistence); save('requests.json', requestEvidence);
  console.log(JSON.stringify(result, null, 2));
}
run().catch(async e => {
  fatal = e; process.exitCode = 1; console.error(e.stack);
  if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
}).finally(finish);
