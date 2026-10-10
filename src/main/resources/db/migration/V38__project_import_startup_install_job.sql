-- 安装任务登记：把同步安装从 HTTP 请求里摘出来，请求只受理并返回任务身份。
-- 该表不授予安装、模型或工程执行权限；安装资格仍由计划版本、发布资格和执行准入决定。
CREATE TABLE biz.project_import_startup_install_job (
    job_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    plan_id UUID NOT NULL REFERENCES biz.project_import_startup_plan(plan_id),
    plan_revision TEXT NOT NULL CHECK (plan_revision ~ '^[0-9a-f]{64}$'),
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    request_hash TEXT NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    state TEXT NOT NULL CHECK (state IN ('PENDING','PREPARING','INSTALLING','VERIFYING','SUCCEEDED','FAILED','REJECTED','CANCELLING','CANCELLED','STOP_FAILED','RECOVERY_REQUIRED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    failure_stage TEXT,
    failure_reason TEXT,
    executor_id UUID NOT NULL,
    executor_pid BIGINT NOT NULL CHECK (executor_pid > 0),
    executor_start TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    execution_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(execution_ids) = 'array'),
    resources_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(resources_json) = 'array'),
    stages_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(stages_json) = 'array'),
    preparation_id UUID,
    cleanup TEXT NOT NULL DEFAULT 'PENDING' CHECK (cleanup IN ('PENDING','DONE','UNVERIFIED','FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE(owner_id,request_id),
    -- 终态必须有结束时刻；未结束的任务不能带结束时刻。
    CHECK ((state IN ('SUCCEEDED','FAILED','REJECTED','CANCELLED')) = (finished_at IS NOT NULL)),
    CHECK (state <> 'SUCCEEDED' OR cancel_requested = false),
    CHECK (state <> 'CANCELLED' OR cancel_requested = true),
    -- 失败和未确认状态必须说明原因，不能靠文案反推。
    CHECK (state NOT IN ('FAILED','REJECTED','STOP_FAILED','RECOVERY_REQUIRED') OR failure_reason IS NOT NULL),
    CHECK (failure_reason IS NULL OR failure_reason ~ '^[A-Za-z][A-Za-z0-9_:,]{0,160}$')
);
-- 同一应用最多一个未完成或资源未核清的安装任务；历史失败只作审计。
CREATE UNIQUE INDEX project_import_startup_install_job_active_idx
    ON biz.project_import_startup_install_job(app_id)
    WHERE state IN ('PENDING','PREPARING','INSTALLING','VERIFYING','CANCELLING','STOP_FAILED','RECOVERY_REQUIRED');
CREATE INDEX project_import_startup_install_job_owner_idx
    ON biz.project_import_startup_install_job(owner_id,state);
-- 身份冻结：状态可以推进，但版本必须加一，执行者身份不能换。
CREATE FUNCTION biz.project_import_startup_install_job_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.job_id<>OLD.job_id OR NEW.request_id<>OLD.request_id OR NEW.owner_id<>OLD.owner_id OR NEW.app_id<>OLD.app_id
        OR NEW.plan_id<>OLD.plan_id OR NEW.plan_revision<>OLD.plan_revision OR NEW.content_hash<>OLD.content_hash
        OR NEW.request_hash<>OLD.request_hash OR NEW.executor_id<>OLD.executor_id OR NEW.executor_pid<>OLD.executor_pid
        OR NEW.executor_start<>OLD.executor_start OR NEW.created_at<>OLD.created_at THEN
        RAISE EXCEPTION 'startup install job identity is immutable' USING ERRCODE='check_violation';
    END IF;
    IF NEW.revision<>OLD.revision+1 THEN
        RAISE EXCEPTION 'startup install job revision must advance' USING ERRCODE='check_violation';
    END IF;
    -- 终态不可回到执行中；重新安装必须开新 requestId 和新任务。
    IF OLD.state IN ('SUCCEEDED','FAILED','REJECTED','CANCELLED') AND NEW.state NOT IN ('SUCCEEDED','FAILED','REJECTED','CANCELLED') THEN
        RAISE EXCEPTION 'startup install job is finished' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_startup_install_job_frozen_trg BEFORE UPDATE ON biz.project_import_startup_install_job
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_startup_install_job_frozen();
