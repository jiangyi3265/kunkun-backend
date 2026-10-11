const assert=require('node:assert/strict'),path=require('node:path'),crypto=require('node:crypto');
module.exports=async function({page,origin,api,call,seller,buyer,listing,image,orders,h5Context,observe,check,out}){
 const button=(p,text)=>p.locator('uni-button').filter({hasText:new RegExp('^'+text+'$')});
 const req=async(p,pathname,act,query={})=>{const pending=p.waitForResponse(r=>{const u=new URL(r.url());return u.pathname===pathname&&Object.entries(query).every(([k,v])=>(u.searchParams.get(k)||'')===v)});await act();const response=await pending;const body=await response.json();assert.equal(body.code,200);return {body,url:new URL(response.url())}};
 const sellerCtx=await h5Context(seller.token),owner=await sellerCtx.newPage();observe(owner);
 try{
 await check('market actual category keyword and four sorting controls return persisted listings',async()=>{
  const second=await api('/boost/listings','POST',{businessType:'PLAYER_ITEM',pricingMode:'UNIT',kind:'SERVICE',game:'和平精英',zone:'QQ区',platform:'iOS',itemCategory:'枪械',quantity:1,priceCents:2500,itemDetails:'隔离页面验证枪械',title:'全流程测试枪械',images:[image],detailImages:[],requestKey:crypto.randomUUID()},seller.token,true);
  await api('/boost-admin/moderate/listings/'+second.id,'POST',{action:'approve',note:'页面交互验收'});
  await page.goto(origin+'/#/pages/market/index');await page.locator('.service-card').first().waitFor();
  for(const [label,sort]of [['推荐','sales'],['最新','newest'],['价格 ↑','priceAsc'],['价格 ↓','priceDesc']]){
   const r=await req(page,'/boost/listings',()=>page.locator('.tabs uni-button').filter({hasText:label}).click());assert.equal(r.url.searchParams.get('sort'),sort);assert.equal(r.body.data.total,2);
   if(sort==='priceAsc')assert.deepEqual(r.body.data.items.map(x=>x.priceCents),[1234,2500]);
   if(sort==='priceDesc')assert.deepEqual(r.body.data.items.map(x=>x.priceCents),[2500,1234]);
  }
  const r=await req(page,'/boost/listings',()=>page.locator('.categories uni-button').filter({hasText:'护甲'}).click());assert.equal(r.url.searchParams.get('itemCategory'),'护甲');assert.equal(r.body.data.total,1);await page.getByText('全流程测试护甲',{exact:true}).waitFor();
  await page.locator('.search input').fill('不存在的机械验收名称');await req(page,'/boost/listings',()=>button(page,'搜索').click());await page.getByText('没有找到相关道具',{exact:true}).waitFor();
  await page.locator('.search input').fill('全流程测试护甲');await req(page,'/boost/listings',()=>button(page,'搜索').click());await page.locator('.service-card').first().click();await page.waitForURL('**/#/pages/service/detail?id='+listing.id);
  await page.reload();await page.getByText('全流程测试护甲',{exact:true}).first().waitFor();
 });
 await check('manage edit offline resubmit and approval change public visibility and preserve stock',async()=>{
  await owner.goto(origin+'/#/pages/manage/index');const card=owner.locator('.card').filter({hasText:'全流程测试护甲'});await card.waitFor();await card.locator('uni-button').filter({hasText:'编辑'}).click();await owner.waitForURL('**/#/pages/publish/service?id='+listing.id);
  await owner.locator('textarea').fill('已更新的六件护甲详细介绍');await owner.locator('.check-box').click();
  await req(owner,'/boost/listings/'+listing.id,()=>button(owner,'保存并重新审核').click());await owner.waitForURL('**/#/pages/manage/index?kind=SERVICE');await card.getByText('审核中',{exact:true}).waitFor();
  assert.equal((await api('/boost/listings/'+listing.id,'GET',null,seller.token,true)).itemDetails,'已更新的六件护甲详细介绍');
  await api('/boost-admin/moderate/listings/'+listing.id,'POST',{action:'approve',note:'确认更新介绍'});await owner.reload();await card.getByText('上架中',{exact:true}).waitFor();
  const action=async(label,status)=>{const pending=owner.waitForResponse(r=>new URL(r.url()).pathname==='/boost/listings/'+listing.id+'/action');await card.locator('uni-button').filter({hasText:label}).click();await owner.locator('.uni-modal__btn_primary').click();assert.equal((await(await pending).json()).code,200);await card.getByText(status,{exact:true}).waitFor()};
  await action('下架','已下架');await owner.reload();await card.getByText('已下架',{exact:true}).waitFor();
  const hidden=await api('/boost/listings?kind=SERVICE&keyword='+encodeURIComponent('全流程测试护甲'),'GET',null,buyer.token,true);assert.equal(hidden.total,0);
  await action('重新提交审核','审核中');await api('/boost-admin/moderate/listings/'+listing.id,'POST',{action:'approve',note:'重新审核通过'});await owner.reload();await card.getByText('上架中',{exact:true}).waitFor();
  const visible=await api('/boost/listings?kind=SERVICE&keyword='+encodeURIComponent('全流程测试护甲'),'GET',null,buyer.token,true);assert.equal(visible.total,1);assert.equal(visible.items[0].availableQuantity,6);
  for(const label of ['审核中','已下架','未通过','已预订','已售出']){const r=await req(owner,'/boost/listings/mine',()=>owner.locator('.tabs uni-button').filter({hasText:new RegExp('^'+label+'$')}).click());assert.equal(r.body.data.total,0);await owner.getByText('还没有发布',{exact:true}).waitFor()}
  await req(owner,'/boost/listings/mine',()=>owner.locator('.tabs uni-button').filter({hasText:/^上架中$/}).click());await card.waitFor();await owner.screenshot({path:path.join(out,'manage-restored.png')});
 });
 await check('request hall category sort profile and contact route work without accepting a booster job',async()=>{
  const wanted=await api('/boost/listings','POST',{businessType:'PLAYER_ITEM',pricingMode:'UNIT',kind:'REQUEST',game:'和平精英',zone:'微信区',platform:'安卓',itemCategory:'密钥',quantity:3,priceCents:1500,itemDetails:'求购测试密钥三件',title:'全流程测试求购密钥',images:[],detailImages:[],requestKey:crypto.randomUUID()},seller.token,true);await api('/boost-admin/moderate/listings/'+wanted.id,'POST',{action:'approve',note:'求购页面验收'});
  await page.goto(origin+'/#/pages/hall/index');await page.getByText('全流程测试求购密钥',{exact:true}).waitFor();
  for(const category of ['护甲','密钥']){const r=await req(page,'/boost/listings',()=>page.locator('.category-tabs uni-button').filter({hasText:new RegExp('^'+category+'$')}).click());assert.equal(r.url.searchParams.get('kind'),'REQUEST');assert.equal(r.body.data.total,category==='密钥'?1:0)}
  const r=await req(page,'/boost/listings',()=>button(page,'预算最高').click());assert.equal(r.url.searchParams.get('sort'),'priceDesc');assert.equal(r.body.data.total,1);
  await page.locator('.request-owner').click();await page.waitForURL('**/#/pages/shop/index?id='+seller.user.id);await page.getByText('全流程测试求购密钥',{exact:true}).waitFor();
  await page.goto(origin+'/#/pages/hall/index');await button(page,'联系买家').click();await page.waitForURL(/pages\/chat\/index\?id=\d+/);
  await owner.goto(origin+'/#/pages/manage/index');await req(owner,'/boost/listings/mine',()=>button(owner,'我的求购').click());await owner.getByText('全流程测试求购密钥',{exact:true}).waitFor();
 });
 await check('order UI searches actual number and keeps buyer seller and all status filters separate',async()=>{
  await page.goto(origin+'/#/pages/orders/index');await page.locator('.order-item').first().waitFor();await page.locator('.order-search input').fill(orders[0].orderNo);
  const found=await req(page,'/boost/orders',()=>button(page,'搜索').click());assert.equal(found.body.data.total,1);assert.equal(found.body.data.items[0].id,orders[0].id);await page.locator('.order-item').click();await page.waitForURL('**/#/pages/orders/detail?id='+orders[0].id);await page.getByText(orders[0].orderNo,{exact:false}).first().waitFor();
  await page.goto(origin+'/#/pages/orders/index');await req(page,'/boost/orders',()=>button(page,'清空').click(),{keyword:''});
  for(const [label,status]of [['待付款','WAIT_PAY'],['待交付','WAIT_DELIVERY'],['待收货','WAIT_CONFIRM'],['历史待开始','WAIT_START'],['历史进行中','IN_PROGRESS'],['历史待验收','REVIEW'],['已完成','COMPLETED'],['售后 / 退款','AFTERSALE'],['已取消','CANCELLED']]){const r=await req(page,'/boost/orders',()=>page.locator('.tabs uni-button').filter({hasText:new RegExp('^'+label+'$')}).click(),{status,keyword:''});assert.equal(r.body.data.total,label==='已取消'?2:0,label+' '+r.url.search)}
  const empty=await req(page,'/boost/orders',()=>button(page,'我卖出的').click(),{role:'worker'});assert.equal(empty.body.data.total,0);assert.equal(empty.url.searchParams.get('role'),'worker');
  await owner.goto(origin+'/#/pages/orders/index?role=worker');await owner.locator('.order-item').first().waitFor();assert.equal(await owner.locator('.order-item').count(),2);await owner.reload();await owner.locator('.order-item').first().waitFor();
 });
 await check('wallet filters and withdrawal record stay usable while money and aftersale forms are closed',async()=>{
  await page.goto(origin+'/#/pages/wallet/index');await page.getByText('支付及资金操作暂未开放，当前仅可查看钱包与历史记录。',{exact:true}).waitFor();assert(await button(page,'提现').evaluate(el=>el.hasAttribute('disabled')));await button(page,'查看提现记录').click();await page.getByText('还没有提现记录',{exact:true}).waitFor();
  for(const [label,kind]of [['收入','income'],['支出','expense'],['全部','']]){const r=await req(page,'/boost/wallet/ledger',()=>page.locator('.tabs uni-button').filter({hasText:new RegExp('^'+label+'$')}).click());assert.equal(r.url.searchParams.get('kind')||'',kind);assert.equal(r.body.data.total,0)}
  await page.reload();await page.getByText('支付及资金操作暂未开放，当前仅可查看钱包与历史记录。',{exact:true}).waitFor();await page.screenshot({path:path.join(out,'wallet-disabled.png')});
  await page.goto(origin+'/#/pages/aftersale/index?id='+orders[0].id);await page.getByText('支付及资金操作暂未开放',{exact:true}).waitFor();assert(await button(page,'提交申请').evaluate(el=>el.hasAttribute('disabled')));assert.equal(await page.locator('textarea').count(),0);
 });
 }finally{await owner.close();await sellerCtx.close()}
};
