-- 同一应用可以同时有多个未结束执行，但每个槽位（安装、某次构建、某个服务）只能有一个。
-- 结束行禁止 UPDATE，所以用常量默认值补齐旧行，不逐行改写。
ALTER TABLE biz.project_import_startup_execution ADD COLUMN slot TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE biz.project_import_startup_execution ALTER COLUMN slot DROP DEFAULT;
ALTER TABLE biz.project_import_startup_execution ADD CONSTRAINT project_import_startup_execution_slot_chk CHECK (slot ~ '^[A-Za-z0-9._:@+-]{1,160}$');
DROP INDEX biz.project_import_startup_execution_active_idx;
CREATE UNIQUE INDEX project_import_startup_execution_active_slot_idx ON biz.project_import_startup_execution(app_id, slot) WHERE finished_at IS NULL;

CREATE OR REPLACE FUNCTION biz.project_import_startup_execution_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.execution_id<>OLD.execution_id OR NEW.owner_id<>OLD.owner_id OR NEW.app_id<>OLD.app_id OR NEW.plan_id<>OLD.plan_id
        OR NEW.plan_revision<>OLD.plan_revision OR NEW.content_hash<>OLD.content_hash OR NEW.directory<>OLD.directory OR NEW.command_hash<>OLD.command_hash
        OR NEW.limits_json IS DISTINCT FROM OLD.limits_json OR NEW.executor_pid<>OLD.executor_pid OR NEW.executor_start<>OLD.executor_start
        OR NEW.container_name<>OLD.container_name OR NEW.created_at<>OLD.created_at OR NEW.slot<>OLD.slot
        OR (OLD.container_id IS NOT NULL AND NEW.container_id IS DISTINCT FROM OLD.container_id) THEN
        RAISE EXCEPTION 'startup execution identity is immutable' USING ERRCODE='check_violation';
    END IF;
    IF OLD.finished_at IS NOT NULL AND (NEW.state<>OLD.state OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.exit_code IS DISTINCT FROM OLD.exit_code
        OR NEW.output_bytes<>OLD.output_bytes OR NEW.finished_at IS DISTINCT FROM OLD.finished_at OR NEW.container_id IS DISTINCT FROM OLD.container_id
        OR NEW.container_started IS DISTINCT FROM OLD.container_started OR NOT (OLD.cleanup='FAILED' AND NEW.cleanup='DONE')) THEN
        RAISE EXCEPTION 'startup execution is finished' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
