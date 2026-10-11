const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const appid=process.env.BOOST_WX_APP_ID,origin=process.env.BOOST_PUBLIC_API_ORIGIN;
if(!/^wx[a-f0-9]{16}$/i.test(appid||''))throw new Error('Set the production BOOST_WX_APP_ID');
const url=new URL(origin||'http://localhost');if(url.protocol!=='https:'||url.hostname==='localhost'||url.hostname.endsWith('.invalid')||/^\d[\d.]+$/.test(url.hostname)||url.pathname!=='/'||url.username||url.password||url.search||url.hash||url.hostname.includes(':'))throw new Error('Set BOOST_PUBLIC_API_ORIGIN to the real HTTPS API origin');
const mini=path.join(root,'代练'),manifest=path.join(mini,'manifest.json'),original=fs.readFileSync(manifest,'utf8');
const json5=require(require.resolve('json5',{paths:[mini]})),parsed=json5.parse(original);parsed['mp-weixin'].appid=appid;parsed['mp-weixin'].setting.urlCheck=true;
const env={...process.env,VITE_API_BASE_URL:url.origin,BOOST_RELEASE:'1',UNI_INPUT_DIR:mini};
function run(cwd,args){const p=spawnSync(process.execPath,args,{cwd,env,stdio:'inherit'});if(p.status!==0)throw new Error('Release build failed')}
try{fs.writeFileSync(manifest,JSON.stringify(parsed,null,2)+'\n');run(mini,['scripts/uni.cjs','build','-p','h5']);run(mini,['scripts/uni.cjs','build','-p','mp-weixin']);run(root,['scripts/check-wxss.cjs']);run(path.join(root,'RuoYi-Vue3'),['node_modules/vite/bin/vite.js','build']);fs.writeFileSync(path.join(mini,'dist/build/mp-weixin/RELEASE.json'),JSON.stringify({appid,apiOrigin:url.origin,builtAt:new Date().toISOString()},null,2))}finally{fs.writeFileSync(manifest,original)}
