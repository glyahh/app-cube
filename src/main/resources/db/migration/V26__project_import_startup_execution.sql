-- 导入工程隔离执行登记：每次执行一行，记录执行者与容器身份；停止/清理以实际资源核验为准，执行入口仍未对用户开放。
CREATE TABLE biz.project_import_startup_execution (
    execution_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    plan_id UUID NOT NULL REFERENCES biz.project_import_startup_plan(plan_id),
    plan_revision TEXT NOT NULL CHECK (plan_revision ~ '^[0-9a-f]{64}$'),
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    directory TEXT NOT NULL,
    command_hash TEXT NOT NULL CHECK (command_hash ~ '^[0-9a-f]{64}$'),
    limits_json JSONB NOT NULL CHECK (jsonb_typeof(limits_json)='object'),
    executor_pid BIGINT NOT NULL CHECK (executor_pid>0),
    executor_start TIMESTAMP WITH TIME ZONE NOT NULL,
    container_name TEXT NOT NULL,
    container_id TEXT CHECK (container_id ~ '^[0-9a-f]{64}$'),
    container_started TEXT,
    state TEXT NOT NULL CHECK (state IN ('PREPARING','RUNNING','STOPPING','SUCCEEDED','FAILED','REFUSED','STOPPED','STOP_FAILED','INTERRUPTED')),
    reason TEXT,
    exit_code INTEGER,
    output_bytes BIGINT NOT NULL DEFAULT 0 CHECK (output_bytes>=0),
    cleanup TEXT NOT NULL DEFAULT 'PENDING' CHECK (cleanup IN ('PENDING','DONE','FAILED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at TIMESTAMP WITH TIME ZONE,
    CHECK ((state IN ('PREPARING','RUNNING','STOPPING'))=(finished_at IS NULL)),
    CHECK (state NOT IN ('REFUSED','FAILED','STOP_FAILED','INTERRUPTED') OR reason IS NOT NULL)
);
-- 同一应用同时最多一个未结束执行；结束行只作审计。
CREATE UNIQUE INDEX project_import_startup_execution_active_idx ON biz.project_import_startup_execution(app_id) WHERE finished_at IS NULL;
CREATE INDEX project_import_startup_execution_app_idx ON biz.project_import_startup_execution(app_id,owner_id,created_at DESC);
-- 身份与结束状态不可改写：结束行不能回到运行，已登记容器不能换成别的容器。
CREATE FUNCTION biz.project_import_startup_execution_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.execution_id<>OLD.execution_id OR NEW.owner_id<>OLD.owner_id OR NEW.app_id<>OLD.app_id OR NEW.plan_id<>OLD.plan_id
        OR NEW.plan_revision<>OLD.plan_revision OR NEW.content_hash<>OLD.content_hash OR NEW.directory<>OLD.directory OR NEW.command_hash<>OLD.command_hash
        OR NEW.limits_json IS DISTINCT FROM OLD.limits_json OR NEW.executor_pid<>OLD.executor_pid OR NEW.executor_start<>OLD.executor_start
        OR NEW.container_name<>OLD.container_name OR NEW.created_at<>OLD.created_at
        OR (OLD.container_id IS NOT NULL AND NEW.container_id IS DISTINCT FROM OLD.container_id) THEN
        RAISE EXCEPTION 'startup execution identity is immutable' USING ERRCODE='check_violation';
    END IF;
    -- 结束行只允许把清理失败补记为已清理（重启核对确认资源消失后）。
    IF OLD.finished_at IS NOT NULL AND (NEW.state<>OLD.state OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.exit_code IS DISTINCT FROM OLD.exit_code
        OR NEW.output_bytes<>OLD.output_bytes OR NEW.finished_at IS DISTINCT FROM OLD.finished_at OR NEW.container_id IS DISTINCT FROM OLD.container_id
        OR NEW.container_started IS DISTINCT FROM OLD.container_started OR NOT (OLD.cleanup='FAILED' AND NEW.cleanup='DONE')) THEN
        RAISE EXCEPTION 'startup execution is finished' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_startup_execution_frozen_trg BEFORE UPDATE ON biz.project_import_startup_execution
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_startup_execution_frozen();
