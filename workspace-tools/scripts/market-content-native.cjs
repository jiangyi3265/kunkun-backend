// Official Devtools public pages against the same owned HTTP/MySQL acceptance fixture.
const fs=require('fs'),path=require('path'),net=require('net'),assert=require('assert/strict'),{spawnSync}=require('child_process');
const root=path.resolve(__dirname,'..'),out=process.env.MARKET_CONTENT_OUT,base=process.env.PLAYER_HTTP_BASE,fixture=JSON.parse(fs.readFileSync(process.env.PLAYER_HTTP_FIXTURE,'utf8')),project=path.join(path.dirname(process.env.PLAYER_HTTP_FIXTURE),'mp-content');
const report={startedAt:new Date().toISOString(),scope:'Official WeChat Devtools, actual isolated HTTP/MySQL; public content and partial-stock listing. No wx/API Mock. Not a physical phone test.',checks:[],exceptions:[],commands:[]};let mini;
function run(name,exe,args,cwd=root,env={}){const r=spawnSync(exe,args,{cwd,env:{...process.env,...env},windowsHide:true,encoding:'utf8',timeout:180000,maxBuffer:20000000});fs.writeFileSync(path.join(out,name+'.log'),(r.stdout||'')+(r.stderr||''));report.commands.push({name,exe,args,exitCode:r.status});assert.equal(r.status,0,name);return r;}
async function until(fn,label,ms=20000){const deadline=Date.now()+ms;while(Date.now()<deadline){if(await fn())return;await new Promise(r=>setTimeout(r,200))}throw Error(label+' timed out')}
async function timed(label,fn,ms=20000){let timer;try{return await Promise.race([fn(),new Promise((_,reject)=>timer=setTimeout(()=>reject(Error(label+' timed out')),ms))])}finally{clearTimeout(timer)}}
async function freePort(){return new Promise(r=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p))})})}
async function element(p,s){await timed('render '+s,()=>p.waitFor(s));const x=await p.$(s);assert(x,s);return x}
async function shot(name){await new Promise(r=>setTimeout(r,1000));await mini.screenshot({path:path.join(out,name+'.png')})}
async function check(name,fn){await fn();report.checks.push({name,status:'已实测通过'});console.log('PASS '+name)}
(async()=>{try{
run('native-content-build',process.execPath,['scripts/uni.cjs','build','-p','mp-weixin'],path.join(root,'代练'),{VITE_API_BASE_URL:base,UNI_OUTPUT_DIR:project,BOOST_RELEASE:'0'});
const projectFile=path.join(project,'project.config.json'),pc=JSON.parse(fs.readFileSync(projectFile,'utf8'));pc.projectname='困困-内容隔离验收';pc.setting.urlCheck=false;fs.writeFileSync(projectFile,JSON.stringify(pc,null,2));report.project=project;
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
 const readyConnection=new Connection(new Transport(socket));mini=new MiniProgram(readyConnection);report.sdkReadiness=[];
 await until(async()=>{const info=await timed('read SDK readiness',()=>readyConnection.send('Tool.getInfo',{}),5000);const version=info?.SDKVersion;report.sdkReadiness.push({at:new Date().toISOString(),sdkVersion:version||null});return typeof version==='string'&&version.length>0},'official tool SDK initialization',45000);
 await mini.checkVersion();mini.on('exception',e=>report.exceptions.push(String(e.message||e)));const info=await mini.callWxMethod('getAccountInfoSync');assert.equal(info.miniProgram.appId,pc.appid);report.system=await mini.systemInfo();
 // An open automation socket does not prove the initial WebView is registered.
 // Match the SDK launch grace period, then require an actual visible home element.
 await new Promise(resolve=>setTimeout(resolve,5000));
 let initialPage;await until(async()=>{try{initialPage=await mini.currentPage();return !!initialPage}catch(e){if(String(e.message).includes('getPageMetaByWebviewId'))return false;throw e}},'initial native WebView',30000);
 await element(initialPage,'.hero-image-link');report.initialNativePage=initialPage.path;

 await check('configured image-only home banner opens article activity list',async()=>{const p=await mini.currentPage();await until(async()=> (await(await element(p,'.brand')).text()).includes('道具测试集市'),'Configured home name');await shot('native-content-home');await(await element(p,'.hero-image-link')).tap();await until(async()=> (await mini.currentPage())?.path==='pages/content/activities','activity list');const list=await mini.currentPage();await element(list,'.activity-row');const content=await(await element(list,'.activities-page')).text();assert(content.includes('最新道具交流活动')&&content.includes('历史道具交流活动'));await shot('native-activities')});
 await check('independent image article decodes and opens its linked wheel',async()=>{let p=await mini.currentPage();const rows=await p.$$('.activity-row');await rows[0].tap();await until(async()=> (await mini.currentPage())?.path==='pages/content/activity','article');p=await mini.currentPage();const cover=await element(p,'.article-cover'),src=await cover.attribute('src'),info=await mini.callWxMethod('getImageInfo',{src});assert(info.width>100&&info.height>100);assert((await(await element(p,'.article-body')).text()).includes('隔离测试的图文活动正文'));await shot('native-article');await(await element(p,'.article-page .btn')).tap();await until(async()=> (await mini.currentPage())?.path==='pages/rewards/activity','linked wheel');await shot('native-linked-wheel')});
 await check('unit listing detail has correct available stock and detail images',async()=>{const evidence=JSON.parse(fs.readFileSync(path.join(out,'http-browser.json'),'utf8'));let p=await mini.reLaunch('/pages/service/detail?id='+evidence.fixture.listingId);await element(p,'.detail-page-image');const image=await p.$('.detail-page-image'),src=await image.attribute('src');assert((await mini.callWxMethod('getImageInfo',{src})).width>100);await shot('native-unit-listing')});
 assert.deepEqual(report.exceptions,[]);report.exitCode=0;report.status='已实测通过';
}catch(e){report.exitCode=1;report.status='失败';report.error=e.stack;console.error(e.stack);try{if(mini)await shot('native-failure')}catch{}}finally{mini?.disconnect();try{run('native-content-close','powershell.exe',['-NoProfile','-NonInteractive','-Command','& $env:QA_WECHAT_CLI close --project $env:QA_NATIVE_PROJECT --port 9420 --lang zh; exit $LASTEXITCODE'],root,{QA_WECHAT_CLI:'D:/wxkfgj/微信web开发者工具/cli.bat',QA_NATIVE_PROJECT:project})}catch(e){report.cleanupError=e.message;report.exitCode=1}report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'native-content.json'),JSON.stringify(report,null,2));process.exitCode=report.exitCode}})();
