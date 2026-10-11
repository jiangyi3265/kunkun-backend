# kunkun-backend · 困困道具交易后端

为和平精英地铁逃生玩家道具集市提供 API、业务规则、持久化和平台审核能力。

## 项目简介

基于若依扩展的 Java 多模块后端，连接 `kunkun-app` 用户端和 `kunkun-admin` 管理后台。支持出售/求购、单价与数量、库存预订、订单幂等处理、交付凭证、售后、私密材料、聊天通知，以及图文活动、免费积分抽奖、勋章和实物兑换。

实名认证与店铺认证分开；求购要求实名，出售还要求店铺审核和手机号验证。会员、成长与积分分别建模，后台变更留有审计。订单金额以整数分保存，订单号使用 `G + 北京时间日期 + 当日四位流水号`。钱包的保证金与可提现金额分开保存，当前仅展示。

支付、保证金缴退未开放；真实短信及实名供应商尚待配置/联调。仓库包含相关接口实现与隔离测试，不代表真实资金渠道已通过验收，也不出售游戏账号。

## 技术栈

- Java 17、Maven 多模块、Spring Boot 4.0.8、Spring Security。
- MySQL 8、Redis、MyBatis、PageHelper、Druid；自定义带校验和的业务数据库迁移。
- JUnit / Spring Boot Test、H2 与独立 MySQL 测试；Python/Node.js 用于工作区辅助验收。

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| kunkun-backend | 后端服务 | [kunkun-backend](https://github.com/jiangyi3265/kunkun-backend) |
| kunkun-admin | 管理后台 | [kunkun-admin](https://github.com/jiangyi3265/kunkun-admin) |
| kunkun-app | 用户端 | [kunkun-app](https://github.com/jiangyi3265/kunkun-app) |

## 快速启动

准备 JDK 17、Maven、MySQL 8 与 Redis。所有命令在本仓库根执行；请使用新建的独立本地数据库。

1. 创建空数据库 `kunkun_dev` 和仅限本地使用的数据库账号。基础若依表使用无凭据模板生成：

```powershell
python -m pip install bcrypt
python scripts/create-local-schema.py
mysql --host=127.0.0.1 --user=你的本地数据库用户 --password kunkun_dev --execute="source .runtime/local-schema.sql"
```

生成器交互读取新管理员密码，长度限制为后台登录使用的 12–20 个 UTF-16 单元（常用汉字/字母各 1 个，补充字符如部分 emoji 各 2 个），仅在忽略目录 `.runtime` 保存 BCrypt 哈希。模板不提供共享初始密码；后台新增用户时必须单独填写密码。原始 `sql/ry_20250522.sql` 留在原工作区但不提交。初始化 SQL 含重建表语句，禁止导入已有共享或生产数据库。

2. 在当前终端设置配置。下面只列变量名与非敏感示例，密码由本人输入，随机密钥每个环境自行生成：

```powershell
$env:BOOST_DB_URL='jdbc:mysql://127.0.0.1:3306/kunkun_dev?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
$env:BOOST_DB_USER='你的本地数据库用户'
$env:BOOST_DB_PASSWORD=[System.Net.NetworkCredential]::new('',(Read-Host 'MySQL password' -AsSecureString)).Password
$env:BOOST_REDIS_HOST='127.0.0.1'
# Redis 使用密码时，同样从安全输入设置 BOOST_REDIS_PASSWORD。
$env:BOOST_JWT_SECRET=[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:BOOST_MEDIA_SIGNING_KEY=[Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
mvn clean package
java -jar ruoyi-admin/target/ruoyi-admin.jar --server.address=127.0.0.1 --boost.initialize-schema=true --boost.payments-enabled=false --boost.simulated-payment=false
```

默认服务地址 `http://127.0.0.1:8080`，就绪检查 `/health/ready`，公开业务配置 `/boost/config`。首次本地启动初始化 `boost_` 业务表；把 `sql/boost*-menu.sql` 按文档导入本地数据库可加入运营菜单。生产使用 `prod` 配置和专用迁移账号，禁止复用本地自动建表参数。PowerShell 随机密钥示例使用 PowerShell 7。

后端基础表及业务表准备好后，分别启动关联的管理端与用户端。管理端使用初始化时设置的管理员密码，不公开共享账号。

## 项目结构

```text
ruoyi-admin/       启动应用、业务控制器/服务、数据库资源和测试
  src/main/java/com/ruoyi/boost/  玩家交易、认证、积分、内容、钱包
  src/main/resources/boost/     schema.sql、V002—V009迁移、IP属地库
ruoyi-framework/   安全、权限、运行配置
ruoyi-system/      系统用户、角色、菜单和组织
ruoyi-common/      通用模型、工具和异常
ruoyi-quartz/      调度基础模块
ruoyi-generator/   代码生成模块
sql/              本地基础表模板及业务菜单SQL
scripts/          本地无明文密码的SQL初始化工具
workspace-tools/  原三端工作区脚本、部署模板、补丁及产品说明
```

## 测试与三端工作区

`mvn test` 可运行本地测试；MySQL 集成用例需要额外数据库配置并指向独立测试库。不同测试类使用不同的 `BOOST_*_TEST_DB_*` 变量，例如 `BoostPlayerMigrationTest` 使用 `BOOST_MIGRATION_TEST_DB_URL`、`BOOST_MIGRATION_TEST_DB_USER`、`BOOST_MIGRATION_TEST_DB_PASSWORD`；具体以测试类和工作区准备脚本为准。未提供条件而跳过的测试不算通过。金融/实名替身测试不代表第三方真实联调。

如需恢复原来的一键联调与验收布局，见 [workspace-tools/README.md](workspace-tools/README.md)。历史截图、数据库备份、会话和服务器私密配置没有上传；不能把缺少的本机证据误报为新机器已经执行通过。

## 简历描述示例

参与和平精英玩家道具交易平台后端开发，实现发布审核、按件交易、库存与订单幂等控制、售后及多角色权限校验。搭建积分活动、勋章兑换和独立认证数据模型，通过统一 API 支撑小程序与运营管理端的数据同步。

## 开源来源与边界

保留若依原有 [MIT 许可证](LICENSE) 及资源的第三方许可。仓库公开不包含服务器凭据或业务备份；示例、隔离测试中的固定测试值不是部署凭据。已有依赖审计风险仍待处理，具体环境和外部供应商应单独验收。
