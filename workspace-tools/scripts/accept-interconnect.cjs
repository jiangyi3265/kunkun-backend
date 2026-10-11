// Repeatable acceptance of the current frozen build, using owned MySQL fixtures.
// This does not deploy, upload, alter live records or replace real-provider checks.
const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),node=process.execPath;
const out=path.resolve(process.env.PLAYER_ACCEPTANCE_OUT||path.join(root,'test-results/interconnect-'+Date.now()));fs.mkdirSync(out,{recursive:true});
const report={startedAt:new Date().toISOString(),scope:'Current built JAR + H5/admin, actual isolated HTTP/MySQL/browser flows, separately compiled official native Devtools. No real KYC/payment/provider/physical-phone claim.',checks:[]};
const python=process.env.PLAYER_PYTHON||'C:/Python314/python.exe';
function run(name,exe,args,cwd=root){const dir=path.join(out,name);fs.mkdirSync(dir,{recursive:true});const start=new Date().toISOString();const r=spawnSync(exe,args,{cwd,windowsHide:true,encoding:'utf8',maxBuffer:80*1024*1024,env:{...process.env,PLAYER_ACCEPTANCE_OUT:dir,QA_OUTPUT_DIR:dir}});fs.writeFileSync(path.join(dir,'command.log'),(r.stdout||'')+(r.stderr||'')+(r.error?String(r.error):''));report.checks.push({name,command:[exe,...args],cwd,startedAt:start,finishedAt:new Date().toISOString(),exitCode:r.status,status:r.status===0?'已实测通过':'失败',evidence:path.relative(root,dir)});console.log((r.status===0?'PASS ':'FAIL ')+name);return r.status===0;}
let ok=run('artifact-identity',python,['-X','utf8','scripts/verify-current-player-artifacts.py']);
if(ok){for(const [name,script]of [['mini-lint','scripts/lint-mini.cjs'],['rewards-ui-races','scripts/test-rewards-ui-races.cjs'],['benefits-ui-races','scripts/test-benefits-ui-races.cjs']]){if(!run(name,node,[...(name.endsWith('races')?['--experimental-vm-modules']:[]),script]))ok=false;}}
if(ok)ok=run('player-http-browser',node,['scripts/run-player-http-isolated.cjs']);
if(ok)ok=run('rewards-browser',node,['scripts/run-player-http-isolated.cjs','--rewards-only']);
if(ok)ok=run('benefits-browser',node,['scripts/run-player-http-isolated.cjs','--benefits-only']);
// The generated release project is owned by this task. Its existing automated
// context shares an AppID with the isolated copy; close only that exact project
// before opening the test copy, leaving source projects and other apps alone.
if(ok)ok=run('close-owned-release-project','powershell.exe',['-NoProfile','-NonInteractive','-Command',"& 'D:/wxkfgj/微信web开发者工具/cli.bat' close --project (Join-Path (Get-Location) '代练/dist/build/mp-weixin') --port 9420 --lang zh; exit $LASTEXITCODE"]);
if(ok)ok=run('native',node,['scripts/player-native-isolated.cjs']);
if(ok)ok=run('native-persistence',python,['-X','utf8','scripts/verify-native-player-state.py',path.join(out,'native')]);
report.externalChecks=[
 {name:'真实双认证→发布',status:'环境阻塞',reason:'部署站 realnameReady=false；需腾讯云FaceID凭据、RuleId与小程序核验渠道，不允许模拟实名代替'},
 {name:'手机体验版微信登录/上传/交互',status:'未测试',reason:'官方Devtools不代表实体手机；上传成功不代表体验版切换或真机通过'},
 {name:'实物实际寄送',status:'未测试',reason:'测试使用合成地址与物流号，仅证明数据/状态闭环'},
 {name:'正式运营协议/监控报警',status:'未测试',reason:'当前为内部测试政策和既有站点，正式材料及报警演练另验'},
 {name:'真实支付',status:'不适用',reason:'用户明确本阶段关闭支付'},
 {name:'付费会员/自动成长奖励',status:'未测试',reason:'当前实现管理员人工授予与配置阈值；未约定额外权益和自动奖励事件'}
];
report.localStatus=ok?'已实测通过':'失败';report.releaseStatus='当前不满足所列范围的发布条件';report.finishedAt=new Date().toISOString();report.exitCode=ok?2:1;
report.cleanup='Owned HTTP servers stopped and test sessions logged out by child suites. Each marked isolated MySQL database/media directory is retained for evidence; no live database cleanup. Never delete a database unless its fixture marker and owned prefix are verified.';
fs.writeFileSync(path.join(out,'acceptance.json'),JSON.stringify(report,null,2));console.log(report.releaseStatus);process.exitCode=report.exitCode;
