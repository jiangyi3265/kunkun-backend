const assert=require('node:assert/strict'),{createRequire}=require('node:module');
const {chromium}=createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const base=require('./test-env.cjs').mini;let browser;
(async()=>{
 browser=await chromium.launch();const p=await browser.newPage({viewport:{width:390,height:1200}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 const sample=(await(await fetch(base+'/api/boost/listings/1')).json()).data;
 const pages=[];let fail=true;
 await p.route('**/api/boost/listings?*',async route=>{
  const q=new URL(route.request().url()).searchParams,n=Number(q.get('page')||1);pages.push(n);
  if(n===2&&fail){fail=false;await route.fulfill({status:503,json:{code:503,msg:'测试网络中断，请重试'}});return}
  await route.fulfill({json:{code:200,data:{items:[{...sample,id:90000+n,title:'分页测试第 '+n+' 页'}],total:3,page:n}}});
 });
 await p.goto(base+'/#/pages/market/index',{waitUntil:'domcontentloaded'});await p.getByText('分页测试第 1 页',{exact:true}).waitFor();
 await p.getByText('查看更多服务',{exact:true}).click();await p.getByText('测试网络中断，请重试',{exact:true}).waitFor();
 await p.getByText('查看更多服务',{exact:true}).click();await p.waitForFunction(()=>document.querySelectorAll('.service-name').length===2);
 assert.deepEqual(pages,[1,2,2],'失败重试必须仍然加载第 2 页');await p.getByText('分页测试第 2 页',{exact:true}).waitFor();await p.getByText('查看更多服务',{exact:true}).click();await p.getByText('分页测试第 3 页',{exact:true}).waitFor();
 pages.length=0;await p.goto(base+'/#/pages/shop/index?id=2',{waitUntil:'domcontentloaded'});await p.getByText('分页测试第 1 页',{exact:true}).waitFor();await p.getByText('查看更多服务',{exact:true}).click();await p.getByText('分页测试第 2 页',{exact:true}).waitFor();await p.getByText('查看更多服务',{exact:true}).click();await p.getByText('分页测试第 3 页',{exact:true}).waitFor();assert.deepEqual(pages,[1,2,3]);
 assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,checks:['分页失败后重试不跳页','店铺全部服务可以逐页加载'],errors},null,2));
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>browser?.close());
