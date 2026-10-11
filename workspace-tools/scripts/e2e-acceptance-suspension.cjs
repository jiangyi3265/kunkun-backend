// Real local HTTP/MySQL account-suspension regression. No payment, refund or settlement calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const env = require('./test-env.cjs');
const contract = require('./test-contract.cjs');
const out = require('./test-output.cjs').dir('acceptance-20261004/suspension');
const root = path.resolve(__dirname, '..');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'))).token;
const password = 'SuspensionHttp2026!', stamp = Date.now();
const accounts = [], tokens = [], orders = [], listings = [], checks = [], traffic = [], cleanupErrors = [];
let buyer, worker, observer, failure;

async function raw(route, token, method = 'GET', body) {
  const response = await fetch(env.api + route, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + adminToken } : {}) },
    body: body ? JSON.stringify(contract.bodyFor(route, method, body)) : undefined
  });
  const result = await response.json();
  traffic.push({ method, path: route.split('?')[0], httpStatus: response.status, code: result.code });
  return result;
}
async function api(...args) {
  const result = await raw(...args); assert.equal(result.code, 200, args[0] + ': ' + result.msg); return result.data;
}
async function register(role) {
  const username = 'qa_suspend_' + role + '_' + stamp;
  const account = await api('/boost/auth/register', null, 'POST', { username, password, nickname: '停用验收' + role });
  accounts.push(account); tokens.push(account.token); return account;
}
async function login(account) {
  const session = await api('/boost/auth/login', null, 'POST', { username: account.user.username, password });
  assert.equal(session.user.id, account.user.id); account.token = session.token; tokens.push(session.token); return session;
}
async function moderate(type, id, action) { return api('/boost-admin/moderate/' + type + '/' + id, null, 'POST', { action, note: '停用验收：' + action }); }
async function listRecord(id) {
  const result = await api('/boost-admin/list/listings?keyword=' + encodeURIComponent('停用验收需求 ' + stamp));
  const record = result.items.find(row => Number(row.id) === id); assert.ok(record, 'Missing listing ' + id); return record;
}
async function request(approve) {
  const result = await api('/boost/listings', buyer.token, 'POST', { requestKey: randomUUID(), kind: 'REQUEST', title: '停用验收需求 ' + stamp + ' ' + listings.length, game: '王者荣耀', zone: '微信区', platform: '安卓', targetRank: '王者', durationHours: 24, priceCents: 12000, description: '仅验证账号停用后的非支付请求与持久化状态', images: [] });
  listings.push(result.id); if (approve) await moderate('listings', result.id, 'approve'); return result.id;
}
async function accept(id) {
  const detail = await api('/boost/listings/' + id, worker.token);
  const result = await api('/boost/orders/accept', worker.token, 'POST', { listingId: id, requestKey: randomUUID(), quoteKey: detail.quoteKey });
  orders.push(result.id); assert.equal(result.status, 'WAIT_PAY'); return result.id;
}

(async () => {
  assert.ok(['127.0.0.1', 'localhost'].includes(new URL(env.api).hostname));
  const config = await api('/boost/config'); assert.equal(config.simulatedPayment, true); assert.equal(config.brand, '困困');
  buyer = await register('b'); worker = await register('w'); observer = await register('o');
  assert.ok((await api('/boost/notifications', buyer.token)).items.some(row => row.title === '欢迎来到困困'));
  checks.push('公开配置与新注册欢迎通知使用困困品牌');
  const proof = await contract.upload(worker.token, 'CERTIFICATION');
  await api('/boost/me/certification', worker.token, 'POST', { skill: '停用验收专用认证材料', images: [proof] });
  await moderate('members', worker.user.id, 'approve');
  assert.equal((await api('/boost/me', worker.token)).certification, 'APPROVED');

  await api('/boost/shops/' + worker.user.id + '/follow', buyer.token, 'POST', { desired: true });
  await moderate('members', worker.user.id, 'block');
  assert.equal((await raw('/boost/me', worker.token)).code, 401);
  assert.equal((await raw('/boost/shops/' + worker.user.id, buyer.token)).code, 404);
  assert.equal((await api('/boost/follows', buyer.token)).total, 0);
  assert.equal((await api('/boost/me', buyer.token)).follows, 0);
  assert.equal((await raw('/boost/shops/' + worker.user.id + '/follow', observer.token, 'POST', { desired: true })).code, 404);
  assert.equal((await api('/boost/follows', observer.token)).total, 0);
  await moderate('members', worker.user.id, 'unblock');
  await login(worker); await login(buyer);
  assert.equal((await api('/boost/follows', buyer.token)).total, 1);
  assert.equal((await api('/boost/me', buyer.token)).follows, 1);
  assert.equal((await api('/boost/shops/' + worker.user.id, buyer.token)).followed, true);
  checks.push('停用店铺在列表和个人关注数中同时隐藏；拒绝新增关注；解封及重新登录后原关系恢复');

  await moderate('members', worker.user.id, 'block');
  for (let i = 0; i < 2; i++) assert.equal(await api('/boost/shops/' + worker.user.id + '/follow', buyer.token, 'POST', { desired: false }), false);
  await moderate('members', worker.user.id, 'unblock'); await login(worker);
  assert.equal((await api('/boost/follows', buyer.token)).total, 0);
  assert.equal((await api('/boost/me', buyer.token)).follows, 0);
  checks.push('已有关注可在对方停用后安全重复解除，解封不会恢复已解除关系');

  const listing = await request(true), order = await accept(listing);
  assert.equal((await listRecord(listing)).status, 'RESERVED');
  await moderate('members', buyer.user.id, 'block');
  assert.equal((await raw('/boost/me', buyer.token)).code, 401);
  for (let i = 0; i < 2; i++) await api('/boost/orders/' + order + '/action', worker.token, 'POST', { action: 'cancel' });
  assert.equal((await api('/boost/orders/' + order, worker.token)).status, 'CANCELLED');
  assert.equal((await listRecord(listing)).status, 'OFFLINE');
  const blockedAccept = await raw('/boost/orders/accept', worker.token, 'POST', { listingId: listing, requestKey: randomUUID() });
  assert.equal(blockedAccept.code, 400); assert.equal(blockedAccept.data, undefined);
  await moderate('members', buyer.user.id, 'unblock'); await login(buyer);
  assert.equal((await api('/boost/listings/mine', buyer.token)).items.find(row => Number(row.id) === listing).status, 'OFFLINE');
  assert.equal((await raw('/boost/listings/' + listing)).code, 400);
  assert.equal((await api('/boost/orders?status=CANCELLED', buyer.token)).items.filter(row => Number(row.listingId) === listing).length, 1);
  checks.push('MySQL 上停用需求买家后取消未付款预留：订单持久化为CANCELLED、发布为OFFLINE；拒绝再次接单，解封重新登录也不自动上架');
  await api('/boost/listings/' + listing + '/action', buyer.token, 'POST', { action: 'resubmit' });
  await moderate('listings', listing, 'approve');
  const nextOrder = await accept(listing); assert.notEqual(nextOrder, order);
  await api('/boost/orders/' + nextOrder + '/action', worker.token, 'POST', { action: 'cancel' });
  assert.equal((await listRecord(listing)).status, 'ACTIVE');
  checks.push('恢复账号后重新提交并审核可再接单；正常账号取消需求仍恢复ACTIVE，未引入正常路径回归');

  const pending = await request(false); await moderate('members', buyer.user.id, 'block');
  const deniedReview = await raw('/boost-admin/moderate/listings/' + pending, null, 'POST', { action: 'approve', note: '验证停用用户需求不能上架' });
  assert.equal(deniedReview.code, 400); assert.match(deniedReview.msg, /停用/);
  assert.equal((await listRecord(pending)).status, 'PENDING');
  assert.equal((await raw('/boost/orders/accept', worker.token, 'POST', { listingId: pending, requestKey: randomUUID() })).code, 400);
  await moderate('members', buyer.user.id, 'unblock'); await login(buyer);
  for (const account of accounts) {
    const wallet = await api('/boost/wallet', account.token); assert.equal(wallet.balanceCents, 0); assert.equal(wallet.ledger.length, 0);
  }
  checks.push('停用用户的待审需求审批被拒绝且保持PENDING，无订单或资金写入；三个专用账号钱包及流水均为零');
})().catch(error => { failure = error.message; console.error(error.stack); }).finally(async () => {
  for (const account of accounts) {
    try { await moderate('members', account.user.id, 'unblock'); await login(account); }
    catch (error) { cleanupErrors.push('恢复本次QA账号 ' + account.user.id + ': ' + error.message); }
  }
  if (worker) for (const id of orders) {
    try {
      const order = await api('/boost/orders/' + id, worker.token);
      if (order.status === 'WAIT_PAY') await api('/boost/orders/' + id + '/action', worker.token, 'POST', { action: 'cancel' });
      assert.equal((await api('/boost/orders/' + id, worker.token)).status, 'CANCELLED');
    } catch (error) { cleanupErrors.push('清理未付款订单 ' + id + ': ' + error.message); }
  }
  if (buyer) for (const id of listings) {
    try {
      const listing = await listRecord(id);
      if (['ACTIVE', 'PENDING'].includes(listing.status)) await api('/boost/listings/' + id + '/action', buyer.token, 'POST', { action: 'offline' });
      assert.equal((await listRecord(id)).status, 'OFFLINE');
    } catch (error) { cleanupErrors.push('下架本次发布 ' + id + ': ' + error.message); }
  }
  for (const token of tokens) {
    try { await api('/boost/auth/logout', token, 'POST'); assert.equal((await raw('/boost/me', token)).code, 401); }
    catch (error) { cleanupErrors.push('撤销本次QA会话失败: ' + error.message); }
  }
  const passed = !failure && checks.length === 6 && cleanupErrors.length === 0;
  const report = { passed, completedAt: new Date().toISOString(), mode: '真实本地HTTP和MySQL持久化；无支付调用；无真机验证', checks, failure, traffic, fixtureIds: { members: accounts.map(account => account.user.id), orders, listings }, cleanup: { passed: cleanupErrors.length === 0, errors: cleanupErrors, retained: '保留本次QA账号、已取消订单、已下架发布与审计记录，所有本次会话撤销' } };
  fs.writeFileSync(path.join(out, 'suspension-http.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ passed, checks, failure, cleanupErrors }, null, 2)); process.exitCode = passed ? 0 : 1;
});
