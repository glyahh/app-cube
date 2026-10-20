# Auto 工作台设计对齐记录（2026-09-19）

## 范围与结论

在现有未提交实现上增量修改，未重置工作区、未修改已有迁移、未提交 Git。
依据此前确认的 Auto / V6 要求、本地 V6 原型及 AUTO_WORKBENCH_MIGRATION.md 核查。
本轮修复下列已确认差距；**不代表完整 plan 的端到端验收完成**。

## 修复项

1. **生成预算**：Java 默认、请求缺省值、Python 默认和环境示例统一为 30 分钟；保留显式配置。新增 Java 墙钟超时，持续事件不会刷新上限，超时回收进程。
2. **知识更新策略**：没有成功知识时自动生成；已有成功知识时保留旧版，不因改代码或重新启用开关自动重建，交由用户手动更新。
3. **不可变分析输入**：快照不再只有摘要。源码先复制、校验，再整体发布；后台知识任务和只读问答都读取独立副本。路径位于 outputRoot 的同级 knowledge_snapshots/{appId}/{version}，不经静态预览公开。排除依赖、凭据、越界链接等内容。
4. **Vite 运行入口**：已有 dist/index.html 时，静态服务工作目录使用 dist，不再错误服务源码根目录。
5. **运行状态**：容器存活但端口未就绪时保持 STARTING；轮询确认就绪后才显示 RUNNING。启动失败更新台账。前端持续查询状态，启动中仍发送心跳并允许停止，不提前提示启动成功。
6. **预览证据**：工作区描述不可用时不再根据旧 codeGenType 猜测入口。

## 本次验证

| 验证 | 实测结果 |
|---|---|
| Java 定向回归 | 73 通过，0 失败、0 跳过；不是全仓库测试 |
| Python agent/tests | 317 通过 |
| 前端单测 | 19 个文件、136 项通过 |
| 前端类型检查与生产构建 | 通过；仍有大于 500 kB 的分包警告 |
| git diff --check | 通过；存在原有换行转换提示 |
| 后端重启 | 按 scripts/restart-backend.ps1 执行，8124 正常监听 |
| /api/actuator/health | UP，PostgreSQL、Redis 为 UP |

前端测试仍有现有组件 stub 的 Vue 警告，未借此宣称浏览器视觉验收通过。

复跑命令（仓库根目录）：

```powershell
.\mvnw.cmd -q '-Dtest=AgentDeadlineTest,AgentWorkerPoolTest,Runtime*Test,Knowledge*Test,WorkspaceVersionerTest,WorkspacePathResolverTest,AppServiceSupportTest,UserPreferenceControllerTest' test
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
Push-Location ai-generate-code-frontend
npm run test:unit
npm run build
Pop-Location
powershell -ExecutionPolicy Bypass -File '.\scripts\restart-backend.ps1'
Invoke-RestMethod 'http://localhost:8124/api/actuator/health'
```

## 尚未完成的整体验收

- 尚未从干净基线实跑模型生成、容器运行、人工确认修复、知识生成及问答的完整用户流程。
- 尚未完成 V6 浏览器视觉对比与 500 / 1000 节点性能测量。
- 本轮未穷尽所有 plan 条目；现有迁移文档中的历史运行记录不作为本次证据。
- 旧快照只有清单、没有源码副本时，不会用当前源码冒充旧版本重试；需要用户重新生成知识。既有知识文档不删除。
- 独立快照目前按版本保留，自动保留期清理尚未实现。

## 工件与回退

本轮修改前逐文件备份、日志、SHA256 清单、Java 测试摘要和健康检查：

`D:\codex\WorkDocument\2026-09-19\auto-workbench-alignment`

before 目录保留用户本轮开始时的文件内容。回退应逐文件比较备份与当前内容，合并撤回本轮差异；不要整目录覆盖或使用 git reset，以免丢失用户的后续修改。
