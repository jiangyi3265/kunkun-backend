const fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');process.env.QA_OUTPUT_DIR ||= path.join(root,'test-results','nonpayment-'+new Date().toISOString().replace(/[-:.]/g,''));const out=require('./test-output.cjs').dir();fs.mkdirSync(out,{recursive:true});
const suites=[];
const crypto=require('node:crypto');
let previous,previousSources,previousDirectory;
if(process.env.QA_REUSE_E2E_FROM){
 previousDirectory=path.resolve(process.env.QA_REUSE_E2E_FROM);
 const validation=JSON.parse(fs.readFileSync(path.join(out,'resume-validation.json')));
 if(!validation.passed||path.resolve(validation.previous)!==previousDirectory)throw Error('Suite reuse requires successful source and build validation');
 previous=JSON.parse(fs.readFileSync(path.join(previousDirectory,'e2e-suites.json'))).suites;
 previousSources=new Map(JSON.parse(fs.readFileSync(path.join(previousDirectory,'source-final.json'))).files.map(f=>[f.path,f.sha256]));
}
for(const name of ['startup','features','auth-concurrency','auth-boundaries','visibility','pagination','ui-recovery','hardening','admin-state','admin-media-preview','admin-permissions','nonpayment-admin']){
 const script='scripts/e2e-'+name+'.cjs';
 const scriptSha256=crypto.createHash('sha256').update(fs.readFileSync(path.join(root,script))).digest('hex');
 const old=previous?.find(s=>s.name===name&&s.passed&&s.exitCode===0);
 if(old&&previousSources.get(script)===scriptSha256){
  suites.push({...old,scriptSha256,reusedEvidence:true,evidenceRun:previousDirectory});console.log('REUSED verified suite '+name);
  fs.writeFileSync(path.join(out,'e2e-suites.json'),JSON.stringify({passed:suites.length===12&&suites.every(s=>s.passed),excludePayments:true,complete:suites.length===12,suites},null,2));continue;
 }
 const start=Date.now();const r=spawnSync(process.execPath,['--require',path.join(__dirname,'nonpayment-request-guard.cjs'),path.join(__dirname,'e2e-'+name+'.cjs')],{cwd:root,encoding:'utf8',timeout:180000,env:{...process.env,QA_EXCLUDE_PAYMENTS:'1',QA_PHASE:'after'}});
 fs.writeFileSync(path.join(out,'e2e-'+name+'.log'),(r.stdout||'')+(r.stderr||'')+(r.error?String(r.error):''));
 suites.push({name,passed:r.status===0,exitCode:r.status,durationMs:Date.now()-start,scriptSha256,command:process.execPath+' --require scripts/nonpayment-request-guard.cjs '+script,finishedAt:new Date().toISOString()});console.log((r.status===0?'PASS ':'FAIL ')+name);
 fs.writeFileSync(path.join(out,'e2e-suites.json'),JSON.stringify({passed:suites.length===12&&suites.every(s=>s.passed),excludePayments:true,complete:suites.length===12,suites},null,2));
}
process.exitCode=suites.every(s=>s.passed)?0:1;
