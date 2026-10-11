// Targeted repair acceptance; does not deploy, send SMS, or certify physical devices.
// node scripts/accept-repair-hardening.cjs <fresh test-results directory>
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.resolve(process.argv[2]||'');
assert(process.argv[2]&&out.startsWith(path.join(root,'test-results')+path.sep)&&!fs.existsSync(out),'Use a fresh evidence directory');fs.mkdirSync(out,{recursive:true});
const metadata=path.join(root,'test-results/user-number-20261010/release-package.json'),meta=JSON.parse(fs.readFileSync(metadata));
const node=process.execPath,python='C:/Python314/python.exe';
const report={startedAt:new Date().toISOString(),scope:'Targeted build security and identity/public-profile repair checks; no deployment or physical device acceptance',checks:[]};
function save(){fs.writeFileSync(path.join(out,'acceptance.json'),JSON.stringify(report,null,2))}
function run(name,exe,args,extra={},cwd=root){
 const dir=path.join(out,name);fs.mkdirSync(dir,{recursive:true});const startedAt=new Date().toISOString();
 const result=spawnSync(exe,args,{cwd,env:{...process.env,BOOST_WX_APP_ID:meta.appid,BOOST_PUBLIC_API_ORIGIN:meta.apiOrigin,QA_OUTPUT_DIR:dir,MARKET_CONTENT_OUT:dir,...extra},windowsHide:true,encoding:'utf8',maxBuffer:50*1024*1024});
 fs.writeFileSync(path.join(dir,'command.log'),(result.stdout||'')+(result.stderr||'')+(result.error?.message||''));
 report.checks.push({name,command:[exe,...args],cwd,startedAt,finishedAt:new Date().toISOString(),exitCode:result.status,status:result.status===0?'已实测通过':'失败',evidence:path.relative(root,dir)});save();console.log((result.status===0?'PASS ':'FAIL ')+name);return result.status===0;
}
const js=(name,script,args=[])=>run(name,node,[script,...args]);
const py=(name,script,args=[])=>run(name,python,['-X','utf8',script,...args]);
try{
 py('source-before','scripts/acceptance-support.py',['source']);
 const previous=JSON.parse(fs.readFileSync(path.join(root,'test-results/full-flow-20261010/delivery-integrity/source/source-final.json')));
 const current=JSON.parse(fs.readFileSync(path.join(out,'source-before/source-final.json')));
 const product=f=>!f.path.startsWith('scripts/')&&f.path!=='代练/scripts/uni.cjs';
 assert.deepEqual(current.files.filter(product),previous.files.filter(product),'Business/backend/dependency source changed; run full acceptance instead');
 const jarSha256=crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'))).digest('hex');
 const tested=JSON.parse(fs.readFileSync(path.join(root,'test-results/full-flow-20261010/final/acceptance.json')));
 assert.equal(jarSha256,tested.jarSha256,'Backend binary changed');
 report.reusedBusinessEvidence={sourceMatched:true,jarSha256,report:'test-results/full-flow-20261010/验收报告.html',reason:'Unchanged application/backend source and JAR; original test times/scopes apply, not newly rerun business tests'};
 js('build-security','scripts/test-mini-build-security.cjs',[path.join(out,'build-security/fixtures')]);
 js('native-trace','scripts/test-native-protocol-trace.cjs',[path.join(out,'native-trace/fixtures')]);
 js('installed-patch','scripts/apply-braces-hardening.cjs',['--check']);
 js('braces-boundaries','scripts/test-braces-hardening-review.cjs');
 const built=js('production-builds','scripts/build-release.cjs');
 if(built)js('identity-browser-native','scripts/accept-identity-phone.cjs');
 else report.checks.push({name:'identity-browser-native',status:'未测试',reason:'Production build failed'});
 run('npm-audit-mini',node,['D:/nodeJS/node_modules/npm/bin/npm-cli.js','audit','--json','--registry=https://registry.npmjs.org'],{},path.join(root,'代练'));
 py('live-readonly','scripts/verify-current-server.py',[metadata,'--packaged-baseline']);
 py('source-after','scripts/acceptance-support.py',['source']);
 report.sourceSha256=JSON.parse(fs.readFileSync(path.join(out,'source-after/source-final.json'))).sha256;
 assert.equal(current.sha256,report.sourceSha256,'Sources changed during acceptance');
 py('secret-scan','scripts/acceptance-secrets.py',['--evidence',out]);
}catch(error){report.error=error.message;console.error(error.message)}finally{
 report.finishedAt=new Date().toISOString();report.releaseStatus='当前不满足所列范围的发布条件';
 report.external=[{name:'真实短信及实名认证',status:'环境阻塞'},{name:'微信实体手机及体验版',status:'未测试'},{name:'原生工具历史超时根因',status:'未测试',reason:'Tracing improved; a successful targeted run does not prove the previous intermittent protocol failure fixed'},{name:'真实支付',status:'不适用',reason:'User requires payment disabled'}];
 report.exitCode=report.error||report.checks.some(c=>c.status!=='已实测通过')?1:2;save();console.log(report.releaseStatus);process.exitCode=report.exitCode;
}
