// Independent, bounded review: no package, lockfile, module or business-code writes.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{createRequire}=require('node:module'),{spawnSync}=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'),base=process.env.QA_BRACES_OUTPUT_DIR?path.resolve(process.env.QA_BRACES_OUTPUT_DIR):process.env.QA_OUTPUT_DIR?path.join(path.resolve(process.env.QA_OUTPUT_DIR),'braces-regression'):path.join(root,'test-results','braces-review-'+Date.now());
const original=path.join(root,'patches/braces-3.0.3-depth-limit/original');
const phase=process.argv.includes('--baseline')?'baseline':'patched';
function sha(p){return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')}
function nested(n,kind='brace'){return kind==='paren'?'('.repeat(n)+'x'+')'.repeat(n):'{'.repeat(n)+'a,b'+'}'.repeat(n)}
function ast(n,asRoot=true){let a={type:'text',value:'x'};for(let i=0;i<n;i++){const p={type:'brace',open:true,close:true,commas:1,ranges:0,nodes:[a]};a.parent=p;a=p}if(asRoot){const r={type:'root',nodes:[a]};a.parent=r;return r}return a}
const securityGroups=['string-depth','ast-depth','ast-fallback','option-bypass','fractional','parent-cycle','child-cycle','escape-invalid','normal-consumers','consumer-depth','watch-consumer'];
async function child(){
 const project=process.argv[3],group=process.argv[4],requireFrom=createRequire(path.join(root,project,'package.json'));
 const entry=phase==='baseline'?path.join(original,'index.js'):requireFrom.resolve('braces');
 const braces=require(entry),count={assertions:0};let observations=[];
 const chokidarVersion=()=>JSON.parse(fs.readFileSync(path.join(path.dirname(requireFrom.resolve('chokidar')),'package.json'),'utf8')).version;
 function equal(a,b){assert.deepEqual(a,b);count.assertions++}
 function guarded(fn,type,message){assert.throws(fn,e=>e.name===type&&message.test(e.message)&&!/(call stack|stack size)/i.test(e.message));count.assertions++}
 if(group==='string-depth'){
  for(const n of [100,101,1000,4998])for(const kind of ['brace','paren'])for(const method of ['parse','compile','expand','stringify']){
   const input=nested(n,kind);if(n===100){assert.doesNotThrow(()=>braces[method](input));count.assertions++}else guarded(()=>braces[method](input),'SyntaxError',/depth/);
  }
  equal(braces.compile('\\{'.repeat(101)+'x'+'\\}'.repeat(101)),nested(101,'brace').replace('a,b','x'));
  assert.doesNotThrow(()=>braces.compile('"'+nested(101)+'"'));count.assertions++;
  assert.doesNotThrow(()=>braces.compile('['+nested(101)+']'));count.assertions++;
  guarded(()=>braces.parse('({'.repeat(51)+'x'+'})'.repeat(51)),'SyntaxError',/depth/);
 }else if(group==='ast-depth'){
  for(const n of [100,101,12000])for(const rooted of [true,false])for(const method of ['compile','expand','stringify']){
   if(n===100){assert.doesNotThrow(()=>braces[method](ast(n,rooted)));count.assertions++}else guarded(()=>braces[method](ast(n,rooted)),'RangeError',/AST depth/);
  }
 }else if(group==='option-bypass'){
  for(const maxDepth of [10000,Infinity,NaN,false,null,'10000',{},undefined])for(const method of ['parse','compile','expand','stringify'])guarded(()=>braces[method](nested(101),{maxDepth}),'SyntaxError',/depth/);
  for(const maxDepth of [10000,Infinity,NaN,false,null,'10000',{},undefined])for(const method of ['compile','expand','stringify'])guarded(()=>braces[method](ast(101),{maxDepth}),'RangeError',/AST depth/);
  equal(braces.compile('plain',{maxDepth:0}),'plain');guarded(()=>braces.parse('{x}',{maxDepth:0}),'SyntaxError',/depth/);
  guarded(()=>braces.compile(ast(0),{maxDepth:-1}),'RangeError',/AST depth/);
 }else if(group==='ast-fallback'){
  for(const flag of ['dollar','invalid','ranges'])for(const [limit,depthAtFallback] of [[100,2],[100,75],[2,2]]){
   for(const totalDepth of [limit,limit+1]){
    const a=ast(totalDepth);let node=a.nodes[0];for(let i=1;i<depthAtFallback;i++)node=node.nodes[0];
    node[flag]=flag==='ranges'?1:true;
    if(totalDepth===limit){assert.doesNotThrow(()=>braces.expand(a,{maxDepth:limit}));count.assertions++}
    else guarded(()=>braces.expand(a,{maxDepth:limit}),'RangeError',/AST depth/);
   }
  }
 }else if(group==='fractional'){
  for(const method of ['parse','compile','expand','stringify'])for(const kind of ['brace','paren']){
   assert.doesNotThrow(()=>braces[method](nested(1,kind),{maxDepth:1.5}));count.assertions++;
   guarded(()=>braces[method](nested(2,kind),{maxDepth:1.5}),'SyntaxError',/depth/);
   guarded(()=>braces[method](nested(1,kind),{maxDepth:0.5}),'SyntaxError',/depth/);
  }
  for(const method of ['compile','expand','stringify'])guarded(()=>braces[method](ast(2),{maxDepth:1.5}),'RangeError',/AST depth/);
 }else if(group==='parent-cycle'){
  const r={type:'root',nodes:[]},p={type:'paren',nodes:[{type:'text',value:'x'}]};p.parent=p;r.nodes=[p];
  guarded(()=>braces.expand(r),'RangeError',/parent chain contains a cycle/);
 }else if(group==='child-cycle'){
  for(const method of ['compile','expand','stringify']){const r={type:'root',nodes:[]};r.nodes.push(r);guarded(()=>braces[method](r),'RangeError',/AST depth/)}
 }else if(group==='escape-invalid'){
  const released=require(path.join(original,'index.js'));
  let matchingPublishedErrors=0;
  const outcome=fn=>{try{return {returned:true,result:fn()}}catch(e){return {returned:false,name:e.name,message:e.message}}};
  function compare(input,method,escapeInvalid){
   const original=outcome(()=>released[method](input,{escapeInvalid})),current=outcome(()=>braces[method](input,{escapeInvalid}));
   assert.deepEqual(current,original,JSON.stringify({input,method,escapeInvalid}));count.assertions++;
   if(!original.returned)matchingPublishedErrors++;
  }
  for(const input of ['{{a}}','{a,{b}}','{{x}y}','{a,{b,{c}}}','{}{a}','{1..8}','a/{b,c}/d','{a,b','a,b}','{x..y}','{1..3..2}','\\{a,b\\}'])for(const escapeInvalid of [true,false])for(const method of ['compile','expand','stringify'])compare(input,method,escapeInvalid);
  // Bounded shallow generated cases compare the actual published install, including malformed patterns.
  const pieces=['a','b','{a,b}','{1..3}','{x..z}','{a}','\\{x\\}','[]','(x)','{a,b','a,b}'];
  for(let i=0;i<pieces.length;i++)for(let j=0;j<pieces.length;j++){
   const input=pieces[i]+'/'+pieces[j]+'/'+pieces[(i*3+j*7+1)%pieces.length];
   for(const method of ['compile','expand','stringify'])for(const escapeInvalid of [false,true])compare(input,method,escapeInvalid);
  }
  observations.push({matchingPublishedErrors,boundary:'Malformed-pattern exceptions are compared by name/message, not treated as successful returned expansions'});
  for(const input of ["'abc","a'bc","a'{b,c}","a\"{b,c}"]){
   const old=released.compile(input),current=braces.compile(input);if(old!==current)observations.push({input,published:old,current,classification:'Unreleased upstream parser quote behavior; not a depth-only change'});
  }
 }else if(group==='normal-consumers'){
  const mm=requireFrom('micromatch'),fg=requireFrom('fast-glob');
  equal(mm.braceExpand('src/{pages,components}/*.{vue,js}').sort(),['src/components/*.js','src/components/*.vue','src/pages/*.js','src/pages/*.vue']);
  equal(mm(['a.vue','b.ts','c.txt'],'*.{vue,ts}'),['a.vue','b.ts']);
  const fixture=path.join(base,'fixtures',project+'-'+phase+'-'+process.pid);fs.mkdirSync(fixture,{recursive:true});
  for(const name of ['alpha.vue','bravo.ts','ignored.txt'])fs.writeFileSync(path.join(fixture,name),'fixture');
  equal(fg.sync('*.{vue,ts}',{cwd:fixture}).sort(),['alpha.vue','bravo.ts']);
  const expansion=braces.expand('{a,b}'.repeat(12));equal(expansion.length,4096);observations.push({boundedCartesianExpansion:4096,defaultRangeLimit:1000,boundary:'Depth guard does not impose a global comma expansion cardinality cap'});
 }else if(group==='consumer-depth'){
  const mm=requireFrom('micromatch'),fg=requireFrom('fast-glob');
  guarded(()=>mm.braces(nested(101)),'SyntaxError',/depth/);
  guarded(()=>mm.braceExpand(nested(101)),'SyntaxError',/depth/);
  guarded(()=>fg.generateTasks(nested(101)+'/*.js'),'SyntaxError',/depth/);
  const c=requireFrom('chokidar'),watcher=new c.FSWatcher({persistent:false}),major=Number(chokidarVersion().split('.')[0]);
  try{
   if(major===3){guarded(()=>watcher._getWatchHelpers(nested(101)+'/*.js',0),'SyntaxError',/depth/);observations.push({chokidarHostileValidation:'Direct call to actual _getWatchHelpers path before filesystem IO; public normal watch tested separately'})}
   else observations.push({chokidarBracesDepth:'不适用',reason:'Installed chokidar '+major+'.x does not support glob expansion and has no braces dependency; actual micromatch and fast-glob guards are required above'});
  }finally{await watcher.close()}
 }else if(group==='watch-consumer'){
  const chokidar=requireFrom('chokidar'),fixture=path.join(base,'watch-fixtures',project+'-'+phase+'-'+process.pid);
  fs.mkdirSync(fixture,{recursive:true});for(const name of ['alpha.vue','bravo.ts','ignored.txt'])fs.writeFileSync(path.join(fixture,name),'fixture');
  const major=Number(chokidarVersion().split('.')[0]);
  const adds=[],watcher=chokidar.watch(major===3?fixture.replaceAll('\\','/')+'/*.{vue,ts}':fixture,{persistent:true,ignoreInitial:false});
  watcher.on('add',p=>adds.push(path.basename(p)));
  try{
   await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('chokidar ready timed out')),3000);watcher.once('ready',()=>{clearTimeout(timer);resolve()});watcher.once('error',reject)});
   equal(adds.sort(),major===3?['alpha.vue','bravo.ts']:['alpha.vue','bravo.ts','ignored.txt']);
   await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('chokidar matching addition timed out')),3000);watcher.on('add',p=>{if(path.basename(p)==='delta.ts'){clearTimeout(timer);resolve()}});fs.writeFileSync(path.join(fixture,'delta.ts'),'new fixture')});
   equal(adds.includes('delta.ts'),true);equal(adds.includes('ignored.txt'),major!==3);
   observations.push({chokidarVersion:chokidarVersion(),realWatchMode:major===3?'brace glob':'directory watch; glob expansion unsupported'});
  }finally{await watcher.close()}
 }
 console.log(JSON.stringify({passed:true,project,group,assertions:count.assertions,observations}));
}
if(process.argv[2]==='--child')child().catch(e=>{console.error(JSON.stringify({passed:false,name:e.name,message:e.message,stack:e.stack}));process.exitCode=1});
else{
 fs.mkdirSync(base,{recursive:true});if(fs.existsSync(path.join(base,'review-'+phase+'.json')))throw Error('Use a new evidence directory; previous results are immutable');const results=[];
 for(const project of ['代练'])for(const group of securityGroups){
  const args=[__filename,'--child',project,group,...(phase==='baseline'?['--baseline']:[])];
  const r=spawnSync(process.execPath,args,{cwd:root,env:{...process.env,QA_BRACES_OUTPUT_DIR:base,NODE_PATH:path.join(root,project,'node_modules')},encoding:'utf8',timeout:10000,maxBuffer:1024*1024});
  let detail;try{detail=JSON.parse(r.stdout.trim())}catch{detail={stderr:r.stderr.trim(),stdout:r.stdout.trim()}}
  const name=phase+'-'+(project==='代练'?'mini':'admin')+'-'+group+'.log';fs.writeFileSync(path.join(base,name),(r.stdout||'')+(r.stderr||''));
  results.push({project,group,passed:r.status===0,exitCode:r.status,signal:r.signal,runnerError:r.error?.message,log:name,...detail});
  console.log((r.status===0?'PASS':'FAIL')+' '+project+' '+group);
 }
 const installed=[];for(const project of ['代练']){const req=createRequire(path.join(root,project,'package.json'));installed.push({project,package:req('braces/package.json'),files:['lib/parse.js','lib/compile.js','lib/expand.js','lib/stringify.js','lib/constants.js'].map(f=>({file:f,sha256:sha(path.join(root,project,'node_modules/braces',f))}))})}
 const report={phase,checkedAt:new Date().toISOString(),node:process.version,upstreamCommit:'28d440b5dd449dbf1fe6f3506cf94ecca4d02660',officialAdvisoryCleared:false,readOnlyTargets:true,isolatedChildProcesses:true,passed:results.every(x=>x.passed),assertions:results.reduce((n,x)=>n+(x.assertions||0),0),results,installed};
 fs.writeFileSync(path.join(base,'review-'+phase+'.json'),JSON.stringify(report,null,2));process.exitCode=report.passed?0:1;
}
