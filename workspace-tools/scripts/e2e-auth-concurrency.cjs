// Actual local HTTP/MySQL requests. Controlled interleavings are covered by BoostRaceTest.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const env = require('./test-env.cjs'), root = path.resolve(__dirname, '..');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.join(root, '.runtime/admin-session.json'))).token;
const password = 'ConcurrentQa2026!', next = 'ChangedQa2026!', tokens = [], checks = [];
async function raw(route, token, method = 'GET', body) { body=require('./test-contract.cjs').bodyFor(route,method,body);
  const r = await fetch(env.api + route, { method, headers: { 'Content-Type': 'application/json', 'X-Boost-Token': token || '', ...(route.startsWith('/boost-admin/') ? { Authorization: 'Bearer ' + adminToken } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return r.json();
}
async function api(...args) { const r = await raw(...args); assert.equal(r.code, 200, args[0] + ': ' + r.msg); return r.data; }
async function moderate(id, action) { return api('/boost-admin/moderate/members/' + id, null, 'POST', { action, note: '账号并发回归 ' + action }); }
(async () => {
  assert.ok(['localhost', '127.0.0.1'].includes(new URL(env.api).hostname));
  assert.equal((await api('/boost/config')).simulatedPayment, true);
  const username = 'qa_race_' + Date.now();
  const account = await api('/boost/auth/register', null, 'POST', { username, password, nickname: '账号并发回归' }); tokens.push(account.token);
  const oldLogins = Array.from({ length: 4 }, () => raw('/boost/auth/login', null, 'POST', { username, password }).then(r => { if (r.code === 200) tokens.push(r.data.token); return r; }));
  const change = raw('/boost/me/password', account.token, 'POST', { oldPassword: password, newPassword: next });
  const results = await Promise.all(oldLogins); assert.equal((await change).code, 200);
  for (const r of results) assert.ok([200, 400].includes(r.code), JSON.stringify(r));
  for (const token of tokens) assert.equal((await raw('/boost/me', token)).code, 401, 'Password change must revoke every old-password session');
  const current = await api('/boost/auth/login', null, 'POST', { username, password: next }); tokens.push(current.token);
  assert.equal((await api('/boost/me', current.token)).id, account.user.id);
  checks.push('MySQL 上并发旧密码登录与改密后，所有旧密码会话均失效，新密码可登录');

  const form = new FormData(); form.append('purpose','CERTIFICATION'); form.append('file', new Blob([fs.readFileSync(path.join(root, '代练/static/logo.png'))], { type: 'image/png' }), 'qa-proof.png');
  const upload = await (await fetch(env.api + '/boost/upload', { method: 'POST', headers: { 'X-Boost-Token': current.token }, body: form })).json(); assert.equal(upload.code, 200);
  let approval;
  const applications = await Promise.all(Array.from({ length: 8 }, (_, i) => raw('/boost/me/certification', current.token, 'POST', { skill: '并发申请游戏能力证明 ' + i, images: [upload.data.url] }).then(r => {
    if (r.code === 200 && !approval) approval = moderate(account.user.id, 'approve');
    return r;
  })));
  assert.equal(applications.filter(r => r.code === 200).length, 1, 'Only one certification submission may succeed');
  for (const r of applications) assert.ok([200, 400].includes(r.code), JSON.stringify(r));
  await approval; assert.equal((await api('/boost/me', current.token)).certification, 'APPROVED');
  checks.push('8 个重复认证申请只接受一次，审核结果保持通过');

  const duringBlock = Array.from({ length: 4 }, () => raw('/boost/auth/login', null, 'POST', { username, password: next }).then(r => { if (r.code === 200) tokens.push(r.data.token); return r; }));
  const block = moderate(account.user.id, 'block');
  const blockResults = await Promise.all(duringBlock); await block;
  for (const r of blockResults) assert.ok([200, 400].includes(r.code), JSON.stringify(r));
  await moderate(account.user.id, 'unblock');
  for (const token of tokens) assert.equal((await raw('/boost/me', token)).code, 401, 'Unblocking must not reactivate a session issued during the block race');
  const restored = await api('/boost/auth/login', null, 'POST', { username, password: next }); tokens.push(restored.token);
  checks.push('并发登录与停用后解封不会复活旧会话，重新登录正常');
  const report = { passed: true, memberId: account.user.id, checks };
  fs.writeFileSync(require('./test-output.cjs').file('race-auth-http.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => { await Promise.allSettled(tokens.map(t => raw('/boost/auth/logout', t, 'POST'))); });
