# 发布、备份与恢复

适用本仓库的买家 / 打手 uni-app 和若依管理端。Java 17、Node 24、MySQL 8、Redis；后端使用 Spring Boot 4.0.8。当前按用户要求暂不接入支付，生产配置默认 `BOOST_PAYMENTS_ENABLED=false`。正式业务尚未启用；最新结论和真实证据见 [发布前验收报告](../发布前验收报告-20261004.md)。

## 配置与权限

- 开发：本机原连接参数已迁至 `.runtime/local-dev.json`，`node scripts/start-dev.cjs` 读取。文件和 `.runtime` 备份不入库。新开发机自行创建含 `BOOST_DB_URL`、`BOOST_DB_USER`、`BOOST_DB_PASSWORD`、`BOOST_JWT_SECRET`、`BOOST_MEDIA_SIGNING_KEY` 的 JSON；签名密钥用密码学随机数生成，JWT 至少 64 字符、图片签名至少 48 字符。
- 生产：复制 `production.env.example` 到服务器 `/etc/boost/production.env`，限制为服务用户可读。不要复用开发密钥或共享库 `ha`。JDBC 使用 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，同时统一连接与数据库会话时区；不要只指定与服务器不同的 `serverTimezone`。此设置只影响本项目连接，不修改 MySQL 全局时区。使用新生产库和专用账户；迁移账户拥有该库 DDL，运行账户只获该库 SELECT / INSERT / UPDATE / DELETE。禁止 root、全局权限和 GRANT OPTION。运营账户必须更换默认密码，停用不需要的账户；不要导入 QA 或模拟资金记录。
- `BOOST_WEB_ORIGINS` 填真实 H5 和后台 HTTPS 源，逗号分隔；不填通配符。Nginx 覆盖转发 IP，Java 只信任本机代理，防止伪造 IP 绕过注册限流。
- 私密图片目录须与公共上传目录分开，Nginx 不得直接映射私密目录。图片通过后端授权取得两分钟有效链接；链接和令牌不写入访问日志。历史未登记文件保留访问约束，不自动删除，先人工核查再归档。
- 在若依角色管理中分开授权各 `boost:*:read`、内容/认证审核、账号状态、售后审核、提现审核、财务核对权限。升级菜单保留旧角色的查看能力，不自动授予新的资金操作权限。
- 管理端每次请求复核当前账号、角色、菜单权限与数据范围，撤权/停用不等待缓存过期。新增角色或菜单权限需重新登录；个人信息及菜单路由刷新、角色编辑和自动续期均不能扩大旧会话的授权，每个角色的菜单权限也保留登录边界，避免多角色数据范围串用。本次升级前的角色缓存若缺少删除状态或角色权限字段，普通运营应重新登录。已授权角色自身的数据范围按当前配置生效，需要收窄时无需等待登录过期。
- 退出和强制下线删除会话后，迟到请求只能原子续期仍存在的会话，不能重新创建已撤销的会话。自然过期也须重新登录；Redis异常不会回退到缓存鉴权成功。
- 非支付正式模式允许注册、认证、发布审核、聊天、创建及取消未付款订单和历史只读查询；支付/付后动作、售后处理、评价新增、提现、财务重查及渠道回调均拒绝。小程序登录仍需要真实 AppID/AppSecret；关闭支付时不要求商户密钥。生产启动仍检查独立库、DML账户、Redis密码、HTTPS来源、签名密钥和真实协议资料，不放宽安全校验。存在未结付后订单、待处理提款或非零钱包的库不得切换到关闭模式；须先制定对账/履约方案，不能直接停掉资金处理。

## 构建与门禁

```powershell
npm ci --prefix RuoYi-Vue3
npm ci --prefix 代练
npm run lint --prefix RuoYi-Vue3
npm run lint --prefix 代练
mvn -f RuoYi-Vue/pom.xml -pl ruoyi-admin -am verify
node scripts/build-release.cjs
```

正式构建脚本读取 `BOOST_WX_APP_ID` 和 `BOOST_PUBLIC_API_ORIGIN`，拒绝空 AppID、非 HTTPS / 本机地址，临时注入小程序 manifest 并开启域名校验，结束后恢复源文件。普通 `build:h5` / `build:mp-weixin` 仅用于开发验证，不能当作正式发布包。发布产物为后端 jar、后台 `dist`、H5 `dist/build/h5`、小程序 `dist/build/mp-weixin`；需记录提交版本、文件 SHA-256 和数据库迁移版本。

小程序 SDK 钉住 Vite 5.2.8 等旧依赖；本项目使用锁文件中的安全补丁 overrides（包括 Vite 6.4.3），须在每次更新时一起验证 H5、小程序构建及浏览器流程。不要执行 `npm audit fix --force`，它会将 DCloud 降级到不兼容的旧主版本。

两端 `postinstall` 会对锁定的 braces 3.0.3 应用可核验的深度限制补丁，执行 `node scripts/apply-braces-hardening.cjs --check` 和 `node scripts/test-braces-hardening-review.cjs` 可检查安装结果及回归。来源、原始/修改后哈希与补丁边界见 [补丁说明](../patches/braces-3.0.3-depth-limit/README.md)。该补丁保留真实版本号，不把 npm 官方审计失败改成通过；当前无上游正式安全版本，总体安全门禁仍需单独处理。

## 数据库迁移

1. 停止本项目应用和定时任务，确认备份与恢复演练成功。对生产独立库执行一致性完整备份，同时快照媒体目录。备份包含个人信息，应加密、限制访问并保存到独立存储。
2. 新生产库先仅导入本项目所需若依基础表，配置一个已改密的管理员；不要对现有共享库执行若依初始化脚本中的 DROP。业务表由下述独立迁移命令创建。已有库先核对原有若依表，再运行同一迁移。
3. 用私密环境文件设置迁移专用 `BOOST_DB_URL/USER/PASSWORD`，设置 `BOOST_MIGRATION_DATABASE` 为准确库名，执行：

```sh
java -Dloader.main=com.ruoyi.boost.BoostMigrationCommand -cp ruoyi-admin.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

命令不启动网站、不加载 Redis、不运行资金任务、不导入演示数据。MySQL 命名锁阻止并发迁移；V002 检查已存在的字段和索引，按 SHA-256 校验已应用脚本。中断后用同一版本重跑；已应用的脚本不能再修改。随后运行 `RuoYi-Vue/sql/boost-menu.sql`，只新增/升级 87000 系列且组件匹配本项目的菜单；不会删除其他项目菜单。

4. 换回只有 DML 的运行账户，设置 `SPRING_PROFILES_ACTIVE=prod`，禁用自动建表、演示及测试支付后启动。启动校验会拒绝关键配置缺失、共享测试库、模拟交易、默认密码、宽泛数据库权限。部署后检查 `/health/ready`、后台登录、发布审核及图片权限。

首次升级为历史待付款单补上“创建后 30 分钟”的截止时间；定时核对会关闭已确认未付款的过期订单并释放占用。渠道结果未知时保留订单待核对，不会把网络失败当成未付款。先在副本演练、核对等待中的订单后再迁移生产。

## 运维、告警与回滚

- 通过 systemd 管理进程；示例 `boost.service` 限定可写目录。Nginx 模板需要填入真实域名及证书，并完成 `nginx -t`。不要对公网开放开发 Vite、MySQL、Redis 或后端直连端口。
- 监控每分钟 `/health/ready` 的状态、HTTP 5xx、磁盘余量和进程重启；持续 DOWN 或 5xx 上升时报警。业务上每日核对“退款异常”、长时间退款中、处理中提现和付款结果待确认记录。
- WARN 和 ERROR 都进入 `application.log`；日志按天/100MB 分卷、保存 30 天、总量上限 2GB。按实际流量、保留要求配置备份及日志归档。
- 回滚先停止接单/资金任务；确认上一版能识别当前数据库新增状态后，才能切换代码版本。**出现 `REFUND_EXCEPTION` 等新状态后，不得直接回退旧业务代码。** 优先向前修复；确需恢复数据库时，保留故障库/媒体快照，在新库恢复备份，再与微信实际支付、退款、转账逐笔对账。不要在有新增真实资金交易时用旧备份覆盖现库。
- 定期在隔离库恢复备份，比较表行数和钱包/流水/退款/优惠券不变量；恢复媒体后验证私密访问仍被拒绝。备份失败必须告警，不能只检查备份文件存在。

## 正式上线仍需的外部验收

运营主体、隐私邮箱、数据保留规则、协议版本及生效日须确认并填写。微信后台配置真实合法域名、小程序 AppID，并实际验证登录、request/upload/download、预览和重进。当前不要求支付商户材料。以后决定开放支付时，需另行提供商户/平台密钥和获批转账场景，并完成真实小额支付、退款、提现与账单核对。新人券默认关闭；开放时须配置预算并核验微信身份。隔离夹具、Mock或本机 HTTPS 不能代替正式平台验收。
