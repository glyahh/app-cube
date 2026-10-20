# 来源请求找回与 Windows Git 清理验证

2026-10-04。`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d` 加当前未提交文件；哈希见 `temp/import-backend-publication-20261004/source-request-current-artifacts/manifest.json`。仅第一项局部增量，不是整项交付。

## 实现

- 正式 Git 创建携带随机 UUID requestId。V18 将请求标识和不可变创建摘要绑定 owner；同一请求重复发送返回原任务，名称/平台/地址/范围/凭据语义版本变化则冲突。范围重建不修改原创建摘要。
- `GET /api/project/import/git/tasks/request/{requestId}` 使用登录身份查原来源，不创建来源、不重新取凭据、不自动恢复读取。旧任务取消或成功不因找回而重新启动。查不到不证明创建请求未到达，前端保留标识并禁止重复创建。
- 前端 sessionStorage 只保存按 owner 分区的随机请求标识，不保存 Token、URL、名称或文件。响应丢失后只读找回；刷新从真实来源恢复仓库、凭据版本、分支、完整封存清单和范围；成功结果另查询原最终业务结果。迟到请求不能清除新版或另一用户记录。
- Git 创建的 Windows 只读对象，在确认读取进程/凭据通道停止、校验任务私有路径及全部子路径后才解除只读并删除。不触碰原工程或其他应用。`CANCELLED / cleanup=FAILED` 保留失败，可再次清理；只有 `DONE` 才释放前端请求记录。

## 实际命令与证据

下列日志根目录为 `temp/import-backend-publication-20261004/`，命令从仓库根执行；node/npm 命令从 `ai-generate-code-frontend` 执行。

| 命令 | 退出码与结果 | 证据 |
|---|---|---|
| `./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest'` | 0；225 项，224 通过、0 失败/错误、1 未执行 | `source-request-java-cleanup-current.log`、`source-cleanup-junit/` |
| `npm run test:unit -- --run` | 0；208 项、28 文件通过 | `source-request-frontend-unit-current.log` |
| `npm run build` | 0；类型检查与构建通过，保留原大 chunk 警告 | `source-request-frontend-build-current.log` |
| `powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-backend-publication-20261004/runtime-source-cleanup` | 标准重启，实际 PID 28036、8124 监听、Spring 启动完成 | `restart-source-cleanup.log`、`runtime-source-cleanup/backend.stdout.log` |
| `.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-source-request.py` | 0；4 组局部真实检查 | `source-request-api-current.log`、`source-request-api/result.json` |
| `node scripts/verify-git-import-recovery-real.mjs` | 0；4 组真实浏览器检查 | `source-request-browser-current.log`、`temp/git-import-recovery-1791103091104/result.json` |
| `node scripts/verify-git-import-cancel-real.mjs` | 0；3 组取消时序回归 | `source-request-cancel-regression.log`、`temp/git-import-cancel-1791103107103/result.json` |
| `node scripts/verify-git-import-real.mjs` | 0；8 组 GitHub 凭据负例/公开导入/最终文件回归 | `source-request-git-regression.log`、`temp/git-import-real-1791103113150/result.json` |
| `node scripts/verify-folder-import-real.mjs` | 0；6 组文件夹业务与工作台回归 | `source-request-folder-regression.log`、`temp/folder-import-real-1791103134759/result.json` |

Java 唯一 skipped 是需显式开启 `import.test.publicGit` 的原有联网测试，不写成通过；本轮另有实际 GitHub 业务验证，不能替代该测试执行记录。新识别/运行预览的后续前端检查单列，不混入上述 208。

V18 在 PID 22812 首次启动时真实应用，Flyway 成功到 v18；此后未修改迁移。实际数据库分别拒绝请求 ID/摘要半空与非法摘要。来源 `a30e6fd1-83f0-49e6-b9d5-12285242b8f0` 范围重建后重复原请求仍是同一任务，最终 `CANCELLED / DONE`、来源和主暂存目录均不存在、无 publication。故障浏览器先真实发送创建，再丢弃响应；刷新两阶段均保持原任务和固定内容，只有一个创建 POST。

## 保留失败，不抹除

- 首轮新增摘要测试发生 Mockito varargs 取值错误，退出 1；改用原始参数数组后通过。原日志 `source-request-unit-first.log` 保留。
- 首轮 API 与浏览器均复现 Windows Git 对象 `ReadOnly` 导致清理失败；真实任务 `d5fce9a5-39fa-42bd-aff5-1119e7989615`、`2760614a-8fa4-4fa1-80a6-df7276dd6515` 保留 `CANCELLED / SOURCE_CLEANUP_FAILED / FAILED`，不改 DB 宣称成功。原结果 `source-request-api/failed-readonly.json`、`temp/git-import-recovery-1791102871813/result.json`；两例失败来源残留仍保留。新基线取消实际清理通过，不将旧例追记 PASS。
- 此前一例文件夹发布失败的原始原因仍未确认，本次未宣称修复。

## 矩阵与未执行边界

局部覆盖 G10、C02、E01–E06 的请求隔离、重复/迟到、取消与故障恢复子场景，不提升 58 个整组为通过。有效私有仓库成功按用户要求暂缓；本轮未重做 Gitee 成功回归。真实后端重启期间读取恢复、完整逃逸进程树、硬磁盘限额、特殊 Git 内容、Linux 权限仍需独立验证。识别、安装批准、独立执行副本和多服务后端未实现，旧导入应用执行门禁继续保留。

## 运行与回退

正式入口 `http://localhost:5173/project/import`。需要本地 PostgreSQL/Redis、既有 Git/受管凭据工具和原密钥环；本功能不要求安装新依赖。测试只创建普通隔离账号，不记录密码或真实 Token。

回退优先切回保存的前端/Java 文件版本并标准重启，保留密钥环和业务数据。V18 为可空增量，旧请求仍可兼容；已应用迁移不编辑、删除或擅自执行逆向 SQL。不清理用户工作区、不删除成功应用、不改 Git 历史。
