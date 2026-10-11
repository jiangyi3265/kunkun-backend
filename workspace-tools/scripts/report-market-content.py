"""Assemble actual evidence; fail if any required final check did not pass."""
from pathlib import Path
import json,hashlib,zipfile,html,datetime
root=Path(__file__).resolve().parents[1];out=root/'test-results/market-content-20261009'
load=lambda p:json.loads((out/p).read_text('utf-8'))
meta=load('release-package.json');integration=load('final-r2/acceptance-run.json');browser=load('final-r2/http-browser.json');native=load('final-r2/native-content.json');live=load('live-content-verification.json');native_live=load('native-live/native-content-live.json');upload=load('upload.json');recovery=load('checkout-recovery.json')
for report in [integration,browser,native,live,native_live,upload,recovery]:assert report['exitCode']==0
assert hashlib.sha256((root/'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar').read_bytes()).hexdigest()==meta['backendSha256']==integration['jarSha256']
assert hashlib.sha256((root/'代练/pages/checkout/index.vue').read_bytes()).hexdigest()==recovery['sourceSha256']
assert 'Tests run: 286, Failures: 0, Errors: 0, Skipped: 0' in (out/'backend-all-mysql.log').read_text('utf-8',errors='replace')
assert load('secret-scan.json')['passed']
deployment=root/'test-results/interconnect-20261008/deployment'/meta['version']
for action in ['prepare','activate']:assert json.loads((deployment/(action+'.json')).read_text('utf-8'))['exitCode']==0
server_run=load('server-verification-command.log');assert server_run['exitCode']==0
source=Path(server_run['report']);server=json.loads(source.read_text('utf-8'));assert server['exitCode']==0
(out/'server-verification.json').write_bytes(source.read_bytes())
with zipfile.ZipFile(out/'source-before.zip') as old:
    before={n:hashlib.sha256(old.read(n)).hexdigest() for n in old.namelist()}
folders=['代练/components','代练/pages','代练/lib','RuoYi-Vue3/src/views/boost','RuoYi-Vue3/src/api','RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/boost','RuoYi-Vue/ruoyi-admin/src/main/resources/boost','RuoYi-Vue/ruoyi-admin/src/test/java/com/ruoyi/boost','RuoYi-Vue/sql','deploy']
files={p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for folder in folders for p in (root/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
for name in ['代练/pages.json','PRODUCT.md']:files[name]=hashlib.sha256((root/name).read_bytes()).hexdigest()
diff={n:{'before':before.get(n),'after':h} for n,h in files.items() if n not in before or before[n]!=h}
snapshot={'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'release':meta['version'],'backendSha256':meta['backendSha256'],'archiveSha256':meta['archiveSha256'],'git':'工作目录无 Git；以原始源码快照、当前文件哈希和不可变发布包标识版本','note':'before=null 表示初始快照未覆盖该文件或文件为本轮新增，不据此断言全为新增','changes':diff,'sourceFiles':files}
(out/'source-version.json').write_text(json.dumps(snapshot,ensure_ascii=False,indent=2),'utf-8')
rows=[
('UI-01','我的订单','买家/卖家','买卖标签及状态文字居中；320/390 无横向溢出','订单页面 CSS / GET /boost/orders','实际浏览器＋截图','已实测通过','final-r2/orders-390.png'),
('PUB-01','出售/发布求购','双认证用户','IOS-QQ / IOS-微信 / 安卓QQ / 安卓微信 / 全平台；非法混搭拒绝','listing.zone/platform / POST /boost/listings','真实 HTTP 五组＋错误输入＋三端构建','已实测通过','final-r2/http-browser.json'),
('PUB-02','发布与编辑','双认证用户','道具介绍＋详情图；取消新发布交付约定/求购说明；编辑保留图片','listing.detail_images / PublishForm','浏览器上传发布、重新进入编辑；单元验证图片权限','已实测通过','final-r2/publish-edit.png'),
('TRADE-01','商品→确认下单→订单','买家','单价12.34×2件=24.68；订单快照刷新保持；旧订单默认LOT','listing/order pricing_mode、unit_price_cents、quantity、detail_images','真实页面/HTTP/MySQL＋旧业务回归','已实测通过','final-r2/order-quantity.png'),
('TRADE-02','下单/取消/付款处理','买家/卖家','部分库存预订、重复请求幂等、并发不超卖、取消释放；下架不误上架','BoostItemStock / BoostTrade / BoostPayments','H2+MySQL集成/锁竞争；真实HTTP取消','已实测通过','backend-all-mysql.log'),
('TRADE-03','下单响应丢失后重入','买家','部分库存或售罄均恢复原意图；切换账号不能重发前账号意图','checkout / requestKey / purchasePayload','真实Vue SFC＋受控网络替身；3项','已实测通过','checkout-recovery.json'),
('ADMIN-01','后台菜单与概览','管理员/受限运营','内容与活动入口可见；只读不能编辑、仅用户权限不能访问内容','sys_menu / boost:content:read/manage','隔离权限矩阵＋线上真实CAPTCHA登录回读','已实测通过','live-content-verification.json'),
('CMS-01','后台内容管理→首页','内容运营/所有用户','修改首页文案、大图及点击目标；个人页横幅配置；保存冲突拒绝','boost_site_content / /boost-admin/content/site','后台UI上传保存刷新→H5读取→点击跳转；服务测试','已实测通过','final-r2/admin-content.png'),
('CMS-02','图文活动→独立详情→转盘','内容运营/所有用户','最新在上、历史在下；草稿/下架不公开；仅开放活动可进入关联转盘','boost_content_activity / /boost/content/activities','后台UI真实发布＋H5/官方Devtools＋权限服务测试','已实测通过','final-r2/native-content.json'),
('MEDIA-01','上传/订单详情图','发布者/买家/非参与者','图片所有权、公私权限、订单快照保留及清理引用','BoostMediaFiles / listing、order detail_images','Java回归＋真实浏览器解码＋官方原生解码','已实测通过','backend-all-mysql.log'),
('REL-01','构建/迁移/部署','维护者','三端构建；备份可恢复、V007重跑不改旧字段；线上字节匹配','V007 / deploy updater / release archive','全量Java286；隔离恢复＋实际升级＋17项部署回读','已实测通过','server-verification.json'),
('WX-01','官方开发者工具/上传','测试者','实际发布包合法域名请求可用；活动页不空白；开发版本已上传','AppID / 微信CLI / HTTPS API','官方隔离Devtools3项＋线上Devtools2项＋CLI上传','已实测通过','upload.json'),
('WX-02','体验版手机打开','体验成员','选择1.6.1009.1体验版后，手机验证页面、相册和长图','微信公众平台 / 手机微信','当前工具不能操作该管理页；无实体手机','未测试','操作说明见下文'),
('KYC-01','真实实名认证','普通发布用户','真实实名通过后才允许发布','腾讯实名渠道 / realnameReady=false','隔离认证资料仅合成测试；线上未配真实渠道','环境阻塞','server-verification.json'),
('PAY-01','真实支付','交易用户','本阶段保持关闭','paymentsEnabled=false / simulatedPayment=false','用户明确暂不接入真实支付','不适用','server-verification.json')]
acceptance=[dict(zip(['id','entry','role','expected','data','method','status','evidence'],r)) for r in rows]
(out/'acceptance.json').write_text(json.dumps(acceptance,ensure_ascii=False,indent=2),'utf-8')
review={'reviewer':'/root/quantity_content_review','mode':'独立只读源码与证据复核，未执行部署','findings':['订单详情图快照未展示','活动复制路径前缀不一致','库存变化阻断超时恢复','恢复意图跨账号','重复升级代理断言会卡维护状态'],'resolution':'以上均修复，复核者最后确认未发现剩余阻断问题；本地与线上执行证据由主代理完成','evidence':['checkout-recovery.json','upgrade-proxy-regression.json','backend-all-mysql.log']}
(out/'independent-review.json').write_text(json.dumps(review,ensure_ascii=False,indent=2),'utf-8')
esc=html.escape
def link(p,label=None):
    return '<a href="'+esc(p,quote=True)+'">'+esc(label or p)+'</a>'
table='<table><thead><tr>'+''.join('<th>'+x+'</th>' for x in ['编号','入口/角色','预期业务结果','接口/数据','验证方式','状态','证据'])+'</tr></thead><tbody>'
for r in rows:table+='<tr>'+''.join('<td>'+v+'</td>' for v in [esc(r[0]),esc(r[1]+' / '+r[2]),esc(r[3]),esc(r[4]),esc(r[5]),'<strong>'+esc(r[6])+'</strong>',link(r[7]) if '.' in r[7] else esc(r[7])])+'</tr>'
table+='</tbody></table>'
fixes=[
('整批价格无法区分件数','发布固定总价，买家无法选择部分数量；现改为UNIT单价和数量，旧数据保留LOT','新库存预订/售出计数、订单单价/图片快照、幂等购买摘要；卖家商品卡、管理页、求购广场均标明每件或整批','BoostMarket.java、BoostTrade.java、BoostItemStock.java、V007、PublishForm.vue、checkout/index.vue'),
('详情图片和区服字段缺失','新发布用文本交付条款，缺少组合区服和详情图','五种组合选择；保存与编辑详情图片；订单和确认页可预览购买时图片','playerTrade.js、PublishForm.vue、service/detail.vue、orders/detail.vue、BoostMediaFiles.java'),
('后台内容与图文活动入口缺失','现有侧栏为可展开分组，原账号实际有9个业务入口；原系统未提供本轮图文CMS','新增内容与活动页面及权限；概览直达按钮；复制活动目标格式与首页大图一致','BoostContent.java、BoostContentController.java、content.vue、ContentImages.vue、boost-content-menu.sql'),
('网络超时后不能恢复／跨账号重发','库存被原订单预订后可售数变少，旧校验拒绝恢复；未限定原身份','优先恢复原requestKey与冻结参数；token改变清空确认和意图，旧响应不得作用于新账号','checkout/index.vue；3项受控SFC回归'),
('重复升级可能停在维护状态','旧升级脚本只接受尚未添加reward-media代理的配置；开站后边界禁止自动回滚','维护前生成并核对候选代理配置，支持旧/已升级状态；实际V007升级成功','deploy/upgrade-player-test.py；upgrade-proxy-regression.json；实际prepare/activate证据')]
fixhtml=''.join('<li><b>'+esc(a)+'</b><p>复现/根因：'+esc(b)+'。修复：'+esc(c)+'。位置：'+esc(d)+'。</p></li>' for a,b,c,d in fixes)
commands=[('完整后端测试及打包（配置四个隔离MySQL库后）','mvn -pl ruoyi-admin -am package','backend-all-mysql.log'),('生产H5、小程序、后台构建','node scripts/build-release.cjs','build-final-r2.log'),('浏览器、HTTP/MySQL、官方原生与持久化闭环',"$env:MARKET_CONTENT_NATIVE='1'; node scripts/accept-market-content.cjs",'final-r2/acceptance-run.json'),('超时/跨账号恢复回归','node --experimental-vm-modules scripts/test-unit-checkout-recovery.cjs','checkout-recovery.json'),('当前代理配置回归','python scripts/test-content-upgrade-proxy.py','upgrade-proxy-regression.json'),('真实部署字节/迁移验证','python scripts/verify-current-server.py test-results/market-content-20261009/release-package.json','server-verification.json'),('线上页面与后台读回','node scripts/verify-market-content-live.cjs','live-content-verification.json'),('官方实际上传包＋线上HTTPS','node scripts/verify-market-content-native-live.cjs','native-live/native-content-live.json'),('官方上传','node scripts/upload-market-content.cjs','upload.json')]
cmdhtml=''.join('<tr><td>'+esc(a)+'</td><td><code>'+esc(b)+'</code></td><td>0</td><td>'+link(c)+'</td></tr>' for a,b,c in commands)
doc=f'''<!doctype html><html lang="zh"><meta charset="utf-8"><title>困困 · 按件交易与图文活动验收</title><style>body{{font:15px/1.75 system-ui,sans-serif;color:#17202b;max-width:1300px;margin:40px auto;padding:0 24px;background:#fafbfc}}h1,h2{{line-height:1.3}}h2{{margin-top:38px}}table{{border-collapse:collapse;width:100%;font-size:13px;background:white}}td,th{{border:1px solid #dce1e8;padding:9px;text-align:left;vertical-align:top}}th{{background:#fff1a0}}code{{overflow-wrap:anywhere;background:#edf0f4;padding:2px 5px}}a{{color:#155aa8}}.notice{{padding:18px;background:#fff3bf;border-radius:10px}}img{{max-width:100%;height:auto;border:1px solid #ddd}}.shots{{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}}li p{{margin:4px 0 18px}}small{{color:#596477}}</style>
<h1>困困 · 本轮按件交易与图文活动验收</h1><p>生成时间：{esc(snapshot['time'])}。全部时间证据为 UTC 或日志注明的本机时区。</p>
<div class="notice"><b>本轮版本在所列隔离环境、官方开发者工具和线上测试站的验收范围内通过。</b><br>后台、H5、服务端已部署：<code>{meta['version']}</code>；小程序开发版 <b>{upload['version']}</b> 官方上传成功。体验版选版及实体手机未确认。<br><b>全量商用：当前不满足所列范围的发布条件。</b>真实实名渠道尚未配置，体验版真机未测试；真实支付按要求关闭。测试通过数量不代表整体业务覆盖率或商用成熟度。</div>
<h2>你现在如何操作后台</h2><ol><li>打开 <a href="https://ceshijjht.oksja.cn/boost/content">后台 → 内容管理</a>。若仍停留在旧侧栏，刷新页面或重新登录，展开“玩家道具交易”。概览也新增了直达按钮。</li><li><b>更换文字和大图：</b>内容管理可修改首页名称、说明、搜索提示、推荐标题及个人页横幅文字；“首页轮播大图”上传1–8张图，设置“点击后页面”。前端整图点击，不叠加按钮或文案。支持个人页横幅整图。不是所有页面静态文字都开放为配置。</li><li><b>发布图文活动：</b><a href="https://ceshijjht.oksja.cn/boost/activities">图文活动</a> → 发布图文活动 → 填标题/摘要/正文、上传封面和详情图、设置起止时间 → 选择“发布” → 保存。进行中与即将开始显示在最新活动，结束后自动进入历史活动。选择“下架”则不再公开，草稿仅后台可见。</li><li><b>关联抽奖：</b>先在 <a href="https://ceshijjht.oksja.cn/boost/rewards">积分与活动</a> 配置转盘和奖品，再把转盘ID填到图文活动“关联转盘”。关联后，开放期间详情页显示“参加积分转盘”；未开始、已结束或转盘不可用时按钮不可用。</li><li><b>首页图跳活动：</b>活动列表“复制路径”获得 <code>content/activity?id=活动ID</code>，粘到大图“点击后页面”。活动列表目标为 <code>content/activities</code>；其他支持目标在表单下方列出。小程序独立详情路由为 <code>/pages/content/activity?id=活动ID</code>。运营输入框使用不带 <code>/pages/</code> 的目标。</li></ol><p>线上当前没有新建测试图文活动，空状态属实。测试文章、奖品、用户和合成认证仅在隔离库。</p>
<h2>本轮修复结果</h2><ul>{fixhtml}</ul><p>订单页标签文字居中；新发布删除交付约定、求购说明输入，详情图片可包含这些介绍。旧订单原有文字和金额语义仍保留。支付关闭时可创建/取消待付款订单，不能将此认作真实付款交付闭环。</p>
<h2>完整验收表</h2>{table}
<h2>测试证据与版本</h2><p>Java全量 <b>286 / 286，通过，零跳过</b>；真实H5/后台/HTTP/MySQL <b>10组</b>；官方隔离Devtools <b>3组</b>；官方上传包线上HTTPS <b>2组</b>；下单恢复SFC替身 <b>3组</b>。不同验证类型分别计数，不相互替代。</p><p>源码标识见 {link('source-version.json')}，修改前快照 {link('source-before.zip')}。后端SHA256：<code>{meta['backendSha256']}</code>；完整发布包SHA256：<code>{meta['archiveSha256']}</code>。线上H5、后台所有文件和JAR与包逐字节一致，V002–V007迁移校验一致。</p><table><tr><th>检查</th><th>实际命令</th><th>退出码</th><th>日志/时间/结果</th></tr>{cmdhtml}</table><p>静态检查：{link('mini-lint.exit')}、{link('admin-lint-r2.exit')}；敏感值扫描：{link('secret-scan.json')}。实际MySQL快照：{link('final-r2/persistence.log')}。独立复核：{link('independent-review.json')}。</p>
<h2>一键业务验收与环境准备</h2><p>在项目根目录 PowerShell 执行：</p><pre><code>$env:MARKET_CONTENT_NATIVE='1'; node scripts/accept-market-content.cjs</code></pre><p>该入口已实际运行成功。前置：Node、Java17、Python314、本地MySQL/Redis可用；<code>.runtime/local-dev.json</code> 已配置；已完成后端打包和生产后台构建；微信开发者工具已登录且CLI端口9420可用。入口创建独立带标记的测试库、随机测试账号及合成认证，启动本地后端，编译直连隔离API的H5和原生副本，后台构建复制到固定测试目录，然后执行上传、发布、下单、取消、活动及原生页面检查。生产小程序合法域名校验保持开启，仅隔离副本关闭。</p><p>成功判定：进程退出0，<code>acceptance-run.json</code>、HTTP/browser及native结果均为“已实测通过”，并有MySQL持久化结果。必要检查失败则入口非0；不把环境阻塞写成成功。Java286项和生产构建另见上表，业务入口不替代它们。</p><p>清理：测试服务结束时停止，账号会话退出；证据及最终隔离库保留供复核。清理单个隔离库用 <code>python scripts/cleanup-market-content-fixture.py 测试目录/acceptance-run.json</code>，校验本机端口已停止、库名和所有权标记，保留证据/图片文件。已在早期失败用例隔离库验证：{link('cleanup-check-r2.log')}。不清理共享库、线上库或用户文件。</p>
<h2>迁移、恢复与发布</h2><p>准备阶段将线上快照加密、恢复到独立库、连续执行两次V007，核对旧列和行指纹及媒体清单；实际切换前再次维护、停服务、备份和恢复校验，迁移后切换不可变包、健康检查，再开放。未覆盖用户已有数据或角色授权。开放新流量后不自动回滚数据库，避免覆盖新写入。</p><p>{link('deploy-prepare-command.log','准备结果')} · {link('deploy-activate-command.log','激活结果')} · {link('server-verification.json','最终线上核对')} · {link('upload.json','官方上传回执')}。加密备份位于服务器 <code>/srv/kunkun/test/upgrades/{meta['version']}/</code>。</p>
<h2>初始失败与纠正记录</h2><p>新增服务测试发现图文详情图返回字符串，无法直接回填，已统一为数组；完整回归286项通过。独立复核找到快照展示、链接格式、超时恢复、跨账号及重复升级共5项，修复后再次复核无剩余阻断。早期联调还修正了测试账号密码长度、Playwright转发multipart丢失文件内容、uni-button/Element Plus点击目标、测试期间构建目录被重建的问题；最终改为直连隔离API与固定后台副本，无重试忽略、无断言弱化。早期失败日志保留在 isolated-r1…r9、final，最终有效证据在 final-r2。</p>
<h2>最少人工项与剩余阻塞</h2><ol><li><b>体验版和真机：</b>当前工具不能操作微信公众平台管理页，也无实体手机。管理员在“版本管理→开发版本”将 <b>1.6.1009.1</b> 设为体验版；手机重新进入，验证五种区服、相册上传长图、活动详情与链接。预期无空白、长图完整、图片点击跳转正确。自动化已验证浏览器及官方Devtools，不要求重复这些机械检查。</li><li><b>真实实名渠道：</b>线上 <code>realnameReady=false</code>；需补齐真实实名服务配置后联调真实核验。未认证用户仍无法发布，本轮没有放开认证权限。隔离合成认证不能证明真实实名成功。</li><li><b>运营内容判断：</b>运营者选择正式图片、文案、活动时间、奖品及免费积分规则；后台现在可自行维护。当前协议仍是内部测试草案，商用前需核定真实运营资料。支付仍关闭，不在本轮接入。</li></ol>
<h2>可视结果</h2><div class="shots"><div><img src="final-r2/publish-edit.png"><p>发布/编辑</p></div><div><img src="final-r2/orders-390.png"><p>订单居中与数量</p></div><div><img src="final-r2/activities.png"><p>最新/历史活动</p></div></div><p><img src="live-admin-content.png"><br>线上真实后台内容管理</p></html>'''
(out/'验收报告与后台操作说明.html').write_text(doc,'utf-8')
receipt={'time':snapshot['time'],'version':meta['version'],'miniVersion':upload['version'],'localStatus':'已实测通过','deployed':True,'uploadedDevelopmentVersion':True,'experienceSelection':'未测试','phone':'未测试','overallCommercialRelease':'当前不满足所列范围的发布条件','report':'验收报告与后台操作说明.html'}
(out/'final-receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(receipt,ensure_ascii=False))
