"""Build a truthful local acceptance index from existing executed evidence, never run tests here."""
import csv, hashlib, html, json, re, shutil
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'test-results/rewards-20261008'
OLD=ROOT/'test-results/player-profile-20261008'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(name,value):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
now=datetime.now(timezone.utc).isoformat()
full=read(OUT/'final/acceptance.json');post=read(OUT/'after-tab-image/rewards-real-browser.json');races=read(OUT/'after-tab-image/rewards-ui-races.json')
assert full['exitCode']==2 and all(c['status']=='已实测通过' for c in full['checks'])
assert post['exitCode']==0 and not post['errors'] and races['passed'] and len(post['decodedImages'])==7
assert read(OUT/'after-tab-image/rewards-http-persistence.json')['exitCode']==0

# A selected-source digest is not a Git commit or a full historical patch.
prior=read(OLD/'最终版本清单.json');names={x['file'] for x in prior['sources']}
for pattern in ('scripts/*.cjs','scripts/*.py','scripts/*.ps1','RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/boost/*.java','RuoYi-Vue/ruoyi-admin/src/test/java/com/ruoyi/boost/*.java','RuoYi-Vue/ruoyi-admin/src/main/resources/boost/migrations/*.sql','RuoYi-Vue/sql/boost-*-menu.sql','代练/pages/rewards/*.vue','代练/lib/rewards.js','代练/components/RewardBadges.vue','RuoYi-Vue3/src/views/boost/rewards.vue','RuoYi-Vue3/src/api/rewards.js'):
    names.update(p.relative_to(ROOT).as_posix() for p in ROOT.glob(pattern) if p.is_file())
names.update(['RuoYi-Vue3/src/utils/request.js','deploy/production.env.example','RuoYi-Vue/ruoyi-admin/src/main/resources/application-prod.yml'])
sources=[{'file':n,'sha256':digest(ROOT/n),'bytes':(ROOT/n).stat().st_size} for n in sorted(names)]
source_id=hashlib.sha256(json.dumps(sources,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
old_by={s['file']:s for s in prior['sources']}
changes=[{'file':s['file'],'currentSha256':s['sha256'],'priorSha256':old_by.get(s['file'],{}).get('sha256'),'comparison':'changed' if s['file'] in old_by else 'added to selected inventory; prior absence is not a full repository history'} for s in sources if s['file'] not in old_by or old_by[s['file']]['sha256']!=s['sha256']]
builds=[]
for previous in prior['builds']:
    p=ROOT/previous['path']
    if p.is_file():builds.append({'name':previous['name'],'path':previous['path'],'sha256':digest(p),'bytes':p.stat().st_size});continue
    inventory=[{'file':f.relative_to(p).as_posix(),'sha256':digest(f),'bytes':f.stat().st_size} for f in sorted(p.rglob('*')) if f.is_file()]
    builds.append({'name':previous['name'],'path':previous['path'],'files':len(inventory),'totalBytes':sum(f['bytes'] for f in inventory),'sha256':hashlib.sha256(json.dumps(inventory,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),'inventory':inventory})
immutable={}
for name in ('V002__commercial_hardening.sql','V003__player_items.sql','V004__player_public_profile.sql'):
    candidates=list(ROOT.glob('RuoYi-Vue/ruoyi-admin/src/main/resources/boost/migrations/'+name))
    if not candidates: # V004 exact filename is taken from the preserved source inventory.
        candidates=list(ROOT.glob('RuoYi-Vue/ruoyi-admin/src/main/resources/boost/migrations/V004*.sql'))
    assert len(candidates)==1
    p=candidates[0];key=p.relative_to(ROOT).as_posix();same=digest(p)==old_by[key]['sha256'];assert same
    immutable[p.name]={'sha256':digest(p),'matchesPriorManifest':same}
write('最终版本清单.json',{'createdAt':now,'sourceId':source_id,'git':None,'gitReason':'本目录没有 Git；选定源码 SHA-256 清单用于复查，不宣称完整 Git 差异','priorSourceId':prior['sourceId'],'sources':sources,'builds':builds,'immutableMigrations':immutable,'selectedInventoryChanges':changes,'baselineScope':'本轮只留存4份修改前文件，其他差异仅按上一轮选定文件哈希比较；新增清单条目不等于能够证明此前文件不存在。'})

java_dir=OUT/'evidence/java-tests';java_dir.mkdir(parents=True,exist_ok=True)
totals=Counter();suite_counts={}
for p in (ROOT/'RuoYi-Vue/ruoyi-admin/target/surefire-reports').glob('TEST-com.ruoyi.boost.*.xml'):
    import xml.etree.ElementTree as ET
    suite=ET.parse(p).getroot();data={key:int(suite.attrib.get(key,0)) for key in ('tests','failures','errors','skipped')};totals.update(data);suite_counts[suite.attrib['name']]=data;shutil.copy2(p,java_dir/p.name)
assert totals==Counter(tests=267,failures=0,errors=0,skipped=0)
write('Java测试统计.json',{'time':now,'totals':dict(totals),'suites':suite_counts,'scope':'Actual surefire XML from final Maven package; 13 H2 + 17 MySQL reward tests are included in 267, not added again.'})

columns=['功能编号','操作入口','角色','预期业务结果','关联接口与数据','验证方式','当前状态','证据位置','边界或说明']
rows=list(csv.DictReader((OLD/'完整验收表.csv').open(encoding='utf-8-sig',newline='')))
def mapped(reference):
    reference=reference.strip()
    if not reference:return ''
    if (OUT/reference).exists():return reference
    if (OUT/'final'/reference).exists():return 'final/'+reference
    if (OLD/reference).exists():return '../player-profile-20261008/'+reference
    raise AssertionError('Missing inherited evidence '+reference)
for row in rows:
    row['证据位置']='；'.join(mapped(r) for r in row['证据位置'].split('；') if r.strip())
    row['边界或说明']=row['边界或说明'].replace('本轮237项','本轮267项').replace('完整入口第三次运行退出2','本轮完整入口实际退出2')
    if row['功能编号']=='P11A':row['关联接口与数据']='mysqldump、mysql restore；36张既有交易测试表（含新空奖励表）'
    if row['功能编号']=='P14':row['关联接口与数据']='生产数据库/媒体/配置/域名；V003/V004/V005协调升级、监控和回滚';row['证据位置']='部署与恢复方案.txt';row['边界或说明']='本轮无生产部署、迁移、体验版上传；需要协议版本更新、公开奖品图代理路由及持久媒体卷。'
    if row['功能编号']=='P12':row['证据位置']='final/secret-scan.json；after-tab-image/secret-scan.json；证据敏感信息检查.json'
    if row['功能编号']=='P10B':row['证据位置']='final/acceptance.json；补充检查.json';row['边界或说明']='完整入口17阶段已过，退出2；后台后续切页修复已lint/重建/SFC/真实闭环再验，未无意义重跑未受影响Java。'
    if row['功能编号']=='H01':row['边界或说明']='保留静态默认大图；新增后台活动封面作为图片直达入口另列R16。旧H5 Mock未模拟新活动接口。'
    if row['功能编号']=='P13':row['当前状态']='未测试';row['边界或说明']='本轮没有实际原生运行；使用H5和mp-weixin编译，不能宣称原生已通过。'
    if row['功能编号'] in ('H09','H10'):row['边界或说明']+='；所引独立头像before/after为前轮历史证据；本轮25组SFC和51项Mock H5再次覆盖，不称本轮新独立执行。'
    if row['功能编号']=='H11':row['证据位置']='final/profile-migration.json；final/profile-migration.log'
    if row['功能编号']=='P11':row['边界或说明']='V002/V003/V004哈希与上一轮一致；实际隔离升级含旧玩家资料保留；新V005另列R19。没有生产迁移。'

def add(n,entry,role,outcome,api,method,evidence,boundary='',status='已实测通过'):
    rows.append(dict(zip(columns,[n,entry,role,outcome,api,method,status,evidence,boundary])))
J='final/backend-all-tests-and-package.log；evidence/java-tests/TEST-com.ruoyi.boost.BoostRewardsTest.xml；evidence/java-tests/TEST-com.ruoyi.boost.BoostRewardsMySqlTest.xml'
B='after-tab-image/rewards-real-browser.json';S='after-tab-image/rewards-http-persistence.json';Q='after-tab-image/rewards-ui-races.json'
add('R01','后台→积分商品','read/manage运营','创建勋章/实物、编辑库存价格、上下架；刷新保留','/boost-admin/rewards/goods；reward_goods', '实际后台创建实物、API创建勋章、Java校验与状态',B+'；'+J,'不批量授予既有角色新权限；勋章代码/类型创建后固定')
add('R02','后台图片上传/公开商品图片','manage运营、访客','实际PNG/JPEG归一化保存；图库与勋章显示；非法路径/文件拒绝','/boost-admin/rewards/upload；/boost-reward-media/*.png','实际两次上传、7条解码证据、截图；Java图像边界',B+'；after-tab-image/rewards-mall-320.png；after-tab-image/rewards-public-badge.png；'+J,'测试奖品使用真实上传的黄色装备图占位；正式勋章/实物图片需运营上传。图片为公开素材，不能上传证件。')
add('R03','后台→免费积分发放','grant运营','有原因、唯一事件键、账本余额审计同时保存，不重复发放','points_account、points_entry、audit','实际后台200积分；同键/改内容/上限/停用Java回归',B+'；'+S+'；'+J,'仅免费，不购买/转让/提现；与钱包金额、成长值分离')
add('R04','后台发放失败/刷新/重登','grant运营','确定4xx可修正；网络/5xx重试保持同键；同账号重新登录恢复意图','localStorage按稳定adminId；requestKey/hash','实际SFC、HTTP/modal/session/storage替身',Q+'；independent-admin-grant-before.json；independent-admin-grant-after.json','此行不是浏览器网络断线或真实发放测试')
add('R05','商城/积分明细独立页面','玩家、访客','仅公开上架商品，分页；实际余额及明细刷新一致','GET rewards/goods、points','实际H5+HTTP+SQL；320/390截图',B+'；'+S+'；after-tab-image/rewards-mall-390.png','未登录可浏览公开内容，私有积分需登录；已登录用户消费积分不要求开店')
add('R06','商品页确认兑换','玩家','核对用户已见报价/版本，积分库存订单原子保存','POST goods/{id}/redeem；expectedPointsCost/revision','H2/MySQL报价变化409；实际实物兑换后余额190、库存2、订单1',J+'；'+B+'；'+S,'历史同键结果优先重放；价格改变不能悄悄多扣')
add('R07','勋章兑换→个人主页','玩家、访客','领取即拥有，公开仅代码/名称/图片/时间；重复拥有无扣分','member_badge、reward_order；GET badges/{id}','实际H5兑换/刷新/公开白名单/图像解码；Java重复持有',B+'；'+S+'；'+J+'；after-tab-image/rewards-public-badge.png','不把活动勋章当店铺审核或实名认证图标')
add('R08','实物兑换→收货信息','本人、外人','保存地址，刷新保留；外人GET/PUT404、匿名401','reward_order WAIT_ADDRESS→WAIT_SHIP','实际填写合成地址、HTTP越权拒绝、SQL核对',B+'；'+S,'合成测试信息，不是真实收件人；详情/履约私有，公开商品不暴露地址')
add('R09','后台发货→本人确认收货','fulfill运营、本人','待发货→已发货→已完成；物流/刷新保持，重复收货稳定','ship/receive；carrier/trackingNo/status/audit','实际后台与用户操作+SQL；Java重复/冲突回归',B+'；'+S+'；'+J+'；after-tab-image/rewards-order-completed.png','人工填写物流，未接物流查询服务、未实际寄包裹')
add('R10','兑换最后库存/并发提交','两个玩家、重试者','最后一件只一个成功；同键并发只一次扣分；部分失败整体回滚','member→account→goods锁；entry/order/badge事务','真实InnoDB并发及Spring事务、持久结果强断言',J,'不是容量/压测达标证明')
add('R11','发货与用户更新地址同时进行','本人、fulfill运营','外键相关锁顺序一致，不死锁，最终发货一次','member→order固定锁序','真实Spring/MySQL屏障测试；独立等价SQL前后探针另列边界',J+'；independent-backend-review-lock-probe.json','独立SQL不是应用层测试；正式应用层回归已实际执行')
add('R12','后台活动配置/编辑奖池','manage运营','开始结束/封面/积分/2～8格/权重校验，编辑版本递增','activities、prize；goods锁升序','实际后台新增与独立路径；Java校验和编辑',B+'；'+J,'不是通用活动CMS；当前为转盘积分活动，最新100个活动列表')
add('R13','不同活动抽取与反向奖池编辑','运营、参与玩家','共同奖品按id升序锁，避免FK锁环','activity→所有goods升序锁→prize','实际Spring/MySQL控制屏障及持久状态',J+'；independent-backend-review-lock-probe.json','不靠反复重跑碰巧通过')
add('R14','活动页单抽/五连','玩家','服务端随机决定结果，单抽cost，五连5×cost；重试同历史结果','SecureRandom、reward_draw、ledger、orders','实际H5单/五共6抽，扣12积分、同键HTTP重放；SQL',B+'；'+S+'；'+J,'规则仅免费积分；Java含五连中途奖池空整体回滚、库存/已有勋章过滤')
add('R15','奖池概率与历史记录','玩家','当前有效概率显示；每次结果保存标题/类型/权重/概率快照，编辑后仍可追溯','draw.results JSON；odds position/title/kind','Java奖池编辑后读取、实际历史刷新与SQL',J+'；'+B+'；'+S,'转盘格子仅展示，不声称格子面积就是概率；售罄/已拥有使后续概率变化')
add('R16','首页活动大图→单开活动页','访客、玩家','全图点击跳转?id，没有文字/CTA叠加；单开商城路径可进入','首页活动covers；pages/rewards/activity?id=…','实际活动图解码、等待轮播进入视口、点击真实路由',B,'默认大图保持；后台添加ACTIVE活动后自动进入首页轮播')
add('R17','后台JWT细分角色及撤权','read/manage、read/fulfill','权限集合准确，合法写前成功、同JWT撤权后同body403且无新增；地址与发放分权','getInfo；read/manage/grant/fulfill当前DB权限','真实登录/授权写入/撤权/数据总数；非权限Mock',B,'范围仅两种受限角色+隔离超级管理员，不能宣称所有组合')
add('R18','后台/用户切号、切页、晚到结果','不同账号','清空私有地址；确认前冻结内容；旧请求不能操作新账号或清理新意图；按钮恢复','SFC generation/mutationVersion/稳定身份storage','真实Vue组件受控替身4组；切页修复前失败/后通过',Q+'；independent-races-tab-before/rewards-ui-races.json；independent-races-tab-after/rewards-ui-races.json','前端替身与浏览器真实正常路径分开；未声称所有设备会话竞争')
add('R19','V004→V005/repeat迁移','运维','保留老资料/认证/钱包/成长；新增9表，迁移校验稳定','V005 additive migrations','真实MySQL旧数据升级；Java重复/校验回归','final/rewards-migration.json；'+J+'；最终版本清单.json','未执行生产迁移；已执行旧迁移脚本不可改')
add('R20','奖励数据库备份恢复','运维','账本/库存/勋章/抽奖快照/订单全表指纹和FK恢复一致','37表mysqldump/restore','实际隔离库dump到另一owned库恢复','final/rewards-backup-restore.json；final/rewards-backup-restore.log','含最小测试sys_user；不证明全部生产表/媒体/整站灾备')
add('R21','活动开关/过期/余额不足','玩家','关闭、过期、停用或不足不扣积分；同键已成功记录仍可重放','free-points-draw-enabled默认false；timewindow','Java负向及持久断言；隔离真实浏览器flag=true','final/backend-all-tests-and-package.log；after-tab-image/rewards-real-browser.json','真实生产开关未改；浏览器positive flag只用于隔离库')
add('R22','六个新页面及入口','访客、玩家','6个页面可单开；商城320/390及其他5页320无横溢；有加载/错误/空态反馈','mall/activity/item/orders/order/points、pages.json','商城320/390；其他5页320布局；详情/订单/活动390实际流程；SFC失败/会话；H5和MP构建',B+'；'+Q+'；final/mini-build.log','未覆盖6页×2宽度的全部12组合；订单列表/积分明细390未专门验收。针对性失败为SFC替身，不声称全部真实浏览器错误/空态或原生兼容。')
add('R23','新活动微信原生/体验版','微信用户','原生conic转盘/图片/缓存/跳转/登录及真机流畅','mp-weixin、真实域名/开发者工具','本轮未实际执行','final/mini-build.log','构建通过，未原生运行、未上传体验版，不能当真机证据',status='未测试')
add('R24','生产媒体卷与代理、协议告知','运维、用户','公开reward-media可读、管理上传代理正确；地址/积分用途写正式版本协议','Nginx、persist media/rewards、privacy version','只准备配置/方案，未生产联调','部署与恢复方案.txt','新增政策说明已经写入源码，正式版本号及重新同意策略未更新',status='未测试')
add('R25','实物寄送/物流真实环境','运营、收件人','按真实活动规则兑换，实际包裹与收货','承运方、真实物资与地址','合成地址状态测试不能替代实际寄送','','需运营确认配送范围/邮费/售后规则；当前只有人工录入物流',status='未测试')
add('R26','抽奖积分来源与发放运营规则','运营负责人','确定仅免费不可购买/提现或另有来源，再决定是否开启','规则确认；生产feature flag','已提问尚无回复','','仅免费积分实现，生产保持关闭；不存在购买积分入口',status='未测试')

# Fix evidence references that point at newly generated local package files.
allowed={'已实测通过','失败','未测试','环境阻塞','不适用'}
assert all(r['当前状态'] in allowed for r in rows)
with (OUT/'完整验收表.csv').open('w',encoding='utf-8-sig',newline='') as f:w=csv.DictWriter(f,fieldnames=columns);w.writeheader();w.writerows(rows)
write('完整验收表.json',rows)

issues=[
 ['F00','初始版本登录后奖励路由仍不存在','使用隔离管理员登录请求/boost-admin/rewards/goods','预期返回商品页；实际HTTP200业务code500 No static resource','初始JAR没有奖励模块','后台/活动/商城全部未实现','BoostRewards*.java、V005、rewards.vue、新6个页面','initial-captcha/initial-missing-rewards.json；'+B],
 ['F01','私有地址缓存串号风险','打开订单地址→切换用户','预期清空；旧页保留上一用户信息','异步/缓存页缺少同步清理与generation校验','兑换详情及收货确认','代练/pages/rewards/order.vue','independent-order-privacy-before.json；independent-order-privacy-after.json；'+Q],
 ['F02','重新登录后不确定兑换请求丢失幂等键','超时结果未确认→同账号重登→重试','预期沿用同键；原token身份改变导致新键','storage以会话token代替稳定memberId','兑换与单/五抽','代练/lib/rewards.js','independent-intent-session-before.json；independent-intent-session-after.json；'+Q],
 ['F03','积分发放重试与会话晚到结果','发放5xx→重载；或确认期间切号/改内容','预期保留意图/冻结目标；旧实现可重复/误提交或清新操作','缺少稳定持久意图、错误分类及每个await会话校验','后台免费积分账本','rewards.vue、src/utils/request.js','independent-admin-grant-before.json；independent-admin-grant-after.json；independent-admin-session-before.json；independent-admin-session-after.json；'+Q],
 ['F04','新报价可超出用户已见积分','用户查看→管理员改价→兑换/抽奖','预期409让用户重新核对；原实现按当前数据库价扣分','未带expectedPointsCost/revision','商品兑换与活动消费','BoostRewards.java、V005 revision、mini item/activity请求',''+J],
 ['F05','旧概率记录无法识别已删奖品','抽奖→编辑替换旧奖池→读取历史','预期历史标题/类型可追溯；仅旧prizeId不够','快照缺少结果映射字段','历史抽奖审计','BoostRewards.java draw odds snapshot',J],
 ['F06','运营旧库存覆盖并发扣减','打开编辑→另一玩家兑换→保存旧库存','预期409；原编辑会回写旧数','未携带expectedStock校验锁定值','库存一致性','BoostRewards.java、rewards.vue',J],
 ['F07','发货与地址更新、奖池编辑与抽取锁环','MySQL外键member/order、共享goods反向顺序的控制屏障','预期两个事务完成；独立等价SQL修复前死锁1213','显式锁顺序与外键隐式父行锁不一致','实物履约、并发抽取','BoostRewards.java ship/saveActivity固定锁顺序','independent-backend-review-lock-probe.json；'+J],
 ['F08','后台图片上传请求不是multipart','后台选择真实JPEG并上传','预期保存可显示PNG；实际Current request is not a multipart request','Axios默认JSON头作用于FormData','商品图片及活动封面','RuoYi-Vue3/src/api/rewards.js Content-Type由浏览器生成','targeted-real-upload-selector/rewards-real-browser.json；'+B],
 ['F09','切后台页签后按钮永久加载','发放等待响应→切页→响应成功','预期操作结束可再次点击；实际saving仍true','finally绑定会随列表切换变化的version','grant/ship及save旧会话清理','rewards.vue 独立mutationVersion','independent-admin-tab-before.json；independent-races-tab-before/rewards-ui-races.json；'+Q],
 ['E01','商品/勋章旧截图白图（证据问题）','页面文字出现后立即截图','预期截图显示已上传图；旧图仍在加载','脚本没有等待mini图像解码，不是已认定应用图像缺陷','展示验收证据','scripts/rewards-real-browser.cjs 解码和两绘制帧等待',B+'；after-tab-image/rewards-mall-320.png；after-tab-image/rewards-public-badge.png']
]
issue_columns=['编号','问题','复现步骤','预期与实际','根因','影响范围','修改位置','验证证据']
write('问题记录.json',[{**dict(zip(issue_columns,i)),'验证结果':'已实测通过（各行范围以对应证据为准）'} for i in issues])

post_checks=[{'name':'rewards-ui-races','command':'node --experimental-vm-modules scripts/test-rewards-ui-races.cjs','exitCode':0,'evidence':'after-tab-image/rewards-ui-races.log'},
 {'name':'admin-lint','command':'node node_modules/eslint/bin/eslint.js src/views/boost/rewards.vue','cwd':'RuoYi-Vue3','exitCode':0,'evidence':'after-tab-image/admin-lint.log'},
 {'name':'admin-build','command':'node node_modules/vite/bin/vite.js build','cwd':'RuoYi-Vue3','exitCode':0,'evidence':'after-tab-image/admin-build.log'},
 {'name':'rewards-real-browser-and-direct-SQL','command':'node scripts/run-player-http-isolated.cjs --rewards-only','exitCode':0,'evidence':'after-tab-image/real-browser-command.log','browserStartedAt':post['startedAt'],'browserFinishedAt':post['finishedAt'],'sqlObservedAt':read(OUT/'after-tab-image/rewards-http-persistence.json')['time'],'timingScope':'browser subprocess timestamps; SQL follows; wrapper start/end were not recorded separately'},
 {'name':'server-ports-cleanup','method':'Python socket.connect_ex against recorded three localhost Spring ports','status':'已实测通过','evidence':'after-tab-image/server-cleanup.json'},
 {'name':'secret-scan-after-admin-build','command':'python -X utf8 scripts/acceptance-secrets.py','exitCode':0,'evidence':'after-tab-image/secret-scan.json'}]
write('补充检查.json',{'createdAt':now,'sourceId':source_id,'reason':'完整入口之后独立复核发现后台切页busy缺陷，修复后重测受影响SFC/lint/admin构建/真实奖励闭环；补图像解码断言。不重跑未改Java/H5/MP。','checks':post_checks,'previousWholeCommand':'final/acceptance.json','wholeCommandExitCode':2,'scope':'Full checks plus affected post-fix checks; separate provenance, not a fabricated second full run'})

command=r"""$env:PLAYER_ACCEPTANCE_OUT=Join-Path (Get-Location) ('test-results/rewards-check-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\验收玩家交易.ps1"""
oneclick='''一键验收（在项目根目录执行）：
'''+command+'''

实际已经执行过相同入口：PLAYER_ACCEPTANCE_OUT=test-results/rewards-20261008/final；17阶段全部通过，进程退出2。之后后台切页缺陷修复的受影响检查另见补充检查.json，不伪称重新跑了整条入口。
前置：Node 24.19、Java17、Maven、Python及bcrypt/cryptography、MySQL8、Redis、本地安装好的三端依赖和Playwright Chromium。脚本优先使用当前用户运行时路径。受保护的.runtime/local-dev.json配置应指向可创建owned隔离库的本地测试MySQL与Redis；不是生产数据库。
准备：自动创建4个带唯一名字的owned测试库，另创建UI库与恢复库；业务初始为空或显式测试夹具。只读复制本地配置/一个管理员，生成测试账号密码仅存.runtime。真实实名/资金测试使用标记替身；新增奖励浏览器HTTP真实连接隔离Spring/MySQL。
成功判定：0是完整验收成功（当前必要外部条件没有满足，所以不会返回0）；1为本地失败；2为本地全部通过但必要外部项未完成，当前不满足所列范围的发布条件。应同时查看acceptance.json。不能将退出2报为整体发布通过。
清理：脚本finally停止Java/浏览器服务并登出生成会话。隔离数据库、备份dump、媒体保留便于复核，占用磁盘；不重置共享Redis，不删除共享库。清理隔离数据必须先按.runtime测试marker与严格库名核对，不能对本地业务库运行递归/批量删除。
快速受影响入口：node --experimental-vm-modules scripts/test-rewards-ui-races.cjs；需要PLAYER_ACCEPTANCE_OUT。真实奖励回归：设置JAVA_HOME及PLAYER_ACCEPTANCE_OUT后运行node scripts/run-player-http-isolated.cjs --rewards-only；依赖已构建JAR/H5/admin，退出0且浏览器12组和直接SQL通过。仅该隔离回归设置draw=true；生产开关不变。
'''
(OUT/'一键验收.txt').write_text(oneclick,encoding='utf-8')
deploy='''本轮未连接生产服务器、未生产迁移、未发布、未上传体验版。新前端不能直接连接旧后端。
发布前准备：确认免费积分来源与活动规则；正式隐私协议加入收件人/地址/免费积分/公开勋章用途，确定新版本号、生效日期及必要重新同意方式。会员/成长奖励和真实实名渠道仍有独立阻塞；不因新增积分模块而打开支付。
建立同一版本清单：后端JAR、管理端、H5/小程序及菜单/迁移文件配套。V002/V003/V004不可修改；V005新增9表，需在完整生产副本及所有实际版本上验证后再迁移。分开权限read/manage/grant/fulfill，按实际角色授权，不能只加菜单就授予所有人发积分/看地址。
备份与恢复：发布前保存应用/前端/配置版本、完整生产数据库一致性备份和全部媒体卷（包含rewards子目录），记录哈希、备份时间、恢复点。现有37表恢复只针对隔离奖励测试库，不代表已完成整站生产恢复。
代理与持久化：/boost-reward-media/公开PNG映射到后端公开读路由；/boost-admin/rewards/upload需鉴权且支持multipart大小；boost.media-dir持久卷应跨重启保留/rewards图片。当前真实浏览器用透明localhost中继证明应用路由，未验证真实Nginx。
上线顺序：有具体版本/备份/恢复方案后取得必要生产授权→冻结相关写入→副本恢复核验→添加迁移/菜单→部署同版本后端及前端→检查API与公开图片、权限、到账与日志→原生/真机验收→按确认规则决定抽奖开关。默认BOOST_FREE_POINTS_DRAW_ENABLED=false、支付false。
回滚：停止新奖励写入，保留新增表及日志，不删除V005；恢复匹配旧应用/前端版本。旧应用对新增表的兼容只在旧测试范围有证据，不能承诺自动降级。若必须恢复数据库快照，需要先核对发布后写入和用户影响、备份新增事件并取得明确授权，不能丢弃已发积分/订单。
观测：核对积分账本和账户余额、库存不负、重复键冲突、429、活动时间/概率、发货审计；避免记录实名材料、收货地址或JWT。容量目标/监控告警/恢复时长尚未实测。
'''
(OUT/'部署与恢复方案.txt').write_text(deploy,encoding='utf-8')

human=[{'事项':'积分来源和活动规则','为什么需要你':'业务决定，尚未回复先前集中问题；生产开关因此保持关闭','具体操作':'确认是否仅免费、不可购买/转让/提现，说明哪些行为发积分；提供活动消耗/奖品/库存规则','预期结果':'按确认规则补齐运营配置后决定是否开启；若涉及付费来源须重新设计并验收，不能直接开当前开关'},
 {'事项':'会员/成长和真实实名服务','为什么需要你':'原三端核心发布需要真实实名；开通方式/权益/成长阈值及服务商仍未确定','具体操作':'一次提供开通方式、权益、成长事件和等级阈值，以及实名服务商及安全提供凭据的渠道','预期结果':'完成真实集成及会员成长闭环，而不是显示固定进度'},
 {'事项':'原生/真机体验','为什么需要你':'本轮未实际运行原生环境；H5无法证明微信SDK及相册/设备行为','具体操作':'协调新前后端部署和原生验收后，再在微信检查新独立活动/兑换/资料页面；主观视觉与交互体验由你判断','预期结果':'图像、单开路径、登录、转盘、持久缓存和布局正常；不重复已有H5机械检查。可用开发工具中的自动化由执行者继续完成，不能只让用户代做'},
 {'事项':'真实履约运营资料','为什么需要你':'真实物资/承运方、配送邮费和售后规则属于运营决策','具体操作':'后台上传实际勋章/实物图，确认库存、地址范围、物流填写与退换规则','预期结果':'实物承诺可履行；不把合成地址状态测试当实际发货'}]
counts=dict(Counter(r['当前状态'] for r in rows))
report={'createdAt':now,'conclusion':'当前版本在本地所列验收范围内通过；当前不满足所列范围的发布条件','sourceId':source_id,'scope':'新增免费积分/转盘单抽五连/勋章及实物商城/后台履约，保留玩家道具交易与主页规则；仅本地隔离Spring/MySQL、生产构建H5/admin、H2/替身和编译。未生产部署、体验版上传、原生/真机/实名渠道或真实包裹。','fullCommand':full,'postChecks':'补充检查.json','metrics':{'fullLocalPhases':len(full['checks']),'java':dict(totals),'newRewardJavaIncluded':30,'rewardSfcGroups':4,'realRewardsBrowserGroups':len(post['checks']),'decodedImages':len(post['decodedImages']),'acceptanceRows':len(rows),'statuses':counts},'issues':'问题记录.json','acceptance':'完整验收表.csv','humanItems':human,'remaining':'已有真实实名/会员成长/原生验收阻塞；新增积分规则确认、生产代理/媒体/正式隐私协议和运营实物履约未验证。没有新未修的已复现重要缺陷。','independentReview':'independent-final-review.txt','verificationBoundaries':'H2/身份付款替身、SFC/Mock H5、实际HTTP/MySQL浏览器、原生/真机各自分开。267通过不是业务覆盖率、容量、全设备兼容或商用成熟度。','nonReproducibleFixtureAttempt':'曾发生一次外键元数据JSON截断，原根因未再复现；fixture改为SQL HEX传输并校验完整FK，不吞解析错误。原日志保留。测试选择器/测试输入和错误cwd等准备失败不计为产品修复。'}
write('验收报告.json',report)
def esc(value):return html.escape(str(value))
def link(ref,label=None):return '<a href="'+quote(ref.replace('\\','/'),safe='/.-')+'">'+esc(label or ref)+'</a>'
def evidence(refs):return '<br>'.join(link(r) for r in refs.split('；') if r)
table=''.join('<tr>'+''.join('<td>'+ (evidence(r[c]) if c=='证据位置' else esc(r[c]))+'</td>' for c in columns)+'</tr>' for r in rows)
fixes=''.join('<article><h3>'+esc(i[0]+' · '+i[1])+'</h3><p>'+esc('复现：'+i[2]+'；'+i[3])+'</p><p>'+esc('根因：'+i[4]+'；影响：'+i[5]+'；修改：'+i[6])+'</p><p>实际验证：'+evidence(i[7])+'</p></article>' for i in issues)
humans=''.join('<li><b>'+esc(h['事项'])+'</b> — '+esc(h['为什么需要你'])+'。'+esc(h['具体操作'])+'。预期：'+esc(h['预期结果'])+'</li>' for h in human)
checks=''.join('<tr><td>'+esc(c['name'])+'</td><td>'+esc(c['status'])+'</td><td>'+esc(c['exitCode'])+'</td><td>'+link(c['evidence'].replace('\\','/').removeprefix('test-results/rewards-20261008/'))+'</td></tr>' for c in full['checks'])
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>积分活动与商城 · 验收报告</title><style>body{margin:0;background:#f6f7fa;color:#18212b;font:15px/1.75 "Microsoft YaHei",sans-serif}main{max-width:1220px;margin:0 auto;padding:40px 28px}h1{font-size:32px;line-height:1.3}h2{margin-top:42px;font-size:22px}h3{font-size:17px}a{color:#2362ad;overflow-wrap:anywhere}code,pre{background:#eef1f5;overflow-wrap:anywhere}pre{white-space:pre-wrap;padding:16px}article,.panel{background:white;border:1px solid #e0e4eb;border-radius:10px;padding:18px 22px;margin:15px 0}article p{margin:6px 0}.banner{border-left:5px solid #d78b18;background:#fff8e9;padding:20px}.muted{color:#647080;font-size:13px}.metrics{display:flex;gap:12px;flex-wrap:wrap}.metrics div{background:#fff;padding:14px 24px;border:1px solid #e0e4eb;border-radius:8px}.metrics b{display:block;font-size:27px}.scroll{overflow:auto}table{border-collapse:collapse;min-width:1050px;background:#fff;width:100%;font-size:12px}td,th{padding:10px;border:1px solid #e1e6ed;text-align:left;vertical-align:top}th{background:#eaf0f6}td:nth-child(1){white-space:nowrap}img{max-width:100%;height:auto}.screens{display:flex;gap:18px;align-items:flex-start;flex-wrap:wrap}.screens figure{margin:0;width:260px}.screens img{border:1px solid #dce1e8;border-radius:8px}.screens figcaption{font-size:12px}li{margin:12px 0}@media(max-width:650px){main{padding:24px 16px}h1{font-size:26px}}</style><main>'''
page+='<p class="muted">2026-10-08 · 本地隔离验收 · 时间以日志UTC为准</p><h1>积分活动与商城验收</h1><div class="banner"><b>当前版本在本地所列验收范围内通过。当前不满足所列范围的发布条件。</b><p>免费积分账本、单抽／五连活动、勋章与实物兑换、后台添加与发货已实现。未部署、未上传体验版。积分来源尚待确认，生产抽奖默认关闭；真实实名、会员成长和原生环境仍未完成。</p></div>'
page+='<p class="muted">源码标识 '+esc(source_id)+'<br>没有Git提交；'+link('最终版本清单.json','源码与四类产物SHA-256清单')+'</p><div class="metrics">'+''.join('<div><b>'+str(n)+'</b>'+esc(t)+'</div>' for n,t in [(267,'Java测试，含新增30项'),(12,'实际奖励浏览器业务组'),(4,'SFC会话回归组'),(len(rows),'功能验收条目')])+'</div>'
page+='<h2>需要确认的事项与剩余阻塞</h2><ul>'+humans+'</ul><p>会员成长仅基础模型；免费活动积分不代替成长值。发布出售／求购仍要求店铺与可信实名。此次不出售账号、不开支付。</p><p>'+link('部署与恢复方案.txt','部署、备份与恢复准备')+' · '+link('independent-final-review.txt','独立最终复核记录')+' · '+link('independent-review.txt','独立复现与修复记录')+'</p>'
page+='<h2>实际页面证据</h2><div class="screens">'+''.join('<figure><a href="'+quote('after-tab-image/'+f)+'"><img src="'+quote('after-tab-image/'+f)+'" alt="'+esc(title)+'"></a><figcaption>'+esc(title)+'</figcaption></figure>' for f,title in [('rewards-mall-320.png','320px商城，实际上传商品图已解码'),('rewards-public-badge.png','兑换勋章后刷新个人主页'),('rewards-roulette.png','服务端结果、奖池概率及历史入口')])+'</div><p class="muted">奖品素材和收货资料为隔离测试数据；正常页面通过生产构建H5透明中继连接实际Spring/MySQL。仅SDK阴影图用1px替身，不替换奖励API。H5不是微信原生或真机。</p>'
page+='<h2>修复与复现记录</h2>'+fixes
page+='<h2>命令、结果与证据</h2><p>整条入口于'+esc(full['startedAt'])+'～'+esc(full['finishedAt'])+'执行，'+str(len(full['checks']))+'阶段均通过，退出2。之后独立复核发现切页busy问题，已重建后台并重跑受影响检查；Java/H5/MP未改，沿用本次完整运行证据，未伪称第二次整套执行。</p><p>'+link('final/acceptance.json','整套实际命令、时间、退出码')+' · '+link('补充检查.json','修复后的补充检查')+' · '+link('Java测试统计.json','原始XML统计')+' · '+link('after-tab-image/rewards-http-persistence.json','实际数据库结果')+' · '+link('证据敏感信息检查.json','证据敏感值扫描')+'</p><div class="scroll"><table><tr><th>检查</th><th>状态</th><th>退出码</th><th>日志</th></tr>'+checks+'</table></div><p class="muted">Java 267/0失败/0错误/0跳过。25组既有SFC、51项Mock H5、8项真实玩家HTTP、6项真实资料H5、5项实际后台与4项真实受限店铺JWT沿用本轮final证据。真实奖励12组及4组新SFC以after-tab-image为准。数量不等于业务覆盖率。真实容量、全设备兼容、生产灾备未证明。</p>'
page+='<h2>一键复查</h2><pre>'+esc(command)+'</pre><p>'+link('一键验收.txt','前置条件、测试数据、清理和成功标准')+'</p><p>退出1＝本地失败；退出2＝本地通过且外部必要项未完成。当前入口应返回2，不能显示整体发布通过。每次使用新的输出目录，避免覆盖历史证据。</p>'
page+='<h2>完整验收表</h2><p>'+link('完整验收表.csv','下载CSV')+' · '+link('完整验收表.json','结构化验收表')+'</p><p>'+esc('；'.join(k+' '+str(v) for k,v in counts.items()))+'。已实测通过只针对每行写明的方法和环境。</p><div class="scroll"><table><thead><tr>'+''.join('<th>'+esc(c)+'</th>' for c in columns)+'</tr></thead><tbody>'+table+'</tbody></table></div><h2>证据边界</h2><p>'+esc(report['verificationBoundaries'])+'</p><p>'+esc(report['nonReproducibleFixtureAttempt'])+'</p><p>完整数据库恢复分别覆盖36张既有交易测试表、37张奖励测试表（含最小sys_user）；均未备份媒体或生产完整系统。自审与独立代理复核分开，独立报告中的等价SQL和SFC替身不冒称应用真实HTTP。</p></main></html>'
(OUT/'验收报告.html').write_text(page,encoding='utf-8')
print(json.dumps({'sourceId':source_id,'sources':len(sources),'rows':len(rows),'statuses':counts,'java':dict(totals),'builds':[{k:v for k,v in b.items() if k!='inventory'} for b in builds]},ensure_ascii=False))
