const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const env=require('./test-env.cjs'),root=path.resolve(__dirname,'..');
const token=process.env.ADMIN_TOKEN||JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'))).token;
let browser,p,release;const errors=[];
(async()=>{
 browser=await chromium.launch();const context=await browser.newContext({viewport:{width:1440,height:1000}});await context.addCookies([{name:'Admin-Token',value:token,domain:'127.0.0.1',path:'/'}]);p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));
 await p.goto(env.admin+'/boost/listings',{waitUntil:'domcontentloaded'});await p.getByRole('heading',{name:'发布审核'}).waitFor();await p.locator('.el-table__row').first().waitFor();
 let started;const waiting=new Promise(r=>started=r),gate=new Promise(r=>release=r);
 await p.route('**/boost-admin/list/listings?*',async route=>{
  const word=new URL(route.request().url()).searchParams.get('keyword');if(word==='慢查询'){started();await gate}
  await route.fulfill({json:{code:200,data:{items:[{id:991,kind:'REQUEST',title:word==='慢查询'?'过期的查询结果':'当前查询结果',nickname:'测试用户',game:'王者荣耀',priceCents:12000,status:'PENDING'}],total:1}}});
 });
 const input=p.locator('.filters input').first();await input.fill('慢查询');await p.getByRole('button',{name:'查询',exact:true}).click();await waiting;
 await input.fill('快查询');await p.getByRole('button',{name:'查询',exact:true}).click();await p.locator('.el-table__row').filter({hasText:'当前查询结果'}).waitFor();
 const response=p.waitForResponse(r=>r.url().includes('/boost-admin/list/listings')&&new URL(r.url()).searchParams.get('keyword')==='慢查询');release();await response;await p.waitForTimeout(250);
 assert.equal(await p.locator('.el-table__row').filter({hasText:'当前查询结果'}).count(),1);assert.equal(await p.locator('.el-table__row').filter({hasText:'过期的查询结果'}).count(),0);assert.deepEqual(errors,[]);
 await p.screenshot({path:require('./test-output.cjs').file('admin-query-state.png')});const report={passed:true,checks:['管理端快速切换查询时，晚到的旧结果不能覆盖当前列表'],errors};fs.writeFileSync(require('./test-output.cjs').file('admin-state.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{release?.();await browser?.close()});
