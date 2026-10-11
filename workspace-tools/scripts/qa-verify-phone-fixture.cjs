// A synthetic receipt in a marked localhost QA database, followed by the real
// verification endpoint. This does not contact an SMS provider or prove KYC.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
module.exports=async function({root,fixtureFile,base,memberId,token}){
 assert(/^http:\/\/127\.0\.0\.1:\d+$/.test(base));
 const result=spawnSync('C:/Python314/python.exe',['-X','utf8','scripts/prepare-identity-phone-fixture.py','--fixture',fixtureFile,'--member',String(memberId),'--challenge'],{cwd:root,encoding:'utf8',windowsHide:true});
 assert.equal(result.status,0,'Owned synthetic phone receipt preparation');
 const proof=JSON.parse(fs.readFileSync(path.join(path.dirname(fixtureFile),'phone-code-'+memberId+'.private.json'),'utf8'));
 const response=await fetch(base+'/boost/me/phone/verify',{method:'POST',headers:{'Content-Type':'application/json','X-Boost-Token':token},body:JSON.stringify(proof),signal:AbortSignal.timeout(15000)});
 const verified=await response.json();assert.equal(verified.code,200);assert.equal(verified.data.phoneVerified,true);
 console.log('Synthetic isolated SMS receipt verified through real HTTP; no actual SMS sent.');
};
