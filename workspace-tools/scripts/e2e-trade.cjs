const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{createRequire}=require('node:module');
const {chromium}=createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const root=path.resolve(__dirname,'..'),base=require('./test-env.cjs').mini;
let activeBrowser;const errors=[];const out=path.join(root,'test-results');fs.mkdirSync(out,{recursive:true});
async function navigate(p,route){await p.goto(base+'/#/pages/'+route,{waitUntil:'domcontentloaded'});}
async function has(p,text){await p.getByText(text,{exact:false}).first().waitFor({timeout:15000});}
async function click(p,text){await p.getByText(text,{exact:true}).last().click();}
async function modal(p){await p.locator('.uni-modal__btn_primary').click();}
async function login(p,role){await navigate(p,'auth/index');await click(p,role==='buyer'?'使用测试买家':'使用测试打手');await has(p,'找高手，上分更轻松');}
(async()=>{
 const browser=activeBrowser=await chromium.launch({headless:true});const buyerContext=await browser.newContext({viewport:{width:390,height:844}}),workerContext=await browser.newContext({viewport:{width:390,height:844}});const buyer=await buyerContext.newPage(),worker=await workerContext.newPage();
 for(const p of [buyer,worker])p.on('pageerror',e=>errors.push(e.message));
 await login(buyer,'buyer');await login(worker,'worker');
 await navigate(buyer,'market/index');await buyer.locator('.search input').fill('没有这个游戏XYZ');await click(buyer,'搜索');await has(buyer,'没有找到相关服务');
 await navigate(buyer,'service/detail?id=1');await has(buyer,'王者排位');await buyer.screenshot({path:path.join(out,'service-mobile.png')});await click(buyer,'立即下单');await has(buyer,'确认下单');await buyer.locator('.check-box').click();await click(buyer,'确认下单并支付');await has(buyer,'测试支付');await modal(buyer);await buyer.waitForURL(/pages\/orders\/detail\?id=/);await has(buyer,'已付款，等待打手开始服务');const id=new URL(buyer.url().split('#')[1],'http://test').searchParams.get('id');assert.ok(id);
 await navigate(worker,'orders/detail?id='+id);await has(worker,'开始服务');await click(worker,'开始服务');await modal(worker);await has(worker,'同步进度');
 await worker.locator('.field-row input').fill('100');await worker.locator('.textarea textarea').fill('目标已完成，成果截图已上传，请验收。');
 const chooser=worker.waitForEvent('filechooser');await worker.locator('.upload-add').click();await(await chooser).setFiles(path.join(root,'代练/static/logo.png'));await worker.locator('.upload-thumb').waitFor();await click(worker,'提交进度');await has(worker,'请查看成果截图，再确认验收');
 await navigate(buyer,'orders/detail?id='+id);await has(buyer,'确认验收');await buyer.screenshot({path:path.join(out,'order-review-mobile.png'),fullPage:true});await click(buyer,'确认验收');await modal(buyer);await has(buyer,'为这次服务打个分');await buyer.locator('.textarea textarea').fill('进度清楚，沟通及时，已经完成目标。');await click(buyer,'提交评价');await buyer.getByText('为这次服务打个分').waitFor({state:'hidden'});
 await click(buyer,'联系打手');await has(buyer,'请在平台内沟通');const conversation=new URL(buyer.url().split('#')[1],'http://test').searchParams.get('id');const message='验收完成，谢谢！'+Date.now();await buyer.locator('.chat-input input').fill(message);await click(buyer,'发送');await has(buyer,message);
 await navigate(worker,'chat/index?id='+conversation);await has(worker,message);await worker.locator('.chat-input input').fill('收到，期待下次合作。');await click(worker,'发送');await has(buyer,'收到，期待下次合作。');await buyer.screenshot({path:path.join(out,'chat-mobile.png'),fullPage:true});
 await navigate(worker,'wallet/index');await has(worker,'订单结算');await worker.screenshot({path:path.join(out,'wallet-mobile.png'),fullPage:true});
 await navigate(buyer,'mine/index');await has(buyer,'小橘同学');await buyer.screenshot({path:path.join(out,'mine-mobile.png')});assert.equal(await buyer.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false,'mobile horizontal overflow');
 await buyer.setViewportSize({width:1280,height:900});await navigate(buyer,'index/index');const width=await buyer.locator('.app-shell').evaluate(e=>e.getBoundingClientRect().width);assert.ok(width<=480);await buyer.screenshot({path:path.join(out,'home-desktop.png')});
 assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,orderId:id,conversationId:conversation,checks:['login both roles','search empty state','checkout and simulated payment','worker start','upload and complete progress','buyer acceptance','review','two-way persisted chat','wallet settlement','mobile and desktop layout'],errors},null,2));await browser.close();
})().catch(async e=>{console.error(e);if(activeBrowser){for(const [i,c]of activeBrowser.contexts().entries())for(const [j,p]of c.pages().entries()){console.error('PAGE',i,j,p.url(),(await p.locator('body').innerText()).slice(-2500));await p.screenshot({path:path.join(out,`failure-${i}-${j}.png`),fullPage:true})}}process.exitCode=1}).finally(async()=>{await activeBrowser?.close()});
