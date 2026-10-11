// Re-encode the unchanged generated artwork for the WeChat package size limit.
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const root=path.resolve(__dirname,'..'),Jimp=createRequire(path.join(root,'代练/package.json'))('jimp');
(async()=>{const source=path.join(root,'design-assets/game-atlas-source.png'),output=path.join(root,'代练/static/game-atlas.jpg');await(await Jimp.read(source)).quality(85).writeAsync(output);console.log('Optimized artwork: '+Math.round(fs.statSync(output).size/1024)+' KB')})().catch(e=>{console.error(e);process.exit(1)});
