const fs=require('node:fs'),path=require('node:path'),{randomUUID}=require('node:crypto');
const env=require('./test-env.cjs');
function bodyFor(route,method,body){
 if(method==='POST'&&(route==='/boost/listings'||/^\/boost\/conversations\/\d+\/messages$/.test(route))&&body&&!body.requestKey)return {...body,requestKey:randomUUID()};
 if(method==='POST'&&/\/(favorite|follow)$/.test(route)&&!body)return {desired:true};
 return body;
}
async function upload(token,purpose){const data=new FormData();data.append('purpose',purpose);data.append('file',new Blob([fs.readFileSync(path.join(__dirname,'../代练/static/logo.png'))],{type:'image/png'}),'qa.png');const r=await(await fetch(env.api+'/boost/upload',{method:'POST',headers:{'X-Boost-Token':token},body:data})).json();if(r.code!==200)throw new Error(r.msg);return r.data.url}
module.exports={bodyFor,upload};
