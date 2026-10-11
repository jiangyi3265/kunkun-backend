// Catch the unsupported universal selectors rejected by the actual WeChat uploader.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),req=require('node:module').createRequire(path.join(root,'代练/package.json'));
const postcss=req('postcss'),selectors=req('postcss-selector-parser');
const directory=path.join(root,'代练/dist/build/mp-weixin'),findings=[];let files=0;
function walk(folder){for(const entry of fs.readdirSync(folder,{withFileTypes:true})){const file=path.join(folder,entry.name);if(entry.isDirectory())walk(file);else if(entry.isFile()&&entry.name.endsWith('.wxss')){files++;try{postcss.parse(fs.readFileSync(file,'utf8'),{from:file}).walkRules(rule=>{let parent=rule.parent;while(parent){if(parent.type==='atrule'&&/keyframes$/i.test(parent.name))return;parent=parent.parent}selectors(ast=>ast.walkUniversals(()=>findings.push({file:path.relative(directory,file),selector:rule.selector,reason:'Universal selector rejected by WeChat WXSS compiler'}))).processSync(rule.selector)})}catch(error){findings.push({file:path.relative(directory,file),reason:error.message})}}}}
try{walk(directory)}catch(error){findings.push({reason:error.message})}
if(!files)findings.push({reason:'No compiled WXSS files found'});
const report={startedAtUtc:new Date().toISOString(),scope:'Compiled WXSS syntax and unsupported universal selectors; does not replace official compilation or device tests',files,findings,passed:files>0&&findings.length===0};
const index=process.argv.indexOf('--output');if(index>=0)fs.writeFileSync(process.argv[index+1],JSON.stringify(report,null,2));
console.log(JSON.stringify(report));process.exitCode=report.passed?0:1;
