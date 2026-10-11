// Scope guard used only by the explicitly non-payment acceptance run.
const fs=require('node:fs'),path=require('node:path');
const out=path.join(require('./test-output.cjs').dir('nonpayment-20261002'),'requests');fs.mkdirSync(out,{recursive:true});
const report={suite:path.basename(process.argv[1]||'unknown'),nodeRequests:0,browserRequests:0,blocked:[]};
function excluded(url,method,body){
 const p=new URL(url).pathname.replace(/^\/(api|prod-api|dev-api)(?=\/)/,'');
 if(/api\.mch\.weixin\.qq\.com/.test(new URL(url).hostname))return true;
 if(/\/boost\/wechat\//.test(p)||/\/boost\/orders\/\d+\/(simulated-pay|wechat-pay|progress|aftersale|review)$/.test(p)||/\/boost-admin\/.*reconcile/.test(p))return true;
 if(method!=='GET'&&(/\/boost\/wallet\/withdraw/.test(p)||/\/boost-admin\/(aftersales|withdrawals)\//.test(p)))return true;
 if(/\/boost\/wallet\/withdrawals\/\d+\/confirmation$/.test(p))return true;
 if(method==='POST'&&/\/boost\/orders\/\d+\/action$/.test(p)){
  try{return JSON.parse(body).action!=='cancel'}catch{return true}
 }
 return false;
}
function record(url,method){const item={method,path:new URL(url).pathname};report.blocked.push(item);return new Error('Non-payment scope forbids '+method+' '+item.path)}
const fetch=globalThis.fetch;
globalThis.fetch=async(input,options={})=>{
 const url=typeof input==='string'||input instanceof URL?String(input):input.url,method=(options.method||input.method||'GET').toUpperCase();
 report.nodeRequests++;
 if(excluded(url,method,options.body))throw record(url,method);
 return fetch(input,options);
};
const {chromium}=require('node:module').createRequire(process.env.APPDATA+'/npm/node_modules/')('playwright');
const contexts=new WeakSet();
async function guard(context){if(contexts.has(context))return;contexts.add(context);await context.route('**/*',async route=>{
 const request=route.request();report.browserRequests++;
 if(excluded(request.url(),request.method(),request.postData())){record(request.url(),request.method());await route.abort('blockedbyclient');return}
 await route.fallback();
})}
const launch=chromium.launch.bind(chromium);
chromium.launch=async(...args)=>{
 const browser=await launch(...args),context=browser.newContext.bind(browser),page=browser.newPage.bind(browser);
 browser.newContext=async(...args)=>{const c=await context(...args);await guard(c);return c};
 browser.newPage=async(...args)=>{const p=await page(...args);await guard(p.context());return p};
 return browser;
};
process.on('exit',()=>{fs.writeFileSync(path.join(out,report.suite+'-'+process.pid+'.json'),JSON.stringify(report,null,2));if(report.blocked.length)process.exitCode=1});
