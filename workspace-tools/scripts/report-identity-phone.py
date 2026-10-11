"""Collect actual receipts, source fingerprints and a human-readable acceptance pack."""
import datetime, hashlib, html, json, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'test-results/identity-phone-20261009'
FLOW = OUT / 'http-r5'
NATIVE = OUT / 'native-r6'

def read(path):
    return json.loads(path.read_text('utf-8-sig'))

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

build = read(OUT / 'final-r2/one-command.json')
flow = read(FLOW / 'acceptance-run.json')
browser = read(FLOW / 'http-browser.json')
native = read(NATIVE / 'native-identity.json')
native_run = read(NATIVE / 'acceptance-run.json')
recovery = read(OUT / 'final-r2/phone-recovery.json')
for receipt in [native_run, browser, native, recovery]:
    assert receipt['exitCode'] == 0
flow_commands = [c for c in flow['commands'] if c['name'] != 'native-identity'] + native_run['commands']
assert all(c['exitCode'] == 0 for c in flow_commands)
build_commands = [c for c in build['commands'] if c['name'] != 'http-browser-run']
assert len(build_commands) == 8 and all(c['exitCode'] == 0 for c in build_commands)
for extra in ['release-build.json', 'persistence-assert-r2.json']:
    receipt = read(OUT / extra)
    assert receipt['exitCode'] == 0
    build_commands.append(receipt)
assert flow['jarSha256'] == digest(ROOT / 'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar')

before = read(OUT / 'source-before.json')['files']
paths = set(before)
for directory in ['代练', 'RuoYi-Vue3/src', 'RuoYi-Vue/ruoyi-admin/src', 'scripts', 'deploy']:
    for p in (ROOT / directory).rglob('*'):
        if p.is_file() and not {'node_modules', 'dist', 'target', '__pycache__', '.git'} & set(p.parts) and p.suffix in {'.vue', '.java', '.js', '.cjs', '.json', '.css', '.sql', '.py', '.ps1', '.yml', '.md'}:
            paths.add(p.relative_to(ROOT).as_posix())
paths.update(['PRODUCT.md', 'DESIGN.md'])
after = {p: digest(ROOT / p) for p in sorted(paths) if (ROOT / p).is_file()}
changes = [{'file': p, 'before': before.get(p), 'after': after.get(p), 'baselineCovered': p in before, 'note': '' if p in before else '基线未采集此文件，不能据此宣称新增'} for p in sorted(set(before) | set(after)) if before.get(p) != after.get(p)]
snapshot_id = hashlib.sha256(json.dumps(after, sort_keys=True).encode()).hexdigest()
with zipfile.ZipFile(OUT / 'source-after.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for p in after:
        z.write(ROOT / p, p)
(OUT / 'source-after.json').write_text(json.dumps({'time': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'snapshotId': snapshot_id, 'files': after, 'changes': changes}, ensure_ascii=False, indent=2), 'utf-8')

validation = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'localStatus': '已实测通过', 'status': '环境阻塞', 'localExitCode': 0, 'fullScopeExitCode': 2, 'sourceSnapshotId': snapshot_id, 'jarSha256': flow['jarSha256'], 'buildCommands': build_commands, 'flowCommands': flow_commands, 'browserChecks': len(browser['checks']), 'nativeChecks': len(native['checks']), 'phoneRecoveryChecks': len(recovery['checks']), 'flowEvidence': str(FLOW.relative_to(OUT)), 'nativeEvidence': str(NATIVE.relative_to(OUT)), 'external': {'sms': '环境阻塞', 'identity': '环境阻塞', 'physicalPhone': '未测试'}, 'supersededAttempts': ['final-r1: PowerShell native stderr warning handling', 'final-r2: ambiguous admin drawer locator', 'http-r3: selected buyer instead of target seller', 'http-r4/http-r5: native component boundary selector', 'package: release metadata missing; standard release builder subsequently completed', 'persistence-assert: mysql header parsing corrected; assertions unchanged'], 'note': 'Backend/lint/build receipts retained without unnecessary repeat. After test locator corrections only HTTP/browser/native/persistence were rerun. Earlier failures remain in their original reports.'}
(OUT / 'final-verification.json').write_text(json.dumps(validation, ensure_ascii=False, indent=2), 'utf-8')

plan = read(OUT / 'acceptance-plan.json')
local_ids = {'ID-01', 'ID-02', 'SHOP-01', 'SHOP-02', 'SHOP-03', 'SMS-01', 'SMS-02', 'USER-01', 'LEVEL-01'}
for row in plan['items']:
    if row['id'] in local_ids:
        row['status'] = '已实测通过'
        row['evidence'] = 'final-r2/backend-all-mysql.log；http-r5/http-browser.json；persistence-assert-r2.log'
    if row['id'] == 'SMS-02':
        row['evidence'] = 'final-r2/phone-recovery.json；final-r2/backend-all-mysql.log'
    if row['id'] in {'USER-01', 'LEVEL-01'}:
        row['evidence'] += '；native-r6/native-identity.json'
    if row['id'] in {'KYC-01', 'SMS-03'}:
        row['evidence'] = 'server-before.json credentialPresence；http-r5 原生未开通提示截图'
    if row['id'] == 'RELEASE-01':
        deployment = OUT / 'server-verification.json'
        row['status'] = '已实测通过' if deployment.exists() and read(deployment)['exitCode'] == 0 else '未测试'
        row['evidence'] = 'final-verification.json；server-verification.json；deployment-receipts/'
    row.setdefault('evidence', '尚无真机证据' if row['id'] == 'DEVICE-01' else '用户要求支付暂不接入；服务配置 paymentsEnabled=false')
(OUT / 'acceptance-matrix.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), 'utf-8')

package = read(OUT / 'release-package.json') if (OUT / 'release-package.json').exists() else {}
upload = read(OUT / 'upload.json') if (OUT / 'upload.json').exists() else {}
delivery = {'server': '未测试', 'upload': upload.get('status', '未测试'), 'experience': '未测试', 'physicalPhone': '未测试', 'release': package.get('version'), 'miniVersion': upload.get('version')}
if (OUT / 'server-verification.json').exists():
    server = read(OUT / 'server-verification.json')
    delivery['server'] = '已实测通过' if server['exitCode'] == 0 else '失败'
    delivery['serverChecks'] = len(server['checks'])
live_path = OUT / 'native-live-r2/native-identity-live.json'
if live_path.exists():
    live = read(live_path)
    delivery['liveNative'] = live['status']
    delivery['liveNativeChecks'] = len(live['checks'])
(OUT / 'delivery-status.json').write_text(json.dumps(delivery, ensure_ascii=False, indent=2), 'utf-8')
esc = lambda s: html.escape(str(s))
link = lambda name: '<a href="' + esc(name) + '">' + esc(name) + '</a>'
cells = ['id', 'entry', 'role', 'expected', 'apiData', 'method', 'status', 'evidence']
rows = ''.join('<tr>' + ''.join('<td>' + esc(row.get(k, '')) + '</td>' for k in cells) + '</tr>' for row in plan['items'])
commands = ''.join('<tr><td>' + esc(c.get('name')) + '</td><td><code>' + esc(c.get('command') or ' '.join([c.get('exe', '')] + c.get('args', []))) + '</code></td><td>' + esc(c.get('startedAt', '')) + '</td><td>' + esc(c['exitCode']) + '</td></tr>' for c in build_commands + flow_commands)
repairs = [
    ('求购被店铺门槛拦住', '原先复用出售认证门槛', '按 REQUEST/SERVICE 分开，后端发布、编辑、重审及后台审核统一校验', 'BoostPlayerMarket.java；BoostMarket.java；BoostAdmin.java；PublishForm.vue；playerTrade.js'),
    ('认证入口混在一起', '原页面同时处理实名和店铺资料', '独立实名认证页；店铺页只处理资料和手机核验；不伪造实名通道', 'pages/identity/index.vue；pages/certification/index.vue'),
    ('店铺材料可重复提交、无法追溯', '缺少提交锁、版本与原件历史', '首次提交后锁定，包括拒绝；后台凭权限和版本更正，留理由、原件与审计；出售重新审核', 'V008__shop_disclosure_phone.sql；BoostPlayerMarket.java；BoostMediaFiles.java；ShopDisclosureEditor.vue'),
    ('旧审核可能批准新材料', '审核请求原先未绑定资料版本', 'expectedRevision 在锁内校验，旧审核返回409', 'BoostPlayerMarket.moderateShop；console.vue；BoostPlayerTradeTest'),
    ('游戏 UID 不能验证联系方式', '缺少手机号验证流程', '腾讯云短信适配、持久化验证码挑战、频率限制、次数/过期/账号/用途/复用校验，号码唯一且不公开', 'BoostPhone.java；BoostSmsGateway.java；PhoneVerification.vue'),
    ('验证已成功但响应丢失', '客户端只显示请求失败', '重新读本人真实绑定状态；管理员未消费票据允许同码恢复；切换账号清空', 'PhoneVerification.vue；BoostPhone.java；test-phone-recovery.cjs'),
    ('用户不好查找', '没有稳定公开编号入口', 'KK 编号在省份前，精确编号搜索和公开主页；禁用用户不返回、私密字段不外泄', 'BoostAuth.java；BoostMarket.searchPlayer；pages/players/search.vue；mine/shop/market'),
    ('成长进度点不开', '卡片原先没有导航和明细页面', '整卡可点击，读本人成长流水、等级门槛和会员到期；保留与积分的区别', 'pages/mine/index.vue；pages/growth/index.vue；BoostPlayerMarket.growthRules'),
]
repair_rows = ''.join('<tr>' + ''.join('<td>' + esc(v) + '</td>' for v in row) + '</tr>' for row in repairs)
gallery = ''.join('<figure><a href="' + ('native-r6/' if f.startswith('native-') else 'http-r5/') + f + '"><img src="' + ('native-r6/' if f.startswith('native-') else 'http-r5/') + f + '"></a><figcaption>' + esc(label) + '</figcaption></figure>' for f, label in [('mine-390.png', 'H5：编号、成长、独立入口'), ('shop-locked.png', 'H5：提交后只读'), ('growth-ledger.png', 'H5：真实成长流水'), ('native-growth.png', '官方开发者工具：成长页'), ('native-shop-phone.png', '官方开发者工具：手机号和服务状态'), ('native-player-number.png', '官方开发者工具：编号查找个人主页')])
document = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>认证、手机号与成长验收包</title><style>body{{font:15px/1.7 system-ui,sans-serif;max-width:1400px;margin:40px auto;padding:0 24px;color:#20232a;background:#f8f9fb}}h1{{font-size:30px}}h2{{margin-top:36px}}.notice{{background:#fff3cf;padding:20px;border-radius:12px}}table{{border-collapse:collapse;background:#fff;width:100%;font-size:13px}}td,th{{border:1px solid #dce0e6;padding:10px;text-align:left;vertical-align:top}}code{{overflow-wrap:anywhere}}a{{color:#075cc1}}.gallery{{display:flex;flex-wrap:wrap;gap:18px}}figure{{margin:0;width:260px}}img{{max-width:260px;border:1px solid #ddd}}.scroll{{overflow:auto}}li{{margin:8px 0}}</style>
<h1>实名认证、店铺资料、手机号与成长验收包</h1><p>生成时间：{esc(validation['createdAt'])}（UTC）</p>
<div class="notice"><strong>当前不满足所列范围的发布条件。</strong>本地隔离验证通过；真实短信收码、实名核验渠道及真机未验收。手机号只证明本次能收到该号码验证码，不能替代实名或保证不会欺诈。支付保持关闭。</div>
<p>部署版本：{esc(package.get('version', '尚未部署'))}；小程序官方上传：{esc(upload.get('version', '尚未上传'))} / {esc(upload.get('status', '未测试'))}。官方上传为开发版本，体验版选择未完成，不能写成已设体验版。</p>
<p>版本标识：<code>{snapshot_id}</code>；JAR：<code>{flow['jarSha256']}</code>。无 Git，以 source-before.zip、source-after.zip 和逐文件 SHA256 提供可复核对照。基线未采集的脚本/部署文件仅保存当前快照，不据此宣称本次新增。</p>
<h2>修复与实际证据</h2><table><tr><th>问题/复现入口</th><th>根因</th><th>修复结果</th><th>修改位置</th></tr>{repair_rows}</table>
<p>复现操作与业务断言完整保存在 scripts/identity-phone-http.cjs 和 Java 回归测试；实际请求记录、截图、持久化核对在 {link('http-r5/http-browser.json')}、{link('persistence-assert-r2.log')}。后台更正入口：玩家道具交易 → 店铺认证审核 → 查看/处理 → 更正资料；需填写原因，修改后重新审核。原材料在同页历史记录中保留。</p>
<h2>完整验收表</h2><p>“已实测通过”仅限各行注明的方法和环境，隔离身份/短信凭据不能等同真实第三方验证。会员由后台授予，成长值由后台按原因调整；没有自动消费成长或会员支付。</p><div class="scroll"><table><tr><th>编号</th><th>入口</th><th>角色</th><th>业务结果</th><th>接口/数据</th><th>验证方式</th><th>状态</th><th>证据位置</th></tr>{rows}</table></div>
<h2>测试结论与失败处理</h2><p>线上核对：{esc(delivery.get("serverChecks", 0))} 项；上传后原包启用合法域名校验并实际访问公网 API：{esc(delivery.get("liveNative", "未测试"))}，{esc(delivery.get("liveNativeChecks", 0))} 项，见 {link("native-live-r2/native-identity-live.json")}。此前已打开上传项目的自动化端口未启动，失败保留在 native-live/；改用字节完全一致的新检查目录后完成，发布包未修改。</p><ul><li>完整后端 303 项（其中 64 项 MySQL），0 失败、0 错误、0 跳过；{link('final-r2/backend-all-mysql.log')}。不是整体业务覆盖率。</li><li>实际 H5/管理端 HTTP+隔离 MySQL：{len(browser['checks'])} 项；官方微信开发者工具：{len(native['checks'])} 项；Vue SFC 受控丢响应回归：{len(recovery['checks'])} 项。微信开发者工具不是手机真机；HTTP 验证使用明确隔离的已发送短信与实名凭据。</li><li>首次统一验收未一次通过：PowerShell stderr 处理、后台抽屉/目标行、原生组件边界选择器错误均保留失败日志。修正测试定位后重跑受影响的完整 UI/HTTP/原生及持久化步骤（最终原生单独复用已核验隔离库）；没有删除断言或跳过失败检查。后端/业务源码未变化，复用后端原始记录；打包缺少 RELEASE.json 后改用既有标准发布构建器，补跑三端发布构建和 WXSS 检查，见 {link('final-verification.json')}。</li><li>独立复核由 quantity_content_review 执行：{link('independent-review/final-review.json')}。店铺提交响应丢失的恢复代码经自审，尚无单独浏览器断网注入证据。成长条实测宽度82.5px，等级区间100～300中的150对应25%，无显示缺陷。</li></ul>
<h2>实际命令与时间</h2><table><tr><th>检查</th><th>实际命令</th><th>开始时间UTC</th><th>退出码</th></tr>{commands}</table>
<h2>一键复查与环境</h2><pre>powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/验收认证资料.ps1 -LocalOnly</pre><p>该统一入口已实际执行，原始失败记录在 final-r2/one-command.json，修正定位后的通过记录分别在 http-r5/http-browser.json、native-r6/acceptance-run.json；不把原始失败退出码改成0。本地全量入口通过应退出0。省略 -LocalOnly 时，真实短信/实名/真机未验证会退出2并显示环境阻塞；检查失败退出1。</p><p>前置：本机 Node 与已有依赖、Java17、Maven、已配置的本地测试 MySQL、官方微信开发者工具登录且服务端口9420。脚本使用唯一标记的隔离库和媒体目录，仅该流程的 API 和微信项目会自动关闭；测试库与媒体留存用于复核，位置见 http-r5/acceptance-run.json（凭据留在私有 .runtime，勿对外分发）。测试库由 scripts/prepare-ui-acceptance.py 创建；先保留当前证据，需要清理时只对报告中唯一的 boost_verify_ui_ 数据库核对 qa_ui_fixture_marker 与 fixture.json 的 marker 一致后执行 DROP DATABASE，并仅删除该 fixture.mediaDirectory 指定的 .runtime 子目录。不得批量删除 .runtime 或操作线上库。</p>
<h2>部署、回滚与敏感信息</h2><p>发布包/远端进程/JAR、H5和后台所有文件比对：{link('server-verification.json')}；迁移及加密备份恢复演练：deployment-receipts/prepare.json、activate.json。V008为新增表和字段，旧迁移校验值保留。本次现有测试服务器切换由既有受控更新器执行，开放前失败自动回滚；开放后不得直接恢复数据库，应先核查新写入并维护停机处理。未做容量压测，不宣称性能或所有设备兼容。</p><p>已知密钥扫描：{link('secret-scan.json')}。报告不包含密钥、验证码或真实私密材料，截图中的认证/手机均为隔离测试数据。全部历史失败报告保留，不用 Mock 充当真实集成。</p>
<h2>最少需要补充的事项</h2><ol><li>短信渠道：需要已开通账号、签名、验证码模板与服务端安全配置。目前实现腾讯云适配；当前按钮明确不可用。接好后由真实手机收一次验证码，再验证后台持久化。</li><li>实名核验渠道：需要已开通核验能力、RuleID和对应小程序接入资料/SDK，再由真实用户完成一次核验。管理员、手机号和测试凭据都不能替代。</li><li>确认手机号强制范围：当前仅店铺认证强制核验；新用户注册是否也必须手机验证尚未确定，未擅自更改存量登录方式。</li><li>微信体验版：需在微信公众平台把上传版本设为体验版；当前工具对该站点有访问限制，未绕过。随后在真实手机核对认证页、成长入口和编号查询；不需要重复本报告已覆盖的机械后台检查。</li></ol>
<h2>界面证据</h2><div class="gallery">{gallery}</div></html>'''
(OUT / '验收报告.html').write_text(document, 'utf-8')
print(json.dumps({'localStatus': validation['localStatus'], 'status': validation['status'], 'sourceSnapshotId': snapshot_id, 'report': str(OUT / '验收报告.html')}, ensure_ascii=False))
