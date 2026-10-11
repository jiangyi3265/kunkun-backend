// Read-only UI/HTTP/MySQL reconciliation. Creates only its own auth sessions; no business writes.
require('./nonpayment-request-guard.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),root=path.resolve(__dirname,'..'),out=process.env.QA_OUTPUT_DIR;
assert.ok(out,'QA_OUTPUT_DIR is required');fs.mkdirSync(out,{recursive:true});
const fixtureDir=process.env.QA_READONLY_FIXTURE_DIR||path.join(out,'../contracts/contracts');
const fixture=JSON.parse(fs.readFileSync(path.join(fixtureDir,'results.json'),'utf8'));
assert.equal(fixture.passed,true);assert.deepEqual(fixture.cleanup.errors,[]);
const [buyerId,workerId]=fixture.cleanup.sessions.map(x=>x.memberId);
const admin=process.env.ADMIN_TOKEN||JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'),'utf8')).token;
const tokens=[],checks=[],pageErrors=[],consoleErrors=[],transportErrors=[],traffic=[],cleanupErrors=[];
let browser,failure,before;
function id(n){assert.ok(Number.isSafeInteger(n)&&n>0);return n;}
function db(sql){
 assert.ok(sql.startsWith('SELECT '),'Read-only database helper');
 const code="import sys,json;sys.path.insert(0,'scripts');from local_db import mysql;s=json.load(sys.stdin);assert s.startswith('SELECT ');print(json.dumps([json.loads(x) for x in mysql(s).splitlines()[1:] if x.strip()],ensure_ascii=False))";
 const r=spawnSync(process.env.QA_PYTHON||'C:/Python314/python.exe',['-X','utf8','-c',code],{cwd:root,input:JSON.stringify(sql),encoding:'utf8'});
 assert.equal(r.status,0,'Read-only DB lookup failed');return JSON.parse(r.stdout);
}
function money(n){return (Number(n)/100).toFixed(2).replace(/\.00$/,'');}
async function raw(route,token,method='GET',body){
 assert.ok(method==='GET'||['/boost/auth/login','/boost/auth/logout'].includes(route),'Business writes forbidden: '+route);
 const response=await fetch(env.api+route,{method,headers:{'Content-Type':'application/json','X-Boost-Token':token||'',...(route.startsWith('/boost-admin/')?{Authorization:'Bearer '+admin}:{})},body:body?JSON.stringify(body):undefined});
 const data=await response.json();traffic.push({method,path:route.split('?')[0],httpStatus:response.status,code:data.code});return data;
}
async function api(...args){const r=await raw(...args);assert.equal(r.code,200,args[0]+': '+r.msg);return r.data;}
async function login(username,password){const r=await api('/boost/auth/login',null,'POST',{username,password});tokens.push(r.token);return r;}
async function pageFor(token,desktop=false){
 const context=await browser.newContext({viewport:desktop?{width:1440,height:1000}:{width:390,height:900}});
 if(token)await context.addInitScript(t=>localStorage.setItem('boost_token',t),token);
 if(desktop)await context.addCookies([{name:'Admin-Token',value:admin,domain:new URL(env.admin).hostname,path:'/'}]);
 const page=await context.newPage();page.setDefaultTimeout(15000);page.setDefaultNavigationTimeout(30000);
 page.on('pageerror',e=>pageErrors.push(e.message));page.on('console',m=>{if(m.type()==='error')consoleErrors.push(m.text().replace(/https?:\/\/[^\s]+/g,'[URL]'));});
 page.on('requestfailed',r=>{if(!String(r.failure()?.errorText).includes('ERR_ABORTED'))transportErrors.push({method:r.method(),path:new URL(r.url()).pathname,error:r.failure()?.errorText});});
 await page.route('**/*',async route=>{
  const p=new URL(route.request().url()).pathname,m=route.request().method();
  if(/\/(?:boost|boost-admin)\//.test(p)&&m!=='GET'){failure='Browser attempted a business write: '+m+' '+p;await route.abort('blockedbyclient');return;}
  await route.fallback();
 });return page;
}
async function nav(page,route){await page.goto(env.mini+'/?readonlyAcceptance='+Date.now()+'#/pages/'+route,{waitUntil:'domcontentloaded'});}
function responseFor(page,pathname,parameters={}){return page.waitForResponse(r=>{const u=new URL(r.url());return r.request().method()==='GET'&&u.pathname.endsWith(pathname)&&Object.entries(parameters).every(([k,v])=>u.searchParams.get(k)===String(v));});}
function orderRows(member,role,status='ALL',keyword=''){
 const q=keyword?" AND (order_no LIKE '%"+keyword.replace(/'/g,"''")+"%' OR title LIKE '%"+keyword.replace(/'/g,"''")+"%')":'';
 return db("SELECT JSON_OBJECT('id',id,'orderNo',order_no,'title',title,'status',status,'buyerId',buyer_id,'workerId',worker_id,'amountCents',amount_cents) FROM boost_order WHERE "+(role==='buyer'?'buyer_id=':'worker_id=')+id(member)+(status==='ALL'?'':" AND status='"+status+"'")+q+' ORDER BY id DESC');
}
function fundsHashes(){
 const queries={balances:"SELECT JSON_OBJECT('id',id,'balanceCents',balance_cents) FROM boost_member ORDER BY id",ledger:"SELECT JSON_OBJECT('id',id,'memberId',member_id,'amountCents',amount_cents,'balanceCents',balance_cents,'kind',kind,'eventKey',event_key) FROM boost_ledger ORDER BY id",withdrawals:"SELECT JSON_OBJECT('id',id,'memberId',member_id,'amountCents',amount_cents,'status',status) FROM boost_withdrawal ORDER BY id",aftersales:"SELECT JSON_OBJECT('id',id,'orderId',order_id,'status',status) FROM boost_aftersale ORDER BY id"};
 return Object.fromEntries(Object.entries(queries).map(([key,query])=>{const rows=db(query);return [key,{rows:rows.length,sha256:crypto.createHash('sha256').update(JSON.stringify(rows)).digest('hex')}];}));
}
(async()=>{
 for(const origin of Object.values(env))assert.ok(['127.0.0.1','localhost'].includes(new URL(origin).hostname),'Local services only');
 assert.equal((await api('/boost/config')).simulatedPayment,true);before=fundsHashes();browser=await chromium.launch();
 const users=db('SELECT JSON_OBJECT(\'id\',id,\'username\',username) FROM boost_member WHERE id IN ('+id(buyerId)+','+id(workerId)+') ORDER BY id');
 const buyer=await login(users.find(x=>x.id===buyerId).username,'ContractQa2026!');assert.equal(buyer.user.id,buyerId);
 const buyerPage=await pageFor(buyer.token);let pending=responseFor(buyerPage,'/api/boost/orders',{role:'buyer',status:'ALL'});await nav(buyerPage,'orders/index');let response=await pending;
 const expected=orderRows(buyerId,'buyer');assert.ok(expected.length>=3);assert.deepEqual((await response.json()).data.items.map(x=>x.id),expected.slice(0,20).map(x=>x.id));
 await buyerPage.locator('.card').filter({hasText:expected[0].title}).first().waitFor();
 pending=responseFor(buyerPage,'/api/boost/orders',{role:'buyer',status:'CANCELLED'});await buyerPage.locator('.tabs uni-button').filter({hasText:'已取消'}).click();response=await pending;
 assert.deepEqual((await response.json()).data.items.map(x=>x.id),orderRows(buyerId,'buyer','CANCELLED').slice(0,20).map(x=>x.id));
 const search=expected[0].orderNo;await buyerPage.locator('.search input').fill(search);await buyerPage.locator('.search input').press('Tab');
 pending=responseFor(buyerPage,'/api/boost/orders',{role:'buyer',status:'CANCELLED',keyword:search});await buyerPage.locator('.search uni-button').filter({hasText:'搜索'}).click();response=await pending;
 assert.deepEqual((await response.json()).data.items.map(x=>x.id),[expected[0].id]);await buyerPage.waitForFunction(()=>document.querySelectorAll('.card').length===1);
 await buyerPage.getByText(expected[0].title,{exact:true}).click();await buyerPage.getByText('订单已关闭',{exact:true}).waitFor();await buyerPage.reload();await buyerPage.getByText('订单已关闭',{exact:true}).waitFor();
 await buyerPage.screenshot({path:path.join(out,'orders-buyer-detail.png'),fullPage:true});
 const worker=await login(users.find(x=>x.id===workerId).username,'ContractQa2026!');assert.equal(worker.user.id,workerId);const workerPage=await pageFor(worker.token);
 pending=responseFor(workerPage,'/api/boost/orders',{role:'buyer',status:'ALL'});await nav(workerPage,'orders/index');response=await pending;assert.equal((await response.json()).data.total,orderRows(workerId,'buyer').length);
 await workerPage.getByText('还没有相关订单',{exact:true}).waitFor();pending=responseFor(workerPage,'/api/boost/orders',{role:'worker',status:'ALL'});await workerPage.getByText('我接的单',{exact:true}).click();response=await pending;
 const workOrders=orderRows(workerId,'worker');assert.deepEqual((await response.json()).data.items.map(x=>x.id),workOrders.slice(0,20).map(x=>x.id));await workerPage.locator('.card').filter({hasText:workOrders[0].title}).first().waitFor();await nav(workerPage,'orders/index?role=worker');await workerPage.locator('.card').filter({hasText:workOrders[0].title}).first().waitFor();await workerPage.reload();await workerPage.locator('.card').filter({hasText:workOrders[0].title}).first().waitFor();
 await workerPage.screenshot({path:path.join(out,'orders-worker-role.png'),fullPage:true});checks.push({id:'A30',status:'已实测通过',scope:'买家已取消订单筛选、订单号搜索、详情刷新；打手角色切换、带role参数重进/刷新及空买家列表；未操作付款后各状态',buyerId,workerId,matchedOrderId:expected[0].id,buyerOrderCount:expected.length,workerOrderCount:workOrders.length});
 assert.ok(process.env.QA_READONLY_WALLET_USERNAME&&process.env.QA_READONLY_WALLET_PASSWORD,'Owned SQL historical wallet fixture is required');
 const seed=await login(process.env.QA_READONLY_WALLET_USERNAME,process.env.QA_READONLY_WALLET_PASSWORD);const sid=id(seed.user.id),walletPage=await pageFor(seed.token);
 const totals=db("SELECT JSON_OBJECT('balanceCents',balance_cents,'pendingCents',(SELECT COALESCE(SUM(original_cents),0) FROM boost_order WHERE worker_id="+sid+" AND status IN ('WAIT_START','IN_PROGRESS','REVIEW')),'totalCents',(SELECT COALESCE(SUM(amount_cents),0) FROM boost_ledger WHERE member_id="+sid+" AND kind='SETTLEMENT')) FROM boost_member WHERE id="+sid)[0];
 const wallet=await api('/boost/wallet',seed.token);for(const key of Object.keys(totals))assert.equal(wallet[key],totals[key]);
 pending=responseFor(walletPage,'/api/boost/wallet/ledger');await nav(walletPage,'wallet/index');response=await pending;
 await walletPage.locator('.balance-number').filter({hasText:money(totals.balanceCents)}).waitFor();assert.equal((await walletPage.locator('.balance-number').innerText()).trim(),money(totals.balanceCents));
 const stats=walletPage.locator('.balance-stats > uni-view');await stats.nth(0).getByText(money(totals.pendingCents),{exact:true}).waitFor();await stats.nth(1).getByText(money(totals.totalCents),{exact:true}).waitFor();
 const ledgerQuery=(kind)=>"SELECT JSON_OBJECT('id',id,'title',title,'amountCents',amount_cents,'memberId',member_id) FROM boost_ledger WHERE member_id="+sid+(kind==='income'?' AND amount_cents>0':kind==='expense'?' AND amount_cents<0':'')+' ORDER BY id DESC';
 const allLedger=db(ledgerQuery(''));assert.ok(allLedger.length>0,'Seed wallet must provide a real nonempty ledger');const initialLedger=(await response.json()).data;assert.deepEqual(initialLedger.items.map(x=>x.id),allLedger.slice(0,20).map(x=>x.id));
 await walletPage.locator('.ledger-list .list-row').first().waitFor();for(let i=0;i<Math.min(allLedger.length,20);i++){const row=walletPage.locator('.ledger-list .list-row').nth(i);assert.ok((await row.innerText()).includes(allLedger[i].title));assert.equal((await row.locator('.ledger-amount').innerText()).trim(),(allLedger[i].amountCents>0?'+':'')+money(allLedger[i].amountCents));}
 for(const [kind,label] of [['income','收入'],['expense','支出']]){pending=responseFor(walletPage,'/api/boost/wallet/ledger',{kind,page:'1'});await walletPage.locator('.tabs uni-button').filter({hasText:label}).click();response=await pending;const list=(await response.json()).data,expectedRows=db(ledgerQuery(kind));assert.equal(list.total,expectedRows.length);assert.deepEqual(list.items.map(x=>x.id),expectedRows.slice(0,20).map(x=>x.id));if(list.items.length)await walletPage.locator('.ledger-list .list-row').first().waitFor();else await walletPage.getByText('还没有相关明细',{exact:true}).waitFor();}
 await walletPage.getByText('提现记录',{exact:true}).click();const withdrawals=await api('/boost/wallet/withdrawals',seed.token),withdrawalRows=db("SELECT JSON_OBJECT('id',id,'amountCents',amount_cents,'status',status) FROM boost_withdrawal WHERE member_id="+sid+' ORDER BY id DESC');assert.equal(withdrawals.total,withdrawalRows.length);assert.deepEqual(withdrawals.items.map(x=>x.id),withdrawalRows.slice(0,20).map(x=>x.id));
 await walletPage.screenshot({path:path.join(out,'wallet-readonly.png'),fullPage:true});await walletPage.reload();await walletPage.locator('.balance-number').filter({hasText:money(totals.balanceCents)}).waitFor();checks.push({id:'A32',status:'已实测通过',scope:'独立库SQL合成历史，真实HTTP/UI/SQL核对非空钱包、三项汇总、首20流水显示、收入/支出过滤、提现记录只读及刷新；分页故障另见hardening。不证明实际付款/结算/提现服务闭环，未触发资金业务',memberId:sid,totals,ledgerRows:allLedger.length,withdrawalRows:withdrawalRows.length});
 const overview=await api('/boost-admin/overview');const expectedOverview=db("SELECT JSON_OBJECT('members',(SELECT COUNT(*) FROM boost_member),'orders',(SELECT COUNT(*) FROM boost_order),'activeOrders',(SELECT COUNT(*) FROM boost_order WHERE status IN ('WAIT_START','IN_PROGRESS','REVIEW')),'gmvCents',(SELECT COALESCE(SUM(amount_cents),0) FROM boost_order WHERE status='COMPLETED'),'pendingListings',(SELECT COUNT(*) FROM boost_listing WHERE status='PENDING'),'pendingCertifications',(SELECT COUNT(*) FROM boost_member WHERE certification='PENDING'),'pendingAftersales',(SELECT COUNT(*) FROM boost_aftersale WHERE status='PENDING'),'pendingWithdrawals',(SELECT COUNT(*) FROM boost_withdrawal WHERE status='PENDING'))")[0];
 for(const key of Object.keys(expectedOverview))assert.equal(overview[key],expectedOverview[key],key);const recent=db("SELECT JSON_OBJECT('id',id,'orderNo',order_no,'title',title,'amountCents',amount_cents) FROM boost_order ORDER BY id DESC LIMIT 8");assert.deepEqual(overview.recentOrders.map(x=>x.id),recent.map(x=>x.id));
 const adminPage=await pageFor(null,true);pending=responseFor(adminPage,'/boost-admin/overview');await adminPage.goto(env.admin+'/boost/overview',{waitUntil:'domcontentloaded'});await pending;await adminPage.getByRole('heading',{name:'代练业务概览'}).waitFor();
 for(const [i,key] of ['members','orders','activeOrders','gmvCents'].entries()){const text=key==='gmvCents'?'¥'+(expectedOverview[key]/100).toFixed(2):String(expectedOverview[key]);await adminPage.locator('.overview-stats > div').nth(i).locator('strong').filter({hasText:text}).waitFor();assert.equal((await adminPage.locator('.overview-stats > div').nth(i).locator('strong').innerText()).trim(),text);}
 for(const [i,key] of ['pendingListings','pendingCertifications','pendingAftersales','pendingWithdrawals'].entries()){await adminPage.locator('.todo-count').nth(i).filter({hasText:String(expectedOverview[key])}).waitFor();assert.equal((await adminPage.locator('.todo-count').nth(i).innerText()).trim(),String(expectedOverview[key]));}
 for(let i=0;i<recent.length;i++)await adminPage.locator('.el-table__row').nth(i).getByText(recent[i].orderNo,{exact:true}).waitFor();
 pending=responseFor(adminPage,'/boost-admin/overview');await adminPage.getByRole('button',{name:'刷新数据',exact:true}).click();const refreshed=(await (await pending).json()).data;assert.deepEqual(Object.fromEntries(Object.keys(expectedOverview).map(k=>[k,refreshed[k]])),expectedOverview);
 await adminPage.screenshot({path:path.join(out,'admin-overview-db.png'),fullPage:true});checks.push({id:'A39',status:'已实测通过',scope:'管理员概览8项真实DB聚合、四指标/四待办页面显示、最新8单及刷新；受限运营隐藏详见admin-permissions',statistics:expectedOverview,recentOrderIds:recent.map(x=>x.id)});
 assert.deepEqual(pageErrors,[]);assert.deepEqual(consoleErrors,[]);assert.deepEqual(transportErrors,[]);
})().catch(e=>{failure=e.message;console.error(e.stack);}).finally(async()=>{
 await browser?.close();for(const token of tokens)try{assert.equal((await raw('/boost/auth/logout',token,'POST')).code,200);assert.equal((await raw('/boost/me',token)).code,401);}catch(e){cleanupErrors.push(e.message);}
 let after;try{after=fundsHashes();assert.deepEqual(after,before);}catch(e){cleanupErrors.push('Money read-only verification: '+e.message);}
 const passed=!failure&&checks.length===3&&cleanupErrors.length===0;const result={passed,completedAt:new Date().toISOString(),environment:env,scriptSha256:crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex'),checks,failure,pageErrors,consoleErrors,transportErrors,traffic,cleanup:{sessionsCreated:tokens.length,sessionsRevoked:cleanupErrors.length===0,errors:cleanupErrors,businessWritesAttempted:!!failure?.includes('business write'),before,after},mode:'真实本机HTTP/MySQL与Chromium H5；仅登录/登出写会话，钱包/订单/后台业务仅GET与SELECT；不等同真机或真实资金'};
 fs.writeFileSync(path.join(out,'readonly-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({passed,checks,failure,cleanupErrors},null,2));process.exitCode=passed?0:1;
});
