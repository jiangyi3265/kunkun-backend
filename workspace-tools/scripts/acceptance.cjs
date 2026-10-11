// Complete local verification; exit 2 explicitly means external release gates remain blocked.
const fs=require('node:fs'),path=require('node:path'),net=require('node:net');
const {spawn,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const stamp=new Date().toISOString().replace(/[-:.]/g,'');
const out=path.resolve(process.env.QA_OUTPUT_DIR||path.join(root,'test-results','acceptance-'+stamp));
fs.mkdirSync(out,{recursive:true});
if(fs.existsSync(path.join(out,'acceptance-result.json')))throw Error('Choose a new QA_OUTPUT_DIR; previous acceptance evidence is immutable');
const env={...process.env,QA_OUTPUT_DIR:out,QA_EXCLUDE_PAYMENTS:'1',QA_PHASE:'after',BOOST_DEV_PAYMENTS_DISABLED:'1',BOOST_TEST_MEMORY_LIMIT:'1',JAVA_HOME:process.env.JAVA_HOME||path.join(process.env.USERPROFILE,'.jdks/azul-17.0.19')};
delete env.QA_NAV_CASES; // The final gate must run the complete navigation suite.
delete env.QA_REUSE_E2E_FROM; // Every newly prepared UI database gets fresh browser checks.
env.QA_MEDIA_SUITE_MODE='full';
delete env.QA_MEDIA_JAR_SOURCE;
delete env.QA_MEDIA_EXPECTED_SOURCE_JAR_SHA256;
env.BOOST_RUNTIME_DIR=path.join(root,'.runtime','acceptance-'+stamp);
const privateConfig=JSON.parse(fs.readFileSync(path.join(root,'.runtime/local-dev.json'),'utf8'));
const python=process.env.QA_PYTHON||'python';
const maven=process.env.QA_MAVEN||path.join(process.env.USERPROFILE,'tools/apache-maven-3.9.16/bin/mvn.cmd');
const report={startedAt:new Date().toISOString(),timeZone:'America/New_York',scope:'Local nonpayment UI/HTTP; isolated backend tests use payment substitutes only',checks:[],localPassed:false,releaseStatus:'当前不满足所列范围的发布条件',external:[
 {id:'EXT-PRODUCTION',status:'环境阻塞',reason:'正式域名业务未启用；本地 prod 非支付模式须另附隔离实测，不能替代线上验收'},
 {id:'EXT-WECHAT',status:'环境阻塞',reason:'没有微信开发者工具/真机登录会话，未验收真机登录、上传及域名配置'},
 {id:'EXT-LEGAL',status:'环境阻塞',reason:'真实运营主体、隐私联系和协议生效信息尚待确认'},
 {id:'EXT-OPS',status:'未测试',reason:'本地故障告警和媒体恢复由独立专项验证；生产告警接收者/定时器及代码切换回滚尚未验收'},
 {id:'EXT-PAYMENT',status:'不适用',reason:'用户明确暂不接入真实支付；本轮不调用真实商户接口'}
]};
const secrets=Object.entries(privateConfig).filter(([k])=>/PASSWORD|SECRET|SIGNING_KEY/.test(k)).map(([,v])=>String(v)).filter(Boolean);
function clean(text){for(const secret of secrets)text=text.split(secret).join('[REDACTED]');return text;}
function save(){fs.writeFileSync(path.join(out,'acceptance-result.json'),JSON.stringify(report,null,2));}
let reuse;
const reusable=new Set(['isolated-db','backend-verify','backend-counts','mini-lint','admin-lint','production-builds','secret-scan-and-package','session-tests','migration-restore','backend-security']);
function run(name,exe,args,options={}){
 if(reuse&&reusable.has(name)){
  const old=reuse.checks.find(c=>c.name===name&&c.exitCode===0&&c.status==='已实测通过');
  if(old){report.checks.push({...old,reusedEvidence:true,evidenceRun:reuse.directory});save();console.log('REUSED verified evidence '+name);return {status:0};}
 }
 const startedAt=new Date().toISOString();
 const {continueOnFailure=false,...processOptions}=options;
 const result=spawnSync(exe,args,{cwd:root,encoding:'utf8',env,windowsHide:true,timeout:600000,maxBuffer:32*1024*1024,...processOptions});
 const log=clean((result.stdout||'')+(result.stderr||'')+(result.error?String(result.error):''));
 fs.writeFileSync(path.join(out,name+'.log'),log);
 const record={name,command:[exe,...args].map(x=>/\s/.test(x)?JSON.stringify(x):x).join(' '),startedAt,finishedAt:new Date().toISOString(),exitCode:result.status,status:result.status===0?'已实测通过':'失败',log:name+'.log'};
 report.checks.push(record);save();console.log(`${result.status===0?'PASS':'FAIL'} ${name}`);
 if(result.status!==0&&!continueOnFailure)throw Error(name+' failed; inspect '+record.log);
 return result;
}
function node(name,file,args=[],options={}){return run(name,process.execPath,[path.join(root,file),...args],options);}
function py(name,file,args=[],options={}){return run(name,python,['-X','utf8',path.join(root,file),...args],options);}
function mvn(){
 // Use the same Java launcher as mvn.cmd, avoiding nested Windows cmd quote parsing.
 const home=path.resolve(path.dirname(maven),'..');
 const boot=fs.readdirSync(path.join(home,'boot')).find(name=>/^plexus-classworlds-.*\.jar$/.test(name));
 if(!boot)throw Error('Maven classworlds bootstrap not found');
 return run('backend-verify',path.join(env.JAVA_HOME,'bin/java.exe'),['-Dfile.encoding=UTF-8','-Dmaven.home='+home,'-Dclassworlds.conf='+path.join(home,'bin/m2.conf'),'-Dmaven.multiModuleProjectDirectory='+path.join(root,'RuoYi-Vue'),'-classpath',path.join(home,'boot',boot),'org.codehaus.plexus.classworlds.launcher.Launcher','-B','-f','RuoYi-Vue/pom.xml','-pl','ruoyi-admin','-am','clean','verify']);
}
function freePort(){return new Promise((resolve,reject)=>{const server=net.createServer();server.on('error',reject);server.listen(0,'127.0.0.1',()=>{const port=server.address().port;server.close(()=>resolve(port));});});}
async function waitReady(){
 for(let i=0;i<90;i++){
  if(launcher.exitCode!==null)throw Error('Owned development launcher stopped; inspect service.log');
  try{const values=await Promise.all([fetch(env.BOOST_API_URL+'/health/ready',{signal:AbortSignal.timeout(1000)}).then(r=>r.json()),fetch(env.MINIAPP_URL,{signal:AbortSignal.timeout(1000)}),fetch(env.ADMIN_URL,{signal:AbortSignal.timeout(1000)})]);if(values[0].status==='UP'&&values[1].ok&&values[2].ok)return;}catch{}
  await new Promise(r=>setTimeout(r,1000));
 }
 throw Error('Local service readiness timed out');
}
let launcher,serviceLog,adminPrepared=false;
(async()=>{
 if(fs.existsSync(path.join(root,'.runtime/admin-session.json'))||fs.existsSync(path.join(root,'.runtime/readonly-qa.json')))throw Error('Previous QA authentication fixtures exist; clean them before accepting a new run');
 py('source-before','scripts/acceptance-support.py',['source']);
 fs.copyFileSync(path.join(out,'source-final.json'),path.join(out,'source-before.json'));
 if(env.QA_RESUME_FROM){
  env.QA_RESUME_FROM=path.resolve(env.QA_RESUME_FROM);
  py('resume-validation','scripts/acceptance-support.py',['verify-resume']);
  const previous=JSON.parse(fs.readFileSync(path.join(env.QA_RESUME_FROM,'acceptance-result.json')));
  for(const entry of fs.readdirSync(env.QA_RESUME_FROM,{withFileTypes:true})){
   // Copy only unchanged static/build/test evidence. A failed new browser run
   // must never find an old successful results.json in its destination.
   if(!/^(backend-|surefire$|isolated-db|mini-lint|admin-lint|production-builds|release-|secret-scan|session-tests|migration-)/.test(entry.name))continue;
   fs.cpSync(path.join(env.QA_RESUME_FROM,entry.name),path.join(out,entry.name),{recursive:true});
  }
  reuse={directory:env.QA_RESUME_FROM,checks:previous.checks};report.reusedFrom=env.QA_RESUME_FROM;
 }
 py('isolated-db','scripts/acceptance-support.py',['prepare-db']);
 const testDb=JSON.parse(fs.readFileSync(path.join(out,'isolated-db.json'))).database;
 env.BOOST_TEST_DB_URL=privateConfig.BOOST_DB_URL.replace(/\/[^/?]+\?/, '/'+testDb+'?');
 env.BOOST_TEST_DB_USER=privateConfig.BOOST_DB_USER;env.BOOST_TEST_DB_PASSWORD=privateConfig.BOOST_DB_PASSWORD;
 mvn();py('backend-counts','scripts/acceptance-support.py',['collect-tests']);
 node('braces-installed-hardening','scripts/apply-braces-hardening.cjs',['--check']);
 node('braces-security-regression','scripts/test-braces-hardening-review.cjs');
 node('mini-lint','scripts/lint-mini.cjs');
 node('admin-lint','RuoYi-Vue3/node_modules/eslint/bin/eslint.js',['src/views/boost','src/api/boost.js','src/utils/boostMedia.js','src/utils/request.js','src/store/modules/permission.js','vite/plugins/svg-icon.js'],{cwd:path.join(root,'RuoYi-Vue3')});
 const json5=require(require.resolve('json5',{paths:[path.join(root,'代练')]}));
 env.BOOST_WX_APP_ID=json5.parse(fs.readFileSync(path.join(root,'代练/manifest.json'),'utf8'))['mp-weixin'].appid;
 env.BOOST_PUBLIC_API_ORIGIN=fs.readFileSync(path.join(root,'代练/.env.production.local'),'utf8').match(/^VITE_API_BASE_URL=(.+)$/m)?.[1].trim();
 node('production-builds','scripts/build-release.cjs');
 py('secret-scan-and-package','scripts/acceptance-secrets.py',['--package']);
 // Browser harness changes do not invalidate builds, but all current source must still be scanned.
 if(reuse)py('current-secret-scan','scripts/acceptance-secrets.py');
 run('session-tests',process.execPath,['--experimental-vm-modules','--test','--test-skip-pattern=payment confirmation',path.join(root,'scripts/test-session-races.cjs')]);
 run('account-ui-races',process.execPath,['--experimental-vm-modules',path.join(root,'scripts/test-account-ui-races.cjs')]);
 py('migration-restore','scripts/verify-migration.py');
 py('acceptance-failure-gates','scripts/test-acceptance-gates.py');
 py('backend-security','scripts/security-scan-backend.py',[],{continueOnFailure:true});
 const npmCandidate=path.join(path.dirname(process.execPath),'node_modules/npm/bin/npm-cli.js');
 const npm=process.env.QA_NPM_CLI||(fs.existsSync(npmCandidate)?npmCandidate:path.join(path.dirname(spawnSync('where.exe',['npm.cmd'],{encoding:'utf8'}).stdout.trim().split(/\r?\n/)[0]),'node_modules/npm/bin/npm-cli.js'));
 for(const dir of ['代练','RuoYi-Vue3'])run(dir==='代练'?'mini-security':'admin-security',process.execPath,[npm,'audit','--json','--registry=https://registry.npmjs.org'],{cwd:path.join(root,dir),continueOnFailure:true});
 node('production-nonpayment-runtime','scripts/run-nonpayment-production.cjs',[],{continueOnFailure:true});
 const media=run('real-admin-media',process.execPath,[path.join(root,'scripts/run-admin-media-acceptance.cjs')],{env:{...env,QA_OUTPUT_DIR:path.join(out,'real-admin-media')},continueOnFailure:true});
 if(media.status===0){
  const wrapper=JSON.parse(fs.readFileSync(path.join(out,'real-admin-media/media-wrapper-result.json'))),suite=JSON.parse(fs.readFileSync(path.join(out,'real-admin-media/suite/admin-media-real.json'))),runtime=JSON.parse(fs.readFileSync(path.join(out,'real-admin-media/owned-runtime.json')));
  const jarSha=require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(root,'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'))).digest('hex');
  if(wrapper.completeSuite!==true||wrapper.suiteMode!=='full'||suite.completeSuite!==true||runtime.jarSha256!==jarSha)throw Error('Media evidence is not the full suite on the current JAR');
 }
 const ports=[];while(ports.length<3){const p=await freePort();if(!ports.includes(p))ports.push(p);}
 const uiFixtureFile=path.join(env.BOOST_RUNTIME_DIR,'fixture.json');
 py('ui-isolated-fixture','scripts/prepare-ui-acceptance.py',['--output',uiFixtureFile]);
 const uiFixture=JSON.parse(fs.readFileSync(uiFixtureFile,'utf8'));
 const uiEnvironment=JSON.parse(fs.readFileSync(uiFixture.environmentFile,'utf8'));
 Object.assign(env,{BOOST_DB_URL:uiEnvironment.BOOST_DB_URL,BOOST_DB_USER:uiEnvironment.BOOST_DB_USER,BOOST_DB_PASSWORD:uiEnvironment.BOOST_DB_PASSWORD,BOOST_DEV_CONFIG_FILE:uiFixture.environmentFile,BOOST_DEV_MEDIA_DIR:uiFixture.mediaDirectory,QA_ADMIN_USERNAME:uiFixture.credentials.username,QA_ADMIN_PASSWORD:uiFixture.credentials.password});
 secrets.push(uiFixture.credentials.password);
 const safeUiFixture={...uiFixture};delete safeUiFixture.credentials;
 fs.writeFileSync(path.join(out,'ui-isolation.json'),JSON.stringify(safeUiFixture,null,2));
 Object.assign(env,{BOOST_API_PORT:String(ports[0]),BOOST_MINI_PORT:String(ports[1]),BOOST_ADMIN_PORT:String(ports[2]),BOOST_API_URL:`http://127.0.0.1:${ports[0]}`,MINIAPP_URL:`http://127.0.0.1:${ports[1]}`,ADMIN_URL:`http://127.0.0.1:${ports[2]}`});
 serviceLog=fs.openSync(path.join(out,'service.log'),'w');
 launcher=spawn(process.execPath,[path.join(root,'scripts/start-dev.cjs')],{cwd:root,env,windowsHide:true,stdio:['ignore',serviceLog,serviceLog]});
 await waitReady();report.environment={node:process.version,api:env.BOOST_API_URL,mini:env.MINIAPP_URL,admin:env.ADMIN_URL,testDatabase:testDb,uiDatabase:uiFixture.database,uiMediaDirectory:uiFixture.mediaDirectory};
 const endpoints=JSON.parse(fs.readFileSync(path.join(env.BOOST_RUNTIME_DIR,'dev-endpoints.json')));
 report.runtimeJarSha256=endpoints.jarSha256;
 const builtJarSha256=require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(root,'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'))).digest('hex');
 if(!report.runtimeJarSha256||report.runtimeJarSha256!==builtJarSha256)throw Error('Running JAR differs from the final build');
 py('ui-wallet-history','scripts/prepare-wallet-readonly-fixture.py',[uiFixtureFile]);
 const walletFixture=JSON.parse(fs.readFileSync(uiFixtureFile,'utf8'));
 Object.assign(env,{QA_READONLY_WALLET_USERNAME:walletFixture.credentials.walletUsername,QA_READONLY_WALLET_PASSWORD:walletFixture.credentials.walletPassword});
 secrets.push(walletFixture.credentials.walletPassword);
 const safeWalletFixture={...walletFixture};delete safeWalletFixture.credentials;
 fs.writeFileSync(path.join(out,'ui-isolation.json'),JSON.stringify(safeWalletFixture,null,2));
 save();
 py('funds-before','scripts/nonpayment-funds-snapshot.py',['before']);
 py('qa-before','scripts/acceptance-support.py',['capture-qa']);
 py('admin-login','scripts/acceptance-support.py',['login-admin']);adminPrepared=true;
 env.ADMIN_TOKEN=JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'))).token;secrets.push(env.ADMIN_TOKEN);
 node('readonly-setup','scripts/e2e-admin-permissions.cjs',['setup']);
 py('readonly-login','scripts/acceptance-support.py',['login-readonly']);
 node('nonpayment-suites','scripts/run-nonpayment-checks.cjs',[],{continueOnFailure:true});
 for(const name of ['frontend','contracts','suspension','navigation','readonly']){
  const suiteOut=path.join(out,name);fs.mkdirSync(suiteOut,{recursive:true});
  run('acceptance-'+name,process.execPath,['--require',path.join(root,'scripts/nonpayment-request-guard.cjs'),path.join(root,'scripts/e2e-acceptance-'+name+'.cjs')],{env:{...env,QA_OUTPUT_DIR:suiteOut},timeout:300000,continueOnFailure:true});
 }
 const raceBrowserOut=path.join(out,'account-ui-browser');fs.mkdirSync(raceBrowserOut,{recursive:true});
 run('account-ui-browser',process.execPath,['--require',path.join(root,'scripts/nonpayment-request-guard.cjs'),path.join(root,'scripts/e2e-account-ui-races.cjs')],{timeout:300000,continueOnFailure:true});
 py('funds-after','scripts/nonpayment-funds-snapshot.py',['after']);
 py('data-audit','scripts/audit-business-data.py');
 py('qa-cleanup-verification','scripts/acceptance-support.py',['verify-qa']);
 const readiness=await (await fetch(env.BOOST_API_URL+'/health/ready')).json();if(readiness.status!=='UP')throw Error('Final readiness failed');
 fs.writeFileSync(path.join(out,'final-readiness.json'),JSON.stringify({readiness,api:env.BOOST_API_URL},null,2));
 report.localPassed=report.checks.every(check=>check.status==='已实测通过');
})().catch(error=>{report.failure=clean(error.message);console.error(report.failure);}).finally(()=>{
 try{
  if(adminPrepared){
   if(fs.existsSync(path.join(root,'.runtime/readonly-qa.json')))node('readonly-cleanup','scripts/e2e-admin-permissions.cjs',['cleanup']);
   py('admin-cleanup','scripts/acceptance-support.py',['cleanup-admin']);
  }
 }catch(error){report.localPassed=false;report.cleanupFailure=clean(error.message);}
 if(launcher){
  // This PID is the child spawned above for this run, never a discovered shared service.
  const stopped=spawnSync('taskkill',['/PID',String(launcher.pid),'/T','/F'],{encoding:'utf8',windowsHide:true});
  report.ownedServicesStopped=stopped.status===0||launcher.exitCode!==null;
  if(!report.ownedServicesStopped){report.localPassed=false;report.cleanupFailure='Owned service process tree did not stop';}
 }
 if(serviceLog!==undefined)fs.closeSync(serviceLog);
 try{py('source-after','scripts/acceptance-support.py',['source']);const before=JSON.parse(fs.readFileSync(path.join(out,'source-before.json'))),after=JSON.parse(fs.readFileSync(path.join(out,'source-final.json')));report.sourceSha256=after.sha256;if(before.sha256!==after.sha256){report.localPassed=false;report.failure='Source changed during final acceptance; reverify affected checks';}}catch(error){report.localPassed=false;report.failure=clean(error.message);}
 report.finishedAt=new Date().toISOString();report.exitCode=report.localPassed?2:1;save();
 console.log(report.localPassed?'本地所列检查通过；当前不满足所列范围的发布条件（外部项见报告）。':'本地验收失败；当前不满足所列范围的发布条件。');
 console.log('Evidence: '+out);process.exitCode=report.exitCode;
});
