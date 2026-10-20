# 第一项关键使用风险：5组专项交付

## 专项结果

当前源码摘要：`e9302662502a8cf3fbdd38bbf3150bd93cb9d9e120f96f95c6f23416d47ba3d9`。5组主体52项断言、浏览器补充4项断言全部PASS，均为正式API、实际Docker与浏览器验证，不是Mock。

1. Vue＋Java 21/Spring Boot 3.5.10：批准安装、Maven构建、双服务运行，浏览器写入后端并读回，停止后重新运行及业务再验证。
2. 依赖就绪：无依赖、正常/损坏/不兼容带入依赖不能依目录存在误报；拒绝安装不执行；真实本地依赖安装；package/lock/pom修改使旧计划失效。
3. 手动补齐：保留已识别前端，手动标准库HTTP后端保存、浏览器刷新重新进入、实际双服务运行；正式拒绝Agent为REJECTED、模型调用0；损坏声明显式失败、旧配置提交冲突。
4. 失败与重试：受控构建失败、不监听、启动后退出；前端执行ID保持，后端修正后单项重试恢复，旧版本重试拒绝。
5. 重复与竞态：并发相同运行不重复、连续运行复用、启动中停止、重启、跨应用隔离；迟到结果不能恢复READY。

## 实际产品修复

真实失败证据：`group5/result.json`。启动尚未返回准备结果时，停止错误返回NOT_RUNNING；迟到启动随后恢复READY。
修复只涉及ImportStartupPreparationService：应用级运行/重试互斥、停止取消标记、停止活跃执行槽位、等待运行收口、阶段与提交前取消检查。停止未确认保留STOP_FAILED及资源占用，不允许立即重新运行。
新增两项并发回归测试。没有新增依赖、公开API或数据库迁移。

第3组正式拒绝补充使用明确受控变换：临时空requirements.txt帮助得到允许批准的Python范围，随后移除声明进行标准库手动运行，清理前恢复原样；未安装新包，不冒充自然扫描行为。原始server.custom未知范围不能直接批准Agent，保留现有权限限制。

## 证据与清理

- `verified-group1` 至 `verified-group5`：各组独立result.json。
- `group3-browser-spaced`：浏览器保存、刷新恢复、REJECTED与零模型调用补充。
- `verified-executions.json`：命令、退出码、执行时间。
- `independent-five-cleanup.json`：192项独立读回。
- `independent-browser-cleanup.json`：60项补充夹具读回。
- 原件哈希不变；本轮容器、原生卷目录、loop设备、挂载、Windows副本及占用均读回确认释放。
- `baseline/source-provenance.json`：本轮基线来源核对，未混入其他会话变更。
- `delivery/round.patch`、`delivery/source-manifest.json`：仅本轮补丁与前后文件摘要。

## 重放

在仓库根目录，服务就绪且没有其它会话构建或重启时：

```powershell
.\agent\.venv\Scripts\python.exe .\scripts\checks\verify-import-frameworks.py vue-java --outputs <全新证据目录>
.\agent\.venv\Scripts\python.exe .\scripts\verify-import-critical-risks.py 2 --outputs <全新证据目录>
.\agent\.venv\Scripts\python.exe .\scripts\verify-import-critical-risks.py 3 --outputs <全新证据目录>
.\agent\.venv\Scripts\python.exe .\scripts\verify-import-critical-risks.py 4 --outputs <全新证据目录>
.\agent\.venv\Scripts\python.exe .\scripts\verify-import-critical-risks.py 5 --outputs <全新证据目录>
```

浏览器补充重放：交付目录manual-browser-supplement.py与manual-browser.mjs。脚本只使用本次创建账号/应用，Cookie仅经子进程环境传递。正式模型核心流程见run-core.py；旧通过步骤仅复用本轮同源码摘要的真实收据，不复用历史版本数字。

## 回退

先检查，再反向应用；发生冲突停止，不reset、不覆盖整文件：

```powershell
git apply --reverse --check D:\codex\WorkDocument\2026-10-09\item1-five-risk\delivery\round.patch
git apply --reverse D:\codex\WorkDocument\2026-10-09\item1-five-risk\delivery\round.patch
powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1
```

本轮没有数据库迁移。保留本轮开始前已有未提交改动；不要用HEAD到当前的整仓补丁回退。

## 最终范围与状态

用户于2026-10-09批准以核心＋5组专项作为最终交付范围，第一项 **COMPLETE**，当前范围无剩余任务。核心18/18检查点、50/50断言、6/6交付包与本报告专项全部PASS。原856条全量验收及未交付扩展已移出要求，不再延期。详见[第一项最终范围与完成记录](../../status/import-scope-20261009.md)；原始门禁报告保持原始字段，范围收缩不改写未执行测试结果。
