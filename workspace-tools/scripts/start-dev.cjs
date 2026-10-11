const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),{spawn}=require('node:child_process'),{createRequire}=require('node:module');
const root=path.resolve(__dirname,'..'),runtime=process.env.BOOST_RUNTIME_DIR?path.resolve(process.env.BOOST_RUNTIME_DIR):path.join(root,'.runtime');const children=[];let stopping=false;
let apiPort=Number(process.env.BOOST_API_PORT||8080);const miniPort=Number(process.env.BOOST_MINI_PORT||5210),adminPort=Number(process.env.BOOST_ADMIN_PORT||5175);
const apiUrl=()=>`http://127.0.0.1:${apiPort}`;
function available(port){return new Promise((resolve,reject)=>{const server=net.createServer();server.once('error',()=>reject(new Error(`端口 ${port} 已被占用，请先关闭本项目已启动的服务。`)));server.listen(port,'127.0.0.1',()=>server.close(resolve))})}
function stop(code=0){if(stopping)return;stopping=true;for(const child of children)child.kill();process.exitCode=code;setTimeout(()=>process.exit(code),1500).unref()}
function start(name,executable,args,cwd,env={}){const child=spawn(executable,args,{cwd,env:{...process.env,...env},windowsHide:true,stdio:['ignore','pipe','pipe']});children.push(child);const log=fs.createWriteStream(path.join(runtime,name+'.log'),{flags:'w'});for(const pipe of[child.stdout,child.stderr])pipe.on('data',b=>{log.write(b);process.stdout.write(`[${name}] ${b}`)});child.on('error',e=>{console.error(`${name} 启动失败: ${e.message}`);stop(1)});child.on('exit',code=>{log.end();if(!stopping){console.error(`${name} 已停止 (${code})`);stop(code||1)}});return child}
async function waitForApi(){for(let i=0;i<60;i++){if(stopping)throw new Error('后端启动失败');try{const r=await fetch(apiUrl()+'/boost/config',{signal:AbortSignal.timeout(1000)});if(r.ok&&(await r.json()).code===200)return}catch{}await new Promise(r=>setTimeout(r,1000))}throw new Error('后端启动超时，请查看 .runtime/backend.log')}
process.on('SIGINT',()=>stop());process.on('SIGTERM',()=>stop());process.on('exit',()=>{for(const c of children)c.kill()});
(async()=>{
 const privateConfig=process.env.BOOST_DEV_CONFIG_FILE?path.resolve(process.env.BOOST_DEV_CONFIG_FILE):path.join(root,'.runtime/local-dev.json');
 if(!fs.existsSync(privateConfig))throw new Error('请按 deploy/README.md 配置本地环境文件');const localConfig=JSON.parse(fs.readFileSync(privateConfig,'utf8'));
 if(process.env.BOOST_DEV_CONFIG_FILE){
  const relative=path.relative(path.join(root,'.runtime'),privateConfig),database=localConfig.BOOST_DB_URL?.match(/^jdbc:mysql:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?\/(boost_verify_ui_\d+_[a-f0-9]{6})\?/);
  if(!relative||relative.startsWith('..')||path.isAbsolute(relative)||!database)throw new Error('验收配置必须属于本项目私有目录和独立本地测试库');
 }
 const jar=path.join(root,'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar');if(!fs.existsSync(jar))throw new Error('请先在 RuoYi-Vue 目录执行 mvn -DskipTests package');
 const mini=path.join(root,'代练'),admin=path.join(root,'RuoYi-Vue3');for(const dir of[mini,admin])if(!fs.existsSync(path.join(dir,'node_modules')))throw new Error(`请先在 ${dir} 执行 npm ci`);
 await Promise.all([miniPort,adminPort].map(available));
 try{await available(apiPort)}catch(e){if(process.env.BOOST_API_PORT)throw e;let found=false;for(let port=18080;port<18090;port++){try{await available(port);apiPort=port;found=true;break}catch{}}if(!found)throw e;console.log(`8080 已被占用，本项目后端改用 ${apiPort}，前端代理已同步。`)}
 fs.mkdirSync(runtime,{recursive:true});const copy=path.join(runtime,'boost-server-'+Date.now()+'.jar');fs.copyFileSync(jar,copy);
 const jarSha256=require('node:crypto').createHash('sha256').update(fs.readFileSync(copy)).digest('hex');
 fs.writeFileSync(path.join(runtime,'dev-endpoints.json'),JSON.stringify({api:apiUrl(),mini:`http://127.0.0.1:${miniPort}`,admin:`http://127.0.0.1:${adminPort}`,jarSha256,launcherPid:process.pid},null,2));
 const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
 const memoryArgs=process.env.BOOST_TEST_MEMORY_LIMIT==='1'?['-Xms64m','-Xmx256m','-XX:MaxMetaspaceSize=256m','-XX:ReservedCodeCacheSize=64m']:[];
 start('backend',java,[...memoryArgs,'-Dfile.encoding=UTF-8','-jar',copy,'--server.port='+apiPort,'--boost.initialize-schema=true','--boost.registration-hourly-limit=300','--boost.demo=true','--boost.simulated-payment=true','--boost.payments-enabled='+(process.env.BOOST_DEV_PAYMENTS_DISABLED!=='1'),'--server.address=127.0.0.1','--boost.media-dir='+(process.env.BOOST_DEV_MEDIA_DIR||path.join(runtime,'boost-media')),'--ruoyi.profile='+path.join(runtime,'uploads'),'--logging.level.com.ruoyi=info'],path.join(root,'RuoYi-Vue'),localConfig);
 await waitForApi();
 const req=createRequire(path.join(mini,'package.json'));const uni=path.join(path.dirname(req.resolve('@dcloudio/vite-plugin-uni/package.json')),'bin/uni.js');
 start('miniapp',process.execPath,[uni,'-p','h5','--host','127.0.0.1','--port',String(miniPort),'--strictPort'],mini,{UNI_INPUT_DIR:mini,BOOST_API_URL:apiUrl()});
 start('admin',process.execPath,[path.join(admin,'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',String(adminPort),'--strictPort'],admin,{BOOST_NO_OPEN:'1',BOOST_API_URL:apiUrl()});
 console.log(`\n用户 / 打手端：http://127.0.0.1:${miniPort}\n平台管理端：http://127.0.0.1:${adminPort}\n后端：${apiUrl()}\n${process.env.BOOST_DEV_PAYMENTS_DISABLED==='1'?'当前支付及资金操作已关闭，使用隔离演示数据验收非支付流程。':'当前为本地测试支付。'}按 Ctrl+C 关闭本次启动的三个服务。\n`);
})().catch(e=>{console.error(e.message);stop(1)});
