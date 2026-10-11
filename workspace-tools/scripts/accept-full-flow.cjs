// Full current-player acceptance. Writes only owned localhost fixtures and evidence.
// Usage: node scripts/accept-full-flow.cjs <release-package.json> <fresh output dir>
const fs=require('fs'),path=require('path'),crypto=require('crypto'),{spawnSync}=require('child_process');
const root=path.resolve(__dirname,'..'),metaFile=path.resolve(process.argv[2]||''),out=path.resolve(process.argv[3]||'');
if(!process.argv[2]||!process.argv[3]||!out.startsWith(path.join(root,'test-results')+path.sep)||fs.existsSync(out))throw Error('Provide release metadata and a fresh test-results directory');
const meta=JSON.parse(fs.readFileSync(metaFile,'utf8'));fs.mkdirSync(out,{recursive:true});
const python='C:/Python314/python.exe',node=process.execPath,java='C:/Users/jiangyi/.jdks/azul-17.0.19';
const env={...process.env,JAVA_HOME:java,BOOST_TEST_MEMORY_LIMIT:'1',PLAYER_PYTHON:python,PLAYER_RELEASE_METADATA:metaFile,BOOST_WX_APP_ID:meta.appid,BOOST_PUBLIC_API_ORIGIN:meta.apiOrigin};
const report={startedAt:new Date().toISOString(),release:meta.version,metadata:metaFile,scope:'Current source, isolated MySQL/HTTP, actual H5/admin browsers, official native DevTools; provider fixtures explicitly synthetic. Live verification is read-only. No deployment, outbound messages or real payment.',checks:[]};
function save(){fs.writeFileSync(path.join(out,'acceptance.json'),JSON.stringify(report,null,2))}
function run(name,exe,args,extra={},cwd=root){const dir=path.join(out,name);fs.mkdirSync(dir,{recursive:true});const startedAt=new Date().toISOString();const r=spawnSync(exe,args,{cwd,env:{...env,QA_OUTPUT_DIR:dir,PLAYER_ACCEPTANCE_OUT:dir,MARKET_CONTENT_OUT:dir,...extra},windowsHide:true,encoding:'utf8',maxBuffer:80*1024*1024});fs.writeFileSync(path.join(dir,'command.log'),(r.stdout||'')+(r.stderr||'')+(r.error?.message||''));report.checks.push({name,command:[exe,...args],cwd,startedAt,finishedAt:new Date().toISOString(),exitCode:r.status,status:r.status===0?'已实测通过':'失败',evidence:path.relative(root,dir)});save();console.log((r.status===0?'PASS ':'FAIL ')+name);return r.status===0}
const py=(name,script,args=[],extra={})=>run(name,python,['-X','utf8',script,...args],extra);
const js=(name,script,args=[],extra={})=>run(name,node,[script,...args],extra);
try{
 py('source-before','scripts/acceptance-support.py',['source']);
 py('deployed-archive-identity','scripts/verify-current-player-artifacts.py',[metaFile,'--archive-only']);
 py('live-readonly','scripts/verify-current-server.py',[metaFile,'--packaged-baseline']);
 const ready=py('backend','scripts/prepare-player-test-db.py');
 let backend=false;
 if(ready){const tests=JSON.parse(fs.readFileSync(path.join(root,'.runtime/player-acceptance-20261007/test-db-environment.json'),'utf8'));backend=run('backend-tests','powershell.exe',['-NoProfile','-NonInteractive','-Command',"& 'C:/Users/jiangyi/tools/apache-maven-3.9.16/bin/mvn.cmd' -B -f RuoYi-Vue/pom.xml -pl ruoyi-admin -am clean verify; exit $LASTEXITCODE"],tests);py('backend-counts','scripts/acceptance-support.py',['collect-tests']);}
 if(backend){for(const [name,script]of [['player-backup','verify-player-backup.py'],['rewards-backup','verify-rewards-backup.py']])py(name,'scripts/'+script,[],{PLAYER_ACCEPTANCE_OUT:path.join(out,'backend')});for(const [name,script]of [['profile-upgrade','verify-player-profile-migration.py'],['rewards-upgrade','verify-rewards-migration.py']])py(name,'scripts/'+script);}
 js('mini-lint','scripts/lint-mini.cjs');
 js('mini-build-security','scripts/test-mini-build-security.cjs',[path.join(out,'mini-build-security','fixtures')]);
 js('native-protocol-trace','scripts/test-native-protocol-trace.cjs',[path.join(out,'native-protocol-trace','fixtures')]);
 run('admin-lint',node,['node_modules/eslint/bin/eslint.js','src/views/boost','src/api/boost.js','src/api/rewards.js','src/utils/request.js','src/utils/boostMedia.js','src/store/modules/permission.js','vite/plugins/svg-icon.js'],{},path.join(root,'RuoYi-Vue3'));
 for(const name of ['session-races','account-ui-races','rewards-ui-races','benefits-ui-races','phone-recovery','unit-checkout-recovery','checkout-remount','notice-routing','mini-startup-storage'])run(name,node,['--experimental-vm-modules','scripts/test-'+name+'.cjs']);
 const built=js('production-builds','scripts/build-release.cjs');
 if(built&&backend){
  for(const [name,args]of [['rewards-browser',['--rewards-only']],['benefits-browser',['--benefits-only']],['player-profile-admin',[]]])js(name,'scripts/run-player-http-isolated.cjs',args);
  js('market-content','scripts/accept-market-content.cjs',[],{MARKET_CONTENT_NATIVE:'1'});
  js('identity-phone','scripts/accept-identity-phone.cjs');
  js('social-browser','scripts/accept-social-flow.cjs');
 }else report.checks.push({name:'HTTP/browser/native prerequisites',status:'未测试',reason:'Backend or production builds failed'});
 py('acceptance-gates','scripts/test-acceptance-gates.py');
 py('backend-dependency-audit','scripts/security-scan-backend.py');
 for(const [name,folder]of [['mini-dependency-audit','代练'],['admin-dependency-audit','RuoYi-Vue3']])run(name,node,[process.env.QA_NPM_CLI||'D:/nodeJS/node_modules/npm/bin/npm-cli.js','audit','--json','--registry=https://registry.npmjs.org'],{},path.join(root,folder));
 js('braces-installed-patch','scripts/apply-braces-hardening.cjs',['--check']);
 js('braces-boundaries','scripts/test-braces-hardening-review.cjs');
 py('secret-scan','scripts/acceptance-secrets.py');
 py('source-after','scripts/acceptance-support.py',['source']);
 const a=JSON.parse(fs.readFileSync(path.join(out,'source-before/source-final.json'))),b=JSON.parse(fs.readFileSync(path.join(out,'source-after/source-final.json')));report.sourceBefore=a.sha256;report.sourceAfter=b.sha256;
 report.applicationChangesDuringRun=b.files.filter(x=>!x.path.startsWith('scripts/')).filter(x=>a.files.find(y=>y.path===x.path)?.sha256!==x.sha256);if(report.applicationChangesDuringRun.length)throw Error('Application source changed while acceptance was running');
 report.testedJarSha256=crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'))).digest('hex');
}catch(e){report.error=e.message;console.error(e.message)}finally{
 report.external=[{name:'真实实名认证与短信接码',status:'环境阻塞',reason:'须以本轮线上 config 为准；隔离身份及验证码夹具不是供应商接入'},{name:'微信真实登录、体验版与实体手机',status:'未测试',reason:'官方 DevTools 不等于真机；开发版本上传不等于已切换体验版'},{name:'实际物流寄送、正式运营资料和告警接收',status:'未测试',reason:'合成测试数据只能证明系统状态；实际运营配置和寄送仍需验收'},{name:'真实支付及新订单付款后交付结算',status:'不适用',reason:'本阶段用户要求支付关闭；服务层替身测试单独报告，线上不会制造付款成功'}];
 report.localStatus=!report.error&&report.checks.every(c=>c.status==='已实测通过')?'已实测通过':'失败';report.releaseStatus='当前不满足所列范围的发布条件';report.exitCode=report.localStatus==='已实测通过'?2:1;report.finishedAt=new Date().toISOString();report.cleanup='Child suites stop their owned services and browsers; marked localhost databases/media retained for inspection. No production business records modified.';save();console.log(report.releaseStatus);console.log(out);process.exitCode=report.exitCode;
}
