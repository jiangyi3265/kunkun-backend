# braces 3.0.3 深度防护回移

来自 [上游 PR #72](https://github.com/micromatch/braces/pull/72) 固定提交 `28d440b5dd449dbf1fe6f3506cf94ecca4d02660`，针对 [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)。许可证保留。官方尚未发布修补版本，因此依赖仍声明真实名称、版本 3.0.3；npm audit 的漏洞报告和非零退出码保留。

`parse.js` 只从该提交移植深度检查，保留已发布版本的引号、逗号处理。其余四个文件移植递归深度/父链循环检查；`expand.js` 额外将剩余深度传入 invalid/dollar 字符串回退，防止子树重置预算。衍生内容及原始/补丁 SHA-256 均记录在 manifest.json，不能当作未经修改的上游文件。

两端 npm postinstall 调用 `scripts/apply-braces-hardening.cjs`。脚本仅接受已知原始 SHA 或已补丁 SHA，未知版本/字节失败；验收先使用 `--check` 验证实际安装内容。若使用 `npm ci --ignore-scripts`，需手动运行该脚本再验收。

默认字符串/AST 容器嵌套上限为 100，允许调用方降低，不能提高；超过限制明确抛错。这项防护不声称限制任意 AST 宽度或所有笛卡尔积展开资源消耗，也不替代官方已修补版本的后续升级。
