# 启动 Agent 补充：内部只读核心

日期：2026-10-04。分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`；未提交源 SHA256 见 `temp/import-startup-agent-20261004/source-manifest.json`。**不是业务补充功能交付，第一项 NOT_COMPLETE。**

## 本轮实现

- `ImportStartupAgentPolicy` 核对当前识别目录：部分目录未识别时可申请，不要求全工程零候选；已识别/手动候选不覆盖，失败、不完整、歧义及受其影响的下级目录不冒充未识别。请求空/重复/未观察目录拒绝。这里只是纯策略，不产生实际用户批准。
- Python `taskKind=startup` 在创建工作区前走专用分支，复用既有主模型/出口，不自动切线路；不加载生成 Agent 的 Shell、文件工具、Git、checkpoint、store 或历史对话。批准后的合法文本直接作为唯一上下文，不给模型磁盘访问能力。
- 输入冻结 approval/scan/app/importTask/content/config/epoch；保护路径、重复 JSON 字段、非法路径及超限明确失败，不截断。配置未就绪不走原 placeholder 成功。
- 输出只允许 services/unidentified，恰好覆盖批准目录；候选命令/名称有界，证据必须属于投影。拒绝模型工具调用、额外安装字段、越界和无依据候选。服务端包裹原身份，标 AGENT/INFERRED 并计算依据 SHA256，不把模型推断当事实、运行成功或安装权限。
- 复用范围仅第一项启动识别；没有新增独立问答、海报或其他三项业务。现有导入旧执行门禁仍关闭。

## 实际命令

```powershell
.\agent\.venv\Scripts\python.exe -m pytest agent/tests/test_startup_agent.py -q
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
.\mvnw.cmd -q test '-Dtest=ImportStartupAgentPolicyTest'
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-agent-protocol.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-agent-20261004/runtime
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --async --cancel --recovery
```

证据根 `temp/import-startup-agent-20261004/`：

- 新 Python 初版24项通过；新增工具调用拒绝后25项，均为明确模型替身，不能记真实模型成功。最终 `python-regression-final.log` 退出0，**349项全部通过**，包括既有主循环/只读后端/敏感路径/路由等回归。
- 新 Java策略11项通过；`java-regression-final.log` 退出0，最终 **39类367项，366 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**；XML `java-xml/`。网络 Git 条件未启用。
- `protocol-first.log` 退出0；真实平台 Python Worker `protocol-1791123873122236000/result.json` 验证两条无效启动请求分别 error/__end__、序号0/1，主循环继续且未创建目标工作区。源码入口在模型装配前拒绝，模型调用0；不是合法批准或真实模型识别验收。stderr只记录长度/摘要，不输出配置。
- 标准重启真实 **PID42496 / 8124**，7.145秒启动，`runtime/`、`restart.log`；外层后台观察句柄不虚构退出码。无新增数据库迁移，V22保留。
- 重启后 `existing-api-regression.log` 退出0，**18组真实已有扫描/配置/取消/恢复回归通过**；原保护字节、版本隔离、跨用户和恢复资源边界仍成立。API没有使用新 Agent分支，不算新业务联调通过，具体 app/task/owner 及结果路径见该日志。

## 下一步与限制

- 必须接持久用户批准、请求幂等/取消/过期、服务器投影构造、当前完整内容/配置/epoch 回包复核，再接正式工作台。客户端提交任意 approvalId 不能获模型或工程执行权限；目前没有该公共入口。
- 目录级策略尚不证明同目录多语言的“局部未识别”已完整表达；继续核对解析诊断粒度，不能靠保守拒绝缩小原需求。扫描规则、合法相关上下文范围与实际模型窗口预算须在业务层再验证。
- 实际模型成功、提示注入/迟到结果业务验证、浏览器批准拒绝/取消与权限审计 **NOT_RUN**。Mock模型和真实协议失败都不是S05整组通过。
- 冻结运行计划、安装批准、隔离准备与多服务运行仍未交付；其余三项不展开，跨功能整组仍NOT_RUN。成功私有平台测试依用户决定暂缓。
- 回退不下发startup任务，保持旧生成入口和导入门禁；保留原导入、工作区、证据和迁移。不删除用户工程或Git历史。
