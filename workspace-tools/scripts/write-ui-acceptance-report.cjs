const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'test-results/ui-fidelity-20261007');
const read=p=>JSON.parse(fs.readFileSync(path.join(out,p),'utf8').replace(/^\uFEFF/,''));
const acceptance=read('accept-command.json'),browser=read('browser-acceptance.json'),upload=read('upload-verified.json'),native=read('native-smoke.json');
assert.equal(acceptance.localStatus,'已实测通过');assert(acceptance.runs.every(x=>x.exitCode===0));assert.equal(browser.status,'已实测通过');assert.equal(upload.accepted,true);
const entries=[
 ['01-home','首页','index/index','访客/用户','黄黑主题、可见横幅和游戏入口、真实字段的推荐列表','GET /boost/listings?kind=SERVICE&sort=sales；Listing'],
 ['02-market','代练市场','market/index','访客/用户','两列卡片、分类与价格排序请求及列表结果一致','GET /boost/listings；game/sort/filter'],
 ['03-request','发布需求','publish/index','登录用户','标题→截图→规格→说明→预算；未同意/无效输入不发送发布请求','POST /boost/listings；kind=REQUEST；POST /boost/upload'],
 ['04-messages','消息','messages/index','登录用户','真实通知/会话字段、头像尺寸、未读标记和跳转入口','GET /boost/notifications、/boost/conversations、/boost/messages/summary'],
 ['05-mine','我的','mine/index','访客/用户/认证打手','个人资料、交易、钱包及服务入口；横幅/促销文案与图标不重叠','GET /boost/me；User.certification/balanceCents'],
 ['06-detail','服务详情','service/detail?id=11','访客/买家','封面、价格、卖家、规格、介绍与评价；认证取真实字段','GET /boost/listings/:id；Listing/Review'],
 ['07-checkout','确认下单','checkout/index?id=11','买家','紧凑规格及金额布局；认证非固定成功；支付关闭边界保留','GET /boost/listings/:id、/boost/coupons、/boost/config；POST /boost/orders'],
 ['08-orders','我的订单','orders/index','买家/打手','订单卡片、状态与进度显示；支付关闭时入口写查看订单','GET /boost/orders；Order.status/progress/role'],
 ['09-progress','订单详情/进度','orders/detail?id=41','订单买家/打手','按实际progress画进度环、记录与证据；资金动作仍受配置约束','GET /boost/orders/:id；Order/OrderUpdate；/action、/progress'],
 ['10-chat','聊天沟通','chat/index?id=51','会话参与者','头像、关联服务、气泡及已有图片入口；Mock消息发送后刷新仍显示','GET /boost/conversations/:id；POST /:id/messages、/:id/read；requestKey'],
 ['11-hall','接单大厅','hall/index','访客/认证打手','需求列表、筛选、预算与规格；认证门槛保留','GET /boost/listings?kind=REQUEST；接单请求与Order'],
 ['12-service','发布服务','publish/service','认证打手','图片→标题→规格→价格→介绍；真实字段、规则和草稿保留','POST /boost/listings；kind=SERVICE；POST /boost/upload'],
 ['13-manage','发布管理','manage/index','发布者','真实状态及编辑/下架/重新提交入口；重新提交仍需审核','GET /boost/listings/mine；POST /boost/listings/:id/action'],
 ['14-wallet','我的钱包','wallet/index','登录用户','黄色余额与流水；提现记录可展开；支付关闭不能提交资金操作','GET /boost/wallet、/ledger、/withdrawals；真实资金操作不执行'],
 ['15-aftersale','售后申请','aftersale/index?id=41','订单买家','独立Mock中验证原稿表单布局；实际支付关闭态展示说明并禁止提交','GET /boost/orders/:id、/boost/config；售后写入禁用']
];
const matrix=[];
for(const [key,name,route,role,expected,api]of entries){
 matrix.push({id:'UI-'+key.slice(0,2),entry:'/pages/'+route,role,expected,apiAndData:api,method:'H5 Chromium；320×844、390×844；隔离Mock；布局/溢出/图片及横幅文字几何；截图复核',status:'已实测通过',scope:'UI渲染及报告中列明的Mock交互，不代表真实持久化',evidence:[`screenshots/${key}-390.png`,`screenshots/${key}-320.png`,'browser-acceptance.json'],name});
 matrix.push({id:'MP-'+key.slice(0,2),entry:'/pages/'+route,role,expected,apiAndData:api,method:'最终微信开发工具原生模拟器',status:['01-home','02-market','05-mine'].includes(key)?'环境阻塞':'未测试',scope:'最终原生重进超时；原生截图仅属于中间版本，不能关闭最终验收',evidence:['native-smoke.json','native-smoke.log'],name});
 matrix.push({id:'DEVICE-'+key.slice(0,2),entry:'/pages/'+route,role,expected,apiAndData:api,method:'最新体验版本的iOS/Android真机',status:'未测试',scope:'没有已连接真机；不能由H5、编译或开发工具替代',evidence:[],name});
}
const extra=[
 ['R-START','小程序启动及会话','访客/登录用户','存储读取异常时页面继续启动；旧登录请求不能污染新会话','App.vue、lib/session.js；存储与登录状态','13项离线VM回归','已实测通过','accept-startup-session.log'],
 ['R-ART','GameArt全站26处调用','各角色','原生可见根独立绑定尺寸，图像不依赖父级scoped样式撑高','GameArt.vue；生成JS/WXML/WXSS','23项编译产物VM检查；旧产物同断言退出1','已实测通过','accept-game-art.log'],
 ['R-BUILD','H5/微信构建与样式','开发/发布','两种构建、静态检查通过；21份WXSS无不支持的通配选择器','uni-app；pages/manifest/依赖配置','实际lint与两端生产构建、WXSS检查','已实测通过','accept-command.json'],
 ['R-REVIEW','独立复核','审查者','直接读取原稿、差异和证据；资金及草稿逻辑不回归','独立子代理frontend_audit；生成产物与AST','独立31项检查及截图阅读；追加横幅专项复核','已实测通过','independent-review/final-change-review.md'],
 ['E-PUBLISH','真实发布→后台审核→市场展示','用户/打手/管理员','正确权限，持久化发布与图片，审核后跨端一致','Listing/Media；/boost/listings、后台审核','本轮没有重做真实数据库写入及管理端闭环','未测试',''],
 ['E-ORDER','真实接单/下单→刷新→取消','用户/打手','创建待付款订单、校验金额及身份、重复请求不重复创建，刷新状态一致','Order；acceptRequest、POST /boost/orders、/action','本轮没有重做真实订单写入、并发与跨用户接口测试','未测试',''],
 ['E-CHAT','真实双用户聊天及图片','会话参与者','消息/附件保存、重进及双端同步，不跨会话越权','Conversation/Message/Media；/boost/conversations、/boost/upload','浏览器只验证Mock发送与刷新；没有外发真实消息','未测试',''],
 ['E-AUTH','微信真实登录及业务接口权限','用户/打手/管理员','有效身份与权限，401/403及跨用户拒绝；重登录后正确数据','WeChat登录/Token；API权限','13项会话VM不能代替真实第三方登录和角色API测试','未测试',''],
 ['E-PAY','真实支付、退款、提现、回调和结算','买家/打手/管理员','按用户本轮要求支付暂不接入，不能发生真实资金操作','paymentsEnabled=false；支付及资金外部依赖','真实资金集成不属于本轮授权范围；禁用防护已另实测','不适用','screenshots/15-aftersale-disabled-390.png'],
 ['E-SERVER','后端/管理端生产部署与数据恢复','运维/管理员','最终代码配置、迁移/恢复、监控与回滚满足服务上线要求','后端、管理端、数据库、服务器','本轮为小程序UI修复，未部署后端/管理端或执行迁移恢复，不沿用历史通过声明','未测试',''],
 ['E-PERF','容量与全设备兼容','运维/各角色','在明确并发/容量和设备矩阵下达标','容量目标、设备矩阵','本轮没有容量目标或相关实测，不能宣称性能/所有设备兼容','未测试',''],
 ['U-UPLOAD','微信开发版上传','开发者','正确AppID、官方上传成功；178份产物与验收及ZIP逐文件一致','官方Devtools CLI；版本'+upload.version,'官方CLI退出0及正向标记；SHA256核对','已实测通过','upload-verified.json'],
 ['U-EXPERIENCE','微信后台指定体验版','开发者/体验者','体验版指向新版本并能在受影响设备进入','微信小程序后台及体验成员','平台站点被工具安全限制，未绕过；开发版上传不等于指定体验版','环境阻塞','upload-verified.json']
];
for(const[id,entry,role,expected,apiAndData,method,status,evidence]of extra)matrix.push({id,entry,role,expected,apiAndData,method,status,scope:method,evidence:evidence?[evidence]:[]});
const issues=[
 {id:'FIX-01',reproduce:'原生进入首页、市场或图片类页面',expected:'封面/头像有明确占位，尺寸与原稿相近',actual:'内部根高度仅24px或受父scoped规则不匹配影响；H5能掩盖原生问题',cause:'原生自定义组件宿主和内部view不是H5同一个元素，父样式不能可靠撑高内部根',impact:'26处GameArt，包括首页/列表/详情/头像及授权页',files:'components/GameArt.vue及各调用页',validation:'旧保存产物同测试退出1；最终23项VM及H5图片尺寸通过。最终原生视觉仍阻塞',evidence:'game-art-before-command.json'},
 {id:'FIX-02',reproduce:'对照三张原稿浏览15页',expected:'一致黄黑配色、紧凑卡片、两种发布顺序、规格/进度/聊天/钱包结构',actual:'间距及内容层次偏离原稿，表单顺序混杂，列表密度和底栏不一致',cause:'共享样式和页面细节缺少统一的原稿约束',impact:'15个核心页面及共享组件',files:'styles/app.css；AppShell、ServiceCard、PublishForm；15页模板/样式',validation:'30个H5页面截图/几何检查，7个交互和控制台通过；独立逐图复核',evidence:'browser-acceptance.json'},
 {id:'FIX-03',reproduce:'原生个人页查看右上按钮及促销卡图标；首页黑按钮',expected:'胶囊不盖住按钮；图标和箭头在对应区域清晰可见',actual:'帮助/设置被胶囊覆盖，促销图标定位在左下裁切，黑箭头不可见',cause:'个人自绘头部未预留原生胶囊行；父scoped样式没有匹配内部image；首页双层绝对定位',impact:'AppIcon使用点、个人页和首页',files:'pages/mine/index.vue；pages/index/index.vue；所有AppIcon宿主class',validation:'显式图标宿主class、wrapper定位、MP头部间距及白色文字箭头；编译断言通过，最终原生仍未验',evidence:'independent-review/final-change-review.md'},
 {id:'FIX-04',reproduce:'未认证卖家的详情/确认下单；固定套餐价列表',expected:'认证与状态由真实字段决定，固定套餐价不误写起价',actual:'原先固定认证标签或起价描述会误导用户',cause:'文案模仿样稿时未约束真实认证/套餐含义',impact:'ServiceCard、详情及下单页',files:'components/ServiceCard.vue；pages/service/detail.vue；pages/checkout/index.vue',validation:'PENDING夹具详情与下单均显示平台用户；未伪造认证/在线/微信绑定状态',evidence:'browser-acceptance.json'},
 {id:'FIX-05',reproduce:'320宽个人页两个促销卡',expected:'副文案完整且不与右侧图标相交',actual:'让你的游戏实力被看见与图标重叠',cause:'未保留右侧图标宽度且强行单行',impact:'个人页两张促销卡',files:'pages/mine/index.vue .profile-promos .tiny',validation:'预留40px并允许换行；320/390最终截图和独立复核',evidence:'screenshots/05-mine-320.png'},
 {id:'FIX-06',reproduce:'390/320首页两轮播及个人页横幅',expected:'文案不压图、不超出横幅，中文按短句自然换行',actual:'首页文字right232大于插画left202；个人页文字压住皇冠；第一轮预留150仍不足',cause:'标题未预留插画实际空间，自动中文换行产生孤字',impact:'首页2轮播、个人页横幅',files:'pages/index/index.vue；pages/mine/index.vue；scripts/ui-fidelity-acceptance.cjs',validation:'新增真实文字Range边界断言：.2退出1；150px版本仍退出1；168px及短句分行最终通过。两轮播必须有2组，个人页1组，缺项/塌缩直接失败',evidence:'banner-before.log'}
];
const report={generatedAt:new Date().toISOString(),version:upload.version,conclusion:'当前版本在所列本地H5隔离Mock、构建及逻辑验收范围内通过。当前不满足所列范围的发布条件：最终原生UI、真机及体验版指定尚未完成。',localStatus:'已实测通过',releaseStatus:'环境阻塞',nativeAttempt:{status:native.status,error:native.error,startedAt:native.startedAt,finishedAt:native.finishedAt,checks:native.checks.length},matrix,issues,acceptance,upload};
const states=new Set(['已实测通过','失败','未测试','环境阻塞','不适用']);assert(matrix.every(x=>states.has(x.status)));assert.equal(browser.checks.length,38);assert(browser.checks.every(x=>x.status==='已实测通过'));assert.deepEqual(browser.consoleErrors,[]);
fs.writeFileSync(path.join(out,'验收表.json'),JSON.stringify(report,null,2));
const esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const link=(file,label=file)=>`<a href="${esc(encodeURI(file))}">${esc(label)}</a>`;
const status=s=>`<strong class="${s==='已实测通过'?'ok':s==='失败'?'bad':'pending'}">${esc(s)}</strong>`;
function table(headers,rows){return `<div class="table-wrap"><table><thead><tr>${headers.map(h=>'<th>'+esc(h)+'</th>').join('')}</tr></thead><tbody>${rows.map(r=>'<tr>'+r.map(c=>'<td>'+c+'</td>').join('')+'</tr>').join('')}</tbody></table></div>`}
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>困困 UI修复验收 ${esc(upload.version)}</title><style>body{margin:0;background:#f7f7f5;color:#171717;font:15px/1.65 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1300px;margin:auto;padding:30px}h1{font-size:28px;line-height:1.3}h2{margin-top:32px;font-size:21px}.conclusion{border-left:5px solid #e0b800;background:#fff7cf;padding:16px 20px}a{color:#145bb5}code{background:#ececea;padding:2px 6px;overflow-wrap:anywhere}.ok{color:#13753d}.bad{color:#b22323}.pending{color:#815a07}.table-wrap{overflow:auto}table{border-collapse:collapse;background:white;width:100%;font-size:13px}th,td{border:1px solid #ddd;padding:9px;vertical-align:top;min-width:90px}th{background:#f0eee4;text-align:left}.shots{display:flex;gap:20px;flex-wrap:wrap}.shots img{max-width:320px;width:100%;height:auto;border:1px solid #ddd}.muted{color:#666}.hash{overflow-wrap:anywhere;font-size:12px}li{margin:7px 0}</style><main>
<h1>困困 · 15页UI修复验收包</h1><p>版本 ${esc(upload.version)} · 报告生成 ${esc(report.generatedAt)}（UTC；美国纽约时间减4小时）</p>
<div class="conclusion"><b>${esc(report.conclusion)}</b><p>开发版官方上传已实测通过；体验版尚未指定。支付暂未接入。此前提供的生产凭据不写入本报告。</p></div>
<h2>1. 范围与需要处理的事项</h2><p>依据你的3张原始UI板检查15个小程序核心页面。已按原稿调整颜色、结构、密度、表单顺序和主要控件。品牌保留困困，余额、认证、订单和状态取实际接口字段。浏览器夹具只在测试脚本中，未替换生产实现。</p><p>现有角色/皇冠素材与原稿不同，未进行像素级一致性量化，不能称100%一比一还原。若继续要求原稿插画完全相同，需要可用的独立切图或设计源文件；目前使用项目原有素材。</p>
${table(['事项','原因/下一步','预期'],[
 ['恢复困困UI验收20261007窗口','开发工具被最小化，自动化重进超时。已发出一次环境恢复请求；恢复后可由我继续自动操作，无需你逐页机械检查。','最终产物的原生首页、市场、个人页截图/尺寸与重新进入检查完成'],
 ['在微信后台指定新体验版','平台站点被工具安全限制，不能绕过。请把'+esc(upload.version)+'设为体验版，确认当前体验成员有权限。','体验版版本号等于'+esc(upload.version)],
 ['受影响手机查看新体验版','真机当前未连接，开发工具和H5不能证明原先手机的空白/布局问题消失。','重进首页、市场、个人页，图片可见、胶囊不遮按钮、文字不压图；登录后进入订单正常；支付入口保持未开放']])}
<h2>2. 实测结果与证据边界</h2><ul><li>统一验收7步全部退出0：静态检查、H5/微信生产构建、21份WXSS、13项启动/会话VM、23项生成组件VM、38项H5浏览器检查。</li><li>浏览器38项=15页×2宽度30项＋7项交互＋1项控制台。浏览器consoleErrors为空；这不是业务覆盖率或商用成熟度。</li><li>浏览器业务API全部隔离Mock。唯一允许的外部请求是DCloud公共GET shadow-grey.png；不发送生产业务写入或真实聊天/资金。</li><li>售后完整表单截图使用单独注明的Mock配置以检查布局。实际非支付模式也已单独检查：提现和售后按钮禁用、强制点击仍无资金POST。</li><li>原生曾用真实配置API进行匿名只读首页/市场/个人页截图，属于中间版本。最终原生尝试于${esc(native.startedAt)}—${esc(native.finishedAt)}，原始结果${esc(native.status)}，checks=${native.checks.length}，错误${esc(native.error)}。本报告将最终原生项列为环境阻塞/未测试，不沿用旧截图通过。</li></ul>
<p>${link('accept-command.json','最终命令/时间/退出码')} · ${link('browser-acceptance.json','38项逐项结果与Mock请求')} · ${link('native-smoke.json','原生失败原始记录')} · ${link('验收表.json','机器可读完整验收包')}</p>
<h2>3. 问题、根因与修复</h2>
${table(['编号/复现','预期与实际','根因/影响','修改位置','实际验证'],issues.map(x=>[esc(x.id)+'<br>'+esc(x.reproduce),esc(x.expected)+'<hr>'+esc(x.actual),esc(x.cause)+'<br>'+esc(x.impact),esc(x.files),esc(x.validation)+'<br>'+link(x.evidence)]))}
<p>测试工具自身问题也保留说明：uni-button不具有原生HTML button的disabled语义，断言检查真实disabled属性并强制点击后确认无POST；规则链接与checkbox区分点击；503故障夹具的预期资源错误单独记录。没有删除失败测试或削弱业务断言。横幅两次确定性失败分别见 ${link('banner-before-command.json')}、${link('banner-partial-fix-command.json')}，最终截图/Range结果以最新browser-acceptance.json为准。</p>
<h2>4. 完整验收表</h2><p>每个UI项目的通过只限H5隔离Mock中描述的验证。原生、真机、真实接口持久化另列，不能互相替代。</p>
${table(['功能编号/入口','角色','预期业务/界面结果','接口与数据','验证方式/范围','当前状态','证据'],matrix.map(x=>[esc(x.id)+'<br>'+esc(x.entry),esc(x.role),esc(x.expected),esc(x.apiAndData),esc(x.method)+'<br>'+esc(x.scope),status(x.status),x.evidence.map(e=>link(e)).join('<br>')||'没有本轮实测证据']))}
<h2>5. 版本、上传和可恢复产物</h2><p>工作目录不是Git仓库，因此不编造commit。用逐文件SHA256、源快照、差异及构建树摘要标识最终版本。官方上传开始${esc(upload.uploadStartedAt)}，结束${esc(upload.uploadFinishedAt)}，退出${upload.exitCode}；AppID ${esc(upload.appId)}；官方打包${upload.officialPackedBytes.toLocaleString()}字节；${upload.files}份产物已逐文件核对。</p><p class="hash">MP树 SHA256：${esc(upload.reviewedMpTreeSha256)}<br>H5树 SHA256：${esc(upload.reviewedH5TreeSha256)}<br>归档 SHA256：${esc(upload.archiveSha256)}</p><p>${link('upload-command.json','实际上传命令（脱敏）')} · ${link('upload-verified.json','上传验证')} · ${link('upload-artifact-manifest.json','178文件摘要')} · ${link('final-source-manifest.json','源文件摘要')} · ${link('source-diff.patch','本轮前期差异')} · ${link('source-diff-final-banner.patch','最终横幅差异')} · ${link('mini-ui-'+upload.version+'.zip','最终完整目录归档')}</p><p>上一启动修复版0.1.20261007.1的完整目录归档保留在${link('../mini-blank-20261007/mini-upload.zip','mini-upload.zip')}。本轮.2证据另保存在previous-ui-0.1.20261007.2，.2不是最终UI版本。回退需在隔离目录恢复归档并用官方工具上传新版本、重新指定体验版；本轮没有对生产数据/后端执行回退或迁移。</p>
<h2>6. 可再次运行的一键验收</h2><p>在项目根目录运行已实际执行过的命令：</p><pre><code>node scripts/accept-ui.cjs</code></pre><p>前置：现有代练/node_modules及静态检查依赖可用；全局Playwright和Chromium可用；Node路径可在系统PATH找到。脚本自动构建、启用临时127.0.0.1服务、注入隔离测试数据、检查后关闭浏览器和服务，不需要清理生产数据库。任一步失败立即退出非0；报告区分localStatus与发布结论，默认命令不计原生/真机为通过。</p><p>成功判定：进程退出0，accept-command.json各步exitCode=0，browser-acceptance.json无失败、无异常控制台错误；这只代表所列本地范围通过。原生补测命令为 <code>node scripts/ui-mini-smoke.cjs</code>，需正确AppID的官方开发工具窗口已恢复并启用19875自动化端口；当前该命令最终未通过，不宣传为已完成。</p>
${table(['检查','实际时间UTC','退出码','日志'],acceptance.runs.map(x=>[esc(x.name)+'<br><code>'+esc(x.command.slice(1).join(' '))+'</code>',esc(x.startedAt)+'<br>'+esc(x.finishedAt),esc(x.exitCode),link(x.evidence)]))}
<h2>7. 独立复核与剩余阻塞</h2><p>独立复核者frontend_audit实际读取原始UI、源码差异、编译产物、命令结果及两种宽度截图，并运行31项针对性检查；资金/草稿/订单运行逻辑做AST/源码比较。另完成最后的横幅文字专项复核。与主验收重合的项目不重复累加统计。${link('independent-review/final-change-review.md','独立初步/最终复核记录')}；${link('independent-review/r08-review.md','最终横幅复核')}。</p>
${table(['问题','严重程度/影响','下一步/最少条件'],[
 ['最终原生验证超时','发布阻塞；微信组件层级、胶囊、安全区效果未在最终运行版本证实','恢复已连接验收窗口，使用同一178份最终产物重测；旧图不能替代'],
 ['体验版未指定、真机未验证','发布阻塞；体验者可能仍看旧版本，不能确认原先设备问题消失','后台指定'+esc(upload.version)+'；受影响手机重进检验'],
 ['真实发布/接单/聊天/权限闭环未在本轮重做','如果验收范围包含业务上线则为阻塞；H5Mock和编译不能证明持久化、并发与跨端状态','在隔离真实后端和测试角色下发送请求、核对数据及重进；不要求用户逐个点击替代可自动测试'],
 ['素材与原稿仍有差异','视觉还原限制；不构成已经100%一比一的依据','若要求完全相同，提供独立切图/设计源；随后重新验证受影响布局'],
 ['支付暂不接入','本轮用户明确排除；真实支付全链路未验证','保持现有禁用防护；不要把测试配置或样例余额视为真实资金']])}
<h2>8. 最终页面示例（H5隔离Mock）</h2><p>下面是最终构建的实际截图，金额、角色和订单仅是视觉夹具。长页截图中固定底栏停留在初始视口；不能把截图视为微信真机。</p><div class="shots"><figure><figcaption>首页 · 390宽</figcaption><img src="screenshots/01-home-390.png" alt="最终首页H5 Mock截图"></figure><figure><figcaption>个人页 · 320宽</figcaption><img src="screenshots/05-mine-320.png" alt="最终个人页H5 Mock截图"></figure></div>
</main></html>`;
fs.writeFileSync(path.join(out,'验收报告.html'),html);console.log(JSON.stringify({report:'test-results/ui-fidelity-20261007/验收报告.html',matrixRows:matrix.length,version:upload.version,releaseStatus:report.releaseStatus}));
