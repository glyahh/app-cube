# Agent 权限门禁：沙箱边界 + 审批策略

状态：设计已确认，第三期开始实施。日期：2026-10-18。

本设计替代早先的「三层结构（硬边界 + 显式规则 + 模式）」方案。显式规则层**已取消**，改为 Codex 式两轴正交设计。

## 1. 为什么改

早先方案第 2 层要求用户写 `allow / ask / deny` 规则。问题是：

- 用户不知道在哪写、什么时候写，规则编辑器是重度功能
- Claude Code 有过真实缺陷（issue #63111）：`acceptEdits` 模式静默覆盖用户写的 per-path `ask` 规则，用户写了规则反被忽略
- 当前真实痛点是「选了手动批准但 Agent 照写不误」，属审批策略未接线，不是缺规则层

按 AGENTS.md「最小充分工程：不为假想的未来需求添加抽象」，本稿删除规则层。

## 2. 调研来源与局限

执行环境对以下域名返回 `non-public IP address`，无法读取正文：

`code.claude.com`、`opencode.ai`、`open-code.ai`、`mintlify.wiki`、`learn.chatgpt.com`、`github.com`、`raw.githubusercontent.com`

因此下表区分依据强度：

| 条目 | 依据 |
|---|---|
| Codex 有 `approval_policy` 与 `sandbox_mode` 两个正交维度 | 搜索摘要 |
| `sandbox_mode` 含 `read-only` / `workspace-write` / `danger-full-access` | 公开文档既有认知，**未核原文** |
| `approval_policy` 含 `untrusted` / `on-failure` / `on-request` / `never` | 公开文档既有认知，**未核原文** |
| Claude Code 的 `acceptEdits` 只自动批文件编辑，命令仍需批准 | 搜索摘要 |
| Claude Code 存在 `defaultMode` 静默覆盖 per-path `ask` 的缺陷 | 搜索摘要 |

实施前须用官方文档复核 `approval_policy` 语义。

来源：
- <https://mintlify.wiki/openai/codex/concepts/approvals>
- <https://mintlify.wiki/openai/codex/configuration/reference>
- <https://code.claude.com/docs/en/permission-modes>
- <https://opencode.ai/docs/zh-cn/permissions/>

## 3. 两轴设计

```
轴 A：沙箱边界（Sandbox）  —— 能碰什么，硬边界，不可协商
轴 B：审批策略（Approval） —— 何时问人，软策略，按模式
```

两轴独立：沙箱决定「做不到」，审批决定「要不要问」。

### 3.1 轴 A：沙箱档位

| 档位 | 文件写入 | 命令执行 | 用途 |
|---|---|---|---|
| `READ_ONLY` | 拒绝 | 拒绝 | 只读分析、知识问答 |
| `WORKSPACE_WRITE` | 仅 workspace 内、白名单命令 | 允许 | 默认，日常编码 |

**不实现 `FULL_ACCESS`**：本项目所有执行都在容器内且只挂载 workspace，该档相对 `WORKSPACE_WRITE` 无实质增量。不为对齐外部产品造没有真实差异的档位。

现有实现已具备的边界：

```
--network bridge          --pids-limit 512
--cpus 2                  --memory 4g / --memory-swap 4g（强制 swap=0 并读回）
--cap-drop ALL            --security-opt no-new-privileges
只挂载 workspace 到 /workspace，不暴露 docker.sock
```

命令层另有 `ensure_safe_command`：白名单 + 禁 shell 操作符 + 禁危险片段 + 参数路径越界检查。

`READ_ONLY` 复用已有 `backends/read_only.py:ReadOnlyBackend`。

### 3.2 轴 B：审批策略

| 模式 | 对应 Codex | 行为 |
|---|---|---|
| `MANUAL` | `untrusted` / `on-request` | 每次写操作 ASK |
| `GUARDED_AUTO` | `on-failure` 变体 | 服务端 AI 判非高风险放行，高风险 ASK |
| `AUTO` | `never` | 合法操作不问（沙箱边界仍拦） |

### 3.3 判定顺序

```
1. 沙箱检查（轴 A）
   路径在工作区内吗？命令在白名单吗？档位允许写吗？
   不通过 → DENY（硬拒绝，不问人，批准也不能过）

2. 审批策略（轴 B）
   MANUAL        → ASK
   GUARDED_AUTO  → AI 审查：ORDINARY 放行 / HIGH ASK
   AUTO          → ALLOW

3. 会话同类授权
   之前批准过相同 tool + risk + 范围 → 直接 ALLOW
```

第 1 步短路。沙箱拒绝时不进入第 2 步，因此「危险命令问一下就好」不存在。

## 4. 数据模型

V44 新增三张表，不改 V30–V43。

### 4.1 编辑会话

```sql
CREATE TABLE biz.agent_edit_session (
    session_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    app_id BIGINT NOT NULL REFERENCES biz.app(id),
    run_id BIGINT NOT NULL,
    source_hash TEXT NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    sandbox_mode TEXT NOT NULL CHECK (sandbox_mode IN ('READ_ONLY','WORKSPACE_WRITE')),
    approval_mode TEXT NOT NULL CHECK (approval_mode IN ('MANUAL','GUARDED_AUTO','AUTO')),
    policy_revision BIGINT NOT NULL,
    session_epoch UUID NOT NULL,
    server_generation UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('EDITING','WAITING_REVIEW','SUCCEEDED','CANCELLED','FAILED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX agent_edit_session_open_idx
    ON biz.agent_edit_session(app_id) WHERE closed_at IS NULL;
```

### 4.2 单次工具许可

字段对齐 V40 `project_import_agent_permission`，便于导入侧复用查询逻辑。

```sql
CREATE TABLE biz.agent_tool_permission (
    permission_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES biz.agent_edit_session(session_id),
    tool TEXT NOT NULL CHECK (tool IN ('write','edit','delete','upload_files','execute')),
    action_hash TEXT NOT NULL CHECK (action_hash ~ '^[0-9a-f]{64}$'),
    action_json JSONB NOT NULL,
    risk TEXT NOT NULL CHECK (risk IN ('ORDINARY','HIGH')),
    scope_hash TEXT NOT NULL CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    display_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
        'REQUESTED','APPROVED','REJECTED','CONSUMED',
        'RESULT_CONFIRMED','RESULT_UNCONFIRMED','EXPIRED','CANCELLED','FAILED')),
    revision BIGINT NOT NULL DEFAULT 0,
    decision_source TEXT CHECK (decision_source IN ('USER','SESSION','AI','AUTO')),
    decision_request_id UUID UNIQUE,
    consumption_proof UUID UNIQUE,
    consume_request_id UUID UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX agent_tool_permission_wait_idx
    ON biz.agent_tool_permission(session_id)
    WHERE status IN ('REQUESTED','APPROVED');
```

### 4.3 会话同类授权

```sql
CREATE TABLE biz.agent_session_grant (
    grant_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES biz.agent_edit_session(session_id),
    tool TEXT NOT NULL,
    risk TEXT NOT NULL,
    scope_hash TEXT NOT NULL,
    permission_id UUID NOT NULL UNIQUE REFERENCES biz.agent_tool_permission(permission_id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4.4 V43 扩展

`agent_edit_settings` 增加 `sandbox_mode` 列，与模式一起持久化。

### 4.5 守卫

- 归属必须为 `biz.app.user_id` 且 `is_delete=0`
- 许可 `APPROVED/CONSUMED/RESULT_*` 必须有 `decision_source`
- `SESSION` 来源必须有匹配 grant
- 身份、摘要、`created_at` 不可变

## 5. Java 层

| 类 | 职责 |
|---|---|
| `AgentSandboxPolicy` | 沙箱档位判定；不通过直接 DENY |
| `AgentApprovalPolicy` | 三档模式判定；调用独立 AI 审查 |
| `AgentEditSessionService` | 会话生命周期，冻结两轴取值 |
| `AgentToolPermissionRegistry` | 许可登记／决定／消费／结果 |

导入侧 `ImportAgentToolPermissionService` 保留 V40 存储与卷依赖，把风险计算与 AI 审查委托 `AgentApprovalPolicy`，行为不变。

接口挂在 `/app/{appId}`：

- `GET /agent-config`、`PUT /agent-config`（revision CAS）
- `GET /agent-current`
- `GET /agent-permissions/{id}`、`POST .../decision`

## 6. Python 层

### 6.1 门禁接入点

`GuardedShellBackend` 与 `DockerSandboxBackend` 覆写：

```python
def write(self, file_path, content):
    return self._perform('write', {'path': rel, 'content': content},
                         lambda: super().write(file_path, content))
```

覆盖 `write` / `edit` / `delete` / `upload_files`；`execute` 在 `ensure_safe_command` **之后**进门禁。

顺序不可颠倒：`ensure_safe_command` 是沙箱层，门禁是审批层。先硬边界，再问人。

### 6.2 许可模块

`core/import_permissions.py` 提取为 `core/tool_permissions.py`，经 `PermissionStore` 抽象切换 V40 / V44。保留全部语义：心跳、300 秒等待、动作重算、消费证明去重、`RESULT_UNCONFIRMED`。

## 7. 前端

- `AgentComposerControls` 补回 `showMode` 属性
- 输入区显示审批模式；沙箱档位放高级设置
- 复用 `AgentPermissionCard`（决策枚举已含 `APPROVE_ONCE` / `APPROVE_SIMILAR` / `REJECT`）

**实施说明（2026-10-19）**：审批模式已在输入区可选并保存；沙箱档位当前**只有后端与接口**，
前端固定 `WORKSPACE_WRITE`。理由：当前没有应用级设置页，`READ_ONLY` 已被知识任务复用，
把档位硬塞进本已拥挤的输入栏收益低于成本。接口已就绪（`PUT /app/{appId}/agent-settings`
带 `sandboxMode`），日后有设置页时直接接。

## 8. 验收规则

依 AGENTS.md：生成完成、编译通过、接口成功、容器启动、业务验收、发布是六件独立的事。

### 8.1 六件事分开记

| 层 | 判据 | 不得代替 |
|---|---|---|
| 代码完成 | 文件存在、逻辑可读 | 不算编译通过 |
| 编译通过 | `mvnw compile` 退出 0 | 不算接口可用 |
| 接口成功 | 真实 HTTP 返回预期 `code` | 不算写入被拦 |
| 拦截生效 | 三模式文件哈希读回 | 不算业务验收 |
| 业务验收 | 真实模型 + 真实工作区 | 不算可发布 |

### 8.2 轴 A（沙箱）

| 场景 | 必须验证 | 证据 |
|---|---|---|
| A1 | `READ_ONLY` 下 `write` 被拒，文件未创建 | 文件哈希 + 拒绝事件 |
| A2 | `READ_ONLY` 下 `execute` 被拒 | 命令未执行 |
| A3 | 越界路径（`../`、盘符绝对路径）被拒 | 两类路径各一次 |
| A4 | 危险操作符（`&&`、`|`、`>`）被拒 | 三类各一次 |
| A5 | 非白名单命令被拒 | 一次 |

A 组拒绝必须是硬拒绝：用户点批准也不放行。

### 8.3 轴 B（审批）

| 场景 | 模式 | 必须验证 | 证据 |
|---|---|---|---|
| B1 | MANUAL | 未批准前文件不变 | 前后哈希一致 |
| B2 | MANUAL | 批准后只执行一次，二次需重新批准 | 许可行数 + 文件内容 |
| B3 | GUARDED_AUTO | 普通源码 AI 判 ORDINARY 自动放行 | 审查记录 + 哈希变化 |
| B4 | GUARDED_AUTO | 高风险（删除、`pom.xml`）仍 ASK | 待决定项存在 |
| B5 | GUARDED_AUTO | AI 审查失败不得降级放行 | 错误透出 + 无写入 |
| B6 | AUTO | 合法操作无人工卡片 | 无 pending + 哈希变化 |
| B7 | AUTO | 沙箱边界仍拦（承 A 组） | 拒绝 + 文件未变 |

### 8.4 会话授权

| 场景 | 期望 |
|---|---|
| 本会话批准同类跨轮有效 | grant 命中，不再问 |
| 不覆盖最终候选合入 | 候选仍 ASK |
| 模式切换不清空已授予范围 | grant 仍在 |
| 会话结束或重启失效 | grant 不再匹配 |

### 8.5 反例（必须拒绝）

| 场景 | 期望 |
|---|---|
| 客户端伪造 `actionHash` | 拒绝 |
| 执行 Agent 自报 `risk=ORDINARY` | 不采信 |
| 复用已消费 ticket | 拒绝 |
| 旧 `revision` 或旧 `sessionEpoch` | 拒绝 |
| 跨账号批准他人应用 | 拒绝 |
| 动作或前置内容变化后沿用旧批准 | 拒绝 |
| 直接 SQL 绕过决定写发布 | 数据库守卫拒绝 |

### 8.6 未验收标注

- Docker 不可用，容器链路记 **NOT_RUN**，不记 PASS
- AI 审查需真实模型，无凭据记 **NOT_RUN**
- 测试数字只对本版本有效，不沿用历史

### 8.7 收尾

- `mvnw -q -DskipTests compile` 退出 0
- 定向 JUnit + 真实 PostgreSQL 反例
- `pytest agent/tests` 相关子集
- `npm run type-check` + `npm run build`
- `scripts/restart-backend.ps1` 退出 0 且 8124 就绪（`code` 为 20000 或 40100）

## 9. 分期

| 期 | 内容 | 验收组 |
|---|---|---|
| 一 | 修 `showMode` 缺陷与 revision 混用 bug | 待浏览器确认 |
| 二 | V43 模式存储与接口 | 已完成 |
| 三 | V44 会话与许可表、`AgentSandboxPolicy`、Python 门禁 | A1–A5、B1–B2、B6 |
| 四 | `AgentApprovalPolicy`、AI 审查、同类授权、前端卡片 | B3–B5、8.4 |
| 五 | 变更账本、候选审阅、回滚 | 另行设计 |

三期做完即「MANUAL 真能拦住写文件」。

## 10. 风险

1. 调研受限：Codex 两轴未读原文，实施前须用官方文档复核。
2. 交互变慢：每次写文件多一次 Java 往返，`MANUAL` 下逐次等待。
3. Docker 缺失：容器链路无法本地验证，只能记 NOT_RUN。
4. 未验收基础：导入权限闭环本身尚未真实验收，三期会触碰同一批代码。
5. 语义差异：导入「写前不落盘」与普通「先落盘可回滚」必须文档写清。
