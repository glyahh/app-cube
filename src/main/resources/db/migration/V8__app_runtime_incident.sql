-- 运行异常台账：一次异常退出或一条明确错误日志就是一条记录。
--
-- 与 app_runtime 的分工：那张记「跑起来了没有」，这张记「跑出什么问题、
-- 用户处理到哪一步了」。按 (app_id, run_no, fingerprint) 唯一 ——
-- 同一次运行里的同一个错误只记一次，不反复弹框。

CREATE TABLE biz.app_runtime_incident (
    id           BIGINT       PRIMARY KEY,          -- 雪花，Java 生成
    app_id       BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    run_no       INTEGER      NOT NULL,
    -- 错误指纹：把错误行归一化（去时间戳/数字/路径）后取的摘要，用于同次运行内去重
    fingerprint  VARCHAR(64)  NOT NULL,
    -- STARTUP_FAILURE（启动失败）/ RUNTIME_FAILURE（运行期异常）
    kind         VARCHAR(24)  NOT NULL,
    -- OPEN（待用户处理）/ DISMISSED（用户关掉弹窗）/ REPAIRING（已发起修复）/ REPAIRED
    status       VARCHAR(24)  NOT NULL,
    summary      VARCHAR(512) NOT NULL,
    -- 脱敏后的诊断内容（日志片段、环境、启动命令、运行版本、关联文件）
    detail       TEXT,
    exit_code    INTEGER,
    detected_at  TIMESTAMP,
    notified_at  TIMESTAMP,
    create_time  TIMESTAMP    NOT NULL DEFAULT now(),
    update_time  TIMESTAMP    NOT NULL DEFAULT now(),
    is_delete    SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_incident_kind CHECK (kind IN ('STARTUP_FAILURE', 'RUNTIME_FAILURE')),
    CONSTRAINT ck_incident_status CHECK (status IN ('OPEN', 'DISMISSED', 'REPAIRING', 'REPAIRED'))
);

-- 同一次运行里的同一类错误只留一条
CREATE UNIQUE INDEX uk_incident_dedup
    ON biz.app_runtime_incident (app_id, run_no, fingerprint)
    WHERE is_delete = 0;

CREATE INDEX idx_incident_app ON biz.app_runtime_incident (app_id, run_no DESC);
CREATE INDEX idx_incident_status ON biz.app_runtime_incident (status, update_time);
