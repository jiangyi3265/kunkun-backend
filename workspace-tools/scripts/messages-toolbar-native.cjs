// Official Devtools public pages against the same owned HTTP/MySQL acceptance fixture.
const fs=require('fs'),path=require('path'),net=require('net'),assert=require('assert/strict'),{spawnSync}=require('child_process');
const root=path.resolve(__dirname,'..'),out=process.env.MARKET_CONTENT_OUT,base=process.env.PLAYER_HTTP_BASE,fixture=JSON.parse(fs.readFileSync(process.env.PLAYER_HTTP_FIXTURE,'utf8')),project=path.join(path.dirname(process.env.PLAYER_HTTP_FIXTURE),'mp-messages'+(process.env.IDENTITY_NATIVE_SUFFIX?'-'+process.env.IDENTITY_NATIVE_SUFFIX:''));
const report={startedAt:new Date().toISOString(),scope:'Official WeChat Devtools, actual isolated HTTP/MySQL; messages toolbar layout and actual actions. No wx/API Mock. Not a physical phone test.',checks:[],exceptions:[],commands:[]};let mini;
function run(name,exe,args,cwd=root,env={}){const r=spawnSync(exe,args,{cwd,env:{...process.env,...env},windowsHide:true,encoding:'utf8',timeout:180000,maxBuffer:20000000});fs.writeFileSync(path.join(out,name+'.log'),(r.stdout||'')+(r.stderr||''));report.commands.push({name,exe,args,exitCode:r.status});assert.equal(r.status,0,name);return r;}
async function until(fn,label,ms=20000){const deadline=Date.now()+ms;while(Date.now()<deadline){if(await fn())return;await new Promise(r=>setTimeout(r,200))}throw Error(label+' timed out')}
async function timed(label,fn,ms=20000){let timer;try{return await Promise.race([fn(),new Promise((_,reject)=>timer=setTimeout(()=>reject(Error(label+' timed out')),ms))])}finally{clearTimeout(timer)}}
async function freePort(){return new Promise(r=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p))})})}
async function element(p,s){let x;await timed('render '+s,()=>until(async()=>{x=(await p.$$(s))[0];return !!x},'element '+s));return x}
async function renderedElements(p,name){
 const xpath="//*[contains(concat(' ',normalize-space(@class),' '),' "+name+" ')]";
 // SDK 3.17.3 returns an array here; automator's public wrapper incorrectly
 // destructures {elements}. Normalize only these two documented-by-source
 // protocol shapes; propagate every transport error and invalid response.
 const raw=await p.send('Page.getElementsByXpath',{selector:xpath}),rows=Array.isArray(raw)?raw:raw?.elements;
 assert(Array.isArray(rows),'XPath response must contain actual element metadata');
 (report.selectorReads??=[]).push({path:p.path,class:name,shape:Array.isArray(raw)?'array':'elements',count:rows.length});
 const Element=require(path.join(root,'.runtime/mini-blank-tools/node_modules/miniprogram-automator/out/Element')).default;
 return rows.map(row=>Element.create(p.connection,{...row,pageId:p.id},p.elementMap));
}
async function renderedClass(p,name){
 let matches;await until(async()=>{matches=await renderedElements(p,name);return matches.length>0},'rendered class '+name);
 assert.equal(matches.length,1,'Expected one rendered '+name);return matches[0];
}
async function shot(name){await new Promise(r=>setTimeout(r,1000));await mini.screenshot({path:path.join(out,name+'.png')})}
async function check(name,fn){report.activeCheck=name;await fn();report.checks.push({name,status:'已实测通过'});console.log('PASS '+name)}
async function route(url){
 const expected=url.replace(/^\//,''),entry={target:expected,startedAt:new Date().toISOString(),observed:[]};(report.routes??=[]).push(entry);
 let page=await mini.reLaunch(url);entry.returnedPath=page?.path;
 await until(async()=>{page=await mini.currentPage();if(entry.observed.at(-1)!==page?.path)entry.observed.push(page?.path||null);return page?.path===expected},'native route '+expected,20000);
 entry.finishedAt=new Date().toISOString();return page;
}
(async()=>{try{
run('native-content-build',process.execPath,['scripts/uni.cjs','build','-p','mp-weixin'],path.join(root,'代练'),{VITE_API_BASE_URL:base,UNI_OUTPUT_DIR:project,BOOST_RELEASE:'0'});
const projectFile=path.join(project,'project.config.json'),pc=JSON.parse(fs.readFileSync(projectFile,'utf8'));pc.projectname='困困-消息按钮验收';pc.setting.urlCheck=false;fs.writeFileSync(projectFile,JSON.stringify(pc,null,2));report.project=project;
 const automationPort=await freePort();report.automationPort=automationPort;
 run('native-auto','powershell.exe',['-NoProfile','-NonInteractive','-Command','& $env:QA_WECHAT_CLI auto --project $env:QA_NATIVE_PROJECT --auto-port $env:QA_AUTO_PORT --port 9420 --lang zh; exit $LASTEXITCODE'],root,{QA_WECHAT_CLI:'D:/wxkfgj/微信web开发者工具/cli.bat',QA_NATIVE_PROJECT:project,QA_AUTO_PORT:String(automationPort)});
 // CLI acknowledgement precedes the automation listener; wait on the actual owned port.
 await until(()=>new Promise(resolve=>{const socket=net.connect(automationPort,'127.0.0.1');socket.setTimeout(700);socket.once('connect',()=>{socket.destroy();resolve(true)});socket.once('error',()=>{socket.destroy();resolve(false)});socket.once('timeout',()=>{socket.destroy();resolve(false)})}),'official automation listener',30000);
 // The automation TCP listener starts before Tool.getInfo has an SDKVersion.
 // Probe the same read-only SDK protocol first; do not bypass its version check.
 const sdkRoot=path.join(root,'.runtime/mini-blank-tools/node_modules/miniprogram-automator');
 const Connection=require(path.join(sdkRoot,'out/Connection')).default,Transport=require(path.join(sdkRoot,'out/Transport')).default,MiniProgram=require(path.join(sdkRoot,'out/MiniProgram')).default;
 const WebSocket=require(require.resolve('ws',{paths:[sdkRoot]}));
 const socket=new WebSocket('ws://127.0.0.1:'+automationPort,{handshakeTimeout:8000});
 try{await new Promise((resolve,reject)=>{socket.once('open',resolve);socket.once('error',reject)})}catch(e){socket.terminate();throw e}
 // Keep a single connection: disposing a readiness-only client can detach the
 // IDE automation context before the replacement client receives page metadata.
 const readyConnection=new Connection(new Transport(socket));
 report.protocol=require('./native-protocol-trace.cjs')(readyConnection,path.join(out,'native-protocol.json'),()=>report.activeCheck||'official SDK initialization');
 mini=new MiniProgram(readyConnection);report.sdkReadiness=[];
 await until(async()=>{const info=await timed('read SDK readiness',()=>readyConnection.send('Tool.getInfo',{}),5000);const version=info?.SDKVersion;report.sdkReadiness.push({at:new Date().toISOString(),sdkVersion:version||null});return typeof version==='string'&&version.length>0},'official tool SDK initialization',45000);
 await mini.checkVersion();mini.on('exception',e=>report.exceptions.push(String(e.message||e)));const info=await mini.callWxMethod('getAccountInfoSync');assert.equal(info.miniProgram.appId,pc.appid);report.system=await mini.systemInfo();
 // An open automation socket does not prove the initial WebView is registered.
 // Match the SDK launch grace period, then require an actual visible home element.
 await new Promise(resolve=>setTimeout(resolve,5000));
 let initialPage;await until(async()=>{try{initialPage=await mini.currentPage();return !!initialPage}catch(e){if(String(e.message).includes('getPageMetaByWebviewId'))return false;throw e}},'initial native WebView',30000);
 await element(initialPage,'.brand');report.initialNativePage=initialPage.path;


 const crypto=require('crypto'),credential={username:'qa_id_'+crypto.randomBytes(7).toString('hex'),password:crypto.randomBytes(18).toString('base64url'),nickname:'原生认证页面测试'};
 const cfg=(await(await fetch(base+'/boost/config')).json()).data;
 const registration=await(await fetch(base+'/boost/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...credential,acceptedPolicy:cfg.policyVersion})})).json();assert.equal(registration.code,200);report.nativeMember=registration.data.user.id;

 await check('native message controls sit below capsule and in one usable row',async()=>{
  let p=await route('/pages/auth/index');await(await element(p,'#username')).input(credential.username);await(await element(p,'#password')).input(credential.password);await(await element(p,'.check-row')).tap();await(await element(p,'.auth-page .btn')).tap();await until(async()=> (await mini.currentPage())?.path==='pages/index/index','native login');
  p=await route('/pages/messages/index');await element(p,'.message-list');
  const buttons=await p.$$('.icon-button');assert.equal(buttons.length,2);
  async function rect(el){const o=await el.offset(),s=await el.size();return {left:Number(o.left),top:Number(o.top),width:Number(s.width),height:Number(s.height)}}
  // This synchronous API has no "Sync" suffix. The SDK callWxMethod bridge
  // waits for an async callback that it never receives; evaluate only reads it.
  const a=await rect(buttons[0]),b=await rect(buttons[1]),menu=await mini.evaluate(()=>wx.getMenuButtonBoundingClientRect());
  report.layout={search:a,readAll:b,capsule:menu};await shot('native-messages');
  assert(Math.abs(a.top-b.top)<=1,'Both actions must be in the same row');
  assert(a.top>=menu.bottom+6&&b.top>=menu.bottom+6,'Actions must sit below the actual native capsule');
  assert(a.width>=44&&a.height>=44&&b.width>=44&&b.height>=44,'Each action needs a 44px touch target');
  assert(a.left+a.width<=b.left&&b.left+b.width<=report.system.windowWidth,'Action targets must fit without overlap');
  // Page CSS lookup is shadow-single; rendered XPath includes component content.
  const title=await renderedClass(p,'root-title');assert.equal(await title.text(),'消息');const titleSize=await title.size();assert(Number(titleSize.width)>0&&Number(titleSize.height)>0);
 });
 await check('native search and read-all remain clickable and persisted',async()=>{
  let p=await mini.currentPage();let buttons=await p.$$('.icon-button');await buttons[0].tap();const input=await element(p,'.search input');await input.input('消息布局测试无匹配');await element(p,'.message-list');await shot('native-search');
  await buttons[0].tap();await until(async()=> (await p.$$('.search input')).length===0,'search hidden');
  const before=await(await fetch(base+'/boost/notifications',{headers:{'X-Boost-Token':registration.data.token}})).json();assert.equal(before.code,200);assert(before.data.unread>0,'New real registration must have unread welcome notice');
  await buttons[1].tap();await until(async()=>{const r=await(await fetch(base+'/boost/notifications',{headers:{'X-Boost-Token':registration.data.token}})).json();return r.code===200&&r.data.unread===0},'read-all persistence');
  p=await route('/pages/messages/index');await element(p,'.message-list');assert.equal((await p.$$('.message-list .badge')).length,0);await shot('native-read-all');
 });
 await check('shared native wallet header stays below capsule and its action opens records',async()=>{
  const p=await route('/pages/wallet/index');await element(p,'.balance-card');
  // The action is a page-owned slot; the title belongs to AppShell's component scope.
  const action=await element(p,'.text-button'),position=await action.offset(),menu=await mini.evaluate(()=>wx.getMenuButtonBoundingClientRect());
  assert(Number(position.top)>=menu.bottom+6,'Wallet action must also clear capsule');assert.equal(await action.text(),'提现记录');
  await action.tap();await until(async()=>{for(const title of await renderedElements(p,'state-title'))if((await title.text())==='还没有提现记录')return true;return false},'wallet records');await shot('native-wallet-header');
 });
 await check('mine keeps its existing custom header without an added status-bar spacer',async()=>{
  const p=await route('/pages/mine/index'),head=await element(p,'.profile-head'),offset=await head.offset();
  report.mineHeaderTop=Number(offset.top);assert(Number(offset.top)<report.system.statusBarHeight,'Untitled mine shell must not add another status-bar height');await shot('native-mine');
 });
 assert.deepEqual(report.exceptions,[]);report.exitCode=0;report.status='已实测通过';
}catch(e){report.exitCode=1;report.status='失败';report.error=e.stack;console.error(e.stack);try{if(mini)await timed('diagnostic screenshot',()=>shot('native-failure'),10000)}catch(secondary){report.diagnosticError=secondary.message}}finally{mini?.disconnect();try{run('native-messages-close','powershell.exe',['-NoProfile','-NonInteractive','-Command','& $env:QA_WECHAT_CLI close --project $env:QA_NATIVE_PROJECT --port 9420 --lang zh; exit $LASTEXITCODE'],root,{QA_WECHAT_CLI:'D:/wxkfgj/微信web开发者工具/cli.bat',QA_NATIVE_PROJECT:project})}catch(e){report.cleanupError=e.message;report.exitCode=1}report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'native-messages.json'),JSON.stringify(report,null,2));process.exitCode=report.exitCode}})();
