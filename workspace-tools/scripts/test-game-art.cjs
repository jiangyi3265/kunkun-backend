// Execute the real generated component. Native rendering is checked separately.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const root=path.resolve(__dirname,'..'),app=path.join(root,'代练');
const artifact=process.argv[2]?path.resolve(process.argv[2]):path.join(app,'dist/build/mp-weixin/components');
const vue=createRequire(path.join(app,'package.json'))('vue');
const js=fs.readFileSync(path.join(artifact,'GameArt.js'),'utf8'),wxml=fs.readFileSync(path.join(artifact,'GameArt.wxml'),'utf8');
// Read the binding from WXML rather than assuming the compiler's field name.
const field=wxml.match(/^<view[^>]*style="\{\{(\w+)\}\}"/)?.[1];
assert(field,'Visible native root must bind its own dimensions; parent scoped classes cannot supply them');
let component;
const vendor={computed:vue.computed,unref:vue.unref,e:(...parts)=>Object.assign({},...parts),n:v=>v,s:v=>JSON.parse(JSON.stringify(v)),_export_sfc:(c,extra)=>Object.assign(c,Object.fromEntries(extra))};
vm.runInNewContext(js,{require:n=>n.endsWith('vendor.js')?vendor:n.endsWith('assets.js')?{_imports_0:'/static/game-atlas.jpg'}:n.endsWith('api.js')?{imageUrl:x=>'resolved:'+x}:(()=>{throw Error('Unexpected dependency '+n)})(),wx:{createComponent:c=>{component=c}}},{timeout:2000});
const render=props=>component.setup(props)(props,{}),style=props=>render(props)[field];let count=0;
for(const [variant,width,height]of [['shortcut','56px','56px'],['cover','100%','168px'],['horizontal','106px','112px'],['mini','72px','72px'],['manage','92px','92px'],['chat','64px','64px'],['detail','100%','285px'],['hero','180px','180px'],['banner','120px','120px'],['auth','90px','90px']]){const s=style({variant});assert.equal(s.width,width);assert.equal(s.height,height);assert.equal(s.minHeight,height);count++}
for(const size of [undefined,20,26,28,38,48,52,54,58,75]){const s=style({avatar:true,size});assert.equal(s.width,(size??40)+'px');assert.equal(s.height,s.width);assert.equal(s.borderRadius,'50%');count++}
assert.equal(style({variant:'unknown'}).height,'72px');count++;
const props=vue.reactive({variant:'mini'}),r=component.setup(props);assert.equal(r(props,{})[field].height,'72px');props.variant='manage';assert.equal(r(props,{})[field].height,'92px');count++;
const uploaded=render({src:'/boost-media/fixture.png',variant:'mini'});assert(Object.values(uploaded).includes('resolved:/boost-media/fixture.png'));assert.equal(uploaded[field].height,'72px');count++;
console.log(JSON.stringify({passed:true,cases:count,scope:'Generated component logic and WXML binding / Node VM with renderer helpers; not native rendering'}));
