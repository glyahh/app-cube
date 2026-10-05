# 启动后端：只读核心局部证据

2026-10-04 后续增量：[启动只读检查与写入协调](./project-import-startup-business-verification.md)已有真实 API/数据库局部证据，组合 296 项中 295 通过、1 未执行。不是持久扫描任务或运行计划，相关安装/执行和第一项整体验收仍未完成；以下未接业务为原时点边界。

2026-10-04。用户已批准启动前端接后端；只推进第一项。分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，脏工作区未清理、未提交。下列不是第一项交付或真实运行验收。

## 实际增量

- `ImportStartupScanner`：只解析不可变文本，不读工作区、不联网、不执行工程或调用模型。支持嵌套 Node 的真实 scripts/包管理器冲突、实际静态入口、显式声明优先且坏声明不回退；Java 安全 XML 与 JDK 语法树只做 parse，不编译、不分析依赖、不运行注解处理器。Spring 插件与源码入口均核对，多个入口保留歧义，推断标 HEURISTIC。
- 声明构建命令保留，不因此获得执行权限；现有静态声明需对应实际入口，无法确认构建输出则 INCOMPLETE。输入限额、坏/重复/深层 JSON、XML 外部实体、越界目录不制造成功。
- `ImportStartupSnapshotStore`：复用私有数字命名暂存与实际字节封存；完整文件清单包括二进制、依赖、敏感文件。摘要包含内容及目录/执行权限/POSIX/DOS/Windows ACL 属性，不靠大小/mtime。原目录再次全量复核；解析只读取合法有界 UTF-8 输入，敏感字节不因进入快照而进入识别器或模型；私有副本被改后拒绝继续解析。
- 新组件尚非 Spring 业务接口，没有放开导入工程的旧 Agent/runtime/deploy 门禁。没有执行任何导入工程脚本、安装工程依赖、增加解析依赖或新增数据库迁移。

## 必须保持的未完成边界

快照组件要求调用方协调受管写入。当前没有接入真实应用写入/删除协调，因此**不能用复核两遍证明并发工作区一致快照已完成**。没有 owner/app/import task/READY/contextEpoch/配置资格的业务接入，也没有持久扫描任务和重启恢复。扫描器的 `inputHash` 仅标识文本解析输入，完整内容身份是快照的 `contentHash`，不可混用。

Python 规则当前明确 INCOMPLETE；Java 仅支持已核对的 Maven Spring 插件/可见 Java 入口，不将任意 pom、父插件继承、Gradle/Kotlin或其他语言称为已支持。Node workspace 关系、模块依赖、相对静态资源检查仍需补齐。声明端口/就绪方法、手动配置、Agent 只读补充、不可变计划/批准、准备凭据、硬资源隔离、多服务就绪/停止均待开发；现有前端还是独立评审样本。

私有副本生命周期/所有者持久化与实际防篡改权限尚未接业务；本组件的内存元数据和摘要核验不冒充已交付的保护快照。外部非受管写入与跨实例协调尚未验证。

## 实际执行

仓库根目录执行，日志均在 `temp/import-startup-backend-20261004/`：

```powershell
./mvnw.cmd -q test '-Dtest=ImportStartupScannerTest'
./mvnw.cmd -q test '-Dtest=ImportStartupScannerTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,WorkspacePathResolverTest,ImportWorkspaceGateTest'
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest'
./mvnw.cmd -q test '-Dtest=ImportStartupScannerTest,ImportStartupSnapshotStoreTest'
```

- 首次清单校验参数编译失败保留 `scanner-unit-first.log`；新增 Java 用例先出现 3 个断言失败，保留 `scanner-java-tests-before.log`。Java 实现曾因局部变量重名出现编译问题，保留 `scanner-java-qualified.log`，修复后 `scanner-java-fixed.log` 退出 0。
- `scanner-regression.log`、`snapshot-first.log`、`startup-and-import-regression.log`、最终代码 `startup-final-unit.log` 均退出 0。
- 两个新增类：22 项识别、11 项快照，共 **33 PASS**。组合既有回归共 **27 类、276 项，275 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**。保留 `junit-current/` XML 与 `results.json`。SKIPPED 是原 Git 网络测试，需显式 `import.test.publicGit` 条件，不记为通过。
- 组合回归后补了 Windows ACL 属性与私有副本读取的内容/长度核验，再执行最终两类单元测试；不将其他类旧 XML 伪称最终代码又全部重跑。
- Maven Mockito/ByteBuddy 动态加载警告保留，不删除警告制造干净结果。

新用例覆盖完整敏感/二进制副本但不给解析器、原目录不改、同大小/同 mtime 变化、额外/缺失文件、依赖内容变化、空目录与权限变化、取消清理、文件/字节限额、范围隔离、非法 UTF-8、私有副本损坏、元数据不可变，以及坏声明/锁冲突/多入口/Java 语法/深 JSON/无脚本等分支。

## 验收状态

S01–S04、S07 仅以上内部子断言局部通过；真实接口、写入协调、支持样本实跑未执行。S05/S06、D01–D08、R01–R08 的业务/执行断言 NOT_RUN。没有本轮生产浏览器联调、真实模型或 Docker 安装运行结果。58 个整组仍不能改 PASS；有效 GitHub/Gitee 私有成功验收按用户要求暂缓。

## 运行与回退

组件无公开调用入口，本轮不能提供真实安装/运行命令。重放用上方测试命令；内部测试只使用临时夹具。回退仅撤销本轮新增组件/测试和文档增量，不修改 V12–V18、不撤销用户其他改动、不清理已导入应用或失败证据。

## 后端重启与版本工件

按仓库规定执行 `powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -TimeoutSeconds 180 -LogDirectory "temp/import-startup-backend-20261004/runtime-core"`，输出保留 `restart-core.log`。原 PID 28036 停止，新 PID 39416；8124 实际监听，Spring `Started`、PostgreSQL checkpointer/store 成功与 Redis 初始化日志均存在，见 `runtime-core/`。`runtime-smoke.json` 记录 health/DB/Redis UP 和未登录导入请求 HTTP 401/code 40100，只作为平台启动/权限冒烟，不作为新业务验收。

脚本已输出“后端已重启”，但执行器会话 42785 的管道尚未回收，因此本轮不伪写该外层命令退出码 0；新进程与 HTTP 状态为独立实测证据。四个新增源码/测试的 SHA256 保留 `source-manifest.json`，未提交修改以这些工件界定代码版本。`git diff --check` 退出 0，原工作区 CRLF 警告保留。