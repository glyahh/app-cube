# 固定 Git 内容的范围重建

2026-10-04。第一项内部小步；正式 Git 前端尚未接入此接口，整项未交付。

## 当前行为

`POST /api/project/import/git/tasks/{taskId}/range`：登录会话 + Origin/CSRF + `ownerId/revision/appName/options`。只能重建本用户、未过期、未发布、已停止来源读取且处于 WAITING_CONFIRMATION 的任务。

- 先撤销来源的旧确认资格，再按新名称/选项重建。无效名称、空范围、错误归属和旧 revision 在清理前拒绝。
- 固定 commit/branch、完整对象清单和凭据语义版本不变；不重新访问 heads、不创建凭据通道、不切换身份。
- 读取器显式 OFFLINE_FIXED_OBJECTS，关闭全部 Git 传输协议，隔离 HOME/config/template；实际对象树必须等于持久快照，blob 校验原始长度及对象摘要。
- 清理仅限本任务原接收字节，保留 Git 对象、原工程和其他任务；主任务 revision 单调递增，不重置为 0。
- 新范围重新接收和封存之后，来源/主任务内容 hash 相同才恢复确认资格。旧主任务版本不能提交；失败或取消不恢复旧确认资格。
- 最终应用仍按来源资格、快照、名称/选项和当前主任务版本再次核验。没有执行项目、安装依赖或开放旧运行入口。

## 当前验证

代码：`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d` + 未提交增量。证据根 `temp/import-backend-publication-20261004/`；测试使用真实 PostgreSQL/Redis、系统 Git 与已配置本机 TLS 代理，未安装新依赖。无新数据库迁移。

```powershell
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,StaticResourceControllerTest' '-Dimport.test.publicGit=true'
powershell -ExecutionPolicy Bypass -File '.\scripts\restart-backend.ps1' -TimeoutSeconds 180 -LogDirectory temp/import-backend-publication-20261004/runtime-git-range-observed
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-source.py --output temp/import-backend-publication-20261004/git-range-github-live --commit-provider GitHub --rebuild-range
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-source.py --output temp/import-backend-publication-20261004/git-range-gitee-numeric-qualified --commit-provider Gitee --rebuild-range
```

- `git-range-observability-regression.log`：214 项 Java 测试，失败/错误/跳过均 0，退出 0。新增离线读取拒绝联网、固定快照、缺失对象、范围授权、空范围不清理、旧版本拒绝及主任务版本推进检查。
- `git-range-github-live/result.json`：40 检查通过，退出 0；固定 `7fd1a60b01f91b314f59955a4e4d4e80d8edf11d`，主任务 revision 2 到 5，最终 App `2106646294164250624`、1 文件实际哈希一致。首次通过发生在发布观测日志增量之前，最终版本需追加当前运行结果，不能偷换代码版本。
- `git-range-github-observed/result.json`：在当前 PID 824 后端重新执行，40 检查通过、退出 0；固定 SHA、旧版本拒绝、最终文件与归属均核验。
- `git-range-gitee-numeric-qualified/result.json`：当前版本 40 检查通过、退出 0；固定 `081373c1b886911532c24851392a494616548f77`，主任务 revision 788 到 1577，最终 App `2106648038931709952`、394 个文件逐一核对长度及 SHA-256。
- 改变测试任务的广告 heads 是**明确受控的数据库夹具**；真实 Git 对象/原 SHA 不改，重建后快照保留，最终文件重新逐一核对。没有冒充真实远端分支移动。
- `credential-range-regression/result.json` 42、`auth-range-regression/result.json` 18 均退出 0、局部通过。它们发生在发布观测增量之前；有效私有 Token 成功验收仍 DEFERRED / NOT_RUN。
- 当前 PID 824 重新执行 `credential-range-observed/result.json`：42 检查通过；`auth-range-observed/result.json`：双平台认证负例通过、17 条请求记录，均退出 0。认证脚本记录轮询次数，17/18 不是独立用例数量差异，也不是覆盖率。
- `folder-range-observed/result.json`：55 检查通过，退出 0。
- `folder-during-range-rebuild/result.json`：55 检查通过，退出 0；触发证据 `folder-range-concurrent-trigger.log` 先确认当前后端 PID 824 的专用来源任务 `0cbd7db1-febb-4ad4-9a03-a5cc1520f656` 正在范围重建，再执行不同测试用户的文件夹提交。不是无关 QPS 压测。
- `restart-git-range-observed.log` 与对应 runtime 日志确认后端 PID 824、8124 `/api` 就绪。任务 ID、最终应用/目录、源码 hash 和 XML 报告以本阶段原始证据为准。

## 保留的失败与风险

1. `git-range-gitee-live/result.json`：首次验收脚本用字符串比较任务版本，较大的数值版本被误判失败。修正为 `int` 数值比较，不降低业务标准；失败报告保留，新的独立样本重跑。
2. `folder-range-regression/result.json`：一次真实文件夹最终提交返回 50001，不能记为通过。随后新增脱敏发布阶段/异常类型日志；标准重启后分别在来源读取和范围重建并发阶段重跑均通过，**尚未确认原始原因，不宣称已修复**。失败任务 `425c1cdd-3f5d-450c-8e53-e8c77ef950a9` 最终 CANCELLED、journal ABORTED，目标/候选目录均不存在，没有可用 App；测试源文件 hash 未改变。
3. 私有凭据版本撤销、取消与重建交错的更多真实故障注入仍需继续；完整磁盘硬配额、重启丢失进程树证明、特殊 Git 资源支持及第一项矩阵仍未全部验收。

回退：停止新范围入口，确认在途任务/进程实际停止；保留现有数据库、保护快照、密钥与用户文件。只回退本阶段精确源码差异，不重写已执行迁移、用户 Git 历史或工作区。旧执行门禁继续保留。
