// Run with: node --experimental-vm-modules --test scripts/test-session-races.cjs
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
async function harness(){
 const session={token:'account-a',user:null,config:null},requests=[],uploads=[],logins=[],modals=[];
 let prepare=async()=>{};
 const context=vm.createContext({uni:{request:o=>requests.push(o),uploadFile:o=>uploads.push(o),login:o=>logins.push(o),showModal:o=>modals.push(o),showToast(){},setStorageSync(){},removeStorageSync(){},navigateTo(){}},getCurrentPages:()=>[]});
 const modules={
  './session':new vm.SyntheticModule(['session','API_BASE'],function(){this.setExport('session',session);this.setExport('API_BASE','http://test.invalid')},{context}),
  './media':new vm.SyntheticModule(['prepareMedia','mediaUrl','clearMediaCache','setMediaRequest'],function(){this.setExport('prepareMedia',v=>prepare(v));this.setExport('mediaUrl',v=>v);this.setExport('clearMediaCache',()=>{});this.setExport('setMediaRequest',()=>{})},{context})
 };
 const source=fs.readFileSync(path.resolve(__dirname,'../代练/lib/api.js'),'utf8'),module=new vm.SourceTextModule(source,{context});
 await module.link(spec=>modules[spec]);await module.evaluate();
 return {api:module.namespace,session,requests,uploads,logins,modals,setPrepare:fn=>{prepare=fn}};
}
function success(data){return {statusCode:200,data:{code:200,data}}}
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
test('late wallet response cannot cross accounts',async()=>{
 const h=await harness(),pending=h.api.request('/boost/wallet');h.session.token='account-b';await h.requests[0].success(success({balanceCents:11111}));await assert.rejects(pending,/登录状态已变化/);
});
test('account change while media resolves discards the whole response',async()=>{
 const h=await harness(),gate=deferred();h.setPrepare(()=>gate.promise);const pending=h.api.request('/boost/orders');const finishing=h.requests[0].success(success({id:11}));h.session.token='account-b';gate.resolve();await finishing;await assert.rejects(pending,/登录状态已变化/);
});
test('old upload cannot populate the new account form',async()=>{
 const h=await harness(),pending=h.api.upload('/tmp/image.png');h.session.token='account-b';h.uploads[0].success({statusCode:200,data:JSON.stringify({code:200,data:{url:'/boost-media/old.png'}})});await assert.rejects(pending,/登录状态已变化/);
});
test('old unauthorized response does not revoke the new account',async()=>{
 const h=await harness(),pending=h.api.request('/boost/me');h.session.token='account-b';await h.requests[0].success({statusCode:401,data:{code:401}});await assert.rejects(pending);assert.equal(h.session.token,'account-b');
});
test('media processing errors settle the request instead of leaving it pending',async()=>{
 const h=await harness();h.setPrepare(async()=>{throw new Error('media decode failed')});const pending=h.api.request('/boost/orders');
 await h.requests[0].success(success({id:11})).catch(()=>{});
 const timeout=new Promise((_,reject)=>setTimeout(()=>reject(new Error('request remained pending')),100));await assert.rejects(Promise.race([pending,timeout]),/media decode failed/);
});
test('WeChat login result cannot link a different account',async()=>{
 const h=await harness(),pending=h.api.linkWechat();pending.catch(()=>{});h.session.token='account-b';h.logins[0].success({code:'wechat-one-time-code'});await new Promise(setImmediate);
 // Complete an incorrect follow-up too, so a regression fails without hanging.
 for(const r of h.requests)await r.success(success({}));await assert.rejects(pending,/登录状态已变化/);assert.equal(h.requests.length,0);
});
test('payment confirmation cannot submit under a changed account',async()=>{
 const h=await harness();h.session.config={simulatedPayment:true};const pending=h.api.payOrder(11);pending.catch(()=>{});await new Promise(setImmediate);h.session.token='account-b';h.modals[0].success({confirm:true});await new Promise(setImmediate);
 for(const r of h.requests)await r.success(success({}));await assert.rejects(pending,/登录状态已变化/);assert.equal(h.requests.length,0);
});
test('unchanged account still receives data and upload result',async()=>{
 const h=await harness(),pending=h.api.request('/boost/wallet');await h.requests[0].success(success({balanceCents:11111}));assert.equal((await pending).balanceCents,11111);
 const uploading=h.api.upload('/tmp/image.png');h.uploads[0].success({statusCode:200,data:JSON.stringify({code:200,data:{url:'/boost-media/current.png'}})});assert.equal(await uploading,'/boost-media/current.png');
});
test('disabled mode blocks simulation before dialog or money request',async()=>{
 const h=await harness();h.session.config={paymentsEnabled:false,simulatedPayment:true};
 const pending=h.api.payOrder(11);pending.catch(()=>{});await new Promise(setImmediate);
 // If the old code asks for confirmation, answer it and settle its request, so it fails without hanging.
 for(const modal of h.modals)modal.success({confirm:true});await new Promise(setImmediate);
 for(const request of h.requests)await request.success(success({}));
 await assert.rejects(pending,/暂未开放/);
 assert.equal(h.modals.length,0);assert.equal(h.requests.length,0);assert.equal(h.logins.length,0);
});
test('disabled mode blocks transfer before native or confirmation request',async()=>{
 const h=await harness();h.session.config={paymentsEnabled:false};
 const pending=h.api.confirmTransfer(11);pending.catch(()=>{});await new Promise(setImmediate);
 for(const request of h.requests)await request.success(success({}));
 await assert.rejects(pending,/暂未开放/);assert.equal(h.requests.length,0);
});
