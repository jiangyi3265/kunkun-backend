// Local HTTP writes plus real page interactions; only the successful response is deliberately lost.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{randomUUID}=require('node:crypto');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),contract=require('./test-contract.cjs'),root=path.resolve(__dirname,'..'),out=path.join(root,'test-results/recheck-retries-20261002');
const admin=JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'),'utf8')).token,phase=process.env.QA_PHASE==='before'?'before':'after',tokens=[],checks=[],errors=[];
let browser,buyer,worker,listing,delivery;
async function api(route,token,method='GET',body){const r=await(await fetch(env.api+route,{method,headers:{'Content-Type':'application/json','X-Boost-Token':token||'',...(route.startsWith('/boost-admin/')?{Authorization:'Bearer '+admin}:{})},body:body?JSON.stringify(contract.bodyFor(route,method,body)):undefined})).json();assert.equal(r.code,200,route+': '+r.msg);return r.data}
async function order(state='IN_PROGRESS'){
 const o=await api('/boost/orders',buyer.token,'POST',{listingId:listing,requestKey:randomUUID()});await api(`/boost/orders/${o.id}/simulated-pay`,buyer.token,'POST');
 if(state!=='WAIT_START')await api(`/boost/orders/${o.id}/action`,worker.token,'POST',{action:'start'});
 if(state==='COMPLETED'){await api(`/boost/orders/${o.id}/progress`,worker.token,'POST',{percent:100,note:'已完成全部约定目标',images:[delivery]});await api(`/boost/orders/${o.id}/action`,buyer.token,'POST',{action:'confirm'})}
 return o.id;
}
async function pageFor(user,route){const c=await browser.newContext({viewport:{width:390,height:900}});await c.addInitScript(t=>localStorage.setItem('boost_token',t),user.token);const p=await c.newPage();p.setDefaultTimeout(12000);p.on('pageerror',e=>errors.push(e.message));await p.goto(env.mini+'/#/pages/'+route);return p}
function loseFirstReply(p,route){let first=true;return p.route('**'+route,async intercepted=>{try{if(intercepted.request().method()!=='POST')return await intercepted.continue();if(!first)return await intercepted.continue();first=false;const r=await intercepted.fetch();assert.equal((await r.json()).code,200);await intercepted.abort('failed')}catch(e){errors.push(e.message.split('\n')[0]);await intercepted.abort().catch(()=>{})}})}
async function check(name,fn){try{await fn();checks.push({name,passed:true});console.log('PASS '+name)}catch(e){checks.push({name,passed:false,error:e.message});console.log('FAIL '+name+': '+e.message)}}
(async()=>{
 fs.mkdirSync(out,{recursive:true});assert.ok(['localhost','127.0.0.1'].includes(new URL(env.api).hostname));assert.equal((await api('/boost/config')).simulatedPayment,true);
 const stamp=Date.now();async function register(k){const u=await api('/boost/auth/register',null,'POST',{username:'qa_retry_'+k+'_'+stamp,password:'RetryTest2026!',nickname:'重试回归'+k});tokens.push(u.token);return u}
 buyer=await register('b');worker=await register('w');const proof=await contract.upload(worker.token,'CERTIFICATION');await api('/boost/me/certification',worker.token,'POST',{skill:'重试回归专用认证资料',images:[proof]});await api('/boost-admin/moderate/members/'+worker.user.id,null,'POST',{action:'approve',note:'本地回归认证'});
 listing=(await api('/boost/listings',worker.token,'POST',{kind:'SERVICE',title:'重试回归测试服务 '+stamp,game:'王者荣耀',zone:'微信区',platform:'安卓',currentRank:'星耀',targetRank:'王者',durationHours:24,priceCents:12000,description:'仅本地测试，验证响应丢失后的恢复',images:[]})).id;await api('/boost-admin/moderate/listings/'+listing,null,'POST',{action:'approve',note:'本地回归审核'});delivery=await contract.upload(worker.token,'DELIVERY');browser=await chromium.launch();
 await check('8 路重复进度只写一次记录',async()=>{const id=await order();await Promise.all(Array.from({length:8},()=>api(`/boost/orders/${id}/progress`,worker.token,'POST',{percent:50,note:'已经完成一半约定目标',images:[]})));assert.equal((await api('/boost/orders/'+id,buyer.token)).updates.length,2)});
 await check('成果提交响应丢失后页面可重试恢复',async()=>{
  const id=await order(),p=await pageFor(worker,'orders/detail?id='+id);
  try{await p.getByText('提交进度',{exact:true}).waitFor();await p.locator('.field-row input').fill('100');await p.locator('textarea').fill('已完成目标，请核验成果');const [chooser]=await Promise.all([p.waitForEvent('filechooser'),p.locator('.upload-add').click()]);await chooser.setFiles(path.join(root,'代练/static/logo.png'));await p.locator('.upload-thumb').waitFor();await loseFirstReply(p,`/boost/orders/${id}/progress`);
   await p.getByText('提交进度',{exact:true}).click();await p.locator('.field-error').filter({hasText:'连接失败'}).waitFor();assert.equal((await api('/boost/orders/'+id,buyer.token)).status,'REVIEW');await p.getByText('提交进度',{exact:true}).click();await p.locator('.order-hero').filter({hasText:'待验收'}).waitFor({timeout:4000});assert.equal((await api('/boost/orders/'+id,buyer.token)).updates.length,2);await p.screenshot({path:path.join(out,'delivery-retry-'+phase+'.png'),fullPage:true,animations:'disabled'});
  }finally{await p.context().close()}
 });
 await check('售后提交响应丢失后页面恢复原申请',async()=>{
  const id=await order('WAIT_START'),p=await pageFor(buyer,'aftersale/index?id='+id);
  try{await p.locator('textarea').fill('服务未按约定时间开始，请核实');await loseFirstReply(p,`/boost/orders/${id}/aftersale`);await p.getByText('提交申请',{exact:true}).click();await p.locator('.field-error').filter({hasText:'连接失败'}).waitFor();await p.getByText('提交申请',{exact:true}).click();await p.locator('.order-hero').filter({hasText:'售后中'}).waitFor({timeout:4000});assert.equal((await api('/boost/orders/'+id,buyer.token)).aftersale.description,'服务未按约定时间开始，请核实');await p.screenshot({path:path.join(out,'aftersale-retry-'+phase+'.png'),fullPage:true,animations:'disabled'});
  }finally{await p.context().close()}
 });
 await check('评价响应丢失后页面恢复已评价状态',async()=>{
  const id=await order('COMPLETED'),p=await pageFor(buyer,'orders/detail?id='+id);
  try{await p.locator('textarea').fill('服务按时完成，沟通顺畅');await loseFirstReply(p,`/boost/orders/${id}/review`);await p.getByText('提交评价',{exact:true}).click();await p.locator('.field-error').filter({hasText:'连接失败'}).waitFor();await p.getByText('提交评价',{exact:true}).click();await p.getByText('提交评价',{exact:true}).waitFor({state:'hidden',timeout:4000});assert.equal((await api('/boost/orders/'+id,buyer.token)).reviewed,true);
  }finally{await p.context().close()}
 });
 await check('提交进度等待期间编辑的新草稿保留',async()=>{
  const id=await order(),p=await pageFor(worker,'orders/detail?id='+id);let release;const gate=new Promise(r=>release=r);let entered;const waiting=new Promise(r=>entered=r);
  try{await p.getByText('提交进度',{exact:true}).waitFor();await p.locator('.field-row input').fill('50');await p.locator('textarea').fill('第一份进度说明');await p.route(`**/boost/orders/${id}/progress`,async route=>{const response=await route.fetch();entered();await gate;await route.fulfill({response})});await p.getByText('提交进度',{exact:true}).click();await waiting;await p.locator('.field-row input').fill('60');await p.locator('textarea').fill('等待期间输入的下一份说明');release();await p.getByText('第一份进度说明',{exact:true}).waitFor();assert.equal(await p.locator('textarea').inputValue(),'等待期间输入的下一份说明');assert.equal(await p.locator('.field-row input').inputValue(),'60');await p.screenshot({path:path.join(out,'progress-draft-'+phase+'.png'),fullPage:true,animations:'disabled'});
  }finally{release();await p.context().close()}
 });
 await check('付款请求失败后返回不会恢复可编辑的旧结算页',async()=>{
  const p=await pageFor(buyer,'service/detail?id='+listing);
  try{await p.getByText('立即下单',{exact:true}).click();await p.locator('.checkout-steps').waitFor();await p.locator('.check-box').click();await p.locator('textarea').fill('原始订单的服务要求');await p.route('**/boost/orders/*/simulated-pay',route=>route.fulfill({status:503,json:{code:503,msg:'付款请求暂时失败'}}));
   await p.getByText('确认下单并支付',{exact:true}).click();await p.locator('.uni-modal__btn_primary').click();await p.locator('.order-hero').waitFor();await p.locator('[aria-label="返回"]').click();await p.locator('.detail-swiper').waitFor({timeout:4000});assert.equal(await p.locator('.checkout-steps').count(),0);
  }finally{await p.context().close()}
 });
 const result={passed:checks.every(c=>c.passed)&&errors.length===0,checks,errors};fs.writeFileSync(path.join(out,'browser-'+phase+'.json'),JSON.stringify(result,null,2));if(!result.passed)process.exitCode=1;
})().catch(e=>{console.error(e.message.split('\n')[0]);process.exitCode=1}).finally(async()=>{await browser?.close();try{if(listing)await api('/boost/listings/'+listing+'/action',worker.token,'POST',{action:'offline'})}finally{await Promise.allSettled(tokens.map(t=>api('/boost/auth/logout',t,'POST')))}});
