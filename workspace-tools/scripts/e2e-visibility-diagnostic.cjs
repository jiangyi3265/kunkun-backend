// Hold a real detail response to reproduce clicking uni-button before its data exists.
// QA_VISIBILITY_DIAGNOSTIC_MODE=early preserves the old test expectation and must fail.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const {chromium} = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs'), output = require('./test-output.cjs');
const adminToken = process.env.ADMIN_TOKEN || JSON.parse(fs.readFileSync(path.resolve(__dirname, '../.runtime/admin-session.json'))).token;
const mode = process.env.QA_VISIBILITY_DIAGNOSTIC_MODE || 'loaded', stamp = Date.now();
const title = '收藏加载诊断 '+stamp;
const password = 'VisibilityDiagnostic2026!', tokens = [], network = [], errors = [], checks = [];
let browser, page, buyer, worker, service, release;
async function nav(route) { await page.goto(env.mini+'/?diagnostic='+Date.now()+'#/pages/'+route,{waitUntil:'domcontentloaded'}); }
async function api(route, token, method = 'GET', body) {
  body = require('./test-contract.cjs').bodyFor(route, method, body);
  const response = await fetch(env.api + route, {method, headers: {'Content-Type':'application/json', 'X-Boost-Token':token || '', ...(route.startsWith('/boost-admin/') ? {Authorization:'Bearer '+adminToken} : {})}, body:body ? JSON.stringify(body) : undefined});
  const result = await response.json(); assert.equal(result.code, 200, route+': '+result.msg); return result.data;
}
(async () => {
  buyer = await api('/boost/auth/register', null, 'POST', {username:'qa_vis_diag_'+stamp, password, nickname:'收藏加载诊断'});
  worker = await api('/boost/auth/login', null, 'POST', {username:'demo_worker',password:'Demo2026!'});
  tokens.push(buyer.token, worker.token);
  service = await api('/boost/listings', worker.token, 'POST', {kind:'SERVICE',title, game:'王者荣耀',zone:'微信区',platform:'安卓',currentRank:'星耀 III',targetRank:'星耀 II',durationHours:24,priceCents:12000,description:'仅用于收藏页面加载与持久化诊断。',images:[]});
  await api('/boost-admin/moderate/listings/'+service.id, null, 'POST', {action:'approve', note:'非支付收藏加载诊断'});
  browser = await chromium.launch(); page = await browser.newPage({viewport:{width:390,height:844}}); page.setDefaultTimeout(15000);
  page.on('pageerror', e=>errors.push(e.message));
  page.on('response', async r=>{
    const u=new URL(r.url()); if(!u.pathname.includes('/boost/'))return;
    network.push({method:r.request().method(),path:u.pathname,status:r.status()});
  });
  await nav('auth/index');
  await page.locator('.text-input input').nth(0).fill(buyer.user.username);
  await page.locator('.text-input input').nth(1).fill(password);
  await page.locator('.check-box').click(); await page.getByText('登录',{exact:true}).click();
  await page.getByText('找高手，上分更轻松',{exact:true}).waitFor();
  if(process.env.QA_VISIBILITY_DIAGNOSTIC_FAIL_AFTER_LOGIN==='1'){
    fs.writeFileSync(output.file('visibility-diagnostic-cleanup-failure.json'),JSON.stringify({expectedInjectedFailure:true,username:buyer.user.username,memberId:buyer.user.id,serviceId:service.id,afterSuccessfulLogin:true,beforeTrackingBrowserToken:true},null,2));
    throw new Error('验收注入：登录成功后、记录浏览器token前失败，验证finally仍撤销新会话');
  }
  tokens.push(await page.evaluate(()=>localStorage.getItem('boost_token')));
  let held;
  const reached = new Promise(resolve=>{held=resolve}), gate = new Promise(resolve=>{release=resolve});
  await page.route('**/boost/listings/*', async route=>{
    const request=route.request(), u=new URL(request.url());
    if(request.method()!=='GET'||!u.pathname.endsWith('/boost/listings/'+service.id)){await route.fallback();return}
    const response=await route.fetch(); held(); await gate; await route.fulfill({response});
  });
  await nav('service/detail?id='+service.id);
  await reached;
  const before=await page.locator('.favorite-btn').evaluate(el=>({tag:el.tagName,disabled:el.hasAttribute('disabled'),disabledAttribute:el.getAttribute('disabled'),text:el.innerText}));
  assert.equal(before.disabled,true,'收藏加载期间应禁用');
  if(mode==='early') {
    await page.locator('.favorite-btn').click(); release();
    await page.locator('.detail-title').getByText(title,{exact:true}).waitFor();
    const persisted = await api('/boost/listings/'+service.id,buyer.token);
    const posts=network.filter(n=>n.method==='POST'&&n.path.endsWith('/'+service.id+'/favorite'));
    const evidence={mode,passed:false,before,clickBeforeData:true,favoritePosts:posts.length,persistedFavorited:persisted.favorited,network,errors,serviceId:service.id};
    fs.writeFileSync(output.file('visibility-diagnostic-early.json'),JSON.stringify(evidence,null,2));
    await page.screenshot({path:output.file('visibility-diagnostic-early.png'),fullPage:true});
    // This is the old suite's business expectation; its failure is deterministic here.
    assert.equal(persisted.favorited,true,'加载前点击没有发出收藏请求，原测试缺少等待详情就绪条件');
  } else {
    release();
    await page.locator('.detail-title').getByText(title,{exact:true}).waitFor();
    await page.locator('.favorite-btn:not([disabled])').waitFor();
    const savedResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/boost/listings/'+service.id+'/favorite'));
    await page.locator('.favorite-btn:not([disabled])').click();
    const savedResult=await (await savedResponse).json(); assert.equal(savedResult.code,200); assert.equal(savedResult.data,true);
    await page.getByText('已收藏',{exact:true}).waitFor();
    assert.equal((await api('/boost/listings/'+service.id,buyer.token)).favorited,true);
    assert.equal((await api('/boost/me',buyer.token)).favorites,1);
    checks.push('等详情加载后真实点击发送收藏目标true且API持久化');
    await page.reload({waitUntil:'domcontentloaded'}); await page.getByText('已收藏',{exact:true}).waitFor();
    checks.push('刷新详情仍显示已收藏');
    await nav('account/index?type=favorites');
    await page.getByText(title,{exact:true}).waitFor(); checks.push('收藏页面显示同一服务');
    await nav('account/index?type=settings');
    await page.getByText('应用内消息提醒',{exact:true}).waitFor();
    await page.getByText('退出登录',{exact:true}).click(); await page.locator('.uni-modal__btn_primary').click();
    await page.getByText('找高手，上分更轻松',{exact:true}).waitFor();
    assert.equal(await page.evaluate(()=>localStorage.getItem('boost_token')),null);
    await nav('auth/index');
    await page.locator('.text-input input').nth(0).fill(buyer.user.username); await page.locator('.text-input input').nth(1).fill(password);
    await page.locator('.check-box').click(); await page.getByText('登录',{exact:true}).click(); await page.getByText('找高手，上分更轻松',{exact:true}).waitFor();
    tokens.push(await page.evaluate(()=>localStorage.getItem('boost_token')));
    await nav('service/detail?id='+service.id); await page.getByText('已收藏',{exact:true}).waitFor();
    checks.push('重新登录后收藏持久化');
    const deleted=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/boost/listings/'+service.id+'/favorite'));
    await page.locator('.favorite-btn:not([disabled])').click(); assert.equal((await (await deleted).json()).data,false);
    await page.locator('.favorite-btn').getByText('收藏',{exact:true}).waitFor();
    assert.equal((await api('/boost/listings/'+service.id,buyer.token)).favorited,false); assert.equal((await api('/boost/me',buyer.token)).favorites,0);
    await page.reload({waitUntil:'domcontentloaded'}); await page.locator('.detail-title').getByText(title,{exact:true}).waitFor(); assert.equal(await page.locator('.favorite-btn').innerText(),'收藏');
    checks.push('取消收藏请求目标false，数量归零且刷新保持');
    assert.deepEqual(errors,[]);
    await page.screenshot({path:output.file('visibility-diagnostic-loaded.png'),fullPage:true});
    const result={mode,passed:true,before,checks,network,errors,serviceId:service.id}; fs.writeFileSync(output.file('visibility-diagnostic-loaded.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result,null,2));
  }
})().catch(async e=>{console.error(e);if(page){console.error((await page.locator('body').innerText()).slice(-2000));await page.screenshot({path:output.file('visibility-diagnostic-'+mode+'-failure.png'),fullPage:true})}process.exitCode=1}).finally(async()=>{
  // A failed post-login UI wait must still revoke the newly issued browser session.
  if(page&&!page.isClosed()){
    const latest=await page.evaluate(()=>localStorage.getItem('boost_token')).catch(()=>null);
    if(latest&&!tokens.includes(latest))tokens.push(latest);
  }
  release?.(); await browser?.close();
  if(service)await api('/boost/listings/'+service.id+'/action',worker.token,'POST',{action:'offline'}).catch(e=>{console.error('QA listing cleanup failed:',e.message);process.exitCode=1});
  await Promise.allSettled(tokens.filter(Boolean).map(token=>api('/boost/auth/logout',token,'POST')));
});
