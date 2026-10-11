const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.join(root,'test-results/ui-fidelity-20261007');fs.mkdirSync(out,{recursive:true});
const runs=[],startedAt=new Date().toISOString();
const steps=[['lint-mini',['scripts/lint-mini.cjs'],root],['build-h5',['scripts/uni.cjs','build','-p','h5'],path.join(root,'代练')],['build-mp',['scripts/uni.cjs','build','-p','mp-weixin'],path.join(root,'代练')],['wxss',['scripts/check-wxss.cjs'],root],['startup-session',['--experimental-vm-modules','--test','scripts/test-mini-startup-storage.cjs','scripts/test-session-races.cjs'],root],['game-art',['scripts/test-game-art.cjs'],root],['browser',['scripts/ui-fidelity-acceptance.cjs'],root]];
if(process.argv.includes('--native'))steps.push(['native',['scripts/ui-mini-smoke.cjs'],root]);
for(const [name,args,cwd]of steps){
 const start=new Date().toISOString(),r=spawnSync(process.execPath,args,{cwd,encoding:'utf8',env:process.env,timeout:240000,maxBuffer:8*1024*1024});
 const log='accept-'+name+'.log';fs.writeFileSync(path.join(out,log),(r.stdout||'')+(r.stderr||''));runs.push({name,command:[process.execPath,...args],cwd,startedAt:start,finishedAt:new Date().toISOString(),exitCode:r.status,error:r.error?.message,evidence:log});
 console.log((r.status===0?'PASS ':'FAIL ')+name);if(r.status!==0){process.exitCode=1;break}
}
const result={startedAt,finishedAt:new Date().toISOString(),localStatus:process.exitCode?'失败':'已实测通过',scope:'H5 visual fixtures + generated MP + startup/session. Real API is used only by optional native read-only smoke.',nativeStatus:process.argv.includes('--native')?(runs.find(x=>x.name==='native')?.exitCode===0?'已实测通过':'失败'):'未测试',realDeviceStatus:'未测试',experienceSelectionStatus:'环境阻塞',releaseConclusion:'当前不满足所列范围的发布条件：完整真机 UI 与体验版指定仍需验证。',runs};
fs.writeFileSync(path.join(out,'accept-command.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({localStatus:result.localStatus,nativeStatus:result.nativeStatus,report:'test-results/ui-fidelity-20261007/accept-command.json'}));
