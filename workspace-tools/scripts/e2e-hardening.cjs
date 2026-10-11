// Local-only hardening acceptance: actual HTTP authorization plus response-loss browser recovery.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{randomUUID}=require('node:crypto');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),contract=require('./test-contract.cjs'),root=path.resolve(__dirname,'..'),stamp=Date.now(),tokens=[],checks=[],errors=[];
let browser,owner,peer,outsider;
async function raw(route,token,method='GET',body){const r=await fetch(env.api+route,{method,headers:{'Content-Type':'application/json','X-Boost-Token':token||''},body:body?JSON.stringify(body):undefined});return {http:r.status,...await r.json()}}
async function api(...args){const r=await raw(...args);assert.equal(r.code,200,args[0]+': '+r.msg);return r.data}
async function register(suffix){const r=await api('/boost/auth/register',null,'POST',{username:'qa_hard_'+suffix+'_'+stamp,password:'Hardening2026!',nickname:'加固验收'+suffix});tokens.push(r.token);return r}
function pass(name){checks.push(name);console.log('PASS '+name)}
(async()=>{
 assert.ok(['127.0.0.1','localhost'].includes(new URL(env.api).hostname));assert.equal((await api('/boost/config')).simulatedPayment,true);
 owner=await register('a');peer=await register('b');outsider=await register('c');
 const publish={requestKey:randomUUID(),kind:'REQUEST',title:'并发发布 '+stamp,game:'王者荣耀',zone:'微信区',platform:'安卓',currentRank:'星耀',targetRank:'王者',durationHours:24,priceCents:12000,description:'并发重试验收，保留唯一发布记录',images:[]};const posted=await Promise.all(Array.from({length:8},()=>api('/boost/listings',owner.token,'POST',publish)));assert.equal(new Set(posted.map(x=>x.id)).size,1);
 for(const desired of [true,false]){await Promise.all(Array.from({length:8},()=>api('/boost/listings/1/favorite',owner.token,'POST',{desired})));await Promise.all(Array.from({length:8},()=>api('/boost/shops/'+peer.user.id+'/follow',owner.token,'POST',{desired})));assert.equal((await api('/boost/listings/1',owner.token)).favorited,desired);assert.equal((await api('/boost/shops/'+peer.user.id,owner.token)).followed,desired)}pass('发布、收藏、关注各 8 路并发重试，唯一记录和目标状态保持正确');
 const image=await contract.upload(owner.token,'CHAT'),conversation=await api('/boost/conversations',owner.token,'POST',{peerId:peer.user.id});
 const body={kind:'IMAGE',content:image,requestKey:randomUUID()};await Promise.all(Array.from({length:8},()=>api(`/boost/conversations/${conversation.id}/messages`,owner.token,'POST',body)));
 assert.equal((await api('/boost/conversations/'+conversation.id,owner.token)).items.filter(m=>m.content===image).length,1);
 assert.equal((await raw(`/boost/conversations/${conversation.id}/messages`,owner.token,'POST',{...body,content:'changed',kind:'TEXT'})).code,409);
 pass('8 个并发图片消息重试只保存一次，重用请求号修改内容被拒绝');
 assert.equal((await fetch(env.api+image)).status,403);
 assert.deepEqual((await api('/boost/media/access',outsider.token,'POST',{paths:[image]})).urls,{});
 const authorized=(await api('/boost/media/access',peer.token,'POST',{paths:[image]})).urls[image];assert.ok(authorized);
 const response=await fetch(env.api+authorized);assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'no-store');assert.equal((await fetch(env.api+authorized.replace(/signature=[^&]+/,'signature=invalid'))).status,403);
 assert.notEqual((await raw('/boost/me',outsider.token,'PUT',{nickname:outsider.user.nickname,avatar:image,notifyEnabled:true})).code,200);
 pass('匿名、无关账号、签名篡改和跨用途引用被拒绝，聊天参与人可查看原图');
 // Put a matching text before >100 newer messages: search must query stored history.
 const marker='历史检索'+stamp;
 await api(`/boost/conversations/${conversation.id}/messages`,owner.token,'POST',{kind:'TEXT',content:marker,requestKey:randomUUID()});
 for(let n=0;n<105;n++)await api(`/boost/conversations/${conversation.id}/messages`,owner.token,'POST',{kind:'TEXT',content:'分页历史 '+n,requestKey:randomUUID()});
 const search=await api('/boost/conversations?keyword='+encodeURIComponent(marker),peer.token);assert.ok(search.items.some(x=>x.id===conversation.id));
 assert.equal((await api('/boost/conversations?keyword='+encodeURIComponent(marker),outsider.token)).items.length,0);
 pass('超过 100 条消息后仍能搜索早期内容，其他账号无法搜索该会话');
 browser=await chromium.launch();const context=await browser.newContext({viewport:{width:390,height:844}});await context.addInitScript(token=>localStorage.setItem('boost_token',token),owner.token);
 const p=await context.newPage();p.setDefaultTimeout(20000);p.on('pageerror',e=>errors.push(e.message));
 let lose=true;const requests=[];
 await p.route('**/api/boost/listings',async route=>{if(route.request().method()!=='POST')return route.continue();requests.push(route.request().postDataJSON());const result=await route.fetch();assert.equal((await result.json()).code,200);if(lose){lose=false;return route.abort('failed')}await route.fulfill({response:result})});
 await p.goto(env.mini+'/#/pages/publish/index',{waitUntil:'domcontentloaded'});const title='响应丢失重试 '+stamp;await p.locator('.text-input input').fill(title);
 const inputs=p.locator('.field-row input');await inputs.nth(0).fill('星耀 III');await inputs.nth(1).fill('王者');await inputs.nth(2).fill('24');await inputs.nth(3).fill('12');await p.locator('.textarea textarea').fill('验证服务端提交成功后响应丢失仍只生成一个发布。');await p.locator('.check-box').click();await p.getByText('确认发布',{exact:true}).click();await p.getByText('连接失败，请检查网络后重试',{exact:true}).first().waitFor();
 await p.getByText('确认发布',{exact:true}).click();await p.getByText('发布管理',{exact:true}).waitFor();assert.equal(requests.length,2);assert.equal(requests[0].requestKey,requests[1].requestKey);
 assert.equal((await api('/boost/listings/mine?kind=REQUEST',owner.token)).items.filter(x=>x.title===title).length,1);pass('发布提交成功但浏览器丢失响应后，再次提交复用请求号且不重复发布');
 await p.screenshot({path:require('./test-output.cjs').file('remediation-20261002/hardening-browser.png'),fullPage:true});
 await api(`/boost/conversations/${conversation.id}/messages`,peer.token,'POST',{kind:'TEXT',content:'提醒开关启用验证',requestKey:randomUUID()});
 await p.getByText('有新的消息或交易通知，请到消息中心查看',{exact:true}).first().waitFor({timeout:22000});
 await p.goto(env.mini+'/#/pages/account/index?type=settings',{waitUntil:'domcontentloaded'});await p.locator('uni-switch').waitFor();const saved=p.waitForResponse(r=>r.url().endsWith('/boost/me')&&r.request().method()==='PUT');await p.locator('uni-switch').click();assert.equal((await(await saved).json()).code,200);
 assert.equal((await api('/boost/me',owner.token)).notifyEnabled,false);
 await api(`/boost/conversations/${conversation.id}/messages`,peer.token,'POST',{kind:'TEXT',content:'提醒开关关闭验证',requestKey:randomUUID()});
 const polled=p.waitForResponse(r=>r.url().endsWith('/boost/messages/summary'));await p.reload({waitUntil:'domcontentloaded'});await polled;await p.waitForTimeout(300);assert.equal(await p.getByText('有新的消息或交易通知，请到消息中心查看',{exact:true}).count(),0);
 assert.ok((await api('/boost/messages/summary',owner.token)).unread>0);pass('应用内提醒启用时显示新消息提示，关闭后不弹提示且未读记录保留');
 let failLedger=true;await p.route('**/boost/wallet/ledger?*',async route=>{if(new URL(route.request().url()).searchParams.get('kind')==='income'&&failLedger){failLedger=false;return route.fulfill({status:503,json:{code:503,msg:'账单分页故障测试'}})}return route.continue()});
 await p.goto(env.mini+'/#/pages/wallet/index',{waitUntil:'domcontentloaded'});await p.getByText('收支明细',{exact:true}).waitFor();await p.getByText('收入',{exact:true}).click();await p.getByText('账单分页故障测试',{exact:true}).first().waitFor();await p.getByText('更多收支明细',{exact:true}).click();await p.getByText('更多收支明细',{exact:true}).waitFor({state:'hidden'});pass('钱包切换筛选请求失败后仍可重试，不会困在空列表');
 assert.deepEqual(errors,[]);
 const report={passed:true,checks,errors};fs.writeFileSync(require('./test-output.cjs').file('remediation-20261002/hardening.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{await browser?.close();try{if(owner){const mine=await api('/boost/listings/mine?kind=REQUEST',owner.token);for(const item of mine.items)if(item.status==='PENDING')await api('/boost/listings/'+item.id+'/action',owner.token,'POST',{action:'offline'})}}catch(e){console.error('QA cleanup: '+e.message);process.exitCode=1}await Promise.allSettled(tokens.map(t=>api('/boost/auth/logout',t,'POST')))});
