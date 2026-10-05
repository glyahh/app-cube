# 安装确认与按计划运行：步骤 6–9

日期：2026-10-05。**第一项 NOT_COMPLETE。** 58 组导入矩阵整组仍是 `NOT_RUN`。本步没有把内存准入失败记成安装成功或服务已启动，也没有跑有效私有仓库成功验收。S05 未声称通过。

## 实现

- `V27__project_import_startup_preparation.sql`：同一已确认计划版本的安装或运行只保留一行。身份列冻结，更新必须把 revision 加一。拒绝、阻断、准入失败和没有任何服务就绪都必须带原因。
- `ImportStartupPreparationService`：
  - 拒绝安装只记 `REJECTED`，不调用执行。
  - 依赖状态保持计划里的 `UNKNOWN`。目录里有文件不算就绪。没有写明安装命令时记 `BLOCKED` / `DEPENDENCY_UNKNOWN`，不自行补 `npm install`。
  - 写明了安装命令时先做内存硬上限准入。Docker 的 `SwapLimit` 仍是 false，但启动后可以把该容器的 `memory.swap.max` 写成 0。钉不住才记 `REFUSED`，不执行命令。
  - 运行前若依赖仍需安装且用户未同意或安装未成功，记 `WAITING_INSTALL` 或沿用安装失败，不启动。
  - 准入通过后按计划执行共享构建和已选服务。一项失败不重跑已就绪项；重试只执行未就绪服务。计划失效时直接拒绝，不把新旧结果拼成一次成功。
  - 服务启动时只读取已有记录，不自动重跑。
- 正式工作台在计划已确认后可以点「安装依赖」和「运行所选」。拒绝、未核对和准入失败只显示原因，不显示已启动。旧的 Agent / 运行 / 发布入口仍然关闭。
- 磁盘：新建扫描和 Git 拉取前，剩余空间低于配置下限就停止，不截断、不开始传输。这是准入拒绝，不是操作系统硬配额。

## 命令与结果

```powershell
.\mvnw.cmd -q test "-Dtest=ImportStartupPreparationTest,ImportStartupPreparationServiceTest,ImportStartupPreparationControllerTest,ImportStartupPreparationRepositoryTest,ImportDiskAdmissionTest,ImportStartupScanServiceTest,ImportExecutionServiceTest,ImportGitReaderTest"
.\agent\.venv\Scripts\python.exe scripts\verify-startup-preparation-schema.py
```

- 准备相关 Java 测试最终退出码 0。其中服务测试覆盖：拒绝安装不执行、未知依赖不启动、内存拒绝不执行、静态计划准入失败不启动、一项失败后重试不重复启动已就绪项、失效计划不能运行。
- 同轮扫描服务与执行服务测试在修正前已通过；Git 读取测试退出码 0。
- Schema 探针退出码 0，4 项 PASS，事务回滚。证据 `temp/import-startup-preparation-20261005/schema-1791183619043001900/result.json`。
- 标准重启退出码 0，`http://localhost:8124/api` 已监听。Flyway `27` 已应用到 `biz`，准备表当前 0 行。
- 前端 `startup.test.ts` 与 `StartupRunPreview.test.ts` 共 13 项通过。
- 2026-10-05 随后的真实钉交换区核对：`IMPORT_EXEC_PIN=1` 下 `ImportExecutionSandboxTest` 13 项、0 跳过、退出码 0。其中 `liveHardMemoryRunsSmallCommandAndKillsOversize` 用时 12.8 秒：默认硬上限准入通过，`echo live-ok` 成功；64MB 上限内容器分配 180MB 被记为 `FAILED` / `MEMORY_LIMIT`。

## 矩阵

F01–F07、G01–G11、C01–C05、U01–U06、D01–D08、S01–S07、R01–R08、E01–E06 整组都是 `NOT_RUN`。

本步单元行为只说明对应判断存在，不能改记整组通过：

- D02 / D04：拒绝安装或不确认安装时不执行、不启动。
- D07：`UNKNOWN` 不会被改成 `READY`。
- R04 / R05：模拟执行里成功项保留，重试不再调用成功项。计划变化直接拒绝。
- R08：内存硬上限无法保证时拒绝，不建容器。
- E04 / E05：没有做真实问题复现和干净基线回放。

有效私有仓库成功验收继续暂缓。完整进程树隔离和操作系统级磁盘硬配额仍未提供；进程停止失败时现有执行账本保持未确认，不把未退出的进程记成已停止。
