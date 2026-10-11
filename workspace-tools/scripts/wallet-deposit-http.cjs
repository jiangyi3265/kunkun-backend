// Real browser/HTTP/MySQL checks, with one separately labeled missing-field UI stub.
const fs=require('fs'),path=require('path'),http=require('http'),crypto=require('crypto'),assert=require('assert/strict'),{spawnSync}=require('child_process');
const {chromium}=require('module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const root=path.resolve(__dirname,'..'),out=process.env.MARKET_CONTENT_OUT,base=process.env.PLAYER_HTTP_BASE,dir=path.resolve(process.env.MARKET_H5_DIR),fixture=process.env.PLAYER_HTTP_FIXTURE;
assert(/^http:\/\/127\.0\.0\.1:\d+$/.test(base));const report={startedAt:new Date().toISOString(),scope:'Real HTTP/MySQL + built H5; synthetic SQL balances prove display, not payment collection.',checks:[],mockChecks:[],errors:[],commands:[]};let browser,server,page;const users=[];
async function call(route,method='GET',body,token=''){return(await fetch(base+route,{method,headers:{'Content-Type':'application/json',...(token?{'X-Boost-Token':token}:{})},...(body?{body:JSON.stringify(body)}:{})})).json()}
async function api(...args){const r=await call(...args);assert.equal(r.code,200,args[0]);return r.data}
async function check(name,fn,mocked=false){await fn();(mocked?report.mockChecks:report.checks).push({name,status:'已实测通过'});console.log('PASS '+name)}
(async()=>{try{
 const cfg=await api('/boost/config');assert.equal(cfg.paymentsEnabled,false);
 for(const nickname of ['保证金展示验收','另一个钱包']){const credentials={username:'qa_deposit_'+crypto.randomBytes(6).toString('hex'),password:crypto.randomBytes(18).toString('base64url'),nickname,acceptedPolicy:cfg.policyVersion};users.push({...await api('/boost/auth/register','POST',credentials),credentials})}
 const actor=users[0];
 server=http.createServer((req,res)=>{try{const p=path.resolve(dir,'.'+decodeURIComponent(new URL(req.url,'http://local').pathname));assert(p===dir||p.startsWith(dir+path.sep));const f=fs.existsSync(p)&&fs.statSync(p).isFile()?p:path.join(dir,'index.html');res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png','.jpg':'image/jpeg','.svg':'image/svg+xml'})[path.extname(f)]||'application/octet-stream');fs.createReadStream(f).pipe(res)}catch{res.writeHead(500);res.end('Static server error')}});
 const origin=await new Promise(resolve=>server.listen(0,'127.0.0.1',()=>resolve('http://127.0.0.1:'+server.address().port)));
 browser=await chromium.launch({headless:true});page=await browser.newPage({viewport:{width:390,height:844}});page.setDefaultTimeout(15000);
 page.on('pageerror',e=>report.errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!m.location().url.includes('cdn.dcloud.net.cn/img/shadow-grey.png'))report.errors.push(m.text())});
 await page.goto(origin+'/#/pages/auth/index');await page.locator('#username input').fill(actor.credentials.username);await page.locator('#password input').fill(actor.credentials.password);await page.locator('.check-box').click();await page.getByText('登录',{exact:true}).click();await page.waitForURL(u=>['#/','#/pages/index/index'].includes(u.hash));await page.locator('.brand').waitFor();
 await check('new wallet displays server-owned zero deposit and no collection/refund buttons',async()=>{
  const w=await api('/boost/wallet','GET',null,actor.token);assert.equal(w.depositCents,0);await page.goto(origin+'/#/pages/wallet/index');await page.locator('.deposit-amount').waitFor();assert.equal((await page.locator('.deposit-amount').innerText()).trim(),'¥ 0');assert.equal(await page.locator('.deposit-card uni-button').count(),0);await page.getByText('缴退暂未开放',{exact:true}).waitFor();
  await page.screenshot({path:path.join(out,'wallet-zero-390.png'),fullPage:true});
 });
 await check('stored deposit formats independently from available balance and survives reload',async()=>{
  const r=spawnSync('C:/Python314/python.exe',['-X','utf8','scripts/seed-wallet-deposit-fixture.py',fixture,String(actor.user.id)],{cwd:root,encoding:'utf8',windowsHide:true});fs.writeFileSync(path.join(out,'h5-fixture.log'),(r.stdout||'')+(r.stderr||''));report.commands.push({command:['python','scripts/seed-wallet-deposit-fixture.py','<owned-fixture>',String(actor.user.id)],exitCode:r.status});assert.equal(r.status,0);
  await page.reload();await page.getByText('¥ 56.78',{exact:true}).waitFor();assert.equal((await page.locator('.balance-number').innerText()).trim(),'12.34');
  await page.goto(origin+'/#/pages/mine/index');await page.locator('.profile-head').waitFor();await page.goto(origin+'/#/pages/wallet/index');await page.getByText('¥ 56.78',{exact:true}).waitFor();
  const w=await api('/boost/wallet','GET',null,actor.token);assert.equal(w.balanceCents,1234);assert.equal(w.depositCents,5678);assert.equal(w.totalCents,0);assert.equal(w.pendingCents,0);assert.equal(w.ledger.length,0);
 });
 await check('deposit remains private to authenticated owner and disabled withdrawals do not change funds',async()=>{
  assert.equal((await call('/boost/wallet')).code,401);
  const other=await api('/boost/wallet?memberId='+actor.user.id,'GET',null,users[1].token);assert.equal(other.depositCents,0);assert.equal(other.balanceCents,0);
  const result=await call('/boost/wallet/withdraw','POST',{amountCents:100,requestKey:crypto.randomUUID(),accountName:'隔离测试'},actor.token);assert.equal(result.code,503,'Payment-disabled guard must explicitly reject the request');
  const w=await api('/boost/wallet','GET',null,actor.token);assert.equal(w.depositCents,5678);assert.equal(w.balanceCents,1234);assert.equal(w.withdrawals.length,0);report.disabledWithdrawalCode=result.code;
 });
 await check('320/390/480px deposit card fits and wallet record controls remain usable',async()=>{
  report.layouts=[];for(const width of [320,390,480]){await page.setViewportSize({width,height:844});const card=await page.locator('.deposit-card').boundingBox();assert(card&&card.x>=0&&card.x+card.width<=width);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);report.layouts.push({width,card});await page.screenshot({path:path.join(out,'wallet-deposit-'+width+'.png'),fullPage:true})}
  await page.locator('.topbar-actions').getByText('提现记录',{exact:true}).click();await page.getByText('还没有提现记录',{exact:true}).waitFor();
 });
 await check('missing deposit field shows unavailable instead of a fabricated zero (response stub only)',async()=>{
  await page.route('**/boost/wallet',async route=>{const response=await route.fetch();const json=await response.json();delete json.data.depositCents;await route.fulfill({response,json})});await page.reload();await page.getByText('暂未获取',{exact:true}).waitFor();assert.equal(await page.locator('.deposit-amount').count(),0);assert.equal((await page.locator('.balance-number').innerText()).trim(),'12.34');await page.screenshot({path:path.join(out,'wallet-deposit-unavailable.png'),fullPage:true});await page.unroute('**/boost/wallet');
 },true);
 await check('no unexpected browser errors',async()=>assert.deepEqual(report.errors,[]));report.exitCode=0;
}catch(e){report.exitCode=1;report.error=e.stack;console.error(e.stack);try{await page?.screenshot({path:path.join(out,'h5-failure.png'),fullPage:true})}catch{}}
finally{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve));for(const u of users)try{await api('/boost/auth/logout','POST',null,u.token)}catch(e){report.cleanupError=e.message;report.exitCode=1}report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'http-browser.json'),JSON.stringify(report,null,2));process.exitCode=report.exitCode}})();
