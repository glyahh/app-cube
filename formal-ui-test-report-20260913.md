# 正式用户 UI 全量测试报告

| 项目 | 内容 |
|---|---|
| 测试日期 | 2026-09-13 |
| 测试入口 | http://127.0.0.1:5173 |
| 测试分支 | agent_next |
| 测试提交 | a714a77 |
| 测试账号 | qa_test account (local only) |
| 后端 | Spring Boot / API 8124 |
| 数据库 | PostgreSQL 5432 |
| Redis | 6379 |
| Docker | CLI unavailable at baseline |
| 证据目录 | temp/ui-test-20260913/ |

> 本报告按目标文件中的 102 条用例逐项记录。状态只在浏览器可见结果、后端日志、数据库/Redis 状态或明确环境阻断证据支持时填写。

## 当前已确认问题

### ISSUE-001：开发服务器旧 HMR 状态导致首次生成路由动态模块加载失败
- 严重程度：High（首次复现；重启 Vite 后未再次复现）
- 复现：在旧 Vite 进程上点击“开始生成”，浏览器报 `Failed to fetch dynamically imported module: AppChatView.vue`，页面不进入聊天页。
- 证据：`temp/ui-test-20260913/screenshots/29-html-generation-started.png`、`30-code-generation-route.png`；浏览器 console/error 输出。
- 处置：重启前端开发服务器后复测通过；保留为环境/热更新稳定性问题。

### ISSUE-002：AI 内部英文工作日志与工具探查内容泄漏到用户聊天正文
- 严重程度：Critical
- 复现：完成 HTML 生成后，聊天正文显示 `I'll start by checking...`、`The workspace is empty`、`python`/`node`/`.scratch`、命令和内部校验过程。
- 证据：`temp/ui-test-20260913/screenshots/38-html-completed.png`、`41-followup-result.png`；页面无障碍树正文。
- 影响：违反工具输出安全、内部提示词保护、英文思考过滤要求。

### ISSUE-003：工作流二轮修改因 Docker 容器名称冲突失败，并向用户暴露原始错误
- 严重程度：High
- 复现：在已生成 HTML 应用中发送“把页面背景改成深色，并新增一个查看作品按钮”。UI 显示失败文本：`Conflict. The container name "/glyahh-sbx-..." is already in use`。
- 证据：`temp/ui-test-20260913/screenshots/44-followup-completed.png`；后端 Agent/工作流日志。
- 影响：多轮修改失败，且错误包含 Docker/容器内部实现细节。

### ISSUE-004：页面加载产生 Vue 控制台警告
- 严重程度：Medium
- 现象：`Failed to resolve directive: loading`；分页 `total` 字段传入字符串而非数字。
- 证据：agent-browser console 输出，发生在首页加载/应用列表渲染。

## 测试记录

| 用例 | 状态 | 证据/备注 |
|---:|:---:|---|
