# 文件夹导入：真实暂存任务与长期偏好验证

后续增量见[应用提交、恢复与工作台联调](./project-import-publication-verification.md)。本页保留暂存/偏好阶段的实际历史证据，不代表最新提交功能仍未实现，也不把后续局部通过视为整项交付。

日期：2026-10-04。范围仅第一项。分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区；源码内容摘要与命令记录见证据。本记录不替代最终应用提交验收。

## 当前业务与接口

- 已实现独立私有任务、冻结清单、顺序流式接收、实际长度与 SHA-256 校验、全量内容封存、会话归属、任务版本 CAS、超时与取消、失败清理状态。来源目录不修改；暂存不进入输出/发布目录，未调用模型、安装或运行工程。
- `GET /api/project/import/csrf` 获取当前会话的 CSRF；写请求须携带 `X-Project-Import-CSRF`。认证在读取请求体之前执行，来源限配置白名单，元数据请求体限制实际读取字节而非仅依赖 Content-Length。
- `POST /api/project/import/tasks` 创建任务；`ownerId` 必须匹配实际登录身份，不能指定其他归属；`options` 省略时仅在创建时读取长期偏好，显式选项仅用于本次任务。
- `PUT /api/project/import/tasks/{taskId}/files/{index}?revision=N` 使用 `application/octet-stream` 接收真实字节；序号和 revision 必须匹配。上传完成只到 `WAITING_CONFIRMATION`，不创建 App，不返回虚构的成功应用 ID。
- `GET /api/project/import/tasks/{taskId}` 读取持久状态；`POST /api/project/import/tasks/{taskId}/cancel?revision=N` 取消。实际接收线程未退出返回 `STOP_FAILED`，不冒充停止。旧进程遗留上传标记待恢复，未实现自动恢复，不冒称安全续跑。
- `GET/PUT /api/project/import/preferences` 读取/明确保存三项默认选项；保存请求包括当前 `ownerId` 与完整布尔字段。未设置时依赖/产物保留、敏感配置排除。已冻结任务不受后续偏好修改影响，临时调整不保存长期偏好。
- 服务端默认上限：10,000 个来源文件、1 GiB 声明来源字节、8 MiB 元数据、用户 2 个/全局 20 个未完成任务、256 MiB 磁盘余量、单文件接收/封存 5 分钟、停止确认 5 秒；均由 `project-import.*` 配置。它们是当前资源策略，不证明任意规模都已验收。默认前端来源仅 localhost/127.0.0.1:5173，部署须显式配置。

共享复用仅现有会话、PostgreSQL/Flyway、工作区路径配置、清单校验；没有开展其余三项或重写运行架构。前端正式提交仍未接通，避免把暂存结果冒充可用应用。

## 测试先行及实际结果

环境：Windows、Java 21、已有 Maven/JUnit/Mockito，已有 Python 虚拟环境、实际 PostgreSQL/Redis 与后端 8124；没有安装新依赖。

| 命令或验证 | 结果及证据 |
| --- | --- |
| 暂存/任务/偏好测试先行 | `staging-red.log`、`task-red.log`、`preference-red.log`：各退出 1，对应类未实现的预期编译失败 |
| 首次综合测试 | `full-tests.log` 保留失败：3 项错误地期待业务成功码 0；核对现有 `ResultUtils` 后测试改为既有 20000，未降低业务标准或改成功码 |
| 上传版最终回归 | `final-tests.log`：退出 0，128 项，失败/错误/跳过均 0 |
| 偏好版最终回归 | 下方 Maven 命令；`preference-tests.log`：退出 0，134 项，失败/错误/跳过均 0；其中本项 99、已有回归 35 |
| 真实暂存接口 | `python scripts/verify-import-task-api.py --output temp/import-backend-upload-20261004/http-0106`：退出 0，31 项检查；接口、真实磁盘哈希、任务刷新与取消清理通过，没有 App |
| 真实偏好及暂存接口 | 同脚本 `--output temp/import-backend-upload-20261004/http-preferences-0116`：退出 0，47 项检查；两位专用账号、另一新登录会话恢复偏好、跨用户隔离、临时选项不保存、旧任务范围不变，以及完整暂存负例与清理。不是 47 个导入整组用例 |
| 规范后端重启 | 两次执行 `powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory <对应证据目录>`；日志确认成功。最后 8124 监听 PID 49228，实际请求使用该服务 |

最终 Maven 命令（PowerShell 中将逗号参数加引号）：

```powershell
.\mvnw.cmd -q "-Dtest=ImportPreferenceServiceTest,ImportTaskServiceTest,ProjectImportControllerTest,ProjectImportStagingTest,ProjectImportManifestTest,WorkspacePathResolverTest,UserPreferenceControllerTest,RuntimeSpecResolverTest,WorkspaceVersionerTest" test
```

真实磁盘测试包括中文/空格、二进制、零字节、同大小/mtime 篡改拒绝、额外文件拒绝、断流、重复上传不覆盖、链接/Windows junction 防护、取消实际退出与非响应流停止失败。Windows junction 两例实际执行，未跳过。单元任务仓库为 Mock，不能替代真实 API 持久化验证。真实 API 使用两个独立账号查询持久任务，核对真实磁盘文件，再确认取消后的状态与目录不存在；源文件前后 SHA-256 一致。

实际启动已通过 Flyway 新增 `V12__project_import_task` 与 `V13__project_import_preference`，未修改已有 App 或用户表数据结构。验证脚本创建专用测试用户（用户名/ID 见 result.json），密码仅内存使用、不记录；本轮四个测试账号及各自任务/偏好记录保留，未擅自删除。测试暂存任务均已取消并核对清理；测试源工程只在证据目录。

## 原矩阵与尚未完成

- F01–F06、C02、U01–U04、R06、E01/E03/E04 获得相应**局部**清单/暂存/鉴权/偏好/取消/故障证据，不把后端局部验证升格为最终 App 或浏览器端到端通过。
- 原 58 个整组仍 `NOT_RUN`：应用提交和所有读取门禁、提交中断恢复、真实工作台跳转未完成；Git/多 Token、安装、识别、运行与多服务尚未完成。
- 按用户最新要求，GitHub/Gitee 私有仓库真实验收暂缓；没有使用 Codex Token 请求仓库，也没有读取/输出 Token。暂缓验收不取消双平台需求。
- 其他三项未实现，不宣称跨项业务联动通过。新项目真实模型生成、Docker 运行、未知工程安装/执行与浏览器联调本轮未执行。
- 单 Java 进程短状态锁与 DB CAS 不是多实例停止协调。旧 executor 遗留任务仍可能 `RECOVERY_REQUIRED/STOP_FAILED`，须完成资源确认/恢复协议；不能只改 DB 状态宣称停止。
- 没有最终发布或 App 清单原子提交；保护快照、敏感配置的模型/沙箱边界与静态读取闸门仍须接入。现阶段私有内容不提供读取/预览路径。

## 证据、运行与回退

证据根：`D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\import-backend-upload-20261004\`。包括失败日志、最终 JUnit XML、源码 SHA-256、Git 状态、真实接口 result.json、两次重启日志与版本记录。

重跑纯 Java 命令不需要运行导入工程。真实接口脚本要求已启动后端和正确 PostgreSQL/Redis，须为 `--output` 使用新的隔离目录；每次创建两个专用普通账号及任务记录，不自动删除账号。不要针对用户现有应用调用测试清理。

回退仅按本步差异撤销新导入 controller/package、暂存校验增量、对应测试/脚本和文档；保留原有用户改动。V12/V13 为已执行的增量迁移，不修改其校验和，不删除 Flyway 历史、不默认 DROP 表或删除记录。回到旧应用代码可保留新增表，后续如需数据迁移另行制定。没有已创建 App 或运行工程需回滚。

下一步：先写提交故障/版本/权限测试，建立持久提交记录和全部读取门禁，再接真实 App 与已批准前端；安装/运行等新增界面仍遵守前端评审节点。
