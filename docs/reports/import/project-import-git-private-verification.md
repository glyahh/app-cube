# Git 凭据读取：当前实现与局部证据

日期：2026-10-04。第一项内部增量；不是完整导入验收通过。

后续[固定范围重建与当前后端回归](project-import-git-range-verification.md)已追加；本页 208 测试/PID 41332 是本阶段原时点，不冒充后续版本。

## 实现范围

- 选中的凭据固定 owner、provider、UUID 与语义版本；来源创建、分支选择、清单发布及最终应用提交共享撤销资格。未选择才是公开路径；认证失败不改成匿名读取。
- 平台身份请求复用系统 curl，关闭宿主配置、重定向、明文协议与隐式线路切换。Authorization 只经标准输入送入 curl，不写 URL、参数、环境、配置文件或错误响应。
- 实际 Git 使用任务内 askpass 包装器与回环能力通道；包装器仅含可信程序路径。helper 每次供应秘密前后核验来源租约，不缓存 Token、不读宿主 GCM/SSH。
- 凭据通道实际停止后才能进入分支选择或范围确认。停止未确认保持 STOP_FAILED，保留资源，不能清理后宣称成功。
- 同 UUID 主任务保存真实凭据租约；提交再次核对来源有效期、固定 commit/branch、完整快照、应用名称、选项与封存内容。替换终止旧值未完成任务，不自动续跑；删除保留已完成应用。

## 当前真实局部验证

代码基线：`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d` 加未提交增量。环境：本机 Windows、Java 21、真实 PostgreSQL/Redis、系统 Git/curl、现有 Python；显式本机代理 7897、TLS 验证开启。没有使用 Codex Token，没有执行导入工程。

证据根：`temp/import-backend-publication-20261004/`。以下命令退出码均为 0：

```powershell
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,StaticResourceControllerTest' '-Dimport.test.publicGit=true'
powershell -ExecutionPolicy Bypass -File '.\scripts\restart-backend.ps1' -TimeoutSeconds 180 -LogDirectory temp/import-backend-publication-20261004/runtime-private-qualified
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-credentials.py --output temp/import-backend-publication-20261004/credential-private-linked-qualified-live
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-auth-negative.py --output temp/import-backend-publication-20261004/git-auth-negative-qualified-live
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-source.py --output temp/import-backend-publication-20261004/git-public-qualified-live --commit-provider GitHub
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-task-api.py --output temp/import-backend-publication-20261004/folder-private-qualified-regression --commit
```

- `git-private-source-types-qualified-regression.log`：23 个 Java 测试类，208 测试，失败/错误/跳过均 0。含实际 Git 从 askpass 取得虚构秘密、实际管道超时/进程退出；不等于私有平台成功认证。
- `credential-private-linked-qualified-live/result.json`：42 检查通过。真实接口/数据库/文件，凭据绑定 Git 主任务的关联与提交使用**明确标记的受控来源夹具**；替换后相关来源与主任务取消，其他凭据任务可提交，完成应用删除凭据后仍保留。没有模拟私有 clone 成功。
- `git-auth-negative-qualified-live/result.json`：18 检查通过。GitHub、Gitee 对虚构无效 Token 的真实身份请求均 AUTH_INVALID；未匿名读分支、未创建主任务/应用，失败来源目录实际清理。只是负例，不证明有效 Token 权限可用。
- `git-public-qualified-live/result.json`：33 检查通过；固定公开 commit 到最终 App、实际文件 SHA-256/归属与过期/失败提交阻断。
- `folder-private-qualified-regression/result.json`：55 检查通过；已有文件夹真实业务回归。
- 上述运行后端 PID 41332、8124 监听；其后范围重建增量需要自己的重新编译、重启及业务验证，不能沿用本段结果。

## 失败与修正

- 首次将多参数命令直接放入 GIT_ASKPASS，实际 Git 无法启动，报 Permission denied。`git-private-managed-tests.log` 保留失败；改为可信程序路径包装器后真实 `git credential fill` 通过，见 `askpass-native-diagnostic/wrapper-result.json`。未通过输出秘密排障。
- `git-private-source-final-regression.log` 与 `runtime-private-final` 保留泛型测试代码编译失败；显式修正 Map 类型后重跑 208 测试，再按标准脚本重启并完成上述真实回归。失败启动不算验证通过。

## 未完成 / 未执行

- G02/G04 有效私有测试仓库与专用 Token 的真实成功验收：用户明确暂缓，DEFERRED / NOT_RUN。不得使用个人/Codex 凭据补测。
- 正式 Git/凭据前端联调、固定范围重建的业务证据、识别/安装授权/多服务、Linux 可执行权限和完整进程/磁盘硬隔离尚需后续独立验证。
- C03 全通道泄露检查、持久化密钥轮换及第一项完整矩阵未全部通过。其他三项功能与跨项未实现联动继续 NOT_RUN。

## 操作与回退

开发环境需要独立 keyring、可信 Git/curl/Python/helper 路径、PostgreSQL/Redis 与明确网络配置；失钥或认证失败显式报错。已有 V12–V17 不修改、不回退数据库；本增量无新迁移。

停用导入时关闭入口、核实实际资源停止，保留业务记录、保护快照与密钥文件，不清空用户目录、不删除已完成应用、不改 Git 历史。回退源码只撤销本阶段精确差异，保留已有改动；版本回退前核对存量任务状态及 schema 兼容性。
