// Exercise draft visibility through real HTTP requests and buyer-facing pages.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs'), root = path.resolve(__dirname, '..');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'))).token;
const stamp = Date.now(), password = 'VisibilityQa2026!', checks = [], errors = [], tokens = [];
let browser, page, worker, service;
async function raw(route, token, method = 'GET', body) { body=require('./test-contract.cjs').bodyFor(route,method,body);
  const response = await fetch(env.api + route, {
    method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + adminToken } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  return response.json();
}
async function api(...args) { const r = await raw(...args); assert.equal(r.code, 200, args[0] + ': ' + r.msg); return r.data; }
async function approve() { await api('/boost-admin/moderate/listings/' + service.id, null, 'POST', { action: 'approve', note: '可见范围回归测试审核' }); }
(async () => {
  assert.equal((await api('/boost/config')).simulatedPayment, true);
  const buyer = await api('/boost/auth/register', null, 'POST', { username: 'qa_vis_b_' + stamp, password, nickname: '可见范围买家' });
  const outsider = await api('/boost/auth/register', null, 'POST', { username: 'qa_vis_o_' + stamp, password, nickname: '可见范围旁观者' });
  worker = await api('/boost/auth/login', null, 'POST', { username: 'demo_worker', password: 'Demo2026!' });
  tokens.push(buyer.token, outsider.token, worker.token);
  const body = { kind: 'SERVICE', title: '可见范围回归服务 ' + stamp, game: '王者荣耀', zone: '微信区', platform: '安卓', currentRank: '星耀 III', targetRank: '星耀 II', durationHours: 24, priceCents: 12000, description: '本地回归服务，检查未审核修改的可见范围。', images: [] };
  service = await api('/boost/listings', worker.token, 'POST', body);
  const unrelated = await api('/boost/conversations', outsider.token, 'POST', { peerId: buyer.user.id, listingId: service.id });
  assert.equal((await api('/boost/conversations/' + unrelated.id, outsider.token)).listing ?? null, null);
  assert.equal((await raw('/boost/listings/' + service.id + '/favorite', outsider.token, 'POST')).code, 404);
  checks.push('无关账号不能通过聊天或收藏引用待审核发布');
  await approve();
  const conversation = await api('/boost/conversations', buyer.token, 'POST', { peerId: worker.user.id, listingId: service.id });
  browser = await chromium.launch();
  page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  page.setDefaultTimeout(15000);
  page.on('pageerror', e => errors.push(e.message));
  async function nav(route) { await page.goto(env.mini + '/?visibility=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' }); }
  await nav('auth/index');
  await page.locator('.text-input input').nth(0).fill(buyer.user.username);
  await page.locator('.text-input input').nth(1).fill(password);
  await page.locator('.check-box').click();
  await page.getByText('登录', { exact: true }).click();
  await page.getByText('找高手，上分更轻松', { exact: true }).waitFor();
  tokens.push(await page.evaluate(() => localStorage.getItem('boost_token')));
  await nav('service/detail?id=' + service.id);
  // H5 uni-button is a custom element: Playwright does not wait for its disabled prop.
  await page.getByText(body.title, { exact: true }).waitFor();
  const favoriteResponse = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/boost/listings/' + service.id + '/favorite'));
  await page.locator('.favorite-btn:not([disabled])').click();
  const favorited = await (await favoriteResponse).json();
  assert.equal(favorited.code, 200); assert.equal(favorited.data, true);
  await page.getByText('已收藏', { exact: true }).waitFor();
  assert.equal((await api('/boost/listings/' + service.id, buyer.token)).favorited, true);
  await nav('chat/index?id=' + conversation.id);
  await page.getByText(body.title, { exact: true }).waitFor();
  checks.push('已审核服务正常显示、收藏并出现在聊天中');
  body.title = '未审核私有修改 ' + stamp;
  await api('/boost/listings/' + service.id, worker.token, 'PUT', body);
  await page.getByText('查看服务', { exact: true }).waitFor({ state: 'hidden' });
  assert.equal(await page.getByText(body.title, { exact: true }).count(), 0);
  assert.equal((await api('/boost/conversations/' + conversation.id, worker.token)).listing.title, body.title);
  await page.locator('.chat-input input').fill('服务重审期间仍然可以沟通');
  await page.getByText('发送', { exact: true }).click();
  await page.getByText('服务重审期间仍然可以沟通', { exact: true }).waitFor();
  checks.push('重审时聊天自动隐藏预览，发布者可查看，双方仍可发送消息');
  for (const type of ['favorites', 'history']) {
    await nav('account/index?type=' + type);
    await page.getByText(type === 'favorites' ? '还没有收藏的服务' : '还没有浏览记录', { exact: true }).waitFor();
    assert.equal(await page.getByText(body.title, { exact: true }).count(), 0);
    assert.equal((await api('/boost/me', buyer.token))[type], 0);
  }
  checks.push('收藏、浏览记录及数量不泄露待审核内容');
  await approve();
  await nav('account/index?type=favorites');
  await page.getByText(body.title, { exact: true }).waitFor();
  assert.equal((await api('/boost/me', buyer.token)).favorites, 1);
  checks.push('重新审核通过后收藏自动恢复，无须重复收藏');
  assert.deepEqual(errors, []);
  await page.screenshot({ path: require('./test-output.cjs').file('recheck-visibility.png'), fullPage: true });
  const result = { passed: true, checks, errors, serviceId: service.id };
  fs.writeFileSync(require('./test-output.cjs').file('recheck-visibility.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
})().catch(async e => {
  console.error(e); process.exitCode = 1;
  if (page) {
    console.error((await page.locator('body').innerText()).slice(-2000));
    await page.screenshot({ path: require('./test-output.cjs').file('recheck-visibility-failure.png'), fullPage: true });
  }
}).finally(async () => {
  await browser?.close();
  if (service) await api('/boost/listings/' + service.id + '/action', worker.token, 'POST', { action: 'offline' }).catch(e => { console.error('QA service cleanup failed:', e.message); process.exitCode = 1; });
  await Promise.allSettled(tokens.filter(Boolean).map(token => api('/boost/auth/logout', token, 'POST')));
});
