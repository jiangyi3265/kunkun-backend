const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),root=path.resolve(__dirname,'..'),stamp=Date.now(),password='AuthBoundary2026!',tokens=[],checks=[],errors=[];
let browser,first,order;
async function raw(route,token,method='GET',body){ body=require('./test-contract.cjs').bodyFor(route,method,body);return(await fetch(env.api+route,{method,headers:{'Content-Type':'application/json','X-Boost-Token':token||''},body:body?JSON.stringify(body):undefined})).json()}
async function api(...args){const r=await raw(...args);assert.equal(r.code,200,args[0]+': '+r.msg);return r.data}
(async()=>{
 assert.equal((await api('/boost/config')).simulatedPayment,true);
 first=await api('/boost/auth/register',null,'POST',{username:'qa_auth_a_'+stamp,password,nickname:'边界账号甲'});
 const second=await api('/boost/auth/register',null,'POST',{username:'qa_auth_b_'+stamp,password,nickname:'边界账号乙'});tokens.push(first.token,second.token);
 order=await api('/boost/orders',first.token,'POST',{listingId:1,requestKey:'auth-boundary-'+stamp,note:'账号隔离回归订单'});
 browser=await chromium.launch();const page=await browser.newPage({viewport:{width:390,height:844}});page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
 async function nav(route){await page.goto(env.mini+'/?authBoundary='+Date.now()+'#/pages/'+route,{waitUntil:'domcontentloaded'})}
 async function login(user){await nav('auth/index');await page.locator('.text-input input').nth(0).fill(user);await page.locator('.text-input input').nth(1).fill(password);await page.locator('.check-box').click();await page.getByText('登录',{exact:true}).click();await page.getByText('找高手，上分更轻松',{exact:true}).waitFor();const token=await page.evaluate(()=>localStorage.getItem('boost_token'));tokens.push(token);return token}
 const old=await login(first.user.username);await nav('orders/index');await page.locator('.card').filter({hasText:'待付款'}).waitFor();
 await nav('account/index?type=settings');await page.getByText('退出登录',{exact:true}).click();await page.locator('.uni-modal__btn_primary').click();await page.getByText('找高手，上分更轻松',{exact:true}).waitFor();
 assert.equal((await raw('/boost/me',old)).code,401);checks.push('实际退出登录后，该浏览器旧令牌立即失效');
 const current=await login(second.user.username);await nav('mine/index');await page.getByText('边界账号乙',{exact:true}).waitFor();assert.equal(await page.getByText('边界账号甲',{exact:true}).count(),0);
 await nav('orders/index');await page.getByText('还没有相关订单',{exact:true}).waitFor();assert.equal((await raw('/boost/orders/'+order.id,current)).code,403);assert.notEqual((await raw('/boost-admin/overview',current)).code,200);
 await page.screenshot({path:require('./test-output.cjs').file('deep-account-switch.png'),fullPage:true});checks.push('切换账号后资料和订单隔离，普通用户不能访问他人订单或管理接口');
 for(let i=0;i<5;i++)assert.equal((await raw('/boost/auth/login',null,'POST',{username:first.user.username,password:'wrong-password'})).code,400);
 assert.equal((await raw('/boost/auth/login',null,'POST',{username:first.user.username.toUpperCase(),password:'wrong-password'})).code,429);
 assert.equal((await raw('/boost/auth/login',null,'POST',{username:first.user.username,password})).code,429);
 checks.push('五次错误后，大小写变化和正确密码均无法绕过当前锁定窗口');
 const other=await api('/boost/auth/login',null,'POST',{username:second.user.username.toUpperCase(),password});tokens.push(other.token);assert.equal(other.user.id,second.user.id);
 assert.equal((await raw('/boost/auth/login',null,'POST',{username:first.user.username.replace('qa','qá'),password})).code,400);
 checks.push('锁定不误伤其他账号，拒绝非账号字符变体');
 assert.deepEqual(errors,[]);const result={passed:true,checks,errors};fs.writeFileSync(require('./test-output.cjs').file('deep-auth.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{await browser?.close();if(order)await api('/boost/orders/'+order.id+'/action',first.token,'POST',{action:'cancel'});await Promise.allSettled(tokens.map(t=>api('/boost/auth/logout',t,'POST')))});
