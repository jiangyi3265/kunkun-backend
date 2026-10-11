const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.resolve(process.env.PLAYER_ACCEPTANCE_OUT||path.join(root,'test-results/rewards-20261008/targeted-build'));fs.mkdirSync(out,{recursive:true});
const env={...process.env,...JSON.parse(fs.readFileSync(path.join(root,'.runtime/player-acceptance-20261007/test-db-environment.json'),'utf8'))},checks=[];let failed=false;
for(const [name,exe,args,cwd,shell] of [
 ['rewards-java',path.join(process.env.USERPROFILE,'tools/apache-maven-3.9.16/bin/mvn.cmd'),['-pl','ruoyi-admin','-am','package','-Dtest=BoostRewardsTest,BoostRewardsMySqlTest','-Dsurefire.failIfNoSpecifiedTests=false'],path.join(root,'RuoYi-Vue'),true],
 ['h5-build',process.execPath,['scripts/uni.cjs','build','-p','h5'],path.join(root,'代练'),false],
 ['admin-build',process.execPath,['node_modules/vite/bin/vite.js','build'],path.join(root,'RuoYi-Vue3'),false]
]){const time=new Date().toISOString(),r=spawnSync(exe,args,{cwd,env,shell,windowsHide:true,encoding:'utf8',maxBuffer:20*1024*1024}),file=path.join(out,name+'.log');fs.writeFileSync(file,(r.stdout||'')+(r.stderr||''));checks.push({name,command:[exe,...args],cwd,time,exitCode:r.status});console.log((r.status===0?'PASS ':'FAIL ')+name);if(r.status!==0){failed=true;break}}
fs.writeFileSync(path.join(out,'commands.json'),JSON.stringify(checks,null,2));process.exitCode=failed?1:0;
