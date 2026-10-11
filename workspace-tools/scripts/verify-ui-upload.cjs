const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'test-results/ui-fidelity-20261007'),startedAt=new Date().toISOString();
const read=name=>JSON.parse(fs.readFileSync(path.join(out,name),'utf8'));
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function inventory(dir,prefix=''){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?inventory(path.join(dir,e.name),prefix+e.name+'/'):[prefix+e.name]).sort()}
const evidence={startedAt,version:process.argv[2]||'0.1.20261007.3',scope:'Official developer upload and unchanged local artifact; does not select an experience version or prove native/device rendering'};
try{
 const command=read('upload-command.json'),manifest=read('upload-artifact-manifest.json'),sources=read('final-source-manifest.json');
 const project=path.join(root,'代练/dist/build/mp-weixin'),config=JSON.parse(fs.readFileSync(path.join(project,'project.config.json'),'utf8'));
 assert.equal(command.exitCode,0);assert.equal(command.version,evidence.version);assert.equal(config.appid,'wxd31a958f0fc66a19');
 const log=fs.readFileSync(path.join(root,'.runtime/ui-fidelity-tools/upload.private.log'),'utf8');
 assert(/√\s*upload/.test(log),'no official positive upload marker');assert(log.includes('√ 使用 AppID: '+config.appid),'wrong upload AppID');
 assert.deepEqual(inventory(project),manifest.map(x=>x.file).sort(),'artifact file list changed');
 for(const x of manifest)assert.equal(hash(path.join(project,x.file)),x.sha256,'artifact changed: '+x.file);
 for(const x of sources)assert.equal(hash(path.join(root,x.file)),x.sha256,'source changed: '+x.file);
 const review=read('independent-review/final-build-manifest.json');
 for(const x of [...review.mp.items,...review.h5.items])assert.equal(hash(path.join(root,x.path)),x.sha256,'reviewed build changed: '+x.path);
 const info=JSON.parse(fs.readFileSync(path.join(root,'.runtime/ui-fidelity-tools/upload-info.private.json'),'utf8'));
 assert(Number(info.size?.total)>0);assert(log.includes(String(info.size.total)),'official packed size disagrees');
 const archive=read('archive-structure.json');assert.equal(archive.status,'已实测通过');assert.equal(archive.entries,manifest.length);
 evidence.status='已实测通过';evidence.accepted=true;evidence.appId=config.appid;evidence.officialPackedBytes=Number(info.size.total);evidence.files=manifest.length;evidence.sourceFiles=sources.length;
 evidence.reviewedMpTreeSha256=review.mp.treeSha256;evidence.reviewedH5TreeSha256=review.h5.treeSha256;evidence.archiveSha256=hash(path.join(out,'mini-ui-'+evidence.version+'.zip'));
 evidence.uploadStartedAt=command.startedAtUtc;evidence.uploadFinishedAt=command.finishedAtUtc;evidence.exitCode=command.exitCode;
 evidence.experienceSelectionStatus='环境阻塞';evidence.finalNativeStatus='环境阻塞';evidence.realDeviceStatus='未测试';
}catch(e){evidence.status='失败';evidence.error=e.message;process.exitCode=1}
evidence.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'upload-verified.json'),JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence,null,2));
