# 数据库表结构图（Markdown 预览）

> 类型：技术说明与学习复盘；旧架构、类名和数字按原记录时点理解。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

本文用 **Mermaid** 描述表字段与逻辑关联。在支持 Mermaid 的预览中（如 VS Code / Cursor 预览、GitHub）可直接渲染。

> 来源：`sql/conversation_memory_tables.sql`、`sql/create_table.sql` 及代码中的 `roundId` = `chat_history.id` 约定。会话记忆三张表在 Java 侧为 `IF NOT EXISTS` 建表，**未声明 MySQL 外键**，图中关系为**业务逻辑关联**。

---

## 1. 会话记忆相关（`conversation_memory_*` + `snapshot_history`）

```mermaid
erDiagram
    conversation_memory_state {
        bigint id PK
        bigint appId UK "每应用一行"
        bigint latestRoundId "最近一轮 chat_history.id"
        bigint latestSnapshotId "指向 snapshot_history.id"
        text softSummary
        text hardSummary
        longtext changedFilesJson
        datetime updatedAt
        datetime createdAt
    }

    snapshot_history {
        bigint id PK
        bigint appId
        bigint roundId "chat_history.id"
        longtext manifestJson
        int filesCount
        datetime createdAt
    }

    conversation_memory_ref {
        bigint id PK
        bigint appId
        bigint roundId "chat_history.id"
        varchar refId UK
        varchar filePath
        longtext content
        bigint contentBytes
        datetime createdAt
    }

    app {
        bigint id PK
    }

    chat_history {
        bigint id PK
        bigint appId
    }

    app ||--o| conversation_memory_state : "appId"
    app ||--o{ snapshot_history : "appId"
    app ||--o{ conversation_memory_ref : "appId"
    chat_history ||--o{ snapshot_history : "roundId=id"
    chat_history ||--o{ conversation_memory_ref : "roundId=id"
```

> `conversation_memory_state.latestSnapshotId` 逻辑上指向 `snapshot_history.id`（图中不画边，避免可选 FK 在 Mermaid 里歧义）。

### 关系说明（逻辑层）

| 从表 | 字段 | 含义 |
|------|------|------|
| `conversation_memory_state` | `appId` | 与 `app.id` 一一对应（`uk_app_id`） |
| `conversation_memory_state` | `latestRoundId` | 最近更新的轮次，对应 `chat_history.id` |
| `conversation_memory_state` | `latestSnapshotId` | 对应当轮写入的 `snapshot_history.id` |
| `snapshot_history` | `roundId` | 本轮 `chat_history` 主键 |
| `conversation_memory_ref` | `roundId` | 同上，大文件归档所属轮次 |

---

## 2. 核心业务表（用户 / 应用 / 对话 / 申请）

```mermaid
erDiagram
    user {
        bigint id PK
        varchar userAccount UK
        varchar userPassword
        varchar userName
        varchar userAvatar
        varchar userProfile
        varchar userRole
        datetime editTime
        datetime createTime
        datetime updateTime
        tinyint isDelete
    }

    app {
        bigint id PK
        varchar appName
        varchar cover
        text initPrompt
        varchar codeGenType
        varchar deployKey UK
        datetime deployedTime
        int priority
        bigint userId FK
        tinyint is_beta
        datetime editTime
        datetime createTime
        datetime updateTime
        tinyint isDelete
    }

    chat_history {
        bigint id PK
        text message
        varchar messageType
        bigint appId FK
        bigint userId
        varchar auditAction
        varchar auditHitRule
        datetime createTime
        datetime updateTime
        tinyint isDelete
    }

    user_app_apply {
        bigint id PK
        bigint userId FK
        bigint appId FK
        int appPropriety
        tinyint operate
        varchar applyReason
        tinyint status
        bigint reviewUserId
        varchar reviewRemark
        datetime reviewTime
        datetime createTime
        datetime updateTime
        tinyint isDelete
    }

    user ||--o{ app : "userId"
    user ||--o{ user_app_apply : "userId"
    app ||--o{ chat_history : "appId"
    app ||--o{ user_app_apply : "appId"
```

### 外键与约束（`user_app_apply`）

- `userId` → `user(id)`
- `appId` → `app(id)`
- `operate`：`CHECK (operate in (1, 2))`

`chat_history`、`app` 等在 `create_table.sql` 中主要为索引，**未**对 `appId` 声明 FK（与线上常见做法一致）。

---

## 3. 会话记忆与核心域总览（简图）

```mermaid
flowchart LR
    subgraph core["核心业务"]
        U[user]
        A[app]
        CH[chat_history]
        UAA[user_app_apply]
    end
    subgraph mem["会话记忆扩展"]
        CMS[conversation_memory_state]
        SH[snapshot_history]
        CMR[conversation_memory_ref]
    end
    U --> A
    U --> UAA
    A --> UAA
    A --> CH
    A --> CMS
    A --> SH
    A --> CMR
    CH -.->|roundId| SH
    CH -.->|roundId| CMR
    SH -.->|latestSnapshotId| CMS
```

---

## 本地预览说明

- **Cursor / VS Code**：打开本文件 → 右上角或命令面板选择 Markdown 预览；若 Mermaid 未渲染，可安装 “Markdown Preview Mermaid Support” 等扩展。
- **GitHub**：推送到仓库后在网页上打开本 `.md` 文件即可渲染 Mermaid。
