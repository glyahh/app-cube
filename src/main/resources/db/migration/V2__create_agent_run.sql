-- PostgreSQL 迁移 V2：agent_run —— Agent 执行台账。
--
-- 这是 agent_next 新增的表，MySQL 里没有对应物。
--
-- **Java 侧独占写入。** Python 不碰 biz schema：
-- is_delete 的逻辑删除、雪花 ID 生成、audit 语义都是 MyBatis-Flex 在 ORM 层实现的，
-- 裸 SQL 会绕过它们。用 database role 从物理上限制，不要靠约定。

CREATE TABLE biz.agent_run (
    id              BIGINT       PRIMARY KEY,          -- 雪花，Java 生成
    app_id          BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    -- chat_history 里那条 USER 行的 id。既是本轮的锚点，也是幂等键。
    round_id        BIGINT       NOT NULL,
    -- app:{appId}。纯函数派生，不需要新表也不需要对账。
    thread_id       VARCHAR(96)  NOT NULL,
    -- 跨进程日志串联用（Java 与 Python 共用）
    trace_id        VARCHAR(64)  NOT NULL,
    code_gen_type   VARCHAR(32)  NOT NULL,
    -- 产物目录名，如 vue_project_431379639885025280
    workspace_name  VARCHAR(160) NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    phase           VARCHAR(48),
    error_code      VARCHAR(48),
    error_message   VARCHAR(512),
    -- 对账用：Python 的事件序号与最后活动时间。
    -- 「跨库一致性不能只靠 checkpoint 保证」，孤儿 run 要靠这两个字段识别。
    last_event_seq  INTEGER      NOT NULL DEFAULT 0,
    last_event_at   TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    deadline_at     TIMESTAMPTZ,
    reconcile_tries INTEGER      NOT NULL DEFAULT 0,
    usage_json      JSONB,
    manifest_json   JSONB,
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_delete       SMALLINT     NOT NULL DEFAULT 0,
    -- status 取值：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED / UNREACHABLE / UNKNOWN
    CONSTRAINT ck_agent_run_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNREACHABLE', 'UNKNOWN')
    )
);

-- 幂等：同一轮只能有一个 run。落库时靠它挡住重复写入，
-- 而不是靠应用层「先查再插」—— 那个有竞态。
CREATE UNIQUE INDEX uk_agent_run_round ON biz.agent_run (round_id) WHERE is_delete = 0;

-- 孤儿 run 扫描（对账器每 60 秒跑一次）
CREATE INDEX idx_agent_run_status ON biz.agent_run (status, update_time) WHERE is_delete = 0;
CREATE INDEX idx_agent_run_app ON biz.agent_run (app_id, create_time);
CREATE INDEX idx_agent_run_thread ON biz.agent_run (thread_id);
CREATE INDEX idx_agent_run_trace ON biz.agent_run (trace_id);
