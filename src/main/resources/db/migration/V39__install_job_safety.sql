-- 不猜测旧任务的安装决定；历史 approved 保持 NULL，不允许改变参数重放。
ALTER TABLE biz.project_import_startup_install_job ADD COLUMN approved BOOLEAN;
ALTER TABLE biz.project_import_startup_install_job ADD COLUMN probes_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(probes_json)='array');
CREATE OR REPLACE FUNCTION biz.project_import_startup_install_job_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.job_id<>OLD.job_id OR NEW.request_id<>OLD.request_id OR NEW.owner_id<>OLD.owner_id OR NEW.app_id<>OLD.app_id
        OR NEW.plan_id<>OLD.plan_id OR NEW.plan_revision<>OLD.plan_revision OR NEW.content_hash<>OLD.content_hash
        OR NEW.request_hash<>OLD.request_hash OR NEW.executor_id<>OLD.executor_id OR NEW.executor_pid<>OLD.executor_pid
        OR NEW.executor_start<>OLD.executor_start OR NEW.created_at<>OLD.created_at OR NEW.approved IS DISTINCT FROM OLD.approved THEN
        RAISE EXCEPTION 'startup install job identity is immutable' USING ERRCODE='check_violation';
    END IF;
    IF OLD.finished_at IS NOT NULL THEN
        RAISE EXCEPTION 'startup install job is finished' USING ERRCODE='check_violation';
    END IF;
    -- 诊断追加不推进取消/控制版本；控制状态变化仍必须 CAS 加一。
    IF NEW.state IS DISTINCT FROM OLD.state OR NEW.cancel_requested IS DISTINCT FROM OLD.cancel_requested
        OR NEW.cleanup IS DISTINCT FROM OLD.cleanup OR NEW.finished_at IS DISTINCT FROM OLD.finished_at THEN
        IF NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'startup install job revision must advance' USING ERRCODE='check_violation'; END IF;
    ELSIF NEW.revision NOT IN (OLD.revision,OLD.revision+1) THEN
        RAISE EXCEPTION 'startup install job revision is invalid' USING ERRCODE='check_violation';
    END IF;
    IF NEW.state IN ('SUCCEEDED','FAILED','REJECTED','CANCELLED') AND NEW.cleanup<>'DONE' THEN
        RAISE EXCEPTION 'startup install resources are unresolved' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
