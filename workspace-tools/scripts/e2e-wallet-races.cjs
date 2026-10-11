const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),root=path.resolve(__dirname,'..'),output=path.join(root,'test-results/recheck-20261002'),checks=[],errors=[],tokens=[];
let browser;
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
async function api(route,token,body){const data=await(await fetch(env.api+route,{method:'POST',headers:{'Content-Type':'application/json','X-Boost-Token':token||''},body:JSON.stringify(body||{})})).json();assert.equal(data.code,200,data.msg);return data.data}
(async()=>{
 fs.mkdirSync(output,{recursive:true});const username='qa_wallet_'+Date.now(),password='WalletRace2026!';
 const user=await api('/boost/auth/register',null,{username,password,nickname:'钱包并发回归'});tokens.push(user.token);
 browser=await chromium.launch();
 for(const kind of ['ledger','withdrawals']){
  const context=await browser.newContext({viewport:{width:390,height:844}}),page=await context.newPage();page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
  await page.goto(env.mini+'/#/pages/auth/index');await page.locator('.text-input input').nth(0).fill(username);await page.locator('.text-input input').nth(1).fill(password);await page.locator('.check-box').click();await page.getByText('登录',{exact:true}).click();await page.getByText('找高手，上分更轻松',{exact:true}).waitFor();tokens.push(await page.evaluate(()=>localStorage.getItem('boost_token')));
  let refreshed=false;const waiting=deferred(),release=deferred(),served=deferred();
  const ledger=id=>({id,title:id===99?'过期分页不应出现':refreshed?'刷新后的收入':'原始收入',amountCents:100,createdAt:'2026-10-02T01:00:00'});
  const withdrawal=id=>({id,note:id===99?'过期提现不应出现':refreshed?'刷新后的提现':'原始提现',amountCents:100,status:'PENDING',createdAt:'2026-10-02T01:00:00'});
  await page.route('**/boost/wallet{,/**}',async route=>{
   const url=new URL(route.request().url()),endpoint=url.pathname.split('/').pop();let data;
   if(endpoint==='wallet')data={balanceCents:100000,pendingCents:0,totalCents:100000};
   else if(endpoint==='withdraw'){refreshed=true;data={};}
   else if(['ledger','withdrawals'].includes(endpoint)){
    if(endpoint===kind&&url.searchParams.get('page')==='2'){waiting.resolve();await release.promise;data={items:[kind==='ledger'?ledger(99):withdrawal(99)],hasMore:false};await route.fulfill({json:{code:200,data}});served.resolve();return;}
    data={items:[endpoint==='ledger'?ledger(refreshed?2:1):withdrawal(refreshed?2:1)],hasMore:!refreshed};
   }else return route.continue();
   await route.fulfill({json:{code:200,data}});
  });
  await page.locator('.tabbar [aria-label="我的"]').click();await page.getByText('我的钱包',{exact:true}).click();await page.locator('.balance-card').waitFor();
  if(kind==='withdrawals')await page.getByText('提现记录',{exact:true}).click();
  await page.getByText(kind==='ledger'?'更多收支明细':'更多提现记录',{exact:true}).click();await waiting.promise;
  await page.getByText('提现',{exact:true}).click();await page.locator('.field-row input').nth(0).fill('1');await page.locator('.field-row input').nth(1).fill('本地回归');await page.locator('.field-row input').nth(2).fill('qa-only');
  await page.getByText('提交提现申请',{exact:true}).click();await page.locator('.uni-modal__btn_primary').click();await page.getByText('刷新后的收入',{exact:true}).waitFor();
  release.resolve();await served.promise;
  // Wait for Vue to render the held response, without a fixed sleep.
  await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  assert.equal(await page.getByText(kind==='ledger'?'过期分页不应出现':'过期提现不应出现',{exact:true}).count(),0,'An old page must not contaminate the refreshed wallet');
  await page.screenshot({path:path.join(output,'wallet-'+kind+'.png'),fullPage:true});checks.push(kind+' pagination pending during withdrawal refresh stays isolated');await context.close();
 }
 assert.deepEqual(errors,[]);const result={passed:true,checks,errors};fs.writeFileSync(path.join(output,'wallet-races.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{await browser?.close();await Promise.allSettled(tokens.map(t=>api('/boost/auth/logout',t)))});
