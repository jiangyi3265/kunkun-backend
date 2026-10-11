// Fresh isolated database + owned services + real media suite + strict cleanup, suitable for the total gate.
const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=process.env.QA_OUTPUT_DIR;
const suiteMode=process.env.QA_MEDIA_SUITE_MODE||'full';assert.ok(['full','system-revocation-probe'].includes(suiteMode),'Unsupported explicit probe mode');
assert.ok(out,'QA_OUTPUT_DIR required');fs.mkdirSync(out,{recursive:true});assert.ok(!fs.existsSync(path.join(out,'media-wrapper-result.json')),'Fresh output directory required');
const privateDirectory=path.join(root,'.runtime','media-acceptance-'+Date.now()+'-'+crypto.randomBytes(4).toString('hex'));fs.mkdirSync(privateDirectory,{recursive:true});
const fixtureFile=path.join(privateDirectory,'fixture.json'),runtime=path.join(privateDirectory,'services');fs.mkdirSync(runtime);
const report={startedAt:new Date().toISOString(),suiteMode,completeSuite:suiteMode==='full',scope:'Fresh owned local database/files; druid development profile, live/demo/initialize/simulated/payments all false. No production or payment integration claim.',checks:[],cleanup:[]};
const children=[],oneShots=[],logs=[];let fixture,failure,childFailure,metadata,stopping=false,finishPromise;
function available(){return new Promise((resolve,reject)=>{const s=net.createServer();s.once('error',reject);s.listen(0,'127.0.0.1',()=>{const port=s.address().port;s.close(()=>resolve(port));});});}
function sha(file){return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');}
function clean(text){return String(text).replace(/https?:\/\/[^\s]+/g,'[URL]').replace(/[a-zA-Z0-9_-]{48,}/g,'[credential redacted]');}
function childRun(name,executable,args,cwd,environment,logFile){
 return new Promise((resolve,reject)=>{const child=spawn(executable,args,{cwd,env:{...process.env,...environment},windowsHide:true,stdio:['ignore','pipe','pipe']}),log=fs.createWriteStream(logFile);oneShots.push({name,pid:child.pid,child});child.stdout.pipe(log,{end:false});child.stderr.pipe(log,{end:false});child.once('error',e=>{log.end();reject(e);});child.once('exit',code=>{log.end(()=>resolve(code));});});
}
function start(name,executable,args,cwd,environment){
 const child=spawn(executable,args,{cwd,env:{...process.env,...environment},windowsHide:true,stdio:['ignore','pipe','pipe']});children.push({name,pid:child.pid,child});
 const log=fs.createWriteStream(path.join(runtime,name+'.log'));logs.push(log);child.stdout.pipe(log,{end:false});child.stderr.pipe(log,{end:false});
 child.on('error',e=>{childFailure=name+' failed: '+clean(e.message);});child.on('exit',code=>{if(!stopping)childFailure=name+' unexpectedly exited '+code;});return child;
}
async function ready(origin,api=false){
 for(let i=0;i<90;i++){if(childFailure)throw new Error(childFailure);try{const response=await fetch(origin+(api?'/boost/config':''),{signal:AbortSignal.timeout(1000)});if(response.ok&&(!api||(await response.json()).code===200))return;}catch{}await new Promise(r=>setTimeout(r,1000));}
 throw new Error('Owned local service not ready: '+origin);
}
function exists(pid){try{process.kill(pid,0);return true;}catch{return false;}}
process.on('SIGINT',()=>{failure='Interrupted';finish().then(()=>process.exit(1));});
function finish(){
 if(finishPromise)return finishPromise;stopping=true;
 return finishPromise=(async()=>{
 for(const owned of [...oneShots,...children]){
  let exitCode=null;if(owned.child.exitCode===null&&exists(owned.pid))exitCode=spawnSync('taskkill',['/PID',String(owned.pid),'/T','/F'],{windowsHide:true,encoding:'utf8'}).status;
  for(let i=0;i<50&&exists(owned.pid);i++)await new Promise(resolve=>setTimeout(resolve,100));
  report.cleanup.push({name:owned.name,pid:owned.pid,stopExitCode:exitCode,absent:!exists(owned.pid)});
 }
 for(const log of logs)log.end();
 if(report.cleanup.some(x=>!x.absent))failure=failure||'Owned service cleanup failed';
 report.finishedAt=new Date().toISOString();report.failure=failure||childFailure;report.passed=!report.failure&&report.checks.length===3&&report.checks.every(x=>x.exitCode===0)&&report.cleanup.every(x=>x.absent);report.exitCode=report.passed?0:1;
 fs.writeFileSync(path.join(out,'media-wrapper-result.json'),JSON.stringify(report,null,2));
 console.log(JSON.stringify({passed:report.passed,exitCode:report.exitCode,failure:report.failure,checks:report.checks,ownedServicesStopped:report.cleanup.every(x=>x.absent)},null,2));process.exitCode=report.exitCode;
 })();
}
(async()=>{
 const python=process.env.QA_PYTHON||'C:/Python314/python.exe';
 const preparation=await childRun('prepare',python,['-X','utf8',path.join(root,'scripts/prepare-admin-media-fixture.py'),'prepare','--output',fixtureFile],root,{},path.join(out,'prepare-command.log'));
 report.checks.push({name:'fresh-isolated-fixture',exitCode:preparation,command:[python,'-X','utf8','scripts/prepare-admin-media-fixture.py','prepare','--output',fixtureFile]});assert.equal(preparation,0,'Fixture preparation failed; retained log');
 fixture=JSON.parse(fs.readFileSync(fixtureFile,'utf8'));
 const safeFixture={...fixture};delete safeFixture.credentials;fs.writeFileSync(path.join(out,'prepared-fixture.json'),JSON.stringify(safeFixture,null,2));
 const local=JSON.parse(fs.readFileSync(path.join(root,'.runtime/local-dev.json'),'utf8'));
 const sourceJar=path.resolve(process.env.QA_MEDIA_JAR_SOURCE||path.join(root,'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'));assert.ok(sourceJar.startsWith(root+path.sep),'Local workspace JAR required');if(process.env.QA_MEDIA_JAR_SOURCE){assert.ok(process.env.QA_MEDIA_EXPECTED_SOURCE_JAR_SHA256,'Explicit probe JAR source requires expected SHA');assert.equal(sha(sourceJar),process.env.QA_MEDIA_EXPECTED_SOURCE_JAR_SHA256);}
 const jar=path.join(runtime,'server.jar');fs.copyFileSync(sourceJar,jar);const jarSha256=sha(jar);
 const [apiPort,miniPort,adminPort]=await Promise.all([available(),available(),available()]);assert.equal(new Set([apiPort,miniPort,adminPort]).size,3);
 const api='http://127.0.0.1:'+apiPort,mini='http://127.0.0.1:'+miniPort,admin='http://127.0.0.1:'+adminPort;
 const databaseEnvironment={...local,BOOST_DB_URL:local.BOOST_DB_URL.replace(/(jdbc:mysql:\/\/[^/]+\/)[^?]+/,'$1'+fixture.database)};assert.ok(databaseEnvironment.BOOST_DB_URL.includes('/'+fixture.database+'?'));
 start('backend',process.env.QA_JAVA||'C:/Users/jiangyi/.jdks/azul-17.0.19/bin/java.exe',['-Xms64m','-Xmx256m','-XX:MaxMetaspaceSize=256m','-XX:ReservedCodeCacheSize=64m','-Dfile.encoding=UTF-8','-jar',jar,'--spring.profiles.active=druid','--server.address=127.0.0.1','--server.port='+apiPort,'--boost.initialize-schema=false','--boost.demo=false','--boost.payments-enabled=false','--boost.simulated-payment=false','--boost.live=false','--boost.registration-hourly-limit=100','--boost.media-dir='+fixture.mediaDirectory,'--ruoyi.profile='+path.join(runtime,'uploads'),'--logging.level.com.ruoyi=info'],path.join(root,'RuoYi-Vue'),databaseEnvironment);
 await ready(api,true);
 const miniDirectory=path.join(root,'代练'),adminDirectory=path.join(root,'RuoYi-Vue3');
 start('mini',process.execPath,[path.join(miniDirectory,'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'),'-p','h5','--host','127.0.0.1','--port',String(miniPort),'--strictPort'],miniDirectory,{UNI_INPUT_DIR:miniDirectory,BOOST_API_URL:api});
 start('admin',process.execPath,[path.join(adminDirectory,'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',String(adminPort),'--strictPort'],adminDirectory,{BOOST_API_URL:api,BOOST_NO_OPEN:'1'});
 await Promise.all([ready(mini),ready(admin)]);
 metadata={api,mini,admin,runtimeJar:jar,jarSha256,database:fixture.database,mediaDirectory:fixture.mediaDirectory,launcherPid:process.pid,children:children.map(({name,pid})=>({name,pid})),runtimeDirectory:runtime,readyAtUtc:new Date().toISOString(),profile:report.scope,scriptSha256:{wrapper:sha(__filename),suite:sha(path.join(root,'scripts/e2e-acceptance-admin-media-real.cjs')),preparation:sha(path.join(root,'scripts/prepare-admin-media-fixture.py'))}};
 fs.writeFileSync(path.join(out,'owned-runtime.json'),JSON.stringify(metadata,null,2));report.checks.push({name:'owned-services-ready',exitCode:0,runtimeJarSha256:jarSha256});
 const suiteOut=path.join(out,'suite');fs.mkdirSync(suiteOut);const suiteEnvironment={BOOST_API_URL:api,MINIAPP_URL:mini,ADMIN_URL:admin,QA_OUTPUT_DIR:suiteOut,QA_MEDIA_FIXTURE_FILE:fixtureFile,QA_MEDIA_RUNTIME_JAR:jar,QA_MEDIA_EXPECTED_JAR_SHA256:jarSha256};
 const suiteArgs=[path.join(root,'scripts/e2e-acceptance-admin-media-real.cjs'),...(suiteMode==='system-revocation-probe'?['--probe-system-revocation']:[])];
 const suite=await childRun('real-suite',process.execPath,suiteArgs,root,suiteEnvironment,path.join(out,'suite-command.log'));
 report.checks.push({name:suiteMode==='full'?'real-media-suite':'system-revocation-probe',exitCode:suite,command:[process.execPath,...suiteArgs]});
 const result=JSON.parse(fs.readFileSync(path.join(suiteOut,suiteMode==='full'?'admin-media-real.json':'probe-system-revocation.json'),'utf8'));assert.equal(suite,0,'Real assertions failed; retained result');assert.equal(result.passed,true);assert.equal(result.completeSuite,suiteMode==='full');
})().catch(e=>{failure=clean(e.stack||e.message);console.error(failure);}).finally(finish);
