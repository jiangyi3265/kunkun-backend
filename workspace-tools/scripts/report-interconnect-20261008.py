"""Build the reviewable acceptance package; never turns missing evidence into a pass."""
import csv,hashlib,html,json,re
from pathlib import Path
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'test-results/interconnect-20261008'
read=lambda p:json.loads((OUT/p).read_text('utf-8-sig'))
write=lambda p,v:(OUT/p).write_text(json.dumps(v,ensure_ascii=False,indent=2),'utf-8')
rows=json.loads((OUT.parent/'three-end-20261008/完整验收表.json').read_text('utf-8'))
by={r['功能编号']:r for r in rows}
allowed={'已实测通过','失败','未测试','环境阻塞','不适用'}
for r in rows:
    # Historical source is retained verbatim in its own immutable report.
    r['此前证据']=r['证据位置']
    r['此前边界或说明']=r.get('边界或说明','')
    r['验证方式']=r['验证方式'].replace('既有证据，本轮未重复执行；','')
    r['证据位置']='full/backend-all-tests-and-package.log；full/player-browser.json；full/real-http.json'
    r['边界或说明']='本表按具体验证方式限定范围；Java实名/付款替身、H5 Mock、隔离真实HTTP、Devtools、线上只读与手机分开，不相互替代。历史限定详见同一行的“此前边界或说明”，历史“本轮”指旧报告时点。'
def setrow(id,status,evidence,method=None,note=None,entry=None,expected=None):
    r=by[id];r['当前状态']=status;r['证据位置']=evidence
    if method:r['验证方式']=method
    if note:r['边界或说明']=note
    if entry:r['操作入口']=entry
    if expected:r['预期业务结果']=expected
def add(id,entry,role,expected,api,method,status,evidence,note):
    r=dict(zip(['功能编号','操作入口','角色','预期业务结果','关联接口与数据','验证方式','当前状态','证据位置','边界或说明'],[id,entry,role,expected,api,method,status,evidence,note]));rows.append(r);by[id]=r
entry=read('final-entry-verified/acceptance.json');assert entry['localStatus']=='已实测通过', 'Final entry has not passed locally'
assert entry['exitCode']==2,'External blockers must not disappear'
player='final-entry-verified/player-http-browser/'
rewards='final-entry-verified/rewards-browser/'
benefits='final-entry-verified/benefits-browser/'
native='final-entry-verified/native/'
live='live-verified/live-verification.json'
liveNative='native-live-port-current/native-live.json'
java='full/backend-all-tests-and-package.log'
for id in ['P01','P03','P06','P06A','P08','H02','H03','H08','H10']:
    setrow(id,'已实测通过',java+'；full/player-browser.json；'+player+'real-http.json')
setrow('P02','已实测通过',java+'；'+player+'real-http.json',note='门禁负向真实HTTP及正向实名/付款替身测试通过；真实双认证发布列P05A，仍环境阻塞。',entry='发布出售/求购的门禁实现（非真实核验）')
for id in ['P04','H04','H05','H06','H07']:
    setrow(id,'已实测通过',player+'real-http.json；'+player+'real-profile-browser.json；'+java)
setrow('P04A','已实测通过',player+'admin-player-browser.json',note='实际隔离后台UI→审批→MySQL持久化；审批店铺不等于通过实名。')
setrow('P09A','已实测通过',player+'admin-player-roles.json',note='真实受限运营账号登录/撤权测试；不扩展为所有新增会员权限均跑过受限JWT矩阵。')
setrow('P05A','环境阻塞',live+'；'+liveNative,'实际部署配置检查',note='realnameReady=false；缺腾讯云FaceID SecretId/SecretKey、RuleId及小程序核验渠道配置。不得为上线绕过实名。')
for id in ['P07','P07B']:
    setrow(id,'已实测通过',java+'；full/player-browser.json',note='道具订单状态/交付/结算在H2/MySQL服务替身中验证；真实支付关闭，当前线上不能完成付费下单交付，不宣称真实成交。')
setrow('P07D','已实测通过',java+'；financial-guard-before-regression.log；financial-guard-after.log；targeted-java-final.log；'+live,note='新增WAIT_DELIVERY/WAIT_CONFIRM仍有资金义务时拒绝关闭支付启动；修复前测试失败，修复后通过。')
setrow('P08A','已实测通过',benefits+'benefits-real-browser.json；'+native+'native-integration.json；'+native+'native-persistence.json；targeted-java-final.log',method='真实后台/H5/Devtools+MySQL；到期计算Java固定时间测试',entry='管理员免费授予/取消会员及到期计算',expected='后台授予会员刷新可见、取消后非会员；服务端判断到期，不改变认证',note='已实现人工会员；未接付费购买、额外权益及自动续费。浏览器测试不代表等待真实到期时刻。')
setrow('P08B','已实测通过',benefits+'benefits-real-browser.json；'+native+'native-persistence.json；'+java,method='实际后台调整/重复请求及原生重新进入、MySQL账本、Java并发',entry='后台成长奖励/等级阈值/进度',expected='人工成长150仅记一次；阈值0,100,300下Lv2进度25%；满级100%',note='已部署测试阈值0,100,300,1000；未给真实用户奖励。自动奖励事件尚未定义，列X01。')
setrow('P09','已实测通过',java+'；'+player+'admin-player-browser.json；'+live)
for id in ['P10','H09']:setrow(id,'已实测通过','full/account-races/account-ui-races-after.json；full/account-ui-races.log；'+player+'real-profile-browser.json')
setrow('P10A','已实测通过','full/player-browser.json',note='该51项为H5 Mock及320/390布局，不是38种手机。最终新增会员/奖励交互另列。')
setrow('P10B','已实测通过','full/acceptance.json；release-build-final.log；validation-final/checks.json；validation-final/admin-lint-final.log；artifact-set-final/artifact-identity.json',note='最终发布包与当前H5/后台/小程序逐文件字节一致，JAR一致；Java基线274项，最后定向15项。')
setrow('P11','已实测通过','full/profile-migration.json；full/rewards-migration.json；deployment/kunkun-20261008T111020102Z/prepare.json；deployment/kunkun-20261008T111020102Z/activate.json',expected='既有V002等历史数据升级到V006；重复迁移不改变旧列/旧配置及认证',note='准备和激活分别完整备份并还原到新隔离库核对，再迁移两遍；数据库结构全面等价不只靠行哈希声明。')
setrow('P11A','已实测通过','full/mysql-backup-restore.json；deployment/kunkun-20261008T111020102Z/activate.json',note='真实备份/隔离恢复通过；未在开放新业务数据后覆盖回滚线上库。')
setrow('P12','已实测通过','secret-scan.json；full/secret-scan.json；package-final/release-package.json',note='已知敏感值扫描及JAR展开内容扫描；报告不含密钥/密码/JWT。不是未知凭据全量安全证明。')
setrow('P13','已实测通过',native+'native-integration.json；'+native+'native-persistence.json；'+liveNative,'官方Devtools、实际MySQL/Spring与实际HTTPS，无wx/API业务Mock',expected='账号密码登录、商城图片、勋章兑换、实物地址/发货/收货、抽奖、会员成长以及公开五页正常',note='隔离环境完成所列流程；线上公开五页及配置只读。原生相机/头像上传/微信授权登录未验证，设备模拟器不是手机。')
setrow('P13A','未测试','wechat-upload-result.json','官方CLI已上传；实体手机未操作',note='1.6.1008上传成功；公众平台访问被浏览器安全策略禁止，未代设体验版。')
setrow('P14','已实测通过','deployment/kunkun-20261008T111020102Z/prepare.json；deployment/kunkun-20261008T111020102Z/activate.json；deployment/kunkun-20261008T111020102Z/inspect.json',method='实际SSH、加密备份、新库恢复、菜单、Nginx、systemd、健康与版本SHA检查',entry='已有测试站匹配版本升级',expected='既有数据保留、升级到V006、新前后端和代理一致、健康UP',note='已开放测试站。监控告警及故障切回演练分别列X02/X03；不是正式商用验收。')
setrow('P15','不适用',live,'按用户本阶段明确不接入支付',note='支付与模拟支付均false；不能以积分流程冒充现金交易。')
setrow('H01','已实测通过','full/player-browser.json；'+live+'；'+liveNative,method='H5构建Mock解码/实际点击；线上H5图点击与无覆盖文字；原生当前页',note='线上脚本检查首个图片链接及跳转，不把该探针写成全轮播图片数量/解码检查。')
setrow('H11','已实测通过','full/profile-migration.json')
setrow('H12','未测试','wechat-upload-result.json',method='已上传1.6.1008；手机资料/主页交互未验',entry='当前版本体验版资料与个人主页',note='不以此前版本上传记录或Devtools截图证明当前手机通过。')
for r in rows:
    if re.fullmatch(r'R\d+',r['功能编号']):r['证据位置']=rewards+'rewards-real-browser.json；'+rewards+'rewards-http-persistence.json；'+java
for id in ['R04','R18']:setrow(id,'已实测通过','final-entry-verified/rewards-ui-races/rewards-ui-races.json；final-entry-verified/benefits-ui-races/benefits-ui-races.json',note='实际Vue SFC执行，网络/权限/模态受控替身；页面离开/切号取消、未知结果保留原请求键。')
setrow('R07','已实测通过',rewards+'rewards-real-browser.json；'+native+'native-integration.json；'+native+'native-local-badge.png；'+native+'native-persistence.json',note='最终原生测试新增等待个人主页实际勋章文字后截图；此前native-complete同名截图仅为跳转前兑换结果，保留但不作主页视觉证据。')
setrow('R19','已实测通过','full/rewards-migration.json；deployment/kunkun-20261008T111020102Z/prepare.json')
setrow('R20','已实测通过','full/rewards-backup-restore.json；deployment/kunkun-20261008T111020102Z/activate.json')
setrow('R23','已实测通过',native+'native-integration.json；'+native+'native-persistence.json；'+liveNative,entry='活动/积分官方Devtools原生联调',expected='真实登录、图片、兑换、确认收货、单/五连抽结果和账本一致',note='仅官方模拟器；手机体验版单列P13A/H12。')
setrow('R24','已实测通过',live+'；deployment/kunkun-20261008T111020102Z/inspect.json；'+rewards+'rewards-real-browser.json',entry='测试站媒体代理和持久卷',expected='新媒体路径不再落入H5页面，私图保留，隔离实际图片可读',note='线上缺图返回非HTML404，仅证明未落H5成功页；实际新图片200/解码由隔离H5/原生验证。正式协议另列X04。')
setrow('R25','未测试',rewards+'rewards-http-persistence.json',note='只有合成地址、物流、发货和收货状态；没有寄送真实包裹。')
setrow('R26','已实测通过',java+'；'+live,method='Java免费积分模式与资金隔离测试、真实HTTPS配置检查；本地H5/原生实际免费积分消费',entry='内部测试免费积分模式与资金隔离',expected='后台人工免费积分，不能购买/提现，与人民币钱包分离',note='当前测试站drawEnabled=true；没有给真实用户发积分或配置奖品；正式奖品/发放规则待运营确认，列X05。')
setrow('N01','已实测通过',live,method='实际HTTPS H5页面和已登录后台页面')
setrow('N02','已实测通过',live+'；'+liveNative,method='当前部署真实HTTPS配置/五页',note='原先旧代练后端及新商城缺失已通过版本升级修正；页面可用不等于真实实名交易闭环。')
for id in ['N03','N04','N05']:setrow(id,'已实测通过',native+'native-integration.json；'+native+'native-persistence.json',method='实际官方Devtools + owned MySQL API、随后直接SQL核对',note='所有写入隔离库；N03/N04/N05是同一7组运行的分项，不另外累加测试数量。')
setrow('N06','已实测通过',native+'native-persistence.json；'+native+'native-integration.json',entry='最终原生环境数据及清理',expected='173积分、5账本、2次抽奖共6结果、收货完成；测试会话注销和owned服务停止',note='旧失败轮次无误写证明保存在旧报告；当前正向写入确实发生并核对。隔离库和媒体保留供复查。')
setrow('N07','已实测通过',player+'real-http.json；'+player+'real-profile-browser.json；'+player+'admin-player-browser.json；'+player+'admin-player-roles.json')
setrow('N08','已实测通过','../three-end-20261008/完整验收表.json',method='历史独立owned项目13断言/4次命令；本轮未重跑该脚本',note='保留此前证据，不能计为本轮重复运行；本轮官方新项目实际编译启动另有P13。')
setrow('N09','已实测通过',rewards+'rewards-real-browser.json；'+rewards+'rewards-http-persistence.json',note='12组实际浏览器/数据库，非线上真实用户写入。')
add('X01','付费会员/额外权益/自动成长事件','运营/玩家','按正式规则自动累计并兑现权益','会员/成长事件','需求边界检查','未测试','PRODUCT.md','当前交付人工会员、成长与阈值；未定义的付费权益/自动事件不宣称完成。')
add('X02','监控与报警','运维','应用/DB/磁盘/证书异常能及时通知','健康/日志/告警服务','健康已查；未做报警演练','未测试','deployment/kunkun-20261008T111020102Z/inspect.json','UP只证明当前健康，不证明值班通知或容量达标。')
add('X03','升级失败与恢复','运维','按维护/迁移/重新开放阶段安全处理','升级脚本、SQL备份、菜单、current','独立控制流替身+真实隔离恢复；线上故障切回未注入','未测试','independent-upgrade-final-review.txt；deployment/kunkun-20261008T111020102Z/activate.json','重新开放后禁止直接旧库覆盖/盲切旧JAR；必须核对新数据并前向修复。')
add('X04','正式协议与隐私','运营主体','真实主体/联系渠道/保留规则及版本生效','policyVersion/config','实际配置检查','环境阻塞',live,'仍为用户授权的内部测试草案；不能对外当正式商用政策。')
add('X05','正式活动奖品及发放规则','运营','真实内容、库存、履约与活动规则一致','rewards goods/activity','测试站未创建正式商品/奖品','未测试',live,'用户需要自行决定奖品、成本、库存、发放和物流规则；不是让用户重复机械检查。')
add('X06','会员变更幂等/并发/不确定响应','管理员','未知结果保持原意图，不能重复发成长；关页不能迟发','benefit_action/benefits_revision','实际Vue SFC5组+真实UI/HTTP4组+H2/MySQL7组','已实测通过','final-entry-verified/benefits-ui-races/benefits-ui-races.json；'+benefits+'benefits-real-browser.json；targeted-java-final.log','替身、真实HTTP与MySQL分别报告；没有声称所有受限会员角色矩阵实测。')
add('X07','官方上传','开发者','1.6.1008代码上传到正确AppID','官方CLI upload','官方CLI退出0与info输出','已实测通过','wechat-upload-result.json；wechat-upload-legacy.log；wechat-upload-info.json','上传不等于公众平台设为体验版；体验版和手机测试未确认。')
add('X08','三端当前包字节一致性','开发者/运维','当前构建与已上传/部署不可变包一致','3个前端目录、JAR、ZIP SHA256','逐文件SHA核对、远程当前版本/JAR校验','已实测通过','artifact-set-final/artifact-identity.json；deployment/kunkun-20261008T111020102Z/inspect.json','没有Git提交ID；用发布版号及源码/产物SHA清单定位。')
for r in rows:assert r['当前状态'] in allowed
# Include source hashes, not credentials, build directories, or copied runtime state.
sources=[]
for base in ['代练','RuoYi-Vue','RuoYi-Vue3','scripts','deploy']:
    for p in (ROOT/base).rglob('*'):
        if not p.is_file() or any(x in {'node_modules','dist','target','.git','__pycache__','.runtime'} for x in p.parts):continue
        if p.name.startswith('.env') or p.suffix not in {'.vue','.js','.cjs','.mjs','.java','.sql','.py','.ps1','.xml','.json','.yml','.yaml','.conf'}:continue
        sources.append({'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
sources.sort(key=lambda x:x['path']);sid=hashlib.sha256(json.dumps(sources,sort_keys=True).encode()).hexdigest();write('source-inventory.json',{'generatedAt':datetime.now(timezone.utc).isoformat(),'sourceSnapshotSha256':sid,'files':sources})
write('完整验收表.json',rows)
fields=list(rows[0])
with (OUT/'完整验收表.csv').open('w',encoding='utf-8-sig',newline='') as f:
    w=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');w.writeheader();w.writerows(rows)
fixes=[
 {'问题':'关闭支付时漏算待交付/待收货资金义务','复现':'有WAIT_DELIVERY/WAIT_CONFIRM未完结订单时启动关闭支付模式','预期与实际':'预期拒绝启动；修复前回归实际未抛异常','根因':'生产资金校验状态枚举漏新交易状态','影响范围':'未结算交易的启动安全','修改位置':'BoostProductionGuard.java','验证':'financial-guard-before-regression.log exit1 → financial-guard-after.log/targeted-java-final.log exit0'},
 {'问题':'原生兑换/收货确认后的提交不稳定','复现':'连续兑换不同商品、切页/重新打开确认','预期与实际':'用户确认后实际扣积分并保存订单；原生key未重建组件导致旧nonce不匹配','根因':'依赖key在小程序重新挂载，以及异步确认生命周期缺少统一取消','影响范围':'勋章/实物兑换与确认收货、切号切页','修改位置':'代练/components/ConfirmAction.vue、lib/confirmation.js、pages/rewards/item.vue、order.vue','验证':'SFC取消/晚到回归；native最终7组+SQL与实际可见按钮'},
 {'问题':'会员成长后台离开后迟发、未知结果重试重复、409无法修正','复现':'确认期间卸载；提交结果丢失后刷新；Axios ERR_BAD_REQUEST+HTTP409','预期与实际':'不能离开后写入；同意图重试一次；明确拒绝可修正。修复前独立SFC实际失败','根因':'缺卸载guard、pending intent未持久化、字符串错误码遮蔽HTTP数值','影响范围':'会员成长以及同类积分发放错误处理','修改位置':'RuoYi-Vue3/src/views/boost/BenefitsEditor.vue、rewards.vue','验证':'independent-benefits-ui-before/after/final；最终可重复SFC5/5、真实UI4组、MySQL并发'},
 {'问题':'服务器仍为旧代练版本、缺新菜单与媒体路由','复现':'新前端访问旧配置/商城接口与后台菜单','预期与实际':'预期PLAYER_ITEM/和平精英/积分活动；旧部署不能提供','根因':'前后端/数据库/菜单/代理版本未协调升级','影响范围':'三端线上互通','修改位置':'deploy/upgrade-player-test.py及三份增量菜单/媒体代理','验证':'实际备份→新库恢复→V006两遍→保留旧数据→部署→线上H5/admin6项→官方HTTPS6项'},
 {'问题':'已有数据升级缺恢复边界且完整包上传慢','复现':'在迁移前/后/重新开放阶段发生异常；传输旧96MB全包','预期与实际':'需不清库、保留菜单角色与媒体；禁止开放新写入后覆盖旧库','根因':'首次安装脚本不适合已有站点；升级未充分按阶段保护','影响范围':'当前既有测试站数据/恢复','修改位置':'upgrade-player-test.py、run-player-upgrade.py、build-player-release-delta.py、package-kunkun-release.py','验证':'独立控制流替身故障+实际681文件字节重建和3损坏拒绝；实际prepare/activate/inspect各exit0'},
 {'问题':'官方工具新MCP上传初始化失败/验收连接错旧端口','复现':'wechatide upload返回MCP_INIT_ERROR；旧19875 mine scope与最终包不符','预期与实际':'上传需明确成功，验收需绑定当前项目；不能看shell0就当JSON成功','根因':'MCP初始化失败；旧自动化端点仍运行，AppID相同不能证明版本','影响范围':'工具执行和验收可信度，不等于产品包本身陈旧','修改位置':'verify-player-native-live.cjs显式端点并记录、改用已授权的官方legacy CLI上传','验证':'19883当前五页scope全部匹配、实际HTTPS6/6；官方legacy upload退出0、1,807,336Bytes'},
 {'问题':'勋章主页截图取证太早','复现':'route刚切换就拍图，仍显示兑换结果','预期与实际':'必须看到个人主页勋章节点；旧截图不足以证明视觉展示','根因':'测试只等待页面路径，没有等待数据渲染','影响范围':'验收证据范围','修改位置':'scripts/player-native-isolated.cjs','验证':'最终入口等待真实reward-badge文字后截图；未弱化兑换/账本断言'}
]
fixes.append({'问题':'一键原生工具启动就绪和上下文冲突','复现':'已有同AppID正式包自动化窗口时新开隔离项目；端口出现但SDKVersion为空或当前WebView元数据未就绪','预期与实际':'预期稳定开始业务；first final-entry在版本比较失败，current轮通过SDK就绪但WebView超时，single-connection即时reLaunch失败，均保留且未算业务通过','根因':'工具端口就绪不等于SDK/页面就绪；探针连接重建和多个同AppID自动化上下文有干扰风险，单因素未做独立因果隔离','影响范围':'本地原生验收运行器；不能据此推定发布包有空白页缺陷','修改位置':'scripts/player-native-isolated.cjs、accept-interconnect.cjs','验证':'使用8秒握手上限、单连接SDK就绪检测并保留SDK版本检查；只关闭本任务生成的正式包项目再开独立测试副本。native-exclusive7项通过；最终整套入口结果见final-entry-verified/acceptance.json'})
write('问题修复记录.json',fixes)
meta=read('package-final/release-package.json')
conclusion={'generatedAt':datetime.now(timezone.utc).isoformat(),'releaseVersion':meta['version'],'miniVersion':'1.6.1008','backendSha256':meta['backendSha256'],'sourceSnapshotSha256':sid,'overall':'当前不满足所列范围的发布条件','local':'当前版本在报告列明的隔离环境、官方Devtools和线上只读范围内通过','payment':'关闭','required':['真实FaceID配置与核验→双认证发布','微信公众平台设为体验版并真机登录/上传/交互','正式运营主体和隐私资料、活动实物运营配置','监控报警/恢复与容量按正式运营要求另验'],'testCountsNote':'274 Java为完整基线；15 Java为最后相关回归，重叠不相加。最终入口HTTP8+资料H5 6+后台5+角色4、奖励12、会员4、原生7、SFC奖励5+会员5；线上H5/admin6、官方HTTPS6。组数不是业务覆盖率。','finalEntry':entry}
write('验收结论.json',conclusion)
counts={s:sum(r['当前状态']==s for r in rows) for s in allowed}
def e(x):return html.escape(str(x))
def links(value):
    result=[]
    for p in value.split('；'):
        target=(OUT/p) if p!='PRODUCT.md' else ROOT/p
        result.append('<a href="'+e(p if p!='PRODUCT.md' else '../../PRODUCT.md')+'">'+e(p)+'</a>' if target.is_file() else e(p))
    return '<br>'.join(result)
table='<table><thead><tr>'+''.join('<th>'+e(k)+'</th>' for k in fields)+'</tr></thead><tbody>'+''.join('<tr data-status="'+e(r['当前状态'])+'">'+''.join('<td>'+(links(r[k]) if k=='证据位置' else e(r.get(k,'')))+'</td>' for k in fields)+'</tr>' for r in rows)+'</tbody></table>'
fixhtml=''.join('<article><h3>'+e(f['问题'])+'</h3><dl>'+''.join('<dt>'+e(k)+'</dt><dd>'+e(v)+'</dd>' for k,v in f.items() if k!='问题')+'</dl></article>' for f in fixes)
doc='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>困困三端互通验收 · 2026-10-08</title><style>body{font:15px/1.65 system-ui,sans-serif;color:#17212b;background:#f5f6f8;margin:0}main{max-width:1450px;margin:auto;padding:28px}h1{font-size:28px}h2{margin-top:36px}section,article{background:white;padding:20px;margin:14px 0;border:1px solid #dae0e6;border-radius:8px}.notice{border-left:5px solid #b45b0e}a{color:#075db3;overflow-wrap:anywhere}code{overflow-wrap:anywhere}table{border-collapse:collapse;width:100%;font-size:13px;background:white}th,td{padding:10px;border:1px solid #dbe0e5;vertical-align:top;min-width:95px}th{background:#edf1f5;position:sticky;top:0}td:last-child{min-width:230px}.scroll{overflow:auto}dt{font-weight:600}dd{margin:0 0 8px}input,select{font:inherit;padding:8px;margin-right:12px}.meta{color:#52606e}img{max-width:220px;max-height:420px;object-fit:contain;vertical-align:top;margin-right:10px}</style><main>
<h1>困困三端互通验收包</h1><p class="meta">2026-10-08 · 后台 / H5 / 微信小程序 · 仅内部测试范围</p>
<section class="notice"><strong>当前不满足所列范围的发布条件。</strong><p>后端、H5和后台已部署匹配版本，小程序1.6.1008代码已上传；实际隔离业务闭环与线上只读检查通过。真实实名渠道仍未配置，真实双认证发布、手机体验版及正式运营条件没有被这些测试代替。</p><p>需你完成：在微信公众平台把1.6.1008设为体验版并进行手机验证；提供已开通FaceID的必要配置；正式运营前确认主体/隐私与实际奖品规则。平台切换由浏览器安全策略阻止访问，未尝试绕过。</p></section>
<section><h2>交付版本与范围</h2><p>版本：VERSION。支付与模拟支付均关闭。仅和平精英/地铁逃生八类道具，不销售账号；发布仍要求店铺+真实实名。会员与成长当前为管理员人工授予/配置，成长测试阈值0、100、300、1000；没有开放会员购买或自动成长事件。</p><p>新后台可配置免费积分、勋章/实物商城、独立转盘单/五连抽。线上未植入假商品/奖品或给真实用户赠送积分；因此商城/活动空态是当前真实内容。</p><p><a href="https://ceshijjht.oksja.cn">后台管理</a> · <a href="https://ceshisss.oksja.cn">H5用户端</a> · <a href="package-final/release-package.json">发布包SHA</a> · <a href="source-inventory.json">源码差异定位清单</a> · <a href="wechat-upload-result.json">官方上传结果</a></p><p>JAR SHA256：<code>JARHASH</code></p></section>
<section><h2>实际验证与边界</h2><p>完整Java基线274项、最终相关Java15项均无失败/跳过；两者重叠，不相加。一键最终入口实跑：真实HTTP8、资料H5 6、后台5、受限角色4；奖励12组；会员4组；官方Devtools7组与独立SQL；奖励SFC5组、会员SFC5组。SFC用受控依赖；H5 Mock51项另列。组数不等于功能覆盖率。</p><p>线上H5/admin6项覆盖首页图片点击、商城/活动空态、已登录后台店铺/用户/积分页面及配置。线上缺图404证明没有返回H5成功页；不等于所有媒体200验证。官方Devtools真实HTTPS6项覆盖配置及五页当前代码渲染，不能替代线上真实用户新增与核验。手机没有实测。</p><p>升级实际保留既有5用户、2订单及私图等数据；准备与激活各自执行新隔离库恢复和内容指纹比对，V006重复迁移成功。部署文件SHA与当前版本确认一致。恢复演练不等于已注入线上失败，也不证明全部告警有效。</p><p><a href="full/acceptance.json">基线命令/时间/退出码</a> · <a href="final-entry-verified/acceptance.json">最终一键命令/时间/退出码</a> · <a href="targeted-java-final.log">最后Java定向测试</a> · <a href="live-verified/live-verification.json">线上浏览器</a> · <a href="native-live-port-current/native-live.json">线上官方原生</a> · <a href="deployment/kunkun-20261008T111020102Z/activate.json">实际激活与恢复证据</a></p><p>所有失败轮次保留。官方新上传接口shell虽0但JSON失败，未算成功；后续官方legacy上传明确成功。旧19875端点导致版本核对失败，改连已打开的19883后全部scope匹配。测试选择器、登录导航等待及返回结构纠正均未放宽数据库/权限断言。</p></section>
<section><h2>可重复的一键验收</h2><p>在仓库根目录执行：</p><pre>powershell -NoProfile -File .\\scripts\\验收三端互通.ps1</pre><p>此命令已实际运行。使用已冻结的JAR/H5/后台包，先与发布包逐文件比对，再启动隔离HTTP/MySQL和浏览器、官方微信工具测试。需要现有Node/Java17、Python、本机MySQL/Redis、Playwright依赖、已登录微信开发者工具及9420服务端口；本地数据库配置保存在受保护.runtime，报告不包含凭据。</p><p>退出1表示内部检查失败；退出2表示本地通过但仍存在必需外部验证，不是总体验收通过。当前设计保留外部阻塞，只有接通真实实名/设备等并增加真实证据后才可调整验收结论。每次默认创建带时间戳的证据目录；本次最终输出final-entry-verified；首次统一入口final-entry因SDK就绪时序失败，完整保留。测试服务和会话在finally清理，标记过的隔离库/媒体保留以便核对，不自动删除真实数据。</p><p>完整源码重建入口：<code>powershell -NoProfile -File .\\scripts\\验收玩家交易.ps1</code>。本轮基线通过它的Node底层入口执行；此后补充会员步骤，最终受影响流程由上述冻结包入口重验，未伪称重新运行整套274项。</p></section>
<h2>问题修复记录</h2>FIXES
<section><h2>最少人工事项及剩余阻塞</h2><ol><li><strong>腾讯FaceID（核心阻塞）</strong>：需已开通服务的SecretId/SecretKey、RuleId及微信小程序支持渠道，通过受保护配置提供。AppSecret不是实名服务凭据。配置后应完整完成核验→后台确认→双认证发布→刷新持久化，当前不能跳过。</li><li><strong>体验版/手机</strong>：公众平台版本管理将1.6.1008设为体验版，扫码确认首页、微信登录、上传头像/证明图和图片展示。原因是工具访问公众平台受安全策略阻止，且实体微信能力不能由Devtools代替；无需重复本地已验的机械积分/库存检查。</li><li><strong>正式运营</strong>：真实主体、隐私联系渠道、数据保留规则、协议版本/生效日期、奖品与物流规则由经营者决定。当前内部草案和合成物流不能代替；未植入正式奖品。</li><li><strong>运维</strong>：正式开放前应明确容量、监控告警和恢复目标，并在隔离环境做对应演练。当前未宣称性能或全设备兼容。</li></ol><p>备份在服务器版本专属upgrade目录，以加密SQL和私图SHA保存。新写入开放后禁止盲目恢复旧快照或切回不支持新数据的旧JAR；若需恢复，应维护窗口内核对当前新数据并制定前向修复/经核验恢复方案。</p></section>
<section><h2>独立复核</h2><p>独立代理直接阅读代码、实际结果和截图，找出3个会员SFC缺陷、升级失败分支问题及原生截图取证遗漏；修复后做对应复核。前版复核报告中的“尚未执行部署”是当时事实，最终部署结果以上方新证据为准。</p><p><a href="independent-benefits-review.txt">会员复核</a> · <a href="independent-upgrade-final-review.txt">升级边界复核</a> · <a href="independent-delta-review.txt">差分重建/损坏拒绝</a> · <a href="independent-final-review.txt">前次证据复核</a> · <a href="independent-entry-review.txt">最终一键入口和原生截图复核</a> · <a href="independent-report-supplement.txt">验收报告专项复核</a></p></section>
<section><h2>关键截图</h2><a href="final-entry-verified/native/native-local-badge.png"><img src="final-entry-verified/native/native-local-badge.png" alt="原生个人主页勋章实际显示"></a><a href="final-entry-verified/native/native-membership-growth.png"><img src="final-entry-verified/native/native-membership-growth.png" alt="会员等级成长"></a><a href="final-entry-verified/native/native-local-draw.png"><img src="final-entry-verified/native/native-local-draw.png" alt="五连抽实际结果"></a><a href="native-live-port-current/home.png"><img src="native-live-port-current/home.png" alt="正式域名首页"></a></section>
<h2>完整验收表（COUNT项）</h2><p>COUNTS。每行只使用要求的五种状态；已通过只对该行方法和边界负责。</p><p><a href="完整验收表.csv">下载CSV</a> · <a href="完整验收表.json">JSON</a> · <a href="问题修复记录.json">问题记录</a></p><p><input id="search" placeholder="搜索功能、接口或证据"><select id="filter"><option value="">全部状态</option><option>已实测通过</option><option>失败</option><option>未测试</option><option>环境阻塞</option><option>不适用</option></select></p><div class="scroll">TABLE</div><script>function filterRows(){const q=document.getElementById('search').value.toLowerCase(),s=document.getElementById('filter').value;document.querySelectorAll('tbody tr').forEach(r=>r.hidden=!!((s&&r.dataset.status!==s)||(q&&!r.textContent.toLowerCase().includes(q))))}document.getElementById('search').addEventListener('input',filterRows);document.getElementById('filter').addEventListener('change',filterRows);</script></main></html>'''
for k,v in {'VERSION':e(meta['version']),'JARHASH':e(meta['backendSha256']),'FIXES':fixhtml,'TABLE':table,'COUNTS':e(' / '.join(f'{k}{v}' for k,v in sorted(counts.items()))),'COUNT':str(len(rows))}.items():doc=doc.replace(k,v)
(OUT/'验收报告.html').write_text(doc,'utf-8')
print(json.dumps({'report':str(OUT/'验收报告.html'),'rows':len(rows),'counts':counts,'sourceSnapshotSha256':sid},ensure_ascii=False))



