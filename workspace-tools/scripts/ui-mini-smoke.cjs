const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const automator=require('../.runtime/mini-blank-tools/node_modules/miniprogram-automator');
const out=path.resolve(__dirname,'../test-results/ui-fidelity-20261007');
(async()=>{let mini;const evidence={startedAt:new Date().toISOString(),scope:'Official WeChat Devtools simulator, real configured API, read-only anonymous navigation',checks:[]};
 try{
  mini=await automator.connect({wsEndpoint:'ws://127.0.0.1:'+(process.env.UI_AUTOMATOR_PORT||19875)});
  const account=await mini.callWxMethod('getAccountInfoSync');assert.equal(account?.miniProgram?.appId,'wxd31a958f0fc66a19','wrong project; no further actions');evidence.appId=account.miniProgram.appId;
  mini.on('exception',e=>{evidence.exceptions??=[];evidence.exceptions.push(String(e.message||e))});
  for(const [name,route] of [['home','/pages/index/index'],['market','/pages/market/index'],['mine','/pages/mine/index']]){
   const page=await mini.reLaunch(route);assert(page);await page.waitFor(1500);assert.equal(page.path,route.slice(1));
   await mini.screenshot({path:path.join(out,'screenshots/native-'+name+'.png')});
   const arts=await page.$$('game-art'),imageBoxes=[];
   for(const art of arts){const inside=await art.$('.game-art');assert(inside,'missing native inner view');const size=await inside.size();assert(Number(size.width)>=19&&Number(size.height)>=19,'native image collapsed');imageBoxes.push(size)}
   assert(arts.length>0,'no native image components');
   evidence.checks.push({name,status:'已实测通过',route:page.path,pageSize:await page.size(),imageBoxes,screenshot:'screenshots/native-'+name+'.png'});
  }
  const home=await mini.reLaunch('/pages/index/index');await home.waitFor(800);evidence.homeData=await home.data();
  evidence.runtimeScope=await mini.evaluate(()=>getCurrentPages()[0].$vm.$options.__scopeId);
  const source=fs.readFileSync(path.join(out,'native-project/pages/index/index.js'),'utf8');assert(source.includes(evidence.runtimeScope),'runtime is not the final compiled homepage');
  evidence.status='已实测通过';console.log(JSON.stringify({appId:evidence.appId,checks:evidence.checks},null,2));
 }catch(e){evidence.status='失败';evidence.error=e.message;process.exitCode=1;console.error(e.stack)}finally{
  if(mini)mini.disconnect();evidence.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'native-smoke.json'),JSON.stringify(evidence,null,2));
 }
})();
