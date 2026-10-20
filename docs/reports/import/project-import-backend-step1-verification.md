# 导入后端第一步：清单校验验证记录

日期：2026-10-04。平台 `code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，脏工作区；本步源码 SHA-256、前后 Git 状态见证据。未提交/推送。

## 实现与边界

- `ProjectImportManifest` 为独立纯 Java 校验器：全量路径/大小/额度校验后按本次不可变选项生成只读清单；默认保留依赖/产物、排除敏感配置并保留示例；排除项不能绕过校验。
- 拒绝绝对路径、目录穿越、重复、大小写别名（含父目录）、文件/目录冲突、Windows 保留名及非法字符、尾点/空格；路径最多 4096 UTF-16 单元、分段最多 255。兼容约束不等于实际 Windows 长路径写入已通过。
- 调用方必须显式传数量/来源字节额度；源码只检验声明元数据，无真实字节与文件类型/链接/磁盘验证。额度包括排除项，安全累加防溢出，空范围失败，不返回截断的部分清单。
- 复用范围仅下一步文件夹上传校验；无 HTTP 接口、Spring Bean、DB 表、任务、App 创建、文件操作、Shell 或模型调用。不提前开发其他三项，也不改旧运行架构。
- 前端提交仍显式报告导入服务未开通；不将本步接成假成功。后续必须实现真实字节校验、受管 staging、来源内容 hash、服务端归属、任务/确认版本、取消和最终提交资格。

## 实际执行

环境：Windows、Java 21、项目既有 Maven/JUnit/Mockito；未增加依赖、未运行导入工程。

| 执行 | 命令/记录 | 退出码与结果 |
|---|---|---|
| 测试先行 | `.\mvnw.cmd -q -Dtest=ProjectImportManifestTest test`；`red-test.log` | 1；校验器未实现，测试编译失败，预期红态 |
| 首次实现回归 | 下方相同 Maven 命令；`green-regression.log` | 0；首次通过，随后补长路径边界并重新执行最终版 |
| 最终版校验与回归 | `.\mvnw.cmd -q -Dtest=ProjectImportManifestTest,WorkspacePathResolverTest,UserPreferenceControllerTest,RuntimeSpecResolverTest,WorkspaceVersionerTest test` | 0；新增 62，既有 35，共 97，失败/错误/跳过均 0；见 `final-regression.log` 和 5 份 XML |
| 全面测试索引 | `powershell -ExecutionPolicy Bypass -File scripts/checks/check-import-backend-test-plan.ps1` | 0；58 个矩阵组逐项一致，不是业务 PASS |
| 索引检查负例 | 同脚本 `-PlanPath` 指向证据目录的 `missing.json`、`stale.json`、`false-pass.json` | 每次 1，符合预期：漏项、旧断言、无证据假 PASS 被拒绝；原计划未被改坏 |
| 差异检查 | `git diff --check` | 0；日志保留既有 CRLF 提示 |
| 项目规范重启 | `powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -LogDirectory "D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\import-backend-baseline-20261004\restart"` | 脚本 60 秒等待超时（1）；原启动进程继续运行，无二次重复重启 |
| 延后恢复核对 | 实际日志、8124 监听及匿名 `GET /api/user/get/login` | 00:10:50 服务启动，PID 23692；HTTP 200 / 业务 40100，匿名未登录。仅服务与已有认证冒烟，不是导入 E2E |

首次索引脚本在 Windows PowerShell 5.1 因无 BOM 的中文源码解析失败；已保留中文并用 UTF-8 BOM 修正，最终正/负检查重新执行。Mockito 动态 Agent 的既有 JDK 警告保留，未忽略测试错误。重启日志显示已有 12 个迁移校验完成、`biz` 已是最新、无需迁移；本步没有新建或手动执行迁移。

## 对原验收矩阵的贡献

F01–F06、U03/U04 仅有清单层局部断言，方法映射写入机器清单。F01 的实际字节/空目录、F05 的真实链接与磁盘边界、F06 的断流/文件读取故障等仍未执行；其他 Git/凭据/偏好/识别/安装/多服务/恢复/E2E 未实现或未执行。**58 个整组仍全部 NOT_RUN**，97 个 Java 测试不能代替它们。

共同版本契约尚未接入真实导入任务，跨项联动不宣称通过。新项目真实模型生成、Docker 沙箱与数据库导入提交回归未执行。本步纯校验器没有对应 owner/app/task/context，记录为不涉及，而非编造标识。

## 证据、重跑与回退

证据目录：`D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\import-backend-baseline-20261004\`，含 `verification-record.json`、源文件 hash、测试 XML、命令日志、索引负例及 `runtime-check.json`。本地运行日志按原平台配置记录，不对外发布凭据或原始配置。

重跑上表最终 Maven 与索引命令即可验证本步，不需要启动服务、数据库或导入工程。回退只需撤销本步新增校验器、其测试、索引脚本与新增测试文档，以及本步状态增量；按差异操作，不用 `git reset/clean` 覆盖现有改动。无新增 DB 迁移，无业务文件需恢复。

下一步：真实文件夹上传、独立任务与 staging。先写实际字节/归属/取消/版本测试，再接接口；最终 App 发布在所有读入口与恢复资格验证后实施，不能先造一个可访问的半成品 App。
