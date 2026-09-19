-- 知识系统：快照 / 任务 / 文档 / 图谱节点与关系 / 问答历史。
--
-- 设计要点（都来自 Plan 的明确约束）：
--   1. 不引入图数据库 —— 节点与关系用关系表存，查询也够用。
--   2. 快照版本按**实际文件内容**算，不依赖宿主仓库 Git SHA。
--   3. 知识结果绑定快照版本：版本落后时展示「待更新」，不能冒充当前版本。
--   4. 文档与图谱共用同一套节点与源码引用，并用 evidence 区分
--      「代码里读出来的证据」和「模型推断」，两者不能混着展示。
--   5. 问答历史持久化，每条回答记录它读取的版本。

-- 源码快照：一次不可变的输入集合
CREATE TABLE biz.knowledge_snapshot (
    id           BIGINT       PRIMARY KEY,          -- 雪花
    app_id       BIGINT       NOT NULL,
    -- 内容摘要（按文件内容算），同一应用同一版本只快照一次
    version      VARCHAR(64)  NOT NULL,
    file_count   INTEGER      NOT NULL DEFAULT 0,
    total_bytes  BIGINT       NOT NULL DEFAULT 0,
    -- 纳入快照的文件清单（路径 + 大小 + 摘要）
    manifest_json TEXT,
    create_time  TIMESTAMP    NOT NULL DEFAULT now(),
    update_time  TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete    SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_knowledge_snapshot ON biz.knowledge_snapshot (app_id, version) WHERE is_delete = 0;

-- 知识生成任务：独立于主生成会话，失败不影响项目本身
CREATE TABLE biz.knowledge_task (
    id               BIGINT       PRIMARY KEY,
    app_id           BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    snapshot_id      BIGINT       NOT NULL,
    snapshot_version VARCHAR(64)  NOT NULL,
    -- PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED / INTERRUPTED
    status           VARCHAR(24)  NOT NULL,
    progress         INTEGER      NOT NULL DEFAULT 0,
    phase            VARCHAR(64),
    error_message    VARCHAR(512),
    -- 关联的 agent run，便于与生成链路对照排障
    run_id           VARCHAR(64),
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    create_time      TIMESTAMP    NOT NULL DEFAULT now(),
    update_time      TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_knowledge_task_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
    )
);

-- 同一应用同一快照同时只允许一个活跃任务：重复点击不会起第二个
CREATE UNIQUE INDEX uk_knowledge_task_active
    ON biz.knowledge_task (app_id, snapshot_version)
    WHERE is_delete = 0 AND status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_knowledge_task_app ON biz.knowledge_task (app_id, id DESC);
CREATE INDEX idx_knowledge_task_status ON biz.knowledge_task (status, update_time);

-- 知识文档（模型产出的说明性内容）
CREATE TABLE biz.knowledge_doc (
    id               BIGINT       PRIMARY KEY,
    app_id           BIGINT       NOT NULL,
    snapshot_id      BIGINT       NOT NULL,
    snapshot_version VARCHAR(64)  NOT NULL,
    title            VARCHAR(256) NOT NULL,
    content          TEXT,
    create_time      TIMESTAMP    NOT NULL DEFAULT now(),
    update_time      TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete        SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX idx_knowledge_doc_snapshot ON biz.knowledge_doc (app_id, snapshot_version);

-- 图谱节点
CREATE TABLE biz.knowledge_node (
    id               BIGINT       PRIMARY KEY,
    app_id           BIGINT       NOT NULL,
    snapshot_id      BIGINT       NOT NULL,
    snapshot_version VARCHAR(64)  NOT NULL,
    -- 稳定标识，用于关系两端对齐（如文件路径或符号名）
    node_key         VARCHAR(200) NOT NULL,
    name             VARCHAR(256) NOT NULL,
    -- file / module / function / class / endpoint / table ...
    kind             VARCHAR(48),
    summary          VARCHAR(1024),
    -- CODE_EVIDENCE（源码里读出来的）/ AI_INFERENCE（模型推断）
    evidence         VARCHAR(24)  NOT NULL DEFAULT 'AI_INFERENCE',
    -- 源码引用，如 src/main.js:42
    source_ref       VARCHAR(512),
    create_time      TIMESTAMP    NOT NULL DEFAULT now(),
    update_time      TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_knowledge_node_evidence CHECK (evidence IN ('CODE_EVIDENCE', 'AI_INFERENCE'))
);

CREATE UNIQUE INDEX uk_knowledge_node ON biz.knowledge_node (app_id, snapshot_version, node_key) WHERE is_delete = 0;

-- 图谱关系
CREATE TABLE biz.knowledge_edge (
    id               BIGINT       PRIMARY KEY,
    app_id           BIGINT       NOT NULL,
    snapshot_id      BIGINT       NOT NULL,
    snapshot_version VARCHAR(64)  NOT NULL,
    from_key         VARCHAR(200) NOT NULL,
    to_key           VARCHAR(200) NOT NULL,
    -- imports / calls / contains / exposes ...
    relation         VARCHAR(48)  NOT NULL,
    evidence         VARCHAR(24)  NOT NULL DEFAULT 'AI_INFERENCE',
    source_ref       VARCHAR(512),
    create_time      TIMESTAMP    NOT NULL DEFAULT now(),
    update_time      TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_knowledge_edge_evidence CHECK (evidence IN ('CODE_EVIDENCE', 'AI_INFERENCE'))
);

CREATE INDEX idx_knowledge_edge_snapshot ON biz.knowledge_edge (app_id, snapshot_version);
CREATE INDEX idx_knowledge_edge_from ON biz.knowledge_edge (app_id, snapshot_version, from_key);

-- 知识问答历史：与生成对话分离，每条回答绑定它读取的版本
CREATE TABLE biz.knowledge_qa_message (
    id               BIGINT       PRIMARY KEY,
    app_id           BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    -- user / assistant
    role             VARCHAR(16)  NOT NULL,
    content          TEXT,
    -- 回答所依据的快照版本；资料过期时置 stale，不假装是最新的
    snapshot_version VARCHAR(64),
    stale            BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 引用的源码/文档（JSON 数组）
    citations_json   TEXT,
    create_time      TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_knowledge_qa_role CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX idx_knowledge_qa_app ON biz.knowledge_qa_message (app_id, id DESC);
