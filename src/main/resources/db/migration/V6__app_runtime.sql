-- 应用运行台账：一次「点击运行」就是一条记录。
--
-- 与 agent_run 的分工：那是**生成**过程的台账（Python 侧跑的一轮对话），
-- 这是**运行**过程的台账（用户在运行时沙箱里常驻的应用进程）。
-- 一次生成可以对应多次运行，反之不成立。
--
-- Java 侧独占写入；Python 不碰 biz schema。

CREATE TABLE biz.app_runtime (
    id                BIGINT       PRIMARY KEY,          -- 雪花，Java 生成
    app_id            BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    -- 启动编号：同一应用内从 1 递增，日志与前端都按它区分「这是第几次运行」
    run_no            INTEGER      NOT NULL,
    -- STARTING / RUNNING / STOPPED / FAILED / UNKNOWN
    status            VARCHAR(24)  NOT NULL,
    -- 容器标识。后端重启后靠 container_name 反查真实状态，不信任内存
    container_name    VARCHAR(96)  NOT NULL,
    container_id      VARCHAR(96),
    -- 宿主侧映射端口；非 HTTP 项目为 NULL
    gateway_port      INTEGER,
    -- 受管进程定义快照（JSON），供停止/重启时复现
    spec_json         TEXT,
    -- 启动时所用源码的版本标识（内容摘要）；与当前工作区不一致即「运行版本落后」
    source_version    VARCHAR(64),
    exit_code         INTEGER,
    error_message     VARCHAR(512),
    started_at        TIMESTAMP,
    stopped_at        TIMESTAMP,
    last_heartbeat_at TIMESTAMP,
    create_time       TIMESTAMP    NOT NULL DEFAULT now(),
    update_time       TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_runtime_status CHECK (
        status IN ('STARTING', 'RUNNING', 'STOPPED', 'FAILED', 'UNKNOWN')
    )
);

-- 一个应用同时只能有一次「活着的」运行。用部分唯一索引兜住并发点击，
-- 而不是靠应用层「先查再插」—— 那个有竞态。
CREATE UNIQUE INDEX uk_app_runtime_active
    ON biz.app_runtime (app_id)
    WHERE is_delete = 0 AND status IN ('STARTING', 'RUNNING');

CREATE INDEX idx_app_runtime_app ON biz.app_runtime (app_id, run_no DESC);
CREATE INDEX idx_app_runtime_status ON biz.app_runtime (status, update_time);
