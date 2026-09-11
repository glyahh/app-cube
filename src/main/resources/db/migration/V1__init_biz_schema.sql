-- PostgreSQL 迁移 V1：业务表从 MySQL 迁到 biz schema。
--
-- 与 MySQL 原表的差异，逐条说明原因：
--
-- 1. 列名统一改 snake_case。PG 会把未加引号的标识符折叠成小写，
--    原来的 camelCase 列（appName / isDelete）不这么做就得到处写双引号，
--    迟早有人漏掉。MyBatis-Flex 靠 @Column 映射，改列名不影响 Java 字段名与前端。
-- 2. 雪花 ID 列是纯 BIGINT PRIMARY KEY，**不加 IDENTITY**。
--    实测这些表里的值就是雪花（3.8e17 量级，应用层生成），
--    MySQL 里那个 auto_increment 是建表残留，加上 IDENTITY 反而会和雪花冲突。
-- 3. conversation_memory_state / _ref / snapshot_history 是真的自增
--    （实测 1~148），保留为 IDENTITY。
-- 4. datetime → TIMESTAMPTZ。跨时区部署时 DATETIME 的语义是模糊的。
-- 5. longtext / text → TEXT，json → JSONB。
-- 6. tinyint 的布尔列 → SMALLINT（保留 0/1，与 Java 的 Integer 字段对齐）。
--
-- 注意：本脚本只建结构。数据搬迁另做（见 V3 与迁移说明），
-- 因为涉及 ID 原值保留与序列重置，不适合混在 DDL 里。

CREATE SCHEMA IF NOT EXISTS biz;

-- ─────────────────────────── 用户 ───────────────────────────

CREATE TABLE biz."user" (
    id            BIGINT       PRIMARY KEY,
    user_account  VARCHAR(256) NOT NULL,
    user_password VARCHAR(512) NOT NULL,
    user_name     VARCHAR(256),
    user_avatar   VARCHAR(1024),
    user_profile  VARCHAR(512),
    user_role     VARCHAR(256) NOT NULL DEFAULT 'user',
    edit_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_delete     SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_user_account ON biz."user" (user_account);
CREATE INDEX idx_user_name ON biz."user" (user_name);

-- ─────────────────────────── 应用 ───────────────────────────

CREATE TABLE biz.app (
    id             BIGINT       PRIMARY KEY,
    app_name       VARCHAR(256),
    cover          VARCHAR(512),
    init_prompt    TEXT,
    code_gen_type  VARCHAR(64),
    deploy_key     VARCHAR(64),
    deployed_time  TIMESTAMPTZ,
    priority       INTEGER      NOT NULL DEFAULT 0,
    user_id        BIGINT       NOT NULL,
    is_beta        SMALLINT     NOT NULL DEFAULT 0,
    edit_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_time    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_delete      SMALLINT     NOT NULL DEFAULT 0
);
-- 原 MySQL 里 deployKey 是唯一索引，但没排除逻辑删除的行。
-- 这里保持同样的语义，避免迁过来之后行为变了。
CREATE UNIQUE INDEX uk_app_deploy_key ON biz.app (deploy_key);
CREATE INDEX idx_app_user ON biz.app (user_id);
CREATE INDEX idx_app_name ON biz.app (app_name);
CREATE INDEX idx_app_priority ON biz.app (priority);

-- ─────────────────────────── 对话历史 ───────────────────────────

CREATE TABLE biz.chat_history (
    id             BIGINT      PRIMARY KEY,
    message        TEXT        NOT NULL,
    message_type   VARCHAR(32) NOT NULL,
    app_id         BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    -- audit_action / audit_hit_rule 是提示词安全审查的结果。
    -- 默认 SKIP / NONE 表示「未审查」，不要写成 ALLOW —— 那会谎称审查通过了。
    audit_action   VARCHAR(16) NOT NULL DEFAULT 'SKIP',
    audit_hit_rule VARCHAR(64) NOT NULL DEFAULT 'NONE',
    loop_id        BIGINT,
    create_time    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time    TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_delete      SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_chat_app_time ON biz.chat_history (app_id, create_time);
CREATE INDEX idx_chat_app_type ON biz.chat_history (app_id, message_type);

-- ─────────────────────────── Loop 技能 ───────────────────────────

CREATE TABLE biz.loop (
    id               BIGINT       PRIMARY KEY,
    loop_name        VARCHAR(128) NOT NULL,
    description      VARCHAR(512) DEFAULT '',
    cover            VARCHAR(256) DEFAULT '',
    user_id          BIGINT       NOT NULL,
    priority         INTEGER      NOT NULL DEFAULT 0,
    workflow_json    TEXT,
    compiled_prompt  TEXT,
    source_type      VARCHAR(32)  NOT NULL DEFAULT 'created',
    visibility       VARCHAR(32)  NOT NULL DEFAULT 'private',
    is_delete        SMALLINT     NOT NULL DEFAULT 0,
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_loop_user ON biz.loop (user_id);
CREATE INDEX idx_loop_priority ON biz.loop (priority);

CREATE TABLE biz.app_loop (
    app_id     BIGINT      NOT NULL,
    loop_id    BIGINT      NOT NULL,
    added_from VARCHAR(32) NOT NULL DEFAULT 'creation',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (app_id, loop_id)
);

-- ─────────────────────────── 申请 ───────────────────────────

CREATE TABLE biz.user_app_apply (
    id             BIGINT       PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    app_id         BIGINT,
    app_propriety  INTEGER,
    operate        SMALLINT     NOT NULL,
    apply_reason   VARCHAR(512),
    status         SMALLINT     NOT NULL DEFAULT 0,
    review_user_id BIGINT,
    review_remark  VARCHAR(512),
    review_time    TIMESTAMPTZ,
    create_time    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_delete      SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_apply_user ON biz.user_app_apply (user_id);
CREATE INDEX idx_apply_app ON biz.user_app_apply (app_id);
CREATE INDEX idx_apply_status ON biz.user_app_apply (status);

CREATE TABLE biz.user_loop_apply (
    id             BIGINT       PRIMARY KEY,
    loop_id        BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    operate        SMALLINT     NOT NULL DEFAULT 1,
    status         SMALLINT     NOT NULL DEFAULT 0,
    apply_reason   VARCHAR(512) DEFAULT '',
    review_user_id BIGINT,
    review_remark  VARCHAR(512) DEFAULT '',
    review_time    TIMESTAMPTZ,
    is_delete      SMALLINT     NOT NULL DEFAULT 0,
    create_time    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_loop_apply_loop ON biz.user_loop_apply (loop_id);
CREATE INDEX idx_loop_apply_user ON biz.user_loop_apply (user_id);
CREATE INDEX idx_loop_apply_status ON biz.user_loop_apply (status);

CREATE TABLE biz.user_personalization (
    id           BIGINT      PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    app_style    TEXT,
    answer_style TEXT,
    create_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_delete    SMALLINT    NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_personalization_user ON biz.user_personalization (user_id);

-- ─────────────────────────── 会话记忆（V4 双轨）───────────────────────────
-- 注意：这套记忆机制在 agent_next 里会被压缩机制取代（见 docs/PLANS）。
-- 这几张表先原样迁过来，是为了**保留可回滚**：新链路稳定前，老链路还得能用。
-- 确认不用了就整表删掉，别让两套记忆系统长期并存。

CREATE TABLE biz.memory_shrink (
    id                    BIGINT      PRIMARY KEY,
    app_id                BIGINT      NOT NULL,
    user_id               BIGINT      NOT NULL,
    message               TEXT        NOT NULL,
    message_type          VARCHAR(32) NOT NULL,
    shrink_type           VARCHAR(64) NOT NULL,
    source_chat_history_ids JSONB,
    chat_history_id       BIGINT,
    anchor_create_time    TIMESTAMPTZ NOT NULL,
    create_time           TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time           TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_delete             SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_shrink_app ON biz.memory_shrink (app_id);

CREATE TABLE biz.conversation_memory_state (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    app_id            BIGINT      NOT NULL,
    latest_round_id   BIGINT,
    latest_snapshot_id BIGINT,
    soft_summary      TEXT,
    hard_summary      TEXT,
    changed_files_json TEXT,
    file_notes_json   TEXT,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_memory_state_app ON biz.conversation_memory_state (app_id);

CREATE TABLE biz.conversation_memory_ref (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    app_id        BIGINT       NOT NULL,
    round_id      BIGINT       NOT NULL,
    ref_id        VARCHAR(128) NOT NULL,
    file_path     VARCHAR(512),
    content       TEXT         NOT NULL,
    content_bytes BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_memory_ref_ref_id ON biz.conversation_memory_ref (ref_id);
CREATE INDEX idx_memory_ref_app ON biz.conversation_memory_ref (app_id);

CREATE TABLE biz.snapshot_history (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    app_id        BIGINT      NOT NULL,
    round_id      BIGINT      NOT NULL,
    manifest_json TEXT        NOT NULL,
    files_count   INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshot_app ON biz.snapshot_history (app_id);
