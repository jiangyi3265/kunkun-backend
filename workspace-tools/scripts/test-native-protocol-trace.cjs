const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const trace=require('./native-protocol-trace.cjs');
const out=path.resolve(process.argv[2]||'');
assert(out.startsWith(path.join(__dirname,'../test-results')+path.sep)&&!fs.existsSync(out),'Use a fresh evidence directory');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const file=path.join(out,'protocol.json'),secret='fixture-private-value-do-not-log';let stage='first',release;
 const response={token:secret};const originalError=new Error('private transport detail '+secret);
 const connection={send(method,params){assert.equal(params.password,secret);if(method==='Delayed')return new Promise(resolve=>release=()=>resolve(response));if(method==='Reject')return Promise.reject(originalError);throw originalError}};
 const records=trace(connection,file,()=>stage);
 const pending=connection.send('Delayed',{password:secret});
 let saved=JSON.parse(fs.readFileSync(file));assert.equal(saved[0].status,'pending');assert(!saved[0].finishedAt);
 stage='second';release();assert.equal(await pending,response);
 await assert.rejects(connection.send('Reject',{password:secret}),e=>e===originalError);
 await assert.rejects(connection.send('Throw',{password:secret}),e=>e===originalError);
 saved=JSON.parse(fs.readFileSync(file));assert.deepEqual(saved.map(r=>[r.sequence,r.status,r.stage]),[[1,'completed','first'],[2,'failed','second'],[3,'failed','second']]);
 assert(saved.every(r=>r.finishedAt));assert.deepEqual(saved,records);assert(!fs.readFileSync(file,'utf8').includes(secret));
 fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({checkedAt:new Date().toISOString(),exitCode:0,scope:'Synthetic transport outcomes only; not native business acceptance',checks:['pending call durably recorded before response','original response identity preserved','async/sync errors propagated unchanged','parameters/results/error contents omitted from trace']},null,2));
 console.log('PASS native trace durability, propagation and redaction');
})().catch(e=>{fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({exitCode:1,error:e.message},null,2));console.error(e);process.exitCode=1});
