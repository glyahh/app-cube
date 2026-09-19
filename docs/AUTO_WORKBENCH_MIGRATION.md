# Auto 通用项目工作台改造 —— 实施与运维说明

本文记录这次改造落地的**代码范围、迁移步骤、启动方法、回滚方式**，以及**明确不支持的能力**。
验收结果分三块单独列出，不把其中一项当成全部完成。

---

## 一、改造做了什么

平台从「创建时预选 HTML / 多文件 / Vue」改为 **Auto**：不再让用户选类型，
产物形态由 agent 按需求决定，能否预览 / 运行由**服务端读实际产物**判定。

| 阶段 | 内容 |
|---|---|
| 1. 统一项目描述与预览 | 移除三类型选择与关键词路由；新增工作区描述接口；入口按真实产物识别 |
| 2. 文件与工作区界面 | 项目文件窗口（文件树 / 编辑 / 版本校验 / 草稿保护）、分栏拖拽与放大、看板勾选项 |
| 3. 运行与修复链路 | 运行时沙箱镜像、受管进程、指标 / 日志 / 网关、异常弹窗与修复入口 |
| 4. 知识系统 | 源码快照、异步知识生成、只读问答；V6 图谱与监控前端 |
| 5. 集成验收与文档 | 本文 |

---

## 二、数据库迁移

Flyway 增量迁移，**只加不删**，全部可空或有默认值，旧数据不受影响。

| 版本 | 内容 |
|---|---|
| `V5__app_dashboard_features.sql` | `app.dashboard_features TEXT` —— 看板功能勾选（监控 / 日志 / 知识图谱） |
| `V6__app_runtime.sql` | `app_runtime` 表 —— 运行台账（启动编号、容器、端口、版本） |
| `V7__user_runtime_alert_prefs.sql` | `user` 表两列 —— 启动异常弹窗默认开、运行异常弹窗默认关 |
| `V8__app_runtime_incident.sql` | `app_runtime_incident` 表 —— 运行异常台账（按指纹去重） |
| `V9__knowledge_system.sql` | 知识快照 / 任务 / 文档 / 节点 / 关系 / 问答历史 六张表 |

迁移在应用启动时自动执行，无需手工操作。

**注意**：`V1`–`V4` 已应用过，**不要修改**，改了会导致 Flyway 校验失败。

---

## 三、运行时沙箱镜像

运行时容器需要 Node.js / Python / Java / SQLite，而生成沙箱用的 `ubuntu:24.04`
**这些一个都没有**（实测确认），所以单独构建了一个镜像：

```bash
powershell -ExecutionPolicy Bypass -File deploy/sandbox/build.ps1
```

得到 `glyahh-sandbox-runtime:24.04`（Node 22.23.2 / Python 3.12.3 / OpenJDK 21 / Maven 3.8.7 / SQLite 3.45.1），
以非 root 用户 `runner` 运行。

**首次运行前必须构建一次**，否则点「运行」会报「运行镜像不存在」。

---

## 四、启动方法

```bash
# 后端（8124，需 PostgreSQL + Redis 在线；Docker 供运行时容器使用）
./mvnw.cmd spring-boot:run

# 前端
cd ai-generate-code-frontend && npm run dev
```

后端启动脚本 `scripts/restart-backend.ps1` 会先检查 Docker 与 Agent 虚拟环境，
两者缺一则拒绝启动 —— 这两者缺失时生成与运行都必然失败，早失败比晚失败好。

（本机 Docker 在 WSL 里，Windows 没有 `docker.exe`：代码与脚本都做了
`wsl.exe -d <distro> -- docker` 的兜底探测。）

---

## 五、回滚

按依赖倒序回滚，**每一步都可独立执行**：

1. **前端**：`git checkout <改造前提交> -- ai-generate-code-frontend/`
2. **后端代码**：`git revert` 改造提交（或切回改造前分支）
3. **数据库**：迁移是增量的，旧代码**不需要删表也能跑** —— 新增的列与表对旧逻辑不可见。
   若要彻底清理：
   ```sql
   DROP TABLE IF EXISTS biz.knowledge_qa_message, biz.knowledge_edge, biz.knowledge_node,
                        biz.knowledge_doc, biz.knowledge_task, biz.knowledge_snapshot;
   DROP TABLE IF EXISTS biz.app_runtime_incident, biz.app_runtime;
   ALTER TABLE biz.app  DROP COLUMN IF EXISTS dashboard_features;
   ALTER TABLE biz."user" DROP COLUMN IF EXISTS alert_on_startup_failure;
   ALTER TABLE biz."user" DROP COLUMN IF EXISTS alert_on_runtime_failure;
   ```
   **警告**：`DROP` 会连同用户已生成的知识与运行记录一起删掉，回滚前先确认这些数据不需要保留。
4. **运行时容器**：`docker rm -f $(docker ps -aq --filter "name=glyahh-rt")`
5. **运行时镜像与日志**：`docker rmi glyahh-sandbox-runtime:24.04`；`temp/runtime_logs/` 可直接删

**旧项目的产物目录不会被迁移或改名**：老项目继续用 `vue_project_*` / `html_*` / `multi_file_*`，
新项目才用 `project_*`。回滚不涉及文件搬动。

---

## 六、明确不支持的能力

以下是**当前确实做不到**的，不是「还没试」。前端在这些情况下会给出明确说明，不会静默降级。

### 运行与发布

| 能力 | 状态 |
|---|---|
| Windows exe 在线执行 | 不支持 |
| Gradle 项目运行 | 不支持（Maven 支持；识别到 Gradle 会明确报「不支持」而不是猜命令） |
| 静态前端发布 | 支持（沿用原有 `deployKey` 机制） |
| 需要常驻服务的后端 / 全栈项目发布 | **不支持**，保留入口但提示「暂不支持」 |
| SQLite 以外的数据库 | 不支持（首期只托管 SQLite，数据随工作区持久化） |
| 跨平台重启保留监控历史 | 不支持（指标只在内存窗口，重启即清零） |
| 断点调试 / 实时调用链 / 长期监控存储 / 告警平台 | 不支持 |

### 知识系统

| 能力 | 状态 |
|---|---|
| 图数据库 | 不引入（节点与关系存关系表，查询够用） |
| 外部资料导入 | 不支持 |
| 知识面板直接改代码 | **不支持**（服务端固定只读，需改代码时回生成对话） |
| 实时请求追踪 | 不支持；图谱的 Trace 是**静态分析路径**，界面上明确标注「静态分析」 |

### 运行沙箱的隔离边界

- 运行容器**只把端口绑在 127.0.0.1**，不暴露到局域网；
- 预览走 `127.0.0.1:{port}`，与平台页面不同源，生成的应用**拿不到平台会话凭据**；
- 经平台网关的请求会**摘掉 Cookie** 再转发给应用；
- **已知边界**：容器出网没有被阻断（`--network bridge`），首期接受这一点。
  真需要出网隔离时，要引入独立 internal 网络 + 出网代理。

---

## 七、验收结果

### 7.1 代码实施

三端全部编译 / 构建通过，测试如下（均为本次实测）：

| 端 | 结果 |
|---|---|
| 后端 | `mvnw compile` 通过；**149 / 149** 测试通过（`BUILD SUCCESS`） |
| 前端 | `vue-tsc --build` 通过；`npm run build` 通过；**135 / 135** 单测通过 |
| Agent（Python） | **301 / 301** 测试通过 |

**两个曾出现失败的测试，都不是本次改动引入的**，如实记录：

- `WebScreenShotUtilTest` —— 抓取外部站点 `https://www.codefather.cn` 的截图。
  该站点不可达时失败（实测 `HTTP 000`），可达时通过。测试文件本次未改动。
- `AgentWorkerPoolTest.discardDoesNotReturnWorkerToPool` —— 在跑完整套件时偶然失败一次
  （`discard 应从池内移除` 期望 0 实得 1），单独重跑 **3/3 通过**。
  它依赖真实 Python 子进程与池内计数，在全量套件的负载下不稳定。
  该类本次未改动（只新增了 `AgentRunService.streamUncoordinated`，不涉及池的结算逻辑）。

### 7.2 真实运行验收（实际跑过，非推断）

| 验收项 | 证据 |
|---|---|
| 原始故障修复 | 错配目录旧入口 `dist/index.html` **404** → 新入口 `index.html` **200** |
| 入口识别四态 | 无产物 → null；仅 index.html → index.html；有 package.json → null；有 dist → dist/index.html |
| 运行闭环 | 启动 → `RUNNING` + 端口映射 → 网关 / 直连 / 日志 / 指标全通 → 停止 → 容器清理、状态 `STOPPED` |
| 日志 | 带进程前缀：`[static] ... "GET / HTTP/1.1" 200` |
| 重复点击运行 | 不重复建容器（`runNo` 保持不变） |
| 运行版本落后 | 改源码后 `stale=true` |
| 空闲自动停止 | 第 43 秒自动停止（用短超时实测：心跳 10s + 保留 20s + 回收 10s） |
| 异常检测 | 启动失败（退出码 1）→ 事件落库（含 Traceback）→ 同指纹去重 → 关闭弹窗记录保留 → 修复返回脱敏提示词 → 重复修复被拒 |
| 重启中断 | 任务 RUNNING 时重启后端 → `INTERRUPTED` + `retryable=true` → 重试成功 |
| 知识生成 | 任务 SUCCEEDED，产出文档 / 节点 / 关系；节点带源码引用与 `CODE_EVIDENCE` / `AI_INFERENCE` 标记 |
| 知识只读问答 | 回答 1656 字并引用到 `/src/main.js:2-3`；`stale` 标记随版本变化 |
| 凭据不进知识 | `.env` 未出现在图谱节点中（修复前出现过）；知识内容未泄漏密钥 |
| 图谱交互（浏览器实测） | 搜索 → 命中 6 条 → 点选 → 抽屉显示源码位置与依赖；Trace 高亮路径；拖拽后画布变化 |
| 偏好开关 | 默认值（启动开 / 运行关）正确；部分更新互不覆盖 |

### 7.3 仍未支持的发布与运行能力

见第六节。要点：

- **需要常驻服务的项目发布**仍未打通（Plan 明确首期不做）；
- **Gradle** 与 **Windows exe** 不支持；
- 运行容器的**出网隔离**未做；
- 知识图谱的**动画性能指标**（500 节点 ≥30 FPS / 1000 节点可用）**没有在基准设备上实测过** ——
  引擎实现了 V6 的自适应降质（低配检测、绘制超时降 dpr、静止停帧、不可见停帧），
  但没有可引用的实测数字，按 Plan 的要求**不能用原型里的旧测试数字冒充**。

---

## 八、设计基准存档

V6 原型已原样归档，不再依赖临时目录：

- 路径：`docs/design/cosmic-code-workbench-v6.html`
- SHA256：`142e14f1b61156a9a3f7e3035d9b7fcff487640625d1feaa87c73c42312dbf8a`

图谱引擎（`ai-generate-code-frontend/src/utils/knowledgeGraphEngine.ts`）
与指标曲线（`.../utils/metricChart.ts`）均从该原型移植，
物理常量、性能策略、交互语义保持一一对应。
