const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{randomBytes}=require('node:crypto');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),root=path.resolve(__dirname,'..'),stateFile=path.join(root,'.runtime/readonly-qa.json');
const admin=JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'))).token;
async function raw(route,token,method='GET',body){return(await fetch(env.api+route,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined})).json()}
async function api(route,token=admin,method='GET',body){const r=await raw(route,token,method,body);assert.equal(r.code,200,route+': '+r.msg);return r}
(async()=>{
 assert.ok(['127.0.0.1','localhost'].includes(new URL(env.api).hostname));
 if(process.argv[2]==='setup'){
  assert.ok(!fs.existsSync(stateFile),'Clean the prior QA role before setting up again');const name='qa_ro_'+Date.now(),state={name,password:randomBytes(12).toString('base64url')};fs.writeFileSync(stateFile,JSON.stringify(state));
  await api('/system/role',admin,'POST',{roleName:name,roleKey:name,roleSort:99,status:'0',dataScope:'1',menuCheckStrictly:true,deptCheckStrictly:true,menuIds:[87000,87001,87002],deptIds:[]});
  state.roleId=(await api('/system/role/list?roleKey='+name)).rows.find(r=>r.roleKey===name).roleId;fs.writeFileSync(stateFile,JSON.stringify(state));
  await api('/system/user',admin,'POST',{userName:name,nickName:'本地权限回归',password:state.password,status:'0',roleIds:[state.roleId],postIds:[]});
  state.userId=(await api('/system/user/list?userName='+name)).rows.find(r=>r.userName===name).userId;fs.writeFileSync(stateFile,JSON.stringify(state));console.log('Created isolated read-only QA operator');return;
 }
 const state=JSON.parse(fs.readFileSync(stateFile));let browser;
 try{
  if(process.argv[2]!=='cleanup'){
   const token=state.token;assert.ok(token);const info=await api('/getInfo',token);assert.ok(info.permissions.includes('boost:listings:read'));assert.ok(!info.permissions.includes('boost:listings:review'));
   const overview=(await api('/boost-admin/overview',token)).data;assert.equal(overview.recentOrders,undefined);
   await api('/boost-admin/list/listings',token);
   for(const [route,method,body]of[['/boost-admin/list/members','GET'],['/boost-admin/list/orders','GET'],['/boost-admin/moderate/listings/1','POST',{action:'approve',note:'Forbidden QA attempt'}],['/boost-admin/orders/1/reconcile','POST',{action:'refresh',note:'Forbidden QA attempt'}]]){if(process.env.QA_EXCLUDE_PAYMENTS==='1'&&route.includes('/reconcile'))continue;assert.equal((await raw(route,token,method,body)).code,403,route)}
   browser=await chromium.launch();const context=await browser.newContext({viewport:{width:1440,height:1000}});await context.addCookies([{name:'Admin-Token',value:token,domain:'127.0.0.1',path:'/'}]);const p=await context.newPage(),errors=[];p.on('pageerror',e=>errors.push(e.message));
   await p.goto(env.admin+'/boost/overview',{waitUntil:'domcontentloaded'});await p.getByRole('heading',{name:'代练业务概览'}).waitFor();assert.equal(await p.getByRole('heading',{name:'最近订单'}).count(),0);assert.equal(await p.locator('.todo-row').count(),1);
   await p.goto(env.admin+'/boost/listings',{waitUntil:'domcontentloaded'});await p.locator('.el-table__row').first().waitFor();await p.locator('.el-table__row').first().getByText('查看 / 处理').click();await p.locator('.el-drawer[aria-label]').waitFor();assert.equal(await p.getByRole('button',{name:'审核通过',exact:true}).count(),0);assert.equal(await p.getByRole('button',{name:'驳回',exact:true}).count(),0);assert.deepEqual(errors,[]);
   await p.screenshot({path:require('./test-output.cjs').file('remediation-20261002/readonly-admin.png'),fullPage:true});const report={passed:true,checks:['受限运营仅获取授权的概览和发布列表',process.env.QA_EXCLUDE_PAYMENTS==='1'?'后台直接调用会员、订单、审核接口均返回 403':'后台直接调用会员、订单、审核、资金接口均返回 403','概览不泄露订单明细，页面不出现未授权审核按钮'],errors};fs.writeFileSync(require('./test-output.cjs').file('remediation-20261002/admin-permissions.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
  }
 }finally{
  await browser?.close();if(state.token)await raw('/logout',state.token,'POST');if(state.userId)await api('/system/user/'+state.userId,admin,'DELETE');if(state.roleId)await api('/system/role/'+state.roleId,admin,'DELETE');fs.unlinkSync(stateFile);
 }
})().catch(e=>{console.error(e);process.exitCode=1});
