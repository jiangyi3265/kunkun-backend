const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.resolve(process.env.PLAYER_ACCEPTANCE_OUT||path.join(root,'test-results/player-trade-20261007/final'));fs.mkdirSync(out,{recursive:true});
const javaHome=process.env.JAVA_HOME,mvn=path.join(process.env.USERPROFILE,'tools/apache-maven-3.9.16/bin/mvn.cmd'),node=process.execPath;
const checks=[],startedAt=new Date().toISOString();let backendEnv={};
function run(name,exe,args,cwd,windowsCmd=false){const before=new Date().toISOString();let r;if(windowsCmd){r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command','& $env:PLAYER_MVN -pl ruoyi-admin -am package; exit $LASTEXITCODE'],{cwd,windowsHide:true,encoding:'utf8',env:{...process.env,...backendEnv,PLAYER_MVN:exe,BOOST_TEST_MEMORY_LIMIT:'1'},maxBuffer:80*1024*1024});}else r=spawnSync(exe,args,{cwd,windowsHide:true,encoding:'utf8',env:{...process.env,PLAYER_ACCEPTANCE_OUT:out},maxBuffer:80*1024*1024});const logfile=path.join(out,name+'.log');fs.writeFileSync(logfile,(r.stdout||'')+(r.stderr||'')+(r.error?String(r.error):''));let passed=r.status===0;if(windowsCmd&&/Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: [1-9]/.test(r.stdout||''))passed=false;checks.push({name,startedAt:before,finishedAt:new Date().toISOString(),command:[exe,...args],cwd,exitCode:r.status,status:passed?'已实测通过':'失败',evidence:path.relative(root,logfile)});console.log((passed?'PASS ':'FAIL ')+name);return passed;}
let failed=false;
if(!javaHome||!fs.existsSync(mvn)){checks.push({name:'Java/Maven prerequisite',status:'环境阻塞',exitCode:null});failed=true;}
else{const python=process.env.PLAYER_PYTHON||'C:/Python314/python.exe';if(!run('prepare-isolated-test-db',python,['-X','utf8','scripts/prepare-player-test-db.py'],root))failed=true;else{backendEnv=JSON.parse(fs.readFileSync(path.join(root,'.runtime/player-acceptance-20261007/test-db-environment.json'),'utf8'));if(!run('backend-all-tests-and-package',mvn,['-pl','ruoyi-admin','-am','package'],path.join(root,'RuoYi-Vue'),true))failed=true;}}
if(!failed&&!run('mysql-backup-restore',process.env.PLAYER_PYTHON||'C:/Python314/python.exe',['-X','utf8','scripts/verify-player-backup.py'],root))failed=true;
if(!failed&&!run('rewards-backup-restore',process.env.PLAYER_PYTHON||'C:/Python314/python.exe',['-X','utf8','scripts/verify-rewards-backup.py'],root))failed=true;
if(!failed&&!run('profile-migration',process.env.PLAYER_PYTHON||'C:/Python314/python.exe',['-X','utf8','scripts/verify-player-profile-migration.py'],root))failed=true;
if(!failed&&!run('rewards-migration',process.env.PLAYER_PYTHON||'C:/Python314/python.exe',['-X','utf8','scripts/verify-rewards-migration.py'],root))failed=true;
if(!run('mini-lint',node,['scripts/lint-mini.cjs'],root))failed=true;
if(!run('admin-lint',node,['node_modules/eslint/bin/eslint.js','src/views/boost','src/api/boost.js','src/api/rewards.js','src/utils/request.js','src/utils/boostMedia.js','src/store/modules/permission.js','vite/plugins/svg-icon.js'],path.join(root,'RuoYi-Vue3')))failed=true;
if(!run('h5-build',node,['scripts/uni.cjs','build','-p','h5'],path.join(root,'代练')))failed=true;
if(!run('mini-build',node,['scripts/uni.cjs','build','-p','mp-weixin'],path.join(root,'代练')))failed=true;
if(!run('admin-build',node,['node_modules/vite/bin/vite.js','build'],path.join(root,'RuoYi-Vue3')))failed=true;
process.env.QA_OUTPUT_DIR=out;
if(!run('secret-scan',process.env.PLAYER_PYTHON||'C:/Python314/python.exe',['-X','utf8','scripts/acceptance-secrets.py'],root))failed=true;
process.env.QA_OUTPUT_DIR=path.join(out,'account-races');
if(!run('rewards-ui-races',node,['--experimental-vm-modules','scripts/test-rewards-ui-races.cjs'],root))failed=true;
if(!run('benefits-ui-races',node,['--experimental-vm-modules','scripts/test-benefits-ui-races.cjs'],root))failed=true;
if(!run('account-ui-races',node,['--experimental-vm-modules','scripts/test-account-ui-races.cjs'],root))failed=true;
if(!failed){if(!run('browser-player',node,['scripts/player-trade-browser.cjs'],root))failed=true;}else checks.push({name:'browser-player',status:'未测试',exitCode:null,reason:'前置检查失败，本轮没有执行，旧 JSON 不能作为本轮结果'});
if(!failed){if(!run('real-http-player',node,['scripts/run-player-http-isolated.cjs'],root))failed=true;}else checks.push({name:'real-http-player',status:'未测试',exitCode:null,reason:'前置检查失败，本轮没有执行，旧 JSON 不能作为本轮结果'});
if(!failed){if(!run('rewards-real-browser',node,['scripts/run-player-http-isolated.cjs','--rewards-only'],root))failed=true;}else checks.push({name:'rewards-real-browser',status:'未测试',exitCode:null,reason:'前置检查失败，本轮没有执行'});
if(!failed){if(!run('benefits-real-browser',node,['scripts/run-player-http-isolated.cjs','--benefits-only'],root))failed=true;}else checks.push({name:'benefits-real-browser',status:'未测试',exitCode:null,reason:'前置检查失败，本轮没有执行'});
// External checks must never be made green by local builds or Mock provider responses.
const external=[{name:'正式活动运营内容',status:'未测试',reason:'当前只有后台发放的免费不可购买/提现积分；实际奖品及寄送规则待运营配置'},{name:'实物寄送及真机活动页面',status:'未测试',reason:'本地可核验信息与状态闭环，未实际寄包裹或在原生设备操作'},{name:'真实实名渠道及小程序 SDK 联调',status:'环境阻塞',reason:'缺真实实名凭据和RuleId，不能以替身结果作为真实核验'},{name:'付费会员/自动成长奖励及额外权益',status:'未测试',reason:'当前仅管理员人工会员/成长与配置阈值，未约定付费及自动奖励规则'},{name:'本命令原生及真机检查',status:'未测试',reason:'本入口不运行原生；另用验收三端互通.ps1执行官方Devtools，仍不代表手机'},{name:'真实支付',status:'不适用',reason:'用户要求本阶段不接入，所有资金操作保持关闭'}];
const report={startedAt,finishedAt:new Date().toISOString(),localStatus:failed?'失败':'已实测通过',releaseStatus:'当前不满足所列范围的发布条件',checks,externalChecks:external,scope:'包含免费积分账本、商城勋章/实物、库存幂等与并发、转盘单/五连、后台管理、实际H5/admin闭环；本地静态检查、三端构建、H2 与 MySQL 新交易集成（实名与资金替身）、MySQL 既有并发边界、有旧数据的 V002 升级、V003既有玩家资料升级、隔离数据库备份恢复、敏感值扫描、SFC 替身、H5 浏览器 Mock、生产H5头像资料真实接口闭环、隔离MySQL真实HTTP及后台；不含真实实名/支付/原生设备',exitCode:failed?1:2};fs.writeFileSync(path.join(out,'acceptance.json'),JSON.stringify(report,null,2));console.log(report.releaseStatus);process.exit(report.exitCode);
