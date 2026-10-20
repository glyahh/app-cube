# 启动只读检查：应用资格与写入协调

2026-10-04，`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。用户已批准启动交互接后端。上一轮[纯核心证据](project-import-startup-backend-verification.md)仍保留，不冒充此前已经存在业务接口。

## 实现与范围

- `ImportWorkspaceCoordinator` 使用 PostgreSQL 会话级 advisory lock，正应用 ID 与 `Long.MIN_VALUE` 异或为专用负键；连接在操作期间持有，忙时 409。成功/异常都核对解锁；连接失效或解锁未确认显式失败。不新增外部队列，不更改原应用删除的事务语义。
- `AppServiceImpl` 的用户/管理员删除及带版本文本保存接共用协调，保留既有文件版本锁/CAS。导入应用旧 Agent/runtime/deploy 执行门禁仍关闭；生成项目 Agent 的其他写入未接本锁，因此本轮检查接口只接受已成功发布的导入应用，不宣称其他三项或生成项目全写入闭环已通过。
- `POST /api/project/import/apps/{appId}/startup/inspection` 接受 `{ownerId, scope}`；沿用导入会话、Origin、CSRF、请求体限额。真正的 owner 来自会话，管理员身份也不借用他人导入内容。
- 服务核对 Publication owner/READY、真实导入 SUCCEEDED 结果、app/task/原导入哈希一致和当前应用存在。在锁内建立完整输入，锁外解析，返回前重新取得同锁并全内容/属性复核；并发编辑或删除导致迟到结果拒绝。
- 返回只读 `Inspection` 的 appId/importTaskId/当前 contentHash/scope/result，不返回私有字节/路径；临时输入结束清理，清理失败显式报错。有效但没有解析覆盖的目录显示 INCOMPLETE，而非偷偷申请模型。

## 不是已完成的功能

该入口是同步只读检查，**不是持久 StartupScan、可批准 RunPlan 或执行授权**。暂时输入清理后不能拿 Inspection 直接执行，也没有上下文代次/持久任务找回/重启恢复协议；后续持久扫描、配置与计划必须重新绑定完整版本和资格。前端尚未联调本接口，预览继续隔离样本。Python、完整工程成员关系、相对静态资源、手动配置、Agent 只读补充、安装授权、多服务受管执行仍待开发。外部非受管文件修改、失联期间的跨实例持续写入及真实扫描故障恢复还未整体验收。

## 实际命令与结果

证据根目录 `temp/import-startup-business-20261004/`：

```powershell
./mvnw.cmd -q test '-Dtest=ImportWorkspaceCoordinatorTest'
./mvnw.cmd -q test '-Dtest=ImportStartupServiceTest,ImportWorkspaceCoordinatorTest,ImportStartupScannerTest,ImportStartupSnapshotStoreTest'
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
./mvnw.cmd -q test '-Dtest=ImportStartupControllerTest,ImportStartupServiceTest,ImportWorkspaceCoordinatorTest'
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-workspace-lock.py
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -TimeoutSeconds 180 -LogDirectory "temp/import-startup-business-20261004/runtime"
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py
```

- 初始协调测试 lambda 非 final 编译失败保留 `coordinator-first.log`，随后修正。`coordinator-qualified.log`、`business-first.log`、`business-regression.log`、最终增量 `business-final-api-unit.log` 均退出 0。
- 3 个新测试类 **13 PASS**：锁忙不执行、不释放他人锁、失败释放、连接/释放失败拒绝成功；本人成功、非本人管理员拒绝、未发布与错误任务/应用拒绝、捕获后编辑拒绝迟到结果、越界、合法未覆盖目录；控制器会话 owner 与真实 scope 绑定。
- 组合 **31 类、296 项，295 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**。全部组合后补控制器、清理资格标记与空目录处理，再重跑这三个受影响类；其余类没有伪称重跑。XML 与 `junit-current/results.json` 保留。唯一跳过仍是原 Git 网络测试，需显式环境条件。Mockito/ByteBuddy 警告保留。
- 实际 PostgreSQL 两个连接 **4 组 PASS**，`database-lock.log`、`database-lock-result.json`：同应用互斥、他人不能释放、另一应用独立、正常释放后可取得、持有连接实际关闭后释放。只用随机测试锁，不改业务表。此项不单独等同 Java/API 整体验收。
- 标准重启后新 Java **PID 47912**，8124 监听、Spring Started 日志与 PostgreSQL/Redis 初始化存在。`restart.log` 与 `runtime/` 保留；外层执行器管道尚未回收，未伪写其退出码 0。
- 新 PID 上真实 API 首轮 **5 组 PASS**，`inspection-api-first.log`、`api/result.json`。增加被占锁时用户删除拒绝和原导入保护字节校验后，独立新夹具 **6 组 PASS**，`inspection-api-qualified.log`、`api-1791107209481889400/result.json`，Python 退出 0。

最后夹具应用 `2106682444887101440`、导入任务 `585116e2-3c3f-463b-a354-7dd92bf04077`、owner `464290786178981888`。真实最终可编辑目录为 `temp/code_output/project_2106682444887101440/`，保护字节为 `temp/project_import_private/585116e2-3c3f-463b-a354-7dd92bf04077/0.bin`。可编辑 index 内容已按试验变化，保护字节仍是原内容；只读临时目录为空。不记录测试密码、Cookie、Token 或数据库连接串，不清理用户其他应用。

## 验收与下一步

仅 S01/S03/S07 的相关内部/同步接口子断言以及共享写入锁的局部真实验证通过。持久扫描任务、所有支持样本、S05/S06、D/R 安装运行业务链路与 58 整组仍 NOT_RUN，第一项 NOT_COMPLETE。有效私有双平台成功验收按用户要求暂缓。

回放用上方命令，脚本每次创建专用测试账号和导入应用，后续 API 证据使用独立时间目录；不在旧用户工程执行代码。回退限定本轮新增类/脚本、AppServiceImpl 的三个协调包装及文档增量，保留原方法内业务；不得 reset 工作区或改 V12–V18。下一步补持久扫描/版本上下文与 Python/模块覆盖，再接手动配置和运行计划，不直接放开旧执行器。
