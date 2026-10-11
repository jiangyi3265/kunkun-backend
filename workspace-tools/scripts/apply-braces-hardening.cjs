// Deterministic vendor backport. Never alters package identity/version or audit output.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'),patch=path.join(root,'patches/braces-3.0.3-depth-limit');
const manifest=JSON.parse(fs.readFileSync(path.join(patch,'manifest.json'),'utf8'));
const check=process.argv.includes('--check'),args=process.argv.slice(2).filter(a=>a!=='--check');
const dirs=args.length?args.map(a=>path.resolve(root,a)):[path.join(root,'代练')];
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function dependencies(nm,found){
 if(!fs.existsSync(nm))throw Error('Dependency directory missing: '+nm);
 if(fs.lstatSync(nm).isSymbolicLink())throw Error('Linked dependency directories are not supported by this audited install: '+nm);
 for(const entry of fs.readdirSync(nm,{withFileTypes:true})){
  if(entry.name.startsWith('.'))continue;
  if(entry.isSymbolicLink())throw Error('Linked dependency directories are not supported by this audited install: '+path.join(nm,entry.name));
  if(!entry.isDirectory())continue;
  const p=path.join(nm,entry.name);
  if(entry.name.startsWith('@')){for(const sub of fs.readdirSync(p,{withFileTypes:true})){
   if(sub.isSymbolicLink())throw Error('Linked dependency directories are not supported by this audited install: '+path.join(p,sub.name));
   if(sub.isDirectory())visit(path.join(p,sub.name),found);
  }}
  else visit(p,found);
 }
}
function visit(p,found){
 if(path.basename(p)==='braces')found.push(p);
 if(fs.existsSync(path.join(p,'node_modules')))dependencies(path.join(p,'node_modules'),found);
}
for(const dir of dirs){
 const found=[];dependencies(path.join(dir,'node_modules'),found);
 if(!found.length)throw Error('Expected audited braces dependency missing: '+dir);
 for(const target of found){
  const pkg=JSON.parse(fs.readFileSync(path.join(target,'package.json'),'utf8'));
  if(pkg.name!==manifest.package||pkg.version!==manifest.version)throw Error('Unknown braces version requires a reviewed update: '+target);
  // Validate every file before any mutation so unexpected bytes cannot be overwritten.
  const changes=[];
  for(const [name,h] of Object.entries(manifest.files)){
   const bytes=fs.readFileSync(path.join(patch,name));if(sha(bytes)!==h.after)throw Error('Backport source fingerprint mismatch: '+name);
   const actual=sha(fs.readFileSync(path.join(target,name)));
   if(actual===h.after)continue;
   if(actual!==h.before)throw Error('Unknown dependency bytes: '+target+'/'+name);
   if(check)throw Error('Backport has not been applied: '+target+'/'+name);
   changes.push([name,bytes]);
  }
  for(const [name,bytes] of changes)fs.writeFileSync(path.join(target,name),bytes);
  console.log(JSON.stringify({directory:path.relative(root,target),version:pkg.version,mode:check?'verified':'applied',changed:changes.length,manifestSha256:sha(fs.readFileSync(path.join(patch,'manifest.json')))}));
 }
}
