// Supplemental non-payment acceptance: real page actions and real local persisted records.
require('./nonpayment-request-guard.cjs');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs'), contract = require('./test-contract.cjs');
const root = path.resolve(__dirname, '..'), out = process.env.QA_OUTPUT_DIR || path.join(root, 'test-results/acceptance-20261004/navigation');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'), 'utf8')).token;
const stamp = Date.now(), password = 'NavigationQa2026!', tokens = [], checks = [], pageErrors = [], consoleErrors = [], transportErrors = [], cleanupErrors = [];
const requestedCases = process.env.QA_NAV_CASES ? new Set(process.env.QA_NAV_CASES.split(',')) : null;
if (requestedCases) assert.ok([...requestedCases].every(id => ['A02', 'A25', 'A40'].includes(id)), 'Unknown targeted navigation check');
let browser, member, peer, listing, release;
fs.mkdirSync(out, { recursive: true });
async function raw(route, token, method = 'GET', body) {
  return (await fetch(env.api + route, { method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + adminToken } : {}) }, body: body ? JSON.stringify(contract.bodyFor(route, method, body)) : undefined })).json();
}
async function api(...args) { const r = await raw(...args); assert.equal(r.code, 200, args[0] + ': ' + r.msg); return r.data; }
async function nav(page, route) { await page.goto(env.mini + '/?acceptanceNavigation=' + Date.now() + '#/pages/' + route, { waitUntil: 'domcontentloaded' }); }
async function login(page) {
  await nav(page, 'auth/index'); await page.locator('.text-input input').nth(0).fill(member.user.username); await page.locator('.text-input input').nth(1).fill(password); await page.locator('.check-box').click();
  const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/boost/auth/login'));
  await page.getByText('登录', { exact: true }).click(); const result = await (await response).json(); assert.equal(result.code, 200); tokens.push(result.data.token);
  await page.getByText('找高手，上分更轻松', { exact: true }).waitFor();
}
function observe(page, id) {
  page.setDefaultTimeout(12000);
  // Development entry modules may compile on first navigation; action assertions stay at 12 seconds.
  page.setDefaultNavigationTimeout(30000);
  page.on('pageerror', e => pageErrors.push({ id, message: e.message }));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push({ id, message: message.text().replace(/https?:\/\/[^\s]+/g, '[URL]') }); });
  page.on('requestfailed', request => { if (!String(request.failure()?.errorText).includes('ERR_ABORTED')) transportErrors.push({ id, method: request.method(), path: new URL(request.url()).pathname, error: request.failure()?.errorText }); });
}
async function check(id, name, run) {
  if (requestedCases && !requestedCases.has(id)) return;
  const context = await browser.newContext({ viewport: { width: 390, height: 900 } }); const page = await context.newPage(); observe(page, id);
  try { const evidence = await run(page); checks.push({ id, name, status: '已实测通过', evidence }); console.log('PASS ' + id + ' ' + name); }
  catch (e) { checks.push({ id, name, status: '失败', error: e.message }); console.error('FAIL ' + id + ': ' + e.message); }
  finally { release?.(); release = null; await page.screenshot({ path: path.join(out, id + '.png'), fullPage: true }).catch(() => {}); await context.close(); }
}
async function moderateWithUI(page, title, action, note) {
  await page.goto(env.admin + '/boost/listings', { waitUntil: 'domcontentloaded' });
  await page.locator('.filters input').first().fill(title); await page.getByRole('button', { name: '查询', exact: true }).click();
  const row = page.locator('.el-table__row').filter({ hasText: title }); await row.getByRole('button', { name: '查看 / 处理', exact: true }).click();
  const drawer = page.locator('.el-drawer[aria-label]'); await drawer.locator('textarea').fill(note); await drawer.getByRole('button', { name: action, exact: true }).click();
  await page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click(); await drawer.waitFor({ state: 'hidden' });
}
(async () => {
  assert.ok(['localhost', '127.0.0.1'].includes(new URL(env.api).hostname), 'Only the local QA environment is allowed');
  browser = await chromium.launch();
  await check('A02', '首页轮播、游戏分类和快捷入口实际跳转并带入正确筛选', async page => {
    const entries = [];
    async function home() { await nav(page, 'index/index'); await page.getByText('找高手，上分更轻松', { exact: true }).waitFor(); }
    async function marketResult(expectedGame) {
      await page.getByText('代练市场', { exact: true }).waitFor(); assert.match(page.url(), /#\/pages\/market\/index/);
      await page.locator('.categories .chosen').filter({ hasText: expectedGame }).waitFor();
    }
    await home(); await page.getByText('去看看', { exact: true }).click(); await marketResult('全部'); entries.push('轮播：去看看 → 市场/全部');
    await home(); await page.getByText('去接单', { exact: true }).click({ timeout: 15000 }); await page.getByText('接单大厅', { exact: true }).waitFor(); assert.match(page.url(), /#\/pages\/hall\/index/); entries.push('轮播：去接单 → 接单大厅');
    for (const game of ['王者荣耀', '和平精英', '原神']) {
      await home();
      const [response] = await Promise.all([
        page.waitForResponse(r => new URL(r.url()).pathname === '/api/boost/listings' && new URL(r.url()).searchParams.get('game') === game),
        page.locator('.shortcuts uni-button').filter({ hasText: game }).click()
      ]); await marketResult(game);
      const result = await response.json(); assert.equal(result.code, 200); assert.ok(result.data.items.every(item => item.game === game)); entries.push(game + ' → 市场，请求参数和选中分类一致');
    }
    await home(); await page.locator('.shortcuts uni-button').filter({ hasText: '接单大厅' }).click(); await page.getByText('接单大厅', { exact: true }).waitFor(); entries.push('接单大厅快捷入口');
    await home(); await page.locator('.home-head .search').click(); await marketResult('全部'); entries.push('首页搜索入口 → 市场');
    await home(); await page.locator('.section-more').getByText('查看更多', { exact: true }).click(); await marketResult('全部'); entries.push('人气推荐查看更多 → 市场');
    return entries;
  });
  member = await api('/boost/auth/register', null, 'POST', { username: 'qa_nav_m_' + stamp, password, nickname: '导航验收发布者' + String(stamp).slice(-5) });
  peer = await api('/boost/auth/register', null, 'POST', { username: 'qa_nav_p_' + stamp, password, nickname: '导航验收联系人' + String(stamp).slice(-5) }); tokens.push(member.token, peer.token);
  await check('A25', '聊天加载中及轮询建立后离页，均停止后续轮询', async page => {
    const conversation = await api('/boost/conversations', member.token, 'POST', { peerId: peer.user.id });
    await api('/boost/conversations/' + conversation.id + '/messages', peer.token, 'POST', { kind: 'TEXT', content: '离页轮询验收真实消息' });
    await login(page); await nav(page, 'messages/index'); await page.getByText(peer.user.nickname, { exact: true }).waitFor();
    let detailRequests = 0, started, held = false;
    page.on('request', r => { if (r.method() === 'GET' && new URL(r.url()).pathname === '/api/boost/conversations/' + conversation.id) detailRequests++; });
    const pending = new Promise(resolve => { started = resolve; }), gate = new Promise(resolve => { release = resolve; });
    await page.route('**/api/boost/me', async route => { if (!held) { held = true; started(); await gate; } await route.continue(); });
    await page.getByText(peer.user.nickname, { exact: true }).click();
    let timer; try { await Promise.race([pending, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('聊天身份请求未到达')), 10000); })]); } finally { clearTimeout(timer); }
    await page.locator('[aria-label="返回"]').last().click(); await page.getByText('订单与平台通知', { exact: true }).waitFor(); const pendingLeaveCount = detailRequests;
    release(); release = null; await page.waitForTimeout(5600); assert.equal(detailRequests, pendingLeaveCount, '加载期间离页后仍发起聊天详情轮询'); await page.unroute('**/api/boost/me');
    await page.getByText(peer.user.nickname, { exact: true }).click(); await page.getByText('离页轮询验收真实消息', { exact: true }).waitFor(); const connectedCount = detailRequests;
    await page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/boost/conversations/' + conversation.id, { timeout: 6500 }); assert.ok(detailRequests > connectedCount, '应先证明连接中确实存在轮询');
    await page.locator('[aria-label="返回"]').last().click(); await page.getByText('订单与平台通知', { exact: true }).waitFor(); const connectedLeaveCount = detailRequests;
    await page.waitForTimeout(5600); assert.equal(detailRequests, connectedLeaveCount, '已建立轮询的聊天页离开后仍继续请求');
    const saved = await api('/boost/conversations/' + conversation.id, member.token); assert.equal(saved.items.filter(item => item.content === '离页轮询验收真实消息').length, 1);
    return { conversationId: conversation.id, pendingLeaveCount, connectedCount, connectedLeaveCount, afterLeaveCount: detailRequests, delayInjection: '仅暂停真实GET/me请求，不替换响应内容', persistedMessages: 1 };
  });
  await check('A40', '发布驳回说明可见，编辑后重审并跨端恢复公开可见', async page => {
    const title = '驳回重审验收需求 ' + stamp, rejectedNote = '验收驳回说明：请补充具体目标与可开始时间';
    listing = await api('/boost/listings', member.token, 'POST', { kind: 'REQUEST', title, game: '王者荣耀', zone: '微信区', platform: '安卓', targetRank: '王者', currentRank: '星耀', durationHours: 24, priceCents: 12000, description: '验收初始需求，验证说明与编辑重审闭环', images: [] });
    const adminContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } }); await adminContext.addCookies([{ name: 'Admin-Token', value: adminToken, domain: new URL(env.admin).hostname, path: '/' }]); const adminPage = await adminContext.newPage(); observe(adminPage, 'A40-admin');
    try {
      await moderateWithUI(adminPage, title, '不通过', rejectedNote); assert.equal((await api('/boost/listings/' + listing.id, member.token)).status, 'REJECTED');
      await login(page); await nav(page, 'manage/index?kind=REQUEST'); const card = page.locator('.card').filter({ hasText: title }); await card.getByText('未通过', { exact: true }).waitFor(); await card.getByText('审核说明：' + rejectedNote, { exact: true }).waitFor();
      await page.screenshot({ path: path.join(out, 'A40-rejected.png'), fullPage: true });
      await card.getByText('编辑', { exact: true }).click(); await page.waitForFunction(value => document.querySelector('.text-input input')?.value === value, title);
      const revisedTitle = '重审后完整验收需求 ' + stamp, description = '已补充：今晚八点开始，当前星耀三，目标最强王者，24小时内完成。';
      await page.locator('.text-input input').fill(revisedTitle); await page.locator('.text-input input').press('Tab'); await page.locator('.textarea textarea').fill(description); await page.locator('.textarea textarea').press('Tab'); await page.locator('.check-box').click();
      const response = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname === '/api/boost/listings/' + listing.id);
      await page.getByText('保存并重新审核', { exact: true }).click(); const submitted = await response; assert.equal(submitted.request().postDataJSON().description, description); assert.equal((await submitted.json()).code, 200);
      await page.getByText('发布管理', { exact: true }).waitFor(); await page.locator('.card').filter({ hasText: revisedTitle }).getByText('审核中', { exact: true }).waitFor();
      await page.reload(); await page.locator('.card').filter({ hasText: revisedTitle }).getByText('审核中', { exact: true }).waitFor();
      const pending = await api('/boost/listings/' + listing.id, member.token); assert.equal(pending.status, 'PENDING'); assert.equal(pending.reviewNote, ''); assert.equal(pending.description, description); assert.equal(pending.ownerId, member.user.id);
      assert.notEqual((await raw('/boost/listings/' + listing.id)).code, 200, '待审核内容不能公开');
      await moderateWithUI(adminPage, revisedTitle, '审核通过', '验收通过：目标和时间已补充完整');
      const published = await api('/boost/listings/' + listing.id); assert.equal(published.status, 'ACTIVE'); assert.equal(published.description, description);
      await page.reload(); await page.locator('.card').filter({ hasText: revisedTitle }).getByText('上架中', { exact: true }).waitFor();
      await adminPage.screenshot({ path: path.join(out, 'A40-admin-approved.png'), fullPage: true });
      return { listingId: listing.id, ownerId: member.user.id, states: ['PENDING', 'REJECTED', 'PENDING', 'ACTIVE'], revisedTitle, persistedDescription: description, anonymousReadAfterApproval: true };
    } finally { await adminContext.close(); }
  });
})().catch(e => { console.error(e.stack); checks.push({ id: 'SETUP', status: '失败', error: e.message }); }).finally(async () => {
  release?.(); await browser?.close();
  if (listing) try { const own = await api('/boost/listings/' + listing.id, member.token); if (['PENDING', 'ACTIVE'].includes(own.status)) await api('/boost/listings/' + listing.id + '/action', member.token, 'POST', { action: 'offline' }); const after = await api('/boost/listings/' + listing.id, member.token); assert.ok(['OFFLINE', 'REJECTED'].includes(after.status)); } catch (e) { cleanupErrors.push(e.message); }
  for (const token of tokens) try { assert.equal((await raw('/boost/auth/logout', token, 'POST')).code, 200); assert.equal((await raw('/boost/me', token)).code, 401); } catch (e) { cleanupErrors.push(e.message); }
  const passed = checks.length === (requestedCases?.size || 3) && checks.every(c => c.status === '已实测通过') && pageErrors.length === 0 && consoleErrors.length === 0 && transportErrors.length === 0 && cleanupErrors.length === 0;
  const result = { passed, completeSuite: !requestedCases, requestedCases: requestedCases ? [...requestedCases] : ['A02', 'A25', 'A40'], completedAt: new Date().toISOString(), environment: env, mode: '真实本地HTTP、MySQL和Chromium；A25只暂停真实请求响应，不伪造数据', checks, pageErrors, consoleErrors, transportErrors, cleanupErrors, cleanup: { memberIds: [member?.user.id, peer?.user.id].filter(Boolean), listingId: listing?.id, exactFixtureListingOfflineOrRejected: !!listing && cleanupErrors.length === 0, allCreatedSessionsRevoked: cleanupErrors.length === 0, paymentOrMoneyMutation: false } };
  fs.writeFileSync(path.join(out, requestedCases ? 'navigation-targeted.json' : 'navigation.json'), JSON.stringify(result, null, 2)); process.exitCode = passed ? 0 : 1;
});
