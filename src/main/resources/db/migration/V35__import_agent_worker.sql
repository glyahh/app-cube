-- 原生进程与编辑任务独立绑定；停止前不能封存或释放编辑资源。
CREATE TABLE biz.project_import_agent_edit_worker (
    edit_id UUID PRIMARY KEY REFERENCES biz.project_import_agent_edit(edit_id),
    lease_id UUID NOT NULL UNIQUE,
    phase TEXT NOT NULL CHECK (phase IN ('PLANNED','RUNNING','STOPPING','STOPPED')),
    windows_pid BIGINT CHECK (windows_pid > 0),
    windows_start TIMESTAMPTZ,
    native_pid BIGINT CHECK (native_pid > 0),
    native_start_ticks TEXT CHECK (native_start_ticks ~ '^[1-9][0-9]{0,19}$'),
    stop_proof JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (phase NOT IN ('RUNNING','STOPPING') OR
        (windows_pid IS NOT NULL AND windows_start IS NOT NULL AND native_pid IS NOT NULL AND native_start_ticks IS NOT NULL)),
    CHECK (phase <> 'STOPPED' OR stop_proof = '{"nativeStopped":true,"launcherStopped":true}'::jsonb)
);

CREATE FUNCTION biz.project_import_agent_edit_worker_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e biz.project_import_agent_edit;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'edit worker audit cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO e FROM biz.project_import_agent_edit WHERE edit_id=NEW.edit_id FOR UPDATE;
    IF e.edit_id IS NULL OR e.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'edit worker identity invalid' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.phase<>'PLANNED' OR NEW.windows_pid IS NOT NULL OR NEW.windows_start IS NOT NULL
            OR NEW.native_pid IS NOT NULL OR NEW.native_start_ticks IS NOT NULL OR NEW.stop_proof<>'{}'::jsonb
            OR e.state<>'PREPARING' OR e.cancel_requested OR e.expires_at<=now() THEN
            RAISE EXCEPTION 'edit worker initial phase invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.edit_id,NEW.lease_id,NEW.created_at) IS DISTINCT FROM (OLD.edit_id,OLD.lease_id,OLD.created_at)
            OR NOT ((OLD.phase='PLANNED' AND NEW.phase IN ('PLANNED','RUNNING','STOPPED')) OR
                    (OLD.phase='RUNNING' AND NEW.phase IN ('STOPPING','STOPPED')) OR
                    (OLD.phase='STOPPING' AND NEW.phase='STOPPED'))
            OR (OLD.windows_pid IS NOT NULL AND
                (NEW.windows_pid,NEW.windows_start) IS DISTINCT FROM (OLD.windows_pid,OLD.windows_start))
            OR (OLD.native_pid IS NOT NULL AND
                (NEW.native_pid,NEW.native_start_ticks) IS DISTINCT FROM (OLD.native_pid,OLD.native_start_ticks))
            OR (NEW.windows_pid IS NULL) <> (NEW.windows_start IS NULL)
            OR (NEW.native_pid IS NULL) <> (NEW.native_start_ticks IS NULL) THEN
            RAISE EXCEPTION 'edit worker frozen identity changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.phase='RUNNING' AND (e.state NOT IN ('PREPARING','EDITING') OR e.cancel_requested OR e.expires_at<=now()) THEN
        RAISE EXCEPTION 'edit worker qualification revoked' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_worker_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.project_import_agent_edit_worker FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_worker_guard();

CREATE FUNCTION biz.project_import_agent_edit_worker_parent_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.closed_at IS NOT NULL OR NEW.state IN ('SEALING','COMMITTING','SUCCEEDED')
        OR NEW.resources_json->0->>'state'='RELEASED') AND EXISTS (
        SELECT 1 FROM biz.project_import_agent_edit_worker WHERE edit_id=NEW.edit_id AND phase<>'STOPPED') THEN
        RAISE EXCEPTION 'edit native worker stop not confirmed' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_worker_parent_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_worker_parent_guard();
