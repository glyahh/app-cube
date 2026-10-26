# 11 项缺陷修复与验证（2026-10-26）

基线：`code_board` / `e58d76125ef1fb0232bec64f92d941c97a7809d7`。原审查版本 `b71ad916bb72be8bc0dc92b2da4389073257ae9b` 与本基线仅相差生产力计划文档，源码相同。
来源：[Notion 原缺陷清单](https://app.notion.com/p/3ff4e68b2092817591b8f871663609c7)。范围仅 F01–F11，不含 2 项设计差异或五项生产力优化。

## 修复清单

| ID | 修复 | 实际证据 |
| --- | --- | --- |
| F01 | UPSERT 的截断状态保持不可恢复事实；回滚整批预检，不把丢失 before blob 且保留原 hash 的旧行当新增文件 | 原/新 SQL 在隔离 PostgreSQL 17 上红绿对照；回滚与账本 JUnit |
| F02 | 发布只复制公共资源白名单，拒绝隐藏/敏感/内部路径；暂存后替换旧发布副本；公开读取端二次拒绝 | 原 FileUtil.copyContent 实际泄露占位 .env 并保留旧残留；修后真实目录与控制器 200/404 回归 |
| F03 | SSE/WS 在 USER 历史写入前共用无副作用归属检查；持久化再校验且不吞权限错误；WS 拒绝请求不取消已有合法运行 | 缺失/其他用户 app 的双入口零写入，合法入口顺序，持久化防线 JUnit |
| F04 | 普通 app 的 execute 不复用/创建同类会话授权，按 HIGH 风险逐次审批；前端隐藏同类批准 | 基线 Java 回归失败、修后通过；前端事件及按钮回归 |
| F05 | 切换 SSE 时使旧 WS 身份/回调失效，已排队回调仍作身份检查 | 真实传输代码的 error→close、超时/迟到回调测试 |
| F06 | 保存绑定 app/path/代次/提交内容，后续草稿保留并保持 dirty | 实际 Vue 组件延迟响应、保存中继续输入与切换文件回归 |
| F07 | 文件读取与树请求按代次落地，关闭/换 app 后旧结果无效 | A/B 逆序完成、关闭与换 app 的 Vue 组件测试 |
| F08 | 失败停止保留执行身份；已结束但清理未确认记录仍可发现/重试；只有 cleanup DONE 才报清理成功 | 原版 runner 不调用移除的红测试；重复失败、恢复清理等 JUnit |
| F09 | 清理绑定原计划版本及归属，不要求旧计划仍满足启动条件；启动 confirmed 门禁不变 | 原版 STALE stop 拒绝的红测试；可停不可启及归属/版本回归 |
| F10 | Python 发完整 text.message；Java 保留 cancelled 和清洗后的 summary，不释放未确认 delta；前端避免摘要重复 | 新增 Python 6 项先红后绿；Python→Java→TS 真实转换链 6/6；相关 Python 78、映射 28、Java 15 |
| F11 | 普通 app 明确“已写入、确认保留、拒绝尝试回滚”；导入 app 保持写前候选合入语义 | 实际候选卡及 session composable 普通/导入两种语义回归 |

## 已完成验证

- Java 定向 12 类：124 tests，0 failures，0 errors，0 skipped；最后新增 WS 拒绝不取消当前任务断言已随最终源码重编译并通过。
- 前端完整 Vitest：33 文件、293 tests 全通过。type-check 与生产 build 通过；构建有既有 chunk size 提示，未隐藏。
- 前端同一新增回归对原基线：12 failed、47 passed；修后定向 59/59。
- F01/F04 Java 同一 15 项回归：原版 7 failed / 8 passed，修后 15 passed；未切换共享工作树源码。
- F08/F09 原版 2 项真实失败分别为未重试资源清理、过期计划拒绝停止；修后相关 86 项通过。
- Python 完整 pytest：652 passed、7 skipped、7 failed、18 errors。精确原基线独立运行全部失败所在 4 个文件：70 passed、7 failed、18 errors；25 个失败/error node ID 与本次完全相同，无新增失败项。

- 最终 Java 扩展回归（仅排除 2 个 Spring 应用上下文/截图集成类）：910 tests，864 passed，10 failures，10 errors，26 skipped。当前 12 个定向类仍为 124/124 全通过。剩余失败所在类已在原版独立目录对照，ModelExecutor/PythonReader 使用匹配的 Python 路径环境复现；涉及 Windows cmd/路径、Git/native worker 环境、既有持久化兜底文案断言和链接路径资格。首轮额外两个并发时序断言失败，在最终固定版本重跑通过，保留首轮记录，不当作稳定性保证。

## 未通过的基线问题与边界

- Python 7 失败：5 项 Windows 反斜杠路径/可执行文件解析相关；2 项 Windows shell/type/dir 行为与 Linux 不同。
- Python 18 errors：Docker 测试替身缺 `record.reused`，初始化即失败，不是实际 Docker daemon 验收失败；安全断言尚未执行。
- 不因此宣称“整个项目全绿”。未启动完整业务数据库/Redis/模型/真实 Docker 生产链，不宣称 Windows 或线上浏览器整链路验收完成。
- F02 白名单覆盖常用浏览器资产，不是任意扩展名托管；不扫描公开 JS/JSON 内容中的秘密。没有修改真实已发布网站，旧目录残留在重新发布时清理，读取端即时拒绝已知敏感路径。
- F04 采用保守逐次授权，不声称已经实现可靠的命令语义归类；明确选择的 AUTO 模式及沙箱硬边界保留。
- 所有修改仅在当前 dot 云端副本，未提交/推送/部署，未操作用户电脑或创建 Codex/Work 会话。

## 可复现命令

前端：`npm ci`，`npm run test:unit`，`npm run type-check`，`npm run build-only`。
Python：在 agent 目录 `uv sync --frozen`，`.venv/bin/python -m pytest -q`（上述已知基线失败应如实出现）。
Java：JDK 21，`sh mvnw test`；本云端 Mockito 动态 self-attach 不可用，测试 JVM 显式 `-javaagent:<mockito-core-5.17.0.jar>`，没有改变系统安全设置。

依赖安装只使用锁定版本与校验：npm 镜像不可用时在仓库外临时安装目录将下载地址映射至 npm 官方 registry，版本/integrity 不变；未修改项目 lockfile。Maven/Python cache/JDK/临时 PostgreSQL 均在临时路径，未安装全局工具或使用真实凭据。
