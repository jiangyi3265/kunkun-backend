// Visual fixtures are browser-only. No request reaches the production API.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {chromium}=createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const root=path.resolve(__dirname,'..'),app=path.join(root,'代练/dist/build/h5'),out=path.join(root,'test-results/ui-fidelity-20261007');
const png=path.join(out,'screenshots');fs.mkdirSync(png,{recursive:true});
const user={id:1,nickname:'小栖同学',avatar:'anime',certification:'APPROVED',balanceCents:26800,favorites:12,history:36,follows:8,coupons:3,unread:3};
const services=[['王者荣耀排位上分 | 实力打手','王者荣耀',2500,'hero','阿星电竞'],['和平精英冲分 | 稳定在线','和平精英',3500,'soldier','橘子工作室'],['原神探索 | 任务全清','原神',1800,'anime','鹿鸣代练'],['巅峰策略陪练 | 耐心教学','王者荣耀',3900,'crown','清风游戏']].map((x,i)=>({id:i+11,kind:'SERVICE',title:x[0],game:x[1],priceCents:x[2],avatar:x[3],nickname:x[4],ownerId:i+2,certification:'APPROVED',zone:'微信区',platform:'安卓',currentRank:'星耀 III',targetRank:'最强王者',durationHours:24,status:i===1?'PENDING':i===2?'OFFLINE':'ACTIVE',sales:268-i*60,views:328-i*60,images:[],description:'接单后沟通目标，开始前确认需求。\n每个阶段同步截图，完成后确认验收。',createdAt:'2026-10-07T09:30:00',quoteKey:'fixture-quote',reviews:[{id:1,nickname:'小栖同学',avatar:'anime',stars:5,content:'沟通耐心，进度同步及时。',createdAt:'2026-10-06T09:30:00'}],reviewTotal:1,favorited:false}));
const requests=services.slice(0,3).map((s,i)=>({...s,id:31+i,kind:'REQUEST',status:'ACTIVE',nickname:'小栖同学',ownerId:6+i,title:s.game+' · '+(i===0?'星耀冲王者':i===1?'王牌冲分':'探索度提升'),priceCents:[12000,8800,6000][i]}));
const orders=services.slice(0,3).map((s,i)=>({...s,id:41+i,listingId:s.id,orderNo:'DL202610070086'+i,workerId:s.ownerId,workerName:s.nickname,buyerId:1,buyerName:user.nickname,amountCents:[11000,8800,1800][i],progress:[70,100,100][i],status:['IN_PROGRESS','REVIEW','COMPLETED'][i],paymentMode:'SIMULATED',note:'今晚可以开始，请同步进度截图。',updates:[{id:3,note:'已升至星耀 I',percent:70,createdAt:'2026-10-07T20:30:00',images:[]},{id:2,note:'打手开始服务',percent:0,createdAt:'2026-10-07T18:00:00',images:[]}],aftersale:null,reviewed:false}));
let messages=[{id:1,senderId:2,kind:'TEXT',content:'你好，已经接单啦～',createdAt:'2026-10-07T19:42:00',isRead:true},{id:2,senderId:2,kind:'TEXT',content:'今晚八点开始，可以吗？',createdAt:'2026-10-07T19:42:00',isRead:true},{id:3,senderId:1,kind:'TEXT',content:'可以，麻烦同步一下进度',createdAt:'2026-10-07T19:43:00',isRead:true},{id:4,senderId:2,kind:'TEXT',content:'没问题，每个阶段都会发截图。',createdAt:'2026-10-07T19:44:00',isRead:true}];
const ledger=[{id:1,title:'订单结算 · 王者荣耀',amountCents:11000,createdAt:'2026-10-07T20:30:00'},{id:2,title:'提现到微信零钱',amountCents:-20000,createdAt:'2026-10-06T14:12:00'},{id:3,title:'订单结算 · 和平精英',amountCents:8800,createdAt:'2026-10-05T21:05:00'},{id:4,title:'订单退款',amountCents:-1800,createdAt:'2026-10-04T10:24:00'}];
const checks=[],requestLog=[],consoleErrors=[],expectedResourceErrors=[],blockedResources=[],requestFailures=[];let activePage;let mode='nonpayment',empty=false,networkError=false,unapproved=false;
const config=()=>({policyVersion:'test-20261007',paymentsEnabled:mode==='aftersale-visual-only',simulatedPayment:true});
const list=items=>({items,total:items.length,hasMore:false});
async function mock(route){
 const req=route.request(),u=new URL(req.url()),p=u.pathname.replace(/^\/api/,''),method=req.method();let body;try{body=req.postDataJSON()}catch{};
 requestLog.push({path:p,method,query:Object.fromEntries(u.searchParams),body});
 if(method==='OPTIONS')return route.fulfill({status:204,headers:{'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'*','Access-Control-Allow-Methods':'*'}});
 let data;
 if(p==='/boost/config')data=config();
 else if(p==='/boost/me')data=user;
 else if(p==='/boost/messages/summary')data={unread:3,messageId:4,noticeId:1};
 else if(p==='/boost/listings'||p==='/boost/listings/mine'){
  if(networkError)return route.fulfill({status:503,headers:{'Access-Control-Allow-Origin':'*'},json:{code:503,msg:'验收网络失败样本'}});
  let xs=p.endsWith('mine')?services:u.searchParams.get('kind')==='REQUEST'?requests:services;
  if(u.searchParams.get('game')&&u.searchParams.get('game')!=='全部')xs=xs.filter(x=>x.game===u.searchParams.get('game'));
  if(u.searchParams.get('status')&&u.searchParams.get('status')!=='ALL')xs=xs.filter(x=>x.status===u.searchParams.get('status'));
  const sort=u.searchParams.get('sort');if(sort==='priceAsc')xs=[...xs].sort((a,b)=>a.priceCents-b.priceCents);if(sort==='priceDesc')xs=[...xs].sort((a,b)=>b.priceCents-a.priceCents);
  data=list(empty?[]:xs);
 }else if(/^\/boost\/listings\/\d+$/.test(p))data={...services.find(x=>x.id===Number(p.split('/').pop())),...(unapproved?{certification:'PENDING',status:'PENDING'}:{})};
 else if(p==='/boost/coupons')data=list([{id:1,title:'新客优惠券',discountCents:1000,minimumCents:1000,expiresAt:'2027-01-01T00:00:00'}]);
 else if(p==='/boost/orders'&&method==='GET')data=list(orders.filter(x=>u.searchParams.get('status')==='ALL'||!u.searchParams.get('status')||x.status===u.searchParams.get('status')));
 else if(/^\/boost\/orders\/\d+$/.test(p))data=orders.find(x=>x.id===Number(p.split('/').pop()));
 else if(p==='/boost/conversations')data=list([{id:51,nickname:'阿星电竞',avatar:'hero',updatedAt:'2026-10-07T09:28:00',lastMessage:'可以的，今晚八点开始',unread:2},{id:52,nickname:'橘子工作室',avatar:'anime',updatedAt:'2026-10-06T09:30:00',lastMessage:'已上传最新进度截图',unread:0}]);
 else if(/^\/boost\/conversations\/\d+$/.test(p))data={id:51,peer:{id:2,nickname:'阿星电竞',avatar:'hero',certification:'APPROVED'},listing:services[0],items:messages,hasMore:false,hasNewer:false,readThrough:messages.at(-1).id};
 else if(/^\/boost\/conversations\/\d+\/read$/.test(p))data=true;
 else if(/^\/boost\/conversations\/\d+\/messages$/.test(p)&&method==='POST'){messages.push({id:messages.at(-1).id+1,senderId:1,...body,createdAt:'2026-10-07T20:00:00',isRead:false});data=messages.at(-1)}
 else if(p==='/boost/notifications')data={...list([{id:1,title:'订单通知',content:'你的订单已接单，查看最新进度',orderId:41,isRead:false,createdAt:'2026-10-07T09:30:00'}]),unread:1};
 else if(p==='/boost/wallet')data={balanceCents:26800,pendingCents:11000,totalCents:128600};
 else if(p==='/boost/wallet/ledger')data=list(ledger.filter(x=>u.searchParams.get('kind')==='income'?x.amountCents>0:u.searchParams.get('kind')==='expense'?x.amountCents<0:true));
 else if(p==='/boost/wallet/withdrawals')data=list([{id:1,amountCents:20000,status:'SIMULATED_PAID',createdAt:'2026-10-06T14:12:00',note:'隔离测试记录'}]);
 else {return route.fulfill({status:400,json:{code:400,msg:'未定义的隔离验收请求 '+p}})}
 return route.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*'},json:{code:200,data}});
}
const pages=[['01-home','index/index'],['02-market','market/index'],['03-request','publish/index'],['04-messages','messages/index'],['05-mine','mine/index'],['06-detail','service/detail?id=11'],['07-checkout','checkout/index?id=11'],['08-orders','orders/index'],['09-progress','orders/detail?id=41'],['10-chat','chat/index?id=51'],['11-hall','hall/index'],['12-service','publish/service'],['13-manage','manage/index'],['14-wallet','wallet/index'],['15-aftersale','aftersale/index?id=41']];
const server=http.createServer((req,res)=>{
 const rel=decodeURIComponent(new URL(req.url,'http://localhost').pathname),resolved=path.resolve(app,'.'+rel);
 if(!resolved.startsWith(app+path.sep)&&resolved!==app){res.writeHead(403);return res.end()}
 const file=fs.existsSync(resolved)&&fs.statSync(resolved).isFile()?resolved:path.join(app,'index.html');
 const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png','.jpg':'image/jpeg','.svg':'image/svg+xml'};res.setHeader('Content-Type',types[path.extname(file)]||'application/octet-stream');fs.createReadStream(file).pipe(res);
});
async function check(name,fn){try{const evidence=await fn();checks.push({name,status:'已实测通过',evidence});console.log('PASS '+name)}catch(e){checks.push({name,status:'失败',error:e.message});console.error('FAIL '+name+': '+e.message);throw e}}
(async()=>{
 const startedAt=new Date().toISOString();let browser;
 try{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base='http://127.0.0.1:'+server.address().port;
  browser=await chromium.launch({headless:true});
  for(const width of [390,320]){
   const ctx=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:1});
   await ctx.addInitScript(()=>localStorage.setItem('boost_token',JSON.stringify({type:'string',data:'visual-fixture-only'})));
   await ctx.route('**/boost/**',mock);await ctx.route('**/*',async(route)=>{const u=new URL(route.request().url());if(u.hostname==='127.0.0.1'||u.protocol==='data:')return route.fallback();if(u.pathname.includes('/boost/'))return route.fallback();if(u.href==='https://cdn.dcloud.net.cn/img/shadow-grey.png'&&route.request().method()==='GET')return route.continue();blockedResources.push({url:route.request().url(),method:route.request().method()});return route.abort()});
   const page=await ctx.newPage();activePage=page;page.on('requestfailed',r=>requestFailures.push({url:r.url(),error:r.failure()?.errorText}));page.on('pageerror',e=>consoleErrors.push({width,error:e.message}));page.on('console',m=>{if(m.type()==='error'||/Failed to resolve component/.test(m.text())){if(/Failed to load resource.*503/.test(m.text()))expectedResourceErrors.push({width,error:m.text(),location:m.location(),reason:'intentional 503 recovery fixture'});else consoleErrors.push({width,error:m.text(),location:m.location()})}});
   for(const [name,route] of pages){
    mode=name==='15-aftersale'?'aftersale-visual-only':'nonpayment';
    await page.goto(base+'/#/pages/'+route);await page.waitForSelector('.app-shell');await page.waitForTimeout(450);
    await check(width+' '+name,async()=>{
     assert(!await page.locator('.field-error').count()||!(await page.locator('.field-error').allTextContents()).some(x=>x.includes('未定义')));
     assert.equal(await page.locator('.skeleton').count(),0,'page still loading');
     const geometry=await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth+1,images:[...document.querySelectorAll('.game-art')].map(el=>{const b=el.getBoundingClientRect();return{width:b.width,height:b.height,style:el.getAttribute('style')}})}));
     assert.equal(geometry.overflow,false,'horizontal overflow');
     for(const img of geometry.images)assert(img.width>=19&&img.height>=19,'collapsed image '+JSON.stringify(img));
     const bannerTextBounds=await page.evaluate(()=>{
      const result=[];
      for(const [bannerIndex,banner] of [...document.querySelectorAll('.hero,.profile-banner')].entries()){
       const graphic=banner.querySelector('.game-art'),copy=banner.querySelector('.hero-copy,.profile-banner-copy')||banner.lastElementChild;
       if(!graphic||!copy)throw new Error('missing banner graphic or copy');
       const art=graphic.getBoundingClientRect(),box=banner.getBoundingClientRect();
       if(box.width<=0||box.height<=0)throw new Error('collapsed banner');
       const walker=document.createTreeWalker(copy,NodeFilter.SHOW_TEXT);
       let node;while((node=walker.nextNode())){if(!node.textContent.trim())continue;const range=document.createRange();range.selectNodeContents(node);
        for(const rect of range.getClientRects())result.push({bannerIndex,text:node.textContent.trim(),right:rect.right,artLeft:art.left,top:rect.top,bottom:rect.bottom,bannerTop:box.top,bannerBottom:box.bottom});
       }
      }return result;
     });
     if(name==='01-home'||name==='05-mine')assert.equal(new Set(bannerTextBounds.map(x=>x.bannerIndex)).size,name==='01-home'?2:1,'missing banner text geometry');
     for(const line of bannerTextBounds){assert(line.right<=line.artLeft+1,'banner text overlaps graphic: '+JSON.stringify(line));assert(line.top>=line.bannerTop-1&&line.bottom<=line.bannerBottom+1,'banner text clipped: '+JSON.stringify(line))}
     await page.screenshot({path:path.join(png,`${name}-${width}.png`),fullPage:true});
     return{scope:'H5 / isolated Mock only',viewport:{width,height:844},imageBoxes:geometry.images,bannerTextBounds,screenshot:`screenshots/${name}-${width}.png`};
    });
   }
   mode='nonpayment';
   if(width===390){
    await check('market classification and price sorting',async()=>{await page.goto(base+'/#/pages/market/index');await page.waitForSelector('.service-card');await page.locator('.categories .ui-button').filter({hasText:'王者荣耀'}).click();await page.waitForTimeout(200);assert.equal(await page.locator('.service-card').count(),2);await page.locator('.tabs .ui-button').filter({hasText:'价格 ↑'}).click();await page.waitForTimeout(200);assert(requestLog.some(x=>x.path==='/boost/listings'&&x.query.game==='王者荣耀'&&x.query.sort==='priceAsc'));return 'real UI clicks; intercepted request arguments and rendered results matched'});
    await check('publish agreement and invalid input',async()=>{await page.goto(base+'/#/pages/publish/index');await page.waitForSelector('.publish-fields');const posts=requestLog.filter(x=>x.method==='POST'&&x.path==='/boost/listings').length;await page.locator('.ui-button').filter({hasText:/^确认发布$/}).click();await page.getByText('请先同意发布规则',{exact:true}).waitFor();await page.locator('.check-box').click();await page.locator('.ui-button').filter({hasText:/^确认发布$/}).click();await page.getByText('请填写完整标题、目标和描述（至少 5 个字）',{exact:true}).waitFor();assert.equal(requestLog.filter(x=>x.method==='POST'&&x.path==='/boost/listings').length,posts);return 'both invalid submissions blocked before request'});
    await check('service form ordering',async()=>{await page.goto(base+'/#/pages/publish/service');await page.waitForSelector('.publish-fields');const labels=await page.locator('.publish-fields .field-label').allTextContents();assert.equal(labels[0],'服务图片');assert.equal(labels[1],'服务标题');const price=await page.locator('.publish-price').boundingBox(),desc=await page.locator('.textarea').boundingBox();assert(price.y<desc.y);return labels});
    await check('nonpayment wallet and aftersale guards',async()=>{await page.goto(base+'/#/pages/wallet/index');await page.locator('.ui-button').filter({hasText:/^提现$/}).waitFor();assert.equal(await page.locator('.ui-button').filter({hasText:/^提现$/}).getAttribute('disabled'),'true');await page.locator('.ui-button').filter({hasText:/^提现$/}).click({force:true});assert.equal(await page.getByText('申请提现',{exact:true}).count(),0);await page.locator('.ui-button').filter({hasText:/^查看提现记录$/}).click();await page.getByText('隔离测试记录',{exact:false}).waitFor();await page.goto(base+'/#/pages/aftersale/index?id=41');await page.getByText('支付及资金操作暂未开放',{exact:true}).waitFor();assert.equal(await page.locator('.ui-button').filter({hasText:/^提交申请$/}).getAttribute('disabled'),'true');await page.locator('.ui-button').filter({hasText:/^提交申请$/}).click({force:true});assert(!requestLog.some(x=>/simulated-pay|wechat-pay|\/withdraw$|\/aftersale$/.test(x.path)&&x.method==='POST'));await page.screenshot({path:path.join(png,'15-aftersale-disabled-390.png'),fullPage:true});return 'no funds request; disabled submit; actual blocked-state screenshot saved'});
    await check('chat send and refresh',async()=>{await page.goto(base+'/#/pages/chat/index?id=51');await page.locator('.chat-peer').waitFor();await page.locator('.chat-input input').fill('UI验收消息');await page.locator('.ui-button').filter({hasText:/^发送$/}).click();await page.getByText('UI验收消息',{exact:true}).waitFor();await page.reload();await page.getByText('UI验收消息',{exact:true}).waitFor();const sent=requestLog.find(x=>x.path==='/boost/conversations/51/messages');assert.equal(sent.body.kind,'TEXT');assert(sent.body.requestKey);return 'Mock persistence and idempotency key; no external message sent'});
    await check('unapproved listing does not show verified claim',async()=>{unapproved=true;await page.goto(base+'/#/pages/service/detail?id=11');await page.getByText('平台用户',{exact:true}).waitFor();assert.equal(await page.getByText('✓ 认证打手',{exact:true}).count(),0);await page.goto(base+'/#/pages/checkout/index?id=11');await page.getByText('阿星电竞 · 平台用户',{exact:true}).waitFor();unapproved=false;return 'pending certification correctly shown on detail and checkout'});
    await check('empty and API error recovery',async()=>{empty=true;await page.goto(base+'/#/pages/market/index');await page.getByText('没有找到相关服务',{exact:true}).waitFor();await page.screenshot({path:path.join(png,'02-market-empty-390.png')});empty=false;networkError=true;await page.reload();await page.getByText('验收网络失败样本',{exact:true}).waitFor();networkError=false;await page.locator('.ui-button').filter({hasText:/^重新加载$/}).click();await page.waitForSelector('.service-card');return 'empty state, 503 error, and retry restored real rendered fixture cards'});
   }
   await ctx.close();
  }
  await check('browser console',async()=>{assert.deepEqual(consoleErrors,[]);return 'no page errors or unresolved components across 30 rendered pages and interactions'});
 }catch(e){process.exitCode=1;console.error(e.stack);if(activePage&&!activePage.isClosed()){await activePage.screenshot({path:path.join(png,'failure.png'),fullPage:true});fs.writeFileSync(path.join(out,'failure-dom.html'),await activePage.content())}}finally{
  if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
  fs.writeFileSync(path.join(out,'browser-acceptance.json'),JSON.stringify({startedAt,finishedAt:new Date().toISOString(),scope:'Browser H5 with isolated visual API fixtures, not real integration / mini-app / device',status:process.exitCode?'失败':'已实测通过',checks,consoleErrors,expectedResourceErrors,blockedResources,requestFailures,requests:requestLog},null,2));
 }
})();
