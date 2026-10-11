// Real HTTP/MySQL/Chromium permission matrix on a newly owned database and media directory.
// SQL delivery/aftersale seeds are read fixtures, not proof of a payment or delivery business flow.
require('./nonpayment-request-guard.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),root=path.resolve(__dirname,'..'),out=process.env.QA_OUTPUT_DIR;
assert.ok(out,'QA_OUTPUT_DIR required');fs.mkdirSync(out,{recursive:true});
const fixtureFile=path.resolve(process.env.QA_MEDIA_FIXTURE_FILE||'');assert.ok(fixtureFile.startsWith(path.join(root,'.runtime')+path.sep),'Private fixture path required');
let fixture=JSON.parse(fs.readFileSync(fixtureFile,'utf8'));const revokeProbe=process.argv.includes('--probe-revoke'),systemProbe=process.argv.includes('--probe-system-revocation'),probeOnly=revokeProbe||systemProbe;
assert.ok(!(revokeProbe&&systemProbe),'Select exactly one probe');
assert.match(fixture.database,/^boost_verify_media_[A-Za-z0-9_]{1,32}$/);assert.ok(probeOnly||!fixture.business,'Use a fresh expressly isolated fixture');
const runtimeJar=process.env.QA_MEDIA_RUNTIME_JAR;assert.ok(runtimeJar,'QA_MEDIA_RUNTIME_JAR required');
const runtimeJarSha256=crypto.createHash('sha256').update(fs.readFileSync(runtimeJar)).digest('hex');
if(process.env.QA_MEDIA_EXPECTED_JAR_SHA256)assert.equal(runtimeJarSha256,process.env.QA_MEDIA_EXPECTED_JAR_SHA256,'Wrong runtime JAR');
for(const origin of Object.values(env))assert.ok(['localhost','127.0.0.1'].includes(new URL(origin).hostname),'Local service only');
const stateFile=path.join(path.dirname(fixtureFile),'operators.private.json');assert.ok(!fs.existsSync(stateFile),'Clean prior owned operator state before running');
const checks=[],traffic=[],uiTraffic=[],observations=[],errors=[],cleanupErrors=[],memberTokens=[],operators=[],ownedMenus=[];
let browser,adminToken,failure,financialBefore,fixtureState,financialAfter;
const state={adminToken:null,operators,memberTokens,ownedMenus};
function saveState(){fs.writeFileSync(stateFile,JSON.stringify(state));}
function check(name,passed,details={}){checks.push({name,passed:!!passed,...details});}
function helper(action,data){
 const r=spawnSync(process.env.QA_PYTHON||'C:/Python314/python.exe',['-X','utf8',path.join(root,'scripts/prepare-admin-media-fixture.py'),action,'--fixture',fixtureFile],{cwd:root,input:data?JSON.stringify(data):undefined,encoding:'utf8'});
 assert.equal(r.status,0,'Isolated fixture helper failed: '+r.stderr.replace(/https?:\/\/\S+/g,'[URL]'));return JSON.parse(r.stdout);
}
function sanitized(text){return String(text).replace(/https?:\/\/[^\s]+/g,'[URL]').replace(/[a-zA-Z0-9_-]{48,}/g,'[credential redacted]');}
async function raw(route,token,method='GET',body,member=false){
 assert.ok(method==='GET'||route==='/boost-admin/media/access'||route==='/boost/media/access'||['/login','/logout','/boost/auth/register','/boost/auth/logout'].includes(route)||route.startsWith('/system/role')||route.startsWith('/system/user')||route==='/system/menu'||/^\/system\/menu\/\d+$/.test(route),'Forbidden business write: '+method+' '+route);
 const response=await fetch(env.api+route,{method,headers:{...(member?{'X-Boost-Token':token||''}:{Authorization:token?'Bearer '+token:''}),'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const result=await response.json();traffic.push({method,path:route.split('?')[0],status:response.status,code:result.code});return result;
}
async function api(...args){const r=await raw(...args);assert.equal(r.code,200,args[0]+': '+sanitized(r.msg));return r;}
async function adminLogin(username,password){
 const captcha=await api('/captchaImage');let code='';
 if(captcha.captchaEnabled!==false){
  assert.match(captcha.uuid,/^[A-Za-z0-9-]+$/);
  const privateConfig=JSON.parse(fs.readFileSync(path.join(root,'.runtime/local-dev.json'),'utf8'));
  const host=process.env.QA_MEDIA_REDIS_HOST||privateConfig.BOOST_REDIS_HOST||'127.0.0.1';assert.ok(['127.0.0.1','localhost'].includes(host));
  const childEnv={...process.env},password=process.env.QA_MEDIA_REDIS_PASSWORD??privateConfig.BOOST_REDIS_PASSWORD;
  if(password)childEnv.REDISCLI_AUTH=password;
  const r=spawnSync(process.env.QA_REDIS_CLI||'C:/Program Files/Redis/redis-cli.exe',['-h',host,'-p',process.env.QA_MEDIA_REDIS_PORT||'6379','-n',String(process.env.QA_MEDIA_REDIS_DATABASE??privateConfig.BOOST_REDIS_DATABASE??0),'--raw','GET','captcha_codes:'+captcha.uuid],{encoding:'utf8',env:childEnv});
  assert.equal(r.status,0,'Local own CAPTCHA read failed');code=JSON.parse(r.stdout.trim());
 }
 return (await api('/login',null,'POST',{username,password,code:String(code),uuid:captcha.uuid||''})).token;
}
const purposes=['CERTIFICATION','LISTING','DELIVERY','AFTERSALE','CHAT','AVATAR'];
const roleDefinitions=[
 {key:'cert',menu:87003,permission:'boost:members:read',allowed:['CERTIFICATION','AVATAR'],view:'members'},
 {key:'listing',menu:87002,permission:'boost:listings:read',allowed:['LISTING'],view:'listings'},
 {key:'delivery',menu:87004,permission:'boost:orders:read',allowed:['DELIVERY'],view:'orders'},
 {key:'aftersale',menu:87005,permission:'boost:aftersales:read',allowed:['AFTERSALE'],view:'aftersales'},
 {key:'none',menu:87001,permission:'boost:overview:read',allowed:[],view:null}
];
async function createOperator(def){
 const name='qa_mm_'+def.key.slice(0,2)+'_'+Date.now().toString(36),operator={...def,name,password:crypto.randomBytes(15).toString('base64url')};operators.push(operator);saveState();
 operator.roleBody={roleName:name,roleKey:name,roleSort:99,status:'0',dataScope:'1',menuCheckStrictly:true,deptCheckStrictly:true,menuIds:def.menuIds||[87000,def.menu],deptIds:[]};
 await api('/system/role',adminToken,'POST',operator.roleBody);
 operator.roleId=(await api('/system/role/list?roleKey='+name,adminToken)).rows.find(x=>x.roleKey===name).roleId;saveState();
 await api('/system/user',adminToken,'POST',{userName:name,nickName:'隔离媒体权限QA',password:operator.password,status:'0',roleIds:[operator.roleId],postIds:[]});
 operator.userId=(await api('/system/user/list?userName='+name,adminToken)).rows.find(x=>x.userName===name).userId;saveState();
 operator.token=await adminLogin(name,operator.password);saveState();
 const info=await api('/getInfo',operator.token);assert.ok(info.permissions.includes(def.permission));
 assert.ok(!info.permissions.includes('*:*:*'));assert.deepEqual(info.permissions.filter(x=>/^boost:/.test(x)).sort(),def.expectedBoostPermissions||(def.permission.startsWith('boost:')?[def.permission]:[]));return operator;
}
async function grant(token,paths,member=false){return (await api(member?'/boost/media/access':'/boost-admin/media/access',token,'POST',{paths},member)).data;}
async function image(url,expected,description){
 assert.match(url,/^\/boost-media\/[a-f0-9]{32}\.png(?:\?|$)/);
 const response=await fetch(env.api+url);traffic.push({method:'GET',path:url.split('?')[0],status:response.status,purpose:'media'});
 check(description,response.status===expected,{httpStatus:response.status,expected});
 if(response.status===200){
  assert.match(response.headers.get('content-type'),/image\/png/);
  assert.match(response.headers.get('cache-control'),/no-store/);
  const bytes=Buffer.from(await response.arrayBuffer()),file=path.join(fixture.mediaDirectory,url.split('?')[0].split('/').pop());
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),'Real returned file must match isolated media bytes');
 }return response.status;
}
async function pageFor(operator){
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addCookies([{name:'Admin-Token',value:operator.token,domain:new URL(env.admin).hostname,path:'/'}]);
 const page=await context.newPage();page.setDefaultTimeout(20000);page.setDefaultNavigationTimeout(30000);
 page.on('pageerror',e=>errors.push({type:'page',message:sanitized(e.message)}));
 page.on('console',m=>{if(m.type()==='error')errors.push({type:'console',message:sanitized(m.text())});});
 page.on('requestfailed',r=>{if(!r.failure()?.errorText.includes('ERR_ABORTED'))errors.push({type:'transport',path:new URL(r.url()).pathname,message:r.failure()?.errorText});});
 page.on('response',r=>{const p=new URL(r.url()).pathname;if(/\/boost-admin\/media\/access$/.test(p)||p.includes('/boost-media/'))uiTraffic.push({operator:operator.key,method:r.request().method(),path:p,status:r.status()});});
 await context.route('**/*',async route=>{const request=route.request(),p=new URL(request.url()).pathname;if(request.method()!=='GET'&&/\/(boost|boost-admin)\//.test(p)&&!p.endsWith('/boost-admin/media/access')){errors.push({type:'forbidden-business-write',path:p,method:request.method()});await route.abort('blockedbyclient');return;}await route.fallback();});
 return page;
}
async function preview(page,selector,operator,tag){
 const authorization=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/boost-admin/media/access')&&r.request().method()==='POST');
 await page.locator(selector).first().click();const response=await authorization;assert.equal((await response.json()).code,200);
 const viewer=page.locator('.el-image-viewer__canvas img');await viewer.waitFor();
 await viewer.evaluate(img=>img.complete&&img.naturalWidth>0?true:new Promise((resolve,reject)=>{img.onload=()=>resolve(true);img.onerror=()=>reject(new Error('real evidence failed to decode'));}));
 check('真实UI预览 '+tag,true,{operator:operator.key});await page.screenshot({path:path.join(out,'preview-'+tag+'.png'),animations:'disabled'});
 await page.locator('.el-image-viewer__close').click();
}
async function systemRevocation(){
 const menu=(await api('/system/menu/list',adminToken)).data.find(x=>x.perms==='system:role:list'&&x.status==='0');assert.ok(menu,'Actual active system:role:list menu required');
 for(const mutation of ['menu-removal','user-disable','role-disable','role-unassignment']){
  const operator=await createOperator({key:'system-'+mutation,permission:'system:role:list',allowed:[],view:null,menuIds:[menu.menuId]}),route='/system/role/list?roleKey='+operator.name;
  const before=await api(route,operator.token);assert.ok(before.rows.some(x=>x.roleId===operator.roleId&&x.roleKey===operator.name));check('系统接口授权前正常角色列表 '+mutation,true,{roleId:operator.roleId,userId:operator.userId});
  if(mutation==='menu-removal')await api('/system/role',adminToken,'PUT',{...operator.roleBody,roleId:operator.roleId,menuIds:[]});
  if(mutation==='user-disable')await api('/system/user/changeStatus',adminToken,'PUT',{userId:operator.userId,status:'1'});
  if(mutation==='role-disable')await api('/system/role/changeStatus',adminToken,'PUT',{roleId:operator.roleId,status:'1'});
  if(mutation==='role-unassignment')await api('/system/role/authUser/cancel',adminToken,'PUT',{roleId:operator.roleId,userId:operator.userId});
  const after=await raw(route,operator.token),allowedCodes=mutation==='menu-removal'?[403]:[401,403];
  check('系统接口同会话立即拒绝 '+mutation,allowedCodes.includes(after.code)&&(!after.rows||after.rows.length===0),{roleId:operator.roleId,userId:operator.userId,code:after.code,rowsDelivered:after.rows?.length||0,expectedCodes:allowedCodes,sessionRefreshedBeforeProbe:false});
 }
}
async function systemGrantBoundaries(){
 const menus=(await api('/system/menu/list',adminToken)).data;
 const list=menus.find(x=>x.perms==='system:role:list'&&x.status==='0');assert.ok(list,'Active system role list menu required');
 // Copied source menus may intentionally disable system administration. Add only an expressly
 // owned permission fixture after the API/database/media ownership probe; never enable source rows.
 const ownedEdit={name:'qa_mm_edit_'+Date.now().toString(36)};ownedMenus.push(ownedEdit);saveState();
 await api('/system/menu',adminToken,'POST',{menuName:ownedEdit.name,parentId:0,orderNum:999,path:'#',isFrame:'1',isCache:'0',menuType:'F',visible:'0',status:'0',perms:'system:role:edit',icon:'#'});
 const edit=(await api('/system/menu/list?menuName='+encodeURIComponent(ownedEdit.name),adminToken)).data.find(x=>x.menuName===ownedEdit.name&&x.perms==='system:role:edit'&&x.status==='0'&&x.menuType==='F');
 assert.ok(edit,'Owned active role-edit permission fixture must persist');ownedEdit.id=edit.menuId;saveState();check('专属库角色编辑权限夹具真实创建并读回',true,{menuId:edit.menuId});
 const operator=await createOperator({key:'system-grants',permission:'system:role:edit',allowed:[],view:null,menuIds:[87000,87001,edit.menuId],expectedBoostPermissions:['boost:overview:read']});
 const routerComponents=routes=>routes.flatMap(route=>[route.component,...routerComponents(route.children||[])]);
 const routerPaths=routes=>routes.flatMap(route=>[route.path,...routerPaths(route.children||[])]);
 const ordersMenu=menus.find(x=>x.perms==='boost:orders:read'&&x.status==='0');assert.ok(ordersMenu,'Active orders menu required');
 check('初始授权菜单正常显示',routerComponents((await api('/getRouters',operator.token)).data).includes('boost/overview'));
 const route='/system/role/list?roleKey='+operator.name;
 check('新增权限前真实接口拒绝',(await raw(route,operator.token)).code===403);
 const granted={...operator.roleBody,roleId:operator.roleId,menuIds:[87000,87001,edit.menuId,list.menuId,ordersMenu.menuId]};
 await api('/system/role',adminToken,'PUT',granted);
 const info=await api('/getInfo',operator.token);
 check('getInfo不把新菜单权限写入旧会话',!info.permissions.includes('system:role:list'));
 check('getInfo后旧会话仍拒绝新接口',(await raw(route,operator.token)).code===403);
 check('旧会话刷新路由不显示尚未取得的订单菜单',!routerPaths((await api('/getRouters',operator.token)).data).includes('orders'));
 await api('/system/role',operator.token,'PUT',granted);
 check('角色编辑者旧会话仍拒绝新菜单权限',(await raw(route,operator.token)).code===403);
 check('角色编辑后旧会话路由仍保持授权边界',!routerPaths((await api('/getRouters',operator.token)).data).includes('orders'));
 // A fresh normal CAPTCHA login must see the persisted database grant.
 operator.previousTokens=[operator.token];operator.token=await adminLogin(operator.name,operator.password);saveState();
 const fresh=await api('/getInfo',operator.token),rows=(await api(route,operator.token)).rows;
 check('重新登录获取持久化新菜单权限',fresh.permissions.includes('system:role:list')&&rows.some(x=>x.roleId===operator.roleId));
 check('重新登录显示已取得的订单菜单',routerPaths((await api('/getRouters',operator.token)).data).includes('orders'));
 const added=await createOperator({key:'system-added-role',permission:'system:role:list',allowed:[],view:null,menuIds:[list.menuId]});
 await api('/system/user/authRole?userId='+operator.userId+'&roleIds='+operator.roleId+','+added.roleId,adminToken,'PUT');
 check('新增角色后getInfo仍保持登录角色边界',!(await api('/getInfo',operator.token)).roles.includes(added.name));
 await api('/system/role',operator.token,'PUT',granted);
 check('角色编辑不把新分配角色写入旧会话',!(await api('/getInfo',operator.token)).roles.includes(added.name));
 operator.previousTokens.push(operator.token);operator.token=await adminLogin(operator.name,operator.password);saveState();
 check('重新登录获取持久化新角色',(await api('/getInfo',operator.token)).roles.includes(added.name));
}
(async()=>{
 financialBefore=helper('inspect');adminToken=await adminLogin(fixture.credentials.username,fixture.credentials.password);state.adminToken=adminToken;saveState();
 // HTTP probe binds this API and media root to the isolated fixture before any member/system writes.
 const probe=(await api('/boost-admin/list/listings?keyword='+encodeURIComponent(fixture.probe.title),adminToken)).data;
 assert.equal(probe.total,1);assert.equal(probe.items[0].id,fixture.probe.listingId);assert.equal(probe.items[0].title,fixture.probe.title);
 const probeGrant=await grant(adminToken,[fixture.probe.path]);assert.ok(probeGrant.urls[fixture.probe.path]);
 const probeBytes=Buffer.from(await (await fetch(env.api+probeGrant.urls[fixture.probe.path])).arrayBuffer());assert.equal(crypto.createHash('sha256').update(probeBytes).digest('hex'),fixture.probe.sha256,'The running service uses another media directory');
 check('真实API库/私密目录隔离探针',true,{database:fixture.database});
 if(revokeProbe){
  assert.ok(fixture.business&&fixture.assets.CERTIFICATION,'Probe needs a preserved isolated certification fixture');
  const operator=await createOperator(roleDefinitions[0]),asset=fixture.assets.CERTIFICATION;
  const before=await grant(operator.token,[asset]);check('撤权前真实运营授权',!!before.urls[asset]);
  await api('/system/role',adminToken,'PUT',{...operator.roleBody,roleId:operator.roleId,menuIds:[87000]});
  const after=await grant(operator.token,[asset]);check('撤权后旧会话新授权立即拒绝',!after.urls[asset],{roleId:operator.roleId,userId:operator.userId,actuallyGranted:!!after.urls[asset]});
  await api('/getInfo',operator.token);const refreshed=await grant(operator.token,[asset]);check('正常getInfo刷新后仍拒绝授权',!refreshed.urls[asset]);
  observations.push({name:'Already issued operator bearer grant after role removal',httpStatus:await(await fetch(env.api+before.urls[asset])).status,scope:'Existing short-lived grant, separate from new authorization'});return;
 }
 await systemRevocation();await systemGrantBoundaries();if(systemProbe)return;
 const config=(await api('/boost/config')).data,stamp=Date.now(),members={},owner={};
 for(const key of ['owner','peer','stranger']){
  const login=(await api('/boost/auth/register',null,'POST',{username:'qa_mm_'+key+'_'+stamp,password:crypto.randomBytes(16).toString('base64url'),nickname:'QA媒体'+key,acceptedPolicy:config.policyVersion},true)).data;
  memberTokens.push(login.token);saveState();members[key]=login.user.id;if(key==='owner')Object.assign(owner,login);
 }
 const assets={},png=fs.readFileSync(path.join(fixture.mediaDirectory,fixture.probe.path.split('/').pop()));
 for(const purpose of purposes){
  const data=new FormData();data.set('purpose',purpose);data.set('file',new Blob([png],{type:'image/png'}),'qa-'+purpose+'.png');
  const uploadToken=purpose==='AFTERSALE'?memberTokens[1]:owner.token;
  const response=await fetch(env.api+'/boost/upload',{method:'POST',headers:{'X-Boost-Token':uploadToken},body:data}),body=await response.json();assert.equal(body.code,200);assets[purpose]=body.data.url;traffic.push({method:'POST',path:'/boost/upload',purpose,status:response.status,code:body.code});
 }
 helper('seed',{members,assets});fixture=JSON.parse(fs.readFileSync(fixtureFile,'utf8'));fixtureState=helper('inspect');
 check('六用途真实上传及独立关联夹具',true,{members,assets,seededBusiness:fixture.business,scope:'Order/progress/aftersale are zero-money SQL read seeds; no payment/delivery/aftersale action ran'});
 for(const purpose of purposes)await image(assets[purpose],403,'匿名直接读取拒绝 '+purpose);
 const anonymous=await grant(null,Object.values(assets));check('匿名授权不返回私密URL',Object.keys(anonymous.urls).length===0);
 const stranger=await grant(memberTokens[2],Object.values(assets),true);check('无关会员不能授权私密媒体',Object.keys(stranger.urls).length===0);
 const owned=await grant(owner.token,Object.values(assets),true);check('上传者/订单参与者可授权相关六用途',Object.keys(owned.urls).length===6);
 const chatPeer=await grant(memberTokens[1],[assets.CHAT],true);check('真实聊天对方参与者可授权聊天图片',!!chatPeer.urls[assets.CHAT]);await image(chatPeer.urls[assets.CHAT],200,'真实聊天参与者媒体内容可读');
 for(const def of roleDefinitions){
  const operator=await createOperator(def),result=await grant(operator.token,Object.values(assets));
  for(const purpose of purposes){const allowed=def.allowed.includes(purpose),url=result.urls[assets[purpose]];check('用途权限矩阵 '+def.key+' / '+purpose,!!url===allowed,{permission:def.permission,purpose,allowed,actuallyGranted:!!url});if(url)await image(url,200,'对应运营真实媒体可读 '+def.key+' / '+purpose);}
  const detail=await raw('/boost-admin/orders/'+fixture.business.orderId,operator.token);
  if(def.key==='delivery'){
   assert.equal(detail.code,200);assert.equal(detail.data.id,fixture.business.orderId);assert.equal(detail.data.buyerId,members.peer);assert.equal(detail.data.workerId,members.owner);
   assert.deepEqual(JSON.parse(detail.data.snapshotImages),[assets.LISTING]);assert.ok(detail.data.updates.some(x=>JSON.parse(x.images).includes(assets.DELIVERY)));
   check('真实订单详情与隔离参与者/快照/交付材料一致',true);
  }else check('非订单运营不能直接读取订单详情 '+def.key,detail.code===403,{code:detail.code});
 }
 const adminGrants=await grant(adminToken,Object.values(assets));check('管理员不能绕过聊天媒体限制',!adminGrants.urls[assets.CHAT]);
 browser=await chromium.launch();
 for(const operator of operators.filter(x=>x.view)){
  const page=await pageFor(operator);operator.page=page;await page.goto(env.admin+'/boost/'+operator.view,{waitUntil:'domcontentloaded'});
  const row=page.locator('.el-table__row').filter({hasText:operator.view==='members'?owner.user.username:operator.view==='listings'?'QA media fixture':fixture.business.orderNo});
  await row.getByRole('button',{name:'查看 / 处理',exact:true}).click();await page.locator('.el-drawer[aria-label]').waitFor();
  const selector=operator.view==='orders'?'.section .el-image':'.evidence .el-image';operator.selector=selector;
  await page.locator(selector+' img').first().waitFor();await preview(page,selector,operator,operator.key);
 }
 // Test immediate new authorization separately from a normal getInfo permission refresh.
 const revoked=operators.find(x=>x.key==='cert'),oldGrant=(await grant(revoked.token,[assets.CERTIFICATION])).urls[assets.CERTIFICATION];
 await api('/system/role',adminToken,'PUT',{...revoked.roleBody,roleId:revoked.roleId,menuIds:[87000]});
 const immediate=await grant(revoked.token,[assets.CERTIFICATION]);check('撤权后旧会话新授权立即拒绝',!immediate.urls[assets.CERTIFICATION],{roleId:revoked.roleId,userId:revoked.userId});
 await api('/getInfo',revoked.token);const refreshed=await grant(revoked.token,[assets.CERTIFICATION]);check('正常getInfo刷新后仍拒绝授权',!refreshed.urls[assets.CERTIFICATION]);
 const oldStatus=await (await fetch(env.api+oldGrant)).status;observations.push({name:'Already issued operator bearer grant after role removal',httpStatus:oldStatus,scope:'Records existing 120-second grant semantics; not counted as a new authorization or an expired-link check'});
 const denied=revoked.page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/boost-admin/media/access')&&r.request().method()==='POST');
 await revoked.page.locator(revoked.selector).first().click();assert.equal(Object.keys((await (await denied).json()).data.urls).length,0);await revoked.page.getByText('图片暂时无法查看，请刷新后重试',{exact:true}).waitFor();assert.equal(await revoked.page.locator('.el-image-viewer__wrapper').count(),0);check('撤权真实UI重新授权拒绝预览',true);await revoked.page.screenshot({path:path.join(out,'preview-revoked.png')});
 // No clock mocking. Wait for an unchanged signed URL to expire at actual server time.
 const listing=operators.find(x=>x.key==='listing'),beforeExpiry=await grant(listing.token,[assets.LISTING]),oldUrl=beforeExpiry.urls[assets.LISTING];assert.ok(oldUrl);
 const deadline=(beforeExpiry.expiresAt+2)*1000;console.log('Waiting for actual server-issued media grant expiry; no clock or media responses mocked');
 while(Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,Math.min(30000,deadline-Date.now())));
 await image(oldGrant,403,'已撤权运营原短时签名真实到期拒绝');
 await image(oldUrl,403,'真实服务器时间旧签名到期拒绝');
 const afterExpiry=await grant(listing.token,[assets.LISTING]);check('到期后服务器重新签发新权限',afterExpiry.expiresAt>beforeExpiry.expiresAt&&!!afterExpiry.urls[assets.LISTING]);await image(afterExpiry.urls[assets.LISTING],200,'新签名真实内容可读');
 await preview(listing.page,listing.selector,listing,'listing-after-real-expiry');
 check('页/控制台/传输错误门禁',errors.length===0,{errors});
})().catch(e=>{failure=sanitized(e.stack||e.message);console.error(failure);}).finally(async()=>{
 await browser?.close();
 for(const operator of operators){
  try{for(const token of [...(operator.previousTokens||[]),...(operator.token?[operator.token]:[])]){await api('/logout',token,'POST',{});check('自有运营会话已撤销 '+operator.key,(await raw('/getInfo',token)).code===401);}}
  catch(e){cleanupErrors.push(sanitized(e.message));}
  try{if(operator.userId)await api('/system/user/'+operator.userId,adminToken,'DELETE');if(operator.roleId)await api('/system/role/'+operator.roleId,adminToken,'DELETE');}
  catch(e){cleanupErrors.push(sanitized(e.message));}
 }
 for(const token of memberTokens)try{await api('/boost/auth/logout',token,'POST',{},true);assert.equal((await raw('/boost/me',token,'GET',undefined,true)).code,401);}catch(e){cleanupErrors.push(sanitized(e.message));}
 for(const owned of ownedMenus)try{
  const found=(await api('/system/menu/list?menuName='+encodeURIComponent(owned.name),adminToken)).data.filter(x=>x.menuName===owned.name);
  assert.ok(found.length<=1,'Owned menu fixture name must be unique');
  if(found.length){if(owned.id)assert.equal(found[0].menuId,owned.id);assert.equal(found[0].perms,'system:role:edit');await api('/system/menu/'+found[0].menuId,adminToken,'DELETE');}
  const after=(await api('/system/menu/list?menuName='+encodeURIComponent(owned.name),adminToken)).data.filter(x=>x.menuName===owned.name);
  assert.equal(after.length,0,'Owned role-edit menu must be removed');check('自有角色编辑权限菜单已清理',true,{menuId:owned.id});
 }catch(e){cleanupErrors.push(sanitized(e.message));}
 try{financialAfter=helper('inspect');assert.equal(financialAfter.walletTotalCents,financialBefore.walletTotalCents,'Isolated wallet sum must return to pre-run baseline');assert.equal(financialAfter.boost_ledger,0,'Media suite must not create ledger rows');assert.equal(financialAfter.boost_withdrawal,0,'Media suite must not create withdrawal rows');assert.equal(financialAfter.boost_session,financialBefore.boost_session,'Member sessions must return to pre-run baseline');assert.equal(financialAfter.activeQaOperators,financialBefore.activeQaOperators,'Owned operator count must return to pre-run baseline');assert.equal(financialAfter.activeQaRoles,financialBefore.activeQaRoles,'Owned role count must return to pre-run baseline');if(financialAfter.qaSessions)assert.ok(financialAfter.qaSessions.every(x=>x.sessions===0),'Every QA member session must be revoked');}catch(e){cleanupErrors.push(sanitized(e.message));}
 try{if(adminToken){await api('/logout',adminToken,'POST',{});assert.equal((await raw('/getInfo',adminToken)).code,401);}}catch(e){cleanupErrors.push(sanitized(e.message));}
 if(cleanupErrors.length===0&&fs.existsSync(stateFile))fs.unlinkSync(stateFile);
 const passed=!failure&&checks.every(x=>x.passed)&&cleanupErrors.length===0&&checks.length>(probeOnly?3:35);
 const result={passed,completeSuite:!probeOnly,completedAt:new Date().toISOString(),scriptSha256:crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex'),runtimeJarSha256,environment:{...env,database:fixture.database},checks,failure,errors,observations,traffic,uiTraffic,cleanup:{errors:cleanupErrors,privateOperatorStateRemoved:!fs.existsSync(stateFile),financialBefore,fixtureState,financialAfter},scope:systemProbe?'Real HTTP system cached-session revocation and grant/relogin boundaries probe only; not full media/UI/expiry acceptance':revokeProbe?'Real HTTP revocation probe only; not complete matrix/UI/expiry acceptance':'Real local HTTP/MySQL/files/Chromium, normal CAPTCHA/operator roles. Explicit independent media read seeds only. No shared ha business writes, payments, refunds, withdrawals, progress/aftersale actions or clock/response mocks.'};
 fs.writeFileSync(path.join(out,systemProbe?'probe-system-revocation.json':revokeProbe?'probe-revoke.json':'admin-media-real.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({passed,completeSuite:!probeOnly,checks:checks.length,failedChecks:checks.filter(x=>!x.passed),failure,cleanupErrors},null,2));process.exitCode=passed?0:1;
});
