# 原三端工作区工具

这里保留原项目根目录的 scripts、patches、deploy 和产品/设计说明；它们原本按固定兄弟目录访问三个项目。各仓库的基本构建已独立，只有跨端验收/部署需要恢复以下工作区：

```text
workspace/
  RuoYi-Vue/   # git clone https://github.com/jiangyi3265/kunkun-backend RuoYi-Vue
  RuoYi-Vue3/  # git clone https://github.com/jiangyi3265/kunkun-admin RuoYi-Vue3
  代练/        # git clone https://github.com/jiangyi3265/kunkun-app 代练
  scripts/     # 从本目录复制
  patches/     # 从本目录复制
  deploy/      # 从本目录复制
  PRODUCT.md
  DESIGN.md
  .runtime/    # 本机自行配置，不能提交
```

在 `workspace` 根执行原来的脚本，例如 `node scripts/start-dev.cjs`；需要先分别安装两端依赖、构建后端，并准备本机 MySQL/Redis。私密变量位于 `.runtime/local-dev.json`，字段包括 BOOST_DB_URL、BOOST_DB_USER、BOOST_DB_PASSWORD、BOOST_JWT_SECRET、BOOST_MEDIA_SIGNING_KEY 及需要的 Redis 配置；密钥自行生成，配置示例见 deploy。运行本地非支付开发时设置 `BOOST_DEV_PAYMENTS_DISABLED=1`。脚本仅用于已配置的隔离本地开发环境。

旧发布/验收脚本中有本机运行时路径和历史报告路径。新机器需配置相应工具和新的输出目录，不能把旧报告路径不存在视为已通过。服务器升级脚本面向原有受控部署，需要私密配置、确认的host key和当前版本元数据，禁止在其他服务器直接套用。跨端证据、账号、媒体、数据库和密钥未纳入仓库。

本目录是一份本次同步的工具快照。修改共享验收脚本时应同时更新对应工作区；日常小程序安装/构建已使用小程序仓库自身的 scripts 和 patches。

`scripts/deploy-kunkun-test.py` 是旧环境首次部署工具，仍读取 `RuoYi-Vue/sql/ry_20250522.sql`，这个含旧密码哈希的文件不会随仓库公开。新克隆不可直接运行该工具：必须先用后端 `scripts/create-local-schema.py` 生成新的安全初始化 SQL，并把脚本中旧 SQL 输入路径改为新生成的 `.runtime/local-schema.sql`，同时核对数据库目标为空及部署配置。不要下载或恢复含共享默认密码的旧 SQL。现有服务器升级也不应重复导入任何初始化 SQL。
