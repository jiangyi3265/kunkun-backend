const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),contract=require('./test-contract.cjs'),root=path.resolve(__dirname,'..'),out=require('./test-output.cjs').dir('nonpayment-20261002');
const admin=JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'))).token,stamp=Date.now(),tokens=[],checks=[],errors=[];
let browser,account,listing;
async function api(route,token,method='GET',body){const r=await(await fetch(env.api+route,{method,headers:{'Content-Type':'application/json','X-Boost-Token':token||'',...(route.startsWith('/boost-admin/')?{Authorization:'Bearer '+admin}:{})},body:body?JSON.stringify(contract.bodyFor(route,method,body)):undefined})).json();assert.equal(r.code,200,route+': '+r.msg);return r.data}
(async()=>{
 assert.ok(['127.0.0.1','localhost'].includes(new URL(env.api).hostname));
 account=await api('/boost/auth/register',null,'POST',{username:'qa_nonpay_'+stamp,password:'Nonpayment2026!',nickname:'非支付审核'+stamp});tokens.push(account.token);
 const proof=await contract.upload(account.token,'CERTIFICATION');await api('/boost/me/certification',account.token,'POST',{skill:'仅进行非支付流程审核测试',images:[proof]});
 browser=await chromium.launch();const context=await browser.newContext({viewport:{width:1440,height:1000}});await context.addCookies([{name:'Admin-Token',value:admin,domain:'127.0.0.1',path:'/'}]);const p=await context.newPage();p.setDefaultTimeout(15000);p.on('pageerror',e=>errors.push(e.message));
 async function processRow(type,text,action){await p.goto(env.admin+'/boost/'+type);const row=p.locator('.el-table__row').filter({hasText:text}).first();await row.getByText('查看 / 处理').click();const drawer=p.locator('.el-drawer[aria-label]');await drawer.locator('textarea').fill('非支付回归：根据本次测试提交内容进行审核');await drawer.getByRole('button',{name:action,exact:true}).click();await p.locator('.el-message-box').getByRole('button',{name:'确定',exact:true}).click();await drawer.waitFor({state:'hidden'})}
 await processRow('members',account.user.nickname,'认证通过');assert.equal((await api('/boost/me',account.token)).certification,'APPROVED');checks.push('后台实际点击认证通过，会员状态更新');
 const title='非支付后台审核服务 '+stamp;listing=(await api('/boost/listings',account.token,'POST',{kind:'SERVICE',title,game:'王者荣耀',zone:'微信区',platform:'安卓',currentRank:'星耀',targetRank:'王者',durationHours:24,priceCents:12000,description:'非支付测试，只校验发布审核和可见性',images:[]})).id;
 await processRow('listings',title,'审核通过');assert.equal((await api('/boost/listings/'+listing)).status,'ACTIVE');checks.push('后台实际审核发布，公开列表可以访问');
 await p.screenshot({path:path.join(out,'admin-approved.png'),fullPage:true});
 await processRow('listings',title,'下架');const response=await(await fetch(env.api+'/boost/listings/'+listing)).json();assert.equal(response.code,400);assert.match(response.msg,/下架|审核/);assert.equal(response.data,undefined);checks.push('后台下架后，游客不能继续读取发布');
 await p.goto(env.admin+'/boost/audit');await p.getByRole('heading',{name:'业务操作记录'}).waitFor();
 const auditRows=(await api('/boost-admin/list/audit')).items;
 for(const [action,targetId] of [['members:approve',account.user.id],['listings:approve',listing],['listings:offline',listing]]){
  assert.ok(auditRows.some(row=>row.action===action&&Number(row.targetId)===Number(targetId)),`Missing persisted audit event ${action} ${targetId}`);
  await p.locator('.el-table__row').filter({hasText:action}).filter({has:p.locator('td').filter({hasText:new RegExp('^'+targetId+'$')})}).first().waitFor();
 }
 await p.screenshot({path:path.join(out,'admin-audit.png'),fullPage:true});checks.push('本次认证、上架、下架操作均保存精确业务ID的日志，并在后台展示');
 assert.deepEqual(errors,[]);const result={passed:true,checks,errors};fs.writeFileSync(path.join(out,'admin-ui.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
})().catch(e=>{console.error(e.message);process.exitCode=1}).finally(async()=>{await browser?.close();if(listing)await api('/boost-admin/moderate/listings/'+listing,null,'POST',{action:'offline',note:'非支付测试清理'}).catch(()=>{});await Promise.allSettled(tokens.map(t=>api('/boost/auth/logout',t,'POST')))});
