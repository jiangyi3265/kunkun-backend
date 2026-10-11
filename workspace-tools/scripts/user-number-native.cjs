// Official Devtools public pages against the same owned HTTP/MySQL acceptance fixture.
const fs=require('fs'),path=require('path'),net=require('net'),assert=require('assert/strict'),{spawnSync}=require('child_process');
const root=path.resolve(__dirname,'..'),out=process.env.MARKET_CONTENT_OUT,base=process.env.PLAYER_HTTP_BASE,fixture=JSON.parse(fs.readFileSync(process.env.PLAYER_HTTP_FIXTURE,'utf8')),project=path.join(path.dirname(process.env.PLAYER_HTTP_FIXTURE),'mini');
const report={startedAt:new Date().toISOString(),scope:'Official WeChat Devtools, actual isolated HTTP/MySQL; numeric user IDs, actual login and exact user search. No wx/API Mock. Not a physical phone test.',checks:[],exceptions:[],commands:[]};let mini;
function run(name,exe,args,cwd=root,env={}){const r=spawnSync(exe,args,{cwd,env:{...process.env,...env},windowsHide:true,encoding:'utf8',timeout:180000,maxBuffer:20000000});fs.writeFileSync(path.join(out,name+'.log'),(r.stdout||'')+(r.stderr||''));report.commands.push({name,exe,args,exitCode:r.status});assert.equal(r.status,0,name);return r;}
async function until(fn,label,ms=20000){const deadline=Date.now()+ms;while(Date.now()<deadline){if(await fn())return;await new Promise(r=>setTimeout(r,200))}throw Error(label+' timed out')}
async function timed(label,fn,ms=20000){let timer;try{return await Promise.race([fn(),new Promise((_,reject)=>timer=setTimeout(()=>reject(Error(label+' timed out')),ms))])}finally{clearTimeout(timer)}}
async function freePort(){return new Promise(r=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p))})})}
async function element(p,s){await timed('render '+s,()=>p.waitFor(s));const x=await p.$(s);assert(x,s);return x}
async function shot(name){await new Promise(r=>setTimeout(r,1000));await mini.screenshot({path:path.join(out,name+'.png')})}
async function check(name,fn){await fn();report.checks.push({name,status:'已实测通过'});console.log('PASS '+name)}
(async()=>{try{
run('native-content-build',process.execPath,['scripts/uni.cjs','build','-p','mp-weixin'],path.join(root,'代练'),{VITE_API_BASE_URL:base,UNI_OUTPUT_DIR:project,BOOST_RELEASE:'0'});
const projectFile=path.join(project,'project.config.json'),pc=JSON.parse(fs.readFileSync(projectFile,'utf8'));pc.projectname='困困-留白验收';pc.setting.urlCheck=false;fs.writeFileSync(projectFile,JSON.stringify(pc,null,2));report.project=project;
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
 await element(initialPage,'.brand');report.initialNativePage=initialPage.path;


 const old=JSON.parse(fs.readFileSync(path.join(path.dirname(process.env.PLAYER_HTTP_FIXTURE),'old-members.private.json'),'utf8'))[0],number=String(10000+old.user.id);
 await check('native actual login and my profile display numeric user number',async()=>{
  let p=await mini.reLaunch('/pages/auth/index');await(await element(p,'#username')).input(old.credentials.username);await(await element(p,'#password')).input(old.credentials.password);await(await element(p,'.check-row')).tap();await(await element(p,'.auth-page .btn')).tap();await until(async()=> (await mini.currentPage())?.path==='pages/index/index','login');p=await mini.reLaunch('/pages/mine/index');await(await element(p,'.avatar-home')).tap();await until(async()=> (await mini.currentPage())?.path==='pages/shop/index','own profile');p=await mini.currentPage();let label;await until(async()=>{label=await p.getElementByXpath('//*[contains(@class,"province")]');return !!label?.id},'number label');assert((await label.text()).includes('编号 '+number));assert(!(await label.text()).includes('KK'));await shot('native-own-number');
 });
 await check('native exact numeric search opens matching user and survives reentry',async()=>{
  for(const no of [number,old.user.userNo]){let p=await mini.reLaunch('/pages/players/search?userNo='+no);let row;await until(async()=>{row=await p.getElementByXpath('//*[contains(@class,"player-result")]');return !!row?.id},'search result');assert((await row.text()).includes(number));await row.tap();await until(async()=> (await mini.currentPage())?.path==='pages/shop/index','search profile');p=await mini.currentPage();let label;await until(async()=>{label=await p.getElementByXpath('//*[contains(@class,"province")]');return !!label?.id},'profile label');assert((await label.text()).includes('编号 '+number));}
  await shot('native-search-number');
 });
 assert.deepEqual(report.exceptions,[]);report.exitCode=0;report.status='已实测通过';
}catch(e){report.exitCode=1;report.status='失败';report.error=e.stack;console.error(e.stack);try{if(mini)await shot('native-failure')}catch{}}finally{mini?.disconnect();try{run('native-identity-close','powershell.exe',['-NoProfile','-NonInteractive','-Command','& $env:QA_WECHAT_CLI close --project $env:QA_NATIVE_PROJECT --port 9420 --lang zh; exit $LASTEXITCODE'],root,{QA_WECHAT_CLI:'D:/wxkfgj/微信web开发者工具/cli.bat',QA_NATIVE_PROJECT:project})}catch(e){report.cleanupError=e.message;report.exitCode=1}report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'native.json'),JSON.stringify(report,null,2));process.exitCode=report.exitCode}})();
