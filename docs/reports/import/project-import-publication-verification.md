# 文件夹导入：应用提交、恢复与工作台联调

日期：2026-10-04。分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`；未提交工作区。仅第一项的文件夹链路，**不是整项交付**。本记录接续[暂存与偏好验证](project-import-backend-upload-verification.md)。

## 已实现的窄链路

1. 当前会话冻结来源清单，接收真实字节，封存逐文件 SHA-256。
2. 确认必须匹配任务归属、revision 和 contentHash；服务端预留应用 ID，持久 journal 登记 `PUBLISHING`。
3. 从独立数字文件保护快照构造真实路径候选副本；再次校验内容和全部文件集合。原工程不修改。
4. 移入目标应用目录但暂不开放读取，记录 `FILES_READY`；短数据库事务提交 App、任务 `SUCCEEDED` 和 journal `READY`。
5. 只有有效 App、任务和 journal 同时成立才返回结果；前端跳转到对应 `/app/{appId}/chat`，无 autoSend，不自动安装或执行。

文件移动与数据库提交**不是跨系统原子事务**。中断由 journal 和读取门禁处理，不在异常时假称已回滚。候选目录不覆盖既有目标，成功任务重放复用原应用；导入成功后合法编辑不改变历史导入内容身份。

接口增量：`POST /api/project/import/tasks/{id}/commit`、`POST .../recover` 接收 revision/contentHash；`GET .../result` 读取合格结果。沿用当前会话、CSRF、Origin、实际元数据读取限额及错误码。上传完成本身仍不创建 App。

## 权限与恢复

- 未提交、恢复中、App 缺失/删除/归属不符的 journal 不提供可用工作区。静态预览须为 App 所有者；敏感文件禁止静态读取。路径校验覆盖实际祖先链接及路径别名。
- 导入静态预览采用 sandbox CSP、禁止 connect/form/frame/object 等权限；浏览器实测不能读取工作台父页面或访问业务 API。不是对所有外部 CDN/运行环境的兼容承诺。
- 恢复须确认旧执行资源不再拥有资格，并重新核对保护快照、目标真实字节与完整集合。PID 与启动时间都匹配的活进程禁止抢占。损坏内容阻断恢复，不把错误内容发布为成功。
- 成功应用不能通过取消导入任务删除。对无法证明属于本任务的最终目录保留并报告清理失败，不盲删。
- **导入应用的旧 Agent、运行和发布执行入口目前明确阻断**：新隔离执行与安装/运行授权尚未完成，不能绕过门禁使用旧宿主执行能力。工作台打开、代码查看/保存不等于 Agent 执行已交付。

## 当前版本实际验证

证据根：`D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\import-backend-publication-20261004\`。

| 检查 | 实际结果及证据 |
| --- | --- |
| Java 综合 | 退出 0；142 项，失败/错误/跳过均 0；`result-qualified-tests.log`，对应 JUnit XML。含清单、真实文件复制、任务、控制器、读取门禁、偏好和 35 项已有能力回归 |
| 结果资格追加负例 | `result-negative-tests.log` 退出 0；成功 journal 但 App 不可用时，查询/重放均拒绝。仓库为 Mock，另做下方真实数据库检查 |
| 前端适配器 | `frontend-qualified-tests.log` 退出 0，4 项；覆盖提交顺序、提前取消、业务失败与停止失败，非真实后端证明 |
| 正式前端构建 | `frontend-build-qualified.log` 退出 0，包含类型检查与 Vite 构建 |
| 实际后端重启 | `restart-qualified.log` 退出 0；8124 监听 PID 30552，启动时间 02:12:26。实际接口使用该进程，不仅检查端口 |
| HTTP/DB/磁盘 | `http-qualified/result.json`：55 项检查 PASS；实际 App 归属/名称/内容、幂等、跨用户、错误哈希、保护快照和取消边界。55 项不是 58 个整组 |
| 受控中断恢复 | `recovery-qualified-negative/result.json`：37 项检查 PASS；旧执行器消失、损坏阻断、活执行器拒绝、同 App 恢复、合法编辑后保护快照不变、删除 App 后历史结果不可用。仅在新专用账号/任务上注入 journal 故障，不冒充真实断电验收 |
| 实际浏览器 | `temp/folder-import-real-1791051216803/result.json`：6 个检查组 PASS；真实登录/偏好/文件选择/提交/对应工作台、最终文件、预览所有权及浏览器隔离、桌面布局、未自动执行 |
| Git 前端精简 | `temp/git-import-preview-1791051112406/result.json`：4 个检查组 PASS；1440/768/390 布局，多凭据/备注/替换，获取入口按需显示，未请求真实平台。后端契约 NOT_IMPLEMENTED，仍待用户确认 |

此前失败日志保留：纯文件/提交测试先行的预期红灯；路径错误提示顺序；Mockito 桩覆盖错误；TypeScript 循环推断；浏览器父页面测试上下文错误与 dist 样本不一致；Token 帮助嵌入 label 导致无障碍名称变化。分别修正实现或测试真实前提，没有缩减验收断言。

## 重跑

```powershell
.\mvnw.cmd -q "-Dtest=ProjectImportPublicationFilesTest,ImportWorkspaceGateTest,ImportTaskServiceTest,ProjectImportControllerTest,WorkspacePathResolverTest,ProjectImportManifestTest,ProjectImportStagingTest,ImportPreferenceServiceTest,UserPreferenceControllerTest,RuntimeSpecResolverTest,WorkspaceVersionerTest" test
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-task-api.py --commit --output temp/<新的专用证据目录>
# 在前端目录，使用现有 Edge 和已启动的 5173/8124
node scripts/verify-folder-import-real.mjs
```

恢复脚本 `scripts/checks/verify-import-publication-recovery.py` 还需提供监听进程的真实 `--live-pid` 和 UTC `--live-start`；仅使用根 .env 已配置 DSN，不输出秘密。每次 HTTP/浏览器/恢复验证均创建独立普通测试账号，成功应用和保护快照保留；不默认清除账号、用户应用或 journal。

## 未完成与回退

- 原矩阵 58 个整组仍 NOT_RUN；本记录只提供局部子场景证据。Git 双平台/凭据真实后端、识别、安装批准、多服务运行及完整独立验收尚未完成。私有仓库真实验收按用户要求暂缓，不取消需求。
- 资源配额/保护快照保留策略、完整任务恢复界面、响应丢失找回、真实进程崩溃/电源故障与多实例协调仍需补齐。完成 App 的工作区物理丢失不能用旧快照盲目覆盖合法编辑。
- 其他三项未实施；真实模型生成及真实 Docker 安装/运行未执行，不记为跨项联动通过。
- 复用范围仅现有会话、PostgreSQL、工作区路径/版本和已批准工作台；门禁是当前导入安全边界，不是重写全平台。
- 回退只撤销本步增量，保留用户既有修改和已创建测试应用。V12/V13/V14 已应用，不修改校验和、不删除 Flyway 历史、不默认 DROP 表。回到旧代码前应阻止导入应用绕过新门禁；不能把直接撤销保护当安全回退。生产未发布，Git 未提交或推送。
