# Python 启动只读识别：局部验证

2026-10-04；`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。仅第一项，用户已批准启动后端。下列结果不等于安装、运行或整项交付。

## 实际增量

- 平台自有 `scripts/import-startup-python.py` 使用 Python 标准库 `tomllib`/`ast` 读取已封存文本，不 import 工程，不执行入口、插件或配置。不增加解析依赖。
- PEP 621 console script 验证实际模块与顶层函数；FastAPI/Flask 导入别名、顶层实例和依赖声明形成 HEURISTIC 候选；src 布局和多入口分别处理，字符串/注释/函数内样例不冒充服务。root/src 同名候选使用独立 ID，歧义保留。
- 坏 TOML/语法 FAILED，单文件/AST节点超限 INCOMPLETE，依赖外部引用 `-r/-c/-e` 暂为 INCOMPLETE，不在只读解析中加载外部文件或偷偷当两层未识别。有效库与缺受支持入口保留诊断。
- `ImportPythonStartupReader` 复用现有平台 Python 与 `ImportProcessRunner`，通过 `-I -S` 移除工程/用户站点和 site 初始化，环境只保留必要 SystemRoot。输入私有数字任务目录、有界 JSON；真实资源 PID/启动时间登记，30 秒时限、1 MiB 输出与 8 KiB 错误上限。未确认停止时保留资源目录而非清理冒充停止。
- 请求实际字节 SHA256 随辅助结果返回并核对，不能把另一个输入的候选套到当前解析结果；完整工作区身份仍由业务快照核对，传输摘要不冒充它。错误保留固定诊断码，STOP_FAILED 明确提示。
- 同目录 Node/Python 不再被首个形态排他覆盖；根显式 run.json 仍优先，坏声明不回退。候选不是就绪证明。正式前端尚未联调。

## 实际验证

日志根 `temp/import-startup-python-20261004/`：

```powershell
.\agent\.venv\Scripts\python.exe -I scripts/test_import_startup_python.py
./mvnw.cmd -q test '-Dtest=ImportPythonStartupReaderTest,ImportStartupServiceTest,ImportProcessRunnerTest'
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
./mvnw.cmd -q test '-Dtest=ImportPythonStartupReaderTest,ImportStartupScannerTest,ImportStartupServiceTest'
./mvnw.cmd -q test '-Dtest=ImportPythonStartupReaderTest'
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -TimeoutSeconds 180 -LogDirectory "temp/import-startup-python-20261004/runtime-input-identity"
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python
```

- 标准库 **12 PASS**：别名/src、文本与样例干扰、声明函数存在、坏 TOML/语法、多实例歧义、库与缺依赖、测试/子工程隔离、顶层异常不执行、越界声明、依赖间接引用、源码超限、同名 ID。`python-unit-final.log`、`python-input-identity-unit.log` 退出 0。
- 新 Reader **6 PASS**：真实平台解析进程、不 import 顶层异常工程；输入身份保留；缺环境无假候选；取消资格在启动前拒绝并清理；非 Python 不需要 Python 环境；同目录双语言保留；异输入 SHA 拒绝（其中异输入响应使用明确 Mock，只证明拒绝分支）。
- 初次真实 Reader 失败保留 `java-first.log`、`java-diagnostic.log`：资源登记的 Instant 序列化未启用 JSR310，修正为实际可用的 JavaTimeModule。之后缺环境用例仍失败是测试错误地重新调用 `properties()`，丢失已设置的缺失路径；反射与字节码定位证据保留 `java-missing-parser-isolated.log`，修正测试参数而非放松产品校验。
- `java-reader-qualified.log`、`combined-regression.log`、`java-final.log`、`java-input-identity-qualified.log`、`java-input-identity-negative.log` 均退出 0。组合历史与最终受影响类共 **32 类、302 项，301 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**，XML/清单在 `junit-final/`。整组回归后新增传输身份验证并重跑 Reader/Scanner/Service，再重跑新增 Reader 负例；其他类不伪称在最终改动后全部重新执行。SKIPPED 为原需显式 Git 网络条件的用例。Mockito/ByteBuddy 警告保留。
- 首次重启 PID11984 实际 85 秒完成，没有因一次观察超时就重启。传输协议变更并测试后标准重启为 **PID4600**、8124，见 `runtime-input-identity/` 与 `restart-input-identity.log`。外层后台管道未回收，不虚构退出码。
- 新 PID 上真实 API **7 组 PASS**，Python 退出 0，`python-real-api.log`，详细结果 `temp/import-startup-business-20261004/api-1791116053416380800/result.json`。包含真实文件夹最终提交、静态/Python混合候选、异常工程不执行、跨用户/会话/目录拒绝、跨数据库连接占锁时检查/保存/删除拒绝、保存后版本变化/旧 CAS 拒绝、保护原字节与临时清理。
- 实际夹具：app `2106719543388635136`、import task `bea4d2ea-392d-435c-8115-d2ba88833430`、owner `464327881249402880`。可编辑副本 `temp/code_output/project_2106719543388635136/`；保护字节仍为原内容。密码、Cookie、Token/DSN 不落证据。
- `parser-cleanup-proof.json`：实际匹配平台 helper 的进程 0、私有解析任务目录 0。只匹配本 helper，不能把其他 Agent Python 进程混为导入解析资源。正常作业资源记录随私有输入清理，尚无持久扫描资源审计账本。

## 剩余边界

只读解析的输入/输出/耗时限制不等于整个执行环境的硬 CPU/内存/磁盘隔离；持久任务配额、跨实例及重启资源恢复、运行中取消和 STOP_FAILED 恢复未完成。目录型输入属于平台自有解析器数据，不授权工程执行。复杂 Poetry/uv 工作区、动态入口/工厂、间接依赖、广泛框架支持及真实环境就绪还未证明；不按这几个样本声称所有 Python 项目可运行。

持久 StartupScan/contextEpoch/手动配置、Agent只读补充、冻结 RunPlan、安装确认、准备凭据、隔离执行副本与多服务最终业务仍待开发。S01–S07 只记录上述子断言；D/R、58整组、第一项整体验收仍 NOT_RUN/NOT_COMPLETE。有效私有双平台成功验收继续按用户要求暂缓。无迁移、依赖安装、模型调用或工程运行；旧执行门禁保留。

回放使用上方命令。回退仅撤销本轮 Reader/helper/测试及 Service/scanner 的对应增量，不改应用数据、保护原字节、V12–V18或用户其他改动；先核对实际解析资源已停止，不删除 STOP_FAILED 留存目录。下一步是持久扫描、上下文版本和手动配置，不直接用本候选执行命令。
