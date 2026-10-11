// Run only against the local simulated-payment environment. Creates labelled QA records.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),base=require('./test-env.cjs').api,tokens=[];
const adminToken=process.env.ADMIN_TOKEN||JSON.parse(fs.readFileSync(path.join(root,'.runtime/admin-session.json'))).token;
async function raw(route,token,method='GET',body){ body=require('./test-contract.cjs').bodyFor(route,method,body);const r=await fetch(base+route,{method,headers:{'Content-Type':'application/json','X-Boost-Token':token||'',...(route.startsWith('/boost-admin/')?{Authorization:'Bearer '+adminToken}:{})},body:body?JSON.stringify(body):undefined});return {http:r.status,...await r.json()}}
async function api(...args){const r=await raw(...args);assert.equal(r.code,200,args[0]+': '+r.msg);return r.data}
async function session(username){const s=await api('/boost/auth/login',null,'POST',{username,password:'Demo2026!'});tokens.push(s.token);return s}
async function duplicate(fn){return Promise.all(Array.from({length:8},fn))}
(async()=>{
 assert.equal((await api('/boost/config')).simulatedPayment,true,'禁止对真实支付环境运行本测试');
 const buyer=await session('demo_buyer'),worker=await session('demo_worker'),stamp=Date.now();
 const stranger=await api('/boost/auth/register',null,'POST',{username:'qa_guard_'+stamp,password:'LocalQa2026!',nickname:'权限回归'+String(stamp).slice(-6)});tokens.push(stranger.token);
 const listing=await api('/boost/listings/1'),before=await api('/boost/wallet',worker.token);
 const body={listingId:listing.id,requestKey:'concurrency-'+stamp,note:'本地并发回归测试订单'};
 const created=await duplicate(()=>api('/boost/orders',buyer.token,'POST',body));assert.equal(new Set(created.map(o=>o.id)).size,1,'重复下单只能产生同一订单');const id=created[0].id;
 assert.equal((await raw('/boost/orders/'+id,stranger.token)).http,403,'第三方不能查看订单');
 assert.notEqual((await raw(`/boost/orders/${id}/simulated-pay`,worker.token,'POST')).code,200,'打手不能替买家付款');
 await duplicate(()=>api(`/boost/orders/${id}/simulated-pay`,buyer.token,'POST'));
 assert.equal((await api('/boost/notifications',worker.token)).items.filter(n=>n.orderId===id&&n.title==='订单已付款').length,1,'重复支付只能通知一次');
 assert.notEqual((await raw(`/boost/orders/${id}/action`,buyer.token,'POST',{action:'start'})).code,200,'买家不能冒用打手开始服务');
 await api(`/boost/orders/${id}/action`,worker.token,'POST',{action:'start'});
 const form=new FormData();form.append('purpose','DELIVERY');form.append('file',new Blob([fs.readFileSync(path.join(root,'代练/static/logo.png'))],{type:'image/png'}),'qa.png');const uploaded=await(await fetch(base+'/boost/upload',{method:'POST',headers:{'X-Boost-Token':worker.token},body:form})).json();assert.equal(uploaded.code,200);
 await api(`/boost/orders/${id}/progress`,worker.token,'POST',{percent:100,note:'本地回归成果，确认并发验收只结算一次',images:[uploaded.data.url]});
 await duplicate(()=>api(`/boost/orders/${id}/action`,buyer.token,'POST',{action:'confirm'}));
 const settled=await api('/boost/wallet',worker.token);assert.equal(settled.balanceCents,before.balanceCents+listing.priceCents,'并发验收只能结算一份收入');assert.equal(settled.ledger.filter(l=>l.orderId===id&&l.kind==='SETTLEMENT').length,1);
 const withdrawals=await duplicate(()=>api('/boost/wallet/withdraw',worker.token,'POST',{amountCents:100,accountName:'测试打手',accountNumber:'qa-concurrency',requestKey:'withdraw-'+stamp}));assert.equal(new Set(withdrawals.map(w=>w.id)).size,1);assert.equal((await api('/boost/wallet',worker.token)).balanceCents,settled.balanceCents-100,'并发提现只能冻结一次');
 await api('/boost-admin/withdrawals/'+withdrawals[0].id,null,'POST',{action:'reject',note:'并发回归完成，退回测试冻结余额'});assert.equal((await api('/boost/wallet',worker.token)).balanceCents,settled.balanceCents);
 const report={passed:true,orderId:id,parallelRequests:8,checks:['重复下单返回同一订单','重复支付只推进一次','第三方访问和越权操作被拒绝','重复验收只结算一次','重复提现只冻结一次','拒绝提现退回余额']};fs.mkdirSync(path.join(root,'test-results'),{recursive:true});fs.writeFileSync(path.join(root,'test-results/concurrency.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{await Promise.allSettled(tokens.map(t=>api('/boost/auth/logout',t,'POST')))});
