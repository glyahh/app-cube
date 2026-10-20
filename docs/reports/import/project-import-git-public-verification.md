# Git 公开来源：业务接入与验证证据

日期：2026-10-04。分支 `code_board`，基线 `d4528044d36f38a91567cb5ceb138169af6a873d`，包含未提交改动。仅第一项内部增量，不是整项交付。证据根 `temp/import-backend-publication-20261004/`。

## 已实现的窄链路

- `/api/project/import/git/tasks` 先持久登记来源、归属、冻结选项、执行器及实际进程资源；清单未取得时不创建 App 或占位主任务。
- 全 heads 取得后选一个服务端记录的分支，固定记录的 commit；客户端附加 commit 不能覆盖它。读取 bare 对象，不 checkout、执行 hooks/filter 或项目脚本。
- 真实树清单取得后，以同一 UUID 转为主导入任务，复用私有逐文件上传、内容封存、保护快照和最终 App 提交。原始 Git 元数据不进入应用目录。
- V16 来源记录及 V17 主任务 `source_kind` 已迁移。最终提交核对凭据、来源阶段、内容哈希与主任务；Git 来源缺失或失效不能回退为文件夹提交。
- 来源终态与主任务、App、发布 journal 在同一短事务内完成。Git 主任务不允许绕过来源协调器取消；实际停止和目录清理核实后才报 `CANCELLED/DONE`。
- 凭据替换/删除的撤销集合已覆盖准备来源和关联主任务，同 UUID 去重；来源先停止，失败不退到主任务接口伪装成功。此增量的真实受控夹具证据另见凭据验证记录。

## 实际命令与最终状态

```powershell
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-source.py --output temp/import-backend-publication-20261004/git-publication-github-sql-qualified --commit-provider GitHub
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-source.py --output temp/import-backend-publication-20261004/git-publication-gitee-child-exit --commit-provider Gitee
```

两次退出码均为 0，各 32 项实际检查通过，使用不同专用普通账号。核对最终文件大小/SHA256、App 归属、来源 `SUCCEEDED`、主任务绑定及无 `.git`。其中来源失效阻断提交用本测试自己的数据库状态注入，是受控负例，不是并发/断电验收。

| 平台 | 来源任务 | App | 固定 commit | 最终包含文件 |
| --- | --- | --- | --- | --- |
| GitHub | `ec6b721c-ab90-4a99-b6b6-600982cc4b69` | `2106487366830899200` | `7fd1a60b01f91b314f59955a4e4d4e80d8edf11d` | 1 |
| Gitee | `a0019d07-77e7-4c2d-9442-3d0230569099` | `2106630406383960064` | `081373c1b886911532c24851392a494616548f77` | 394 |

最终目录分别为 `temp/code_output/project_2106487366830899200/` 和 `temp/code_output/project_2106630406383960064/`。成功应用和保护文件保留；不清理用户工程，不修改远端仓库。脚本报告顶层整项 `gitApplicationAcceptance=NOT_RUN`，局部 `fixedContentPublication=PASS`，不是矛盾或整项通过。

## 失败与修复

- `git-publication-github-live/result.json`：读取资格/资源登记失败。短命子进程在观察时已经退出的情况不再当作未知活进程；活进程身份不明仍失败。旧 worker 结束仅删除自己的活动记录，不能删除下一阶段 worker。
- `git-publication-github-race-qualified/result.json`：来源封存失败；主任务已封存但未创建 App。`git-source-content-sql-diagnostic.json` 的真实 PostgreSQL EXPLAIN 证明未限定 `revision` 引发 SQLState `42702`，修复为 `s.revision` 后完成最终提交。
- `git-publication-gitee-live/result.json`：`CHILD_STILL_RUNNING`。主命令退出后辅助进程稍晚自然结束，立即判断误报失败。增加最长 5 秒自然退出等待，原超时/撤销门禁保持有效；仍存活则停止并失败，不能强制终止后报成功。
- `git-child-exit-tests.log`：`ImportProcessRunnerTest` 10 项通过、无跳过，命令退出 0；包含实际子进程自然退出与超时停止。`credential-source-revocation-tests.log`：服务/控制器/来源/进程共 31 项通过、无跳过，退出 0。
- `restart-child-exit.log`：标准重启脚本的 60 秒窗口超时，未视为启动成功，也未重复启动。随后有界等待 180 秒，原启动完成并取得监听证据，再执行 Gitee 验证。后续重启按实测使用 180 秒窗口。

## 尚未完成

### 2026-10-04 后续：过期门禁及当前回归

来源过期检查已覆盖关联主任务的 `READING_CONTENT` 和 `WAITING_CONFIRMATION`，不再只查 `main_task_created=false`；最终 reserve/finish/reclaim 的来源行锁查询采用数据库 `expires_at>now()`。成功来源不进入过期取消集合。

- `git-source-expiry-tests.log`：来源仓储 10 项通过，退出 0；其中自动过期集合覆盖由 SQL 条件单元检查，尚未冒充真实定时器/重启故障通过。
- `git-source-expiry-live/result.json`：退出 0、33 项检查通过。仅本测试来源有效期受控注入，过期提交拒绝且无 publication；恢复原有效期后最终成功。新任务 `182f8962-023f-4af6-bc5c-5fa39d2bba0e`、App `2106632465036369920`，GitHub 固定 commit 未变，1 文件最终哈希/归属校验通过。不是完整并发 TTL 验收。
- `folder-source-expiry-regression/result.json`：当前后端文件夹接口、提交及目录局部回归 55 项通过，退出 0。
- `credential-source-expiry-regression/result.json`：当前后端凭据及来源撤销受控回归 39 项通过，退出 0；无真实私有认证。
- `git-source-expiry-full-regression.log`：`mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,StaticResourceControllerTest' '-Dimport.test.publicGit=true'` 退出 0，实际匹配 21 类、191 项、0 失败/错误/跳过。未匹配的其他类不计入通过。
- `restart-source-expiry.log`、`runtime-source-expiry/backend.stdout.log`：已按项目规则重启，当前 PID 25708、8124 实际监听；启动日志与上述业务请求共同核验，不仅凭监听认定成功。

上述当前代码工件见 `git-source-expiry-artifacts/`：源码 SHA256、HEAD、未提交清单、独立 JUnit 副本及统计。旧 Gitee 394 文件实测对应前一增量，不冒充此次过期改动后重新完成 Gitee 全内容验收。

私有 Git 实际认证、准备来源凭据签发/认证读取、正式 Git 前端联调、固定内容上的过滤重建、孤儿进程完整停止恢复、源码执行模式应用与外部对象支持仍有缺口。执行隔离、识别、安装确认、多服务运行及第一项独立完整矩阵未完成。真实私有平台验收按用户要求暂缓，不能将公开读取或受控凭据绑定当作私有认证成功。

旧 Agent/运行/部署执行门禁仍保持；两份公开工程均未安装、运行或执行测试脚本。直连 DNS 未验收；显式本机代理保持 TLS 校验，不声明完整 OS 级进程/磁盘硬隔离。其余三项仍不开发。

## 复核与回退

复核以报告里的同一来源 UUID、App、commit、主任务 receipts 和最终目录为准；运行脚本会创建新的专用账号、任务及独立 App，不复用旧对象或覆盖已存在目录。完整回归需重新执行，不沿用本页历史数字。

不修改已执行 V12–V17，不回滚用户 Git 历史，不删除密钥或成功工程。需临时停止增量时停止后端来源入口，保留来源/主任务/journal 与私有目录，核实受管进程实际停止；前端不显示模拟成功。数据库结构后续只追加迁移。
