// Native storage and Vue are substitutes; this tests the actual session module.
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
async function session(getStorageSync){
 const source=fs.readFileSync(path.join(__dirname,'../代练/lib/session.js'),'utf8')
  .replaceAll('import.meta.env.VITE_API_BASE_URL',JSON.stringify('https://example.invalid'))
  .replaceAll('import.meta.env.DEV','false');
 const context=vm.createContext({uni:{getStorageSync}});
 const vue=new vm.SyntheticModule(['reactive'],function(){this.setExport('reactive',v=>v)},{context});
 const module=new vm.SourceTextModule(source,{context});
 await module.link(()=>vue);await module.evaluate();return module.namespace;
}
test('storage read error does not abort app imports and reports recovery once',async()=>{
 const state=await session(()=>{throw new Error('getStorageSync:fail injected storage failure')});
 assert.equal(state.session.token,'');assert.equal(state.session.user,null);
 assert.equal(state.consumeStartupStorageIssue(),'SESSION_STORAGE_UNAVAILABLE');
 assert.equal(state.consumeStartupStorageIssue(),'');
});
test('corrupted stored login cannot become an authentication header',async()=>{
 for(const token of [{token:'secret'},['secret'],123,true]){
  const state=await session(()=>token);assert.equal(state.session.token,'');
  assert.equal(state.consumeStartupStorageIssue(),'INVALID_SESSION_STORAGE');
 }
});
test('valid stored login and empty anonymous storage retain their behavior',async()=>{
 for(const token of ['saved-token','',undefined,null]){
  const state=await session(()=>token);assert.equal(state.session.token,token||'');
  assert.equal(state.consumeStartupStorageIssue(),'');
 }
});
