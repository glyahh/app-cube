# 应用魔方：品牌与仓库命名

用户于 2026-10-03 确认本次命名方案。

- 产品名：**应用魔方**。
- 定位：**Agent 驱动的应用开发工作台**。
- 简历标题：**应用魔方｜基于 DeepAgents / LangGraph 的 AI 应用开发平台**。
- GitHub：[glyahh/app-cube](https://github.com/glyahh/app-cube)。
- 页面品牌只展示“应用魔方”，不常驻技术定位或新增宣传段落。

## 本次范围与状态

已统一正式入口标题、页头、页脚、主入口品牌、创建输入提示、导入界面品牌及页面标题；同步 README、当前状态文档、CONTEXT 和本地 AGENTS。Maven 只调整展示名称和描述。

GitHub 仓库已从 `glyahh/ai-generate-code` 改名为 `glyahh/app-cube`，描述同步为新定位；仓库 ID `1152898219`、默认分支 `master` 保持不变。本地 `origin` 已更新，新地址 `git ls-remote --symref origin HEAD` 成功。

**未提交、未推送本地文件。GitHub 默认分支 README 尚未同步本地的新名称。** 本地仍在 `code_board`，HEAD 为 `d4528044d36f38a91567cb5ceb138169af6a873d`；已有改动保留。没有修改简历文件。

保留 Java 包名、Maven artifactId、前端 npm 包名及目录名、数据库/schema、配置键、OSS 地址和工作区路径。它们是技术标识，不是用户展示品牌。历史日志、截图、验收记录及存档路径不做批量替换。本次不重做主入口营销内容，不改变功能状态或将待开发需求写成已交付。

## 本次验证

证据目录：`temp/brand-rename-20261003-195226/`。

| 检查 | 实际结果 | 证据 |
|---|---|---|
| `gh api --method PATCH repos/glyahh/ai-generate-code`，随后查询 `repos/glyahh/app-cube` | 退出码 0；同一仓库 ID、新名称及描述确认 | `github-before.json`、`github-rename.log`、`github-after.json` |
| `git ls-remote --symref origin HEAD` | 退出码 0；新 remote 可读 | `ls-remote.log`、`remote-after.txt` |
| 前端 `vue-tsc --build` | 退出码 0 | `type-check-final.log` |
| 前端 `vite build --outDir ../temp/brand-build/app --emptyOutDir false` | 退出码 0；仍有单块超过 500 kB 警告，未调整阈值 | `build-production-final.log` |
| `node scripts/verify-import-preview.mjs` | 退出码 0；15 组导入前端检查通过，加入新品牌断言；无真实后端请求 | `browser.log`；`temp/import-frontend-preview/browser-1791028401947/browser-results.json` |
| 对最终正式构建的隔离浏览器检查 | 退出码 0；登录页标题/页头/页脚、主入口品牌、1440/375 px 标题边界通过；无页面运行异常 | `verify-production-brand.mjs`、`production-browser.log`、`production-browser.json`、对应截图 |

正式构建浏览器检查使用临时本地静态服务器，API/外部请求由测试上下文隔离拦截，结束后关闭。不是认证、后端或业务端到端验收。未启动后端、迁移数据库、运行导入工程、调用模型或部署生产；本次未重跑完整单元测试。

## 复核与回退

- 导入入口：`http://127.0.0.1:5175/import-preview.html#/`，刷新后检查品牌名称及导入后工作台衔接。
- 正式构建复核：仓库根目录运行 `node temp/brand-rename-20261003-195226/verify-production-brand.mjs "<仓库绝对路径>/ai-generate-code-frontend" "<仓库绝对路径>/temp/brand-rename-20261003-195226"`。
- 改名前文件备份位于证据目录的 `before/`。需要回退时只撤销本次品牌文字差异，不整文件覆盖、不清理工作区、不改 Git 历史。
- GitHub 若要恢复旧名，须先确认旧名未被占用，再将同一仓库改回 `ai-generate-code`、恢复 `github-before.json` 中的描述，并同步 `origin`。这一步未执行。
