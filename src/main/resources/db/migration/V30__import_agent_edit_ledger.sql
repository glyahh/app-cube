-- 编辑资格与执行/发布结果分开；本表本身不授予运行或文件发布权限。
CREATE TABLE biz.project_import_agent_edit (
    edit_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    import_task_id UUID NOT NULL REFERENCES biz.project_import_task(task_id),
    run_id BIGINT NOT NULL UNIQUE CHECK (run_id > 0),
    source_hash TEXT NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    config_revision BIGINT NOT NULL CHECK (config_revision >= 0),
    context_epoch BIGINT NOT NULL CHECK (context_epoch >= 0),
    executor_id UUID NOT NULL,
    executor_pid BIGINT NOT NULL CHECK (executor_pid > 0),
    executor_start TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > executor_start),
    state TEXT NOT NULL DEFAULT 'PREPARING' CHECK (state IN (
        'PREPARING','EDITING','SEALING','COMMITTING','SUCCEEDED','FAILED',
        'CANCELLING','CANCELLED','STALE','STOP_FAILED','RECOVERY_REQUIRED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    resources_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(resources_json)='array'),
    candidate_hash TEXT CHECK (candidate_hash ~ '^[0-9a-f]{64}$'),
    authorized_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(authorized_json)='array'),
    publish_phase TEXT NOT NULL DEFAULT 'NONE' CHECK (publish_phase IN ('NONE','CANDIDATE_READY','OLD_MOVED','NEW_MOVED','COMMITTED')),
    recovery_json JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(recovery_json)='object'),
    stop_confirmed BOOLEAN NOT NULL DEFAULT false,
    cleanup TEXT NOT NULL DEFAULT 'PENDING' CHECK (cleanup IN ('PENDING','DONE','FAILED','UNVERIFIED')),
    reason TEXT CHECK (reason ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    CHECK ((closed_at IS NOT NULL) = (state IN ('SUCCEEDED','FAILED','CANCELLED','STALE'))),
    CHECK (closed_at IS NULL OR (cleanup='DONE' AND stop_confirmed)),
    CHECK (state <> 'SUCCEEDED' OR (candidate_hash IS NOT NULL AND publish_phase='COMMITTED' AND NOT cancel_requested)),
    CHECK (state NOT IN ('SEALING','COMMITTING','SUCCEEDED') OR stop_confirmed),
    CHECK (state <> 'COMMITTING' OR (candidate_hash IS NOT NULL AND NOT cancel_requested))
);
CREATE UNIQUE INDEX project_import_agent_edit_active_idx
    ON biz.project_import_agent_edit(app_id) WHERE closed_at IS NULL;
CREATE INDEX project_import_agent_edit_owner_idx ON biz.project_import_agent_edit(owner_id,created_at);

CREATE FUNCTION biz.project_import_agent_edit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'PREPARING' OR NEW.revision<>0 OR NEW.cancel_requested
            OR NEW.publish_phase<>'NONE' OR NEW.candidate_hash IS NOT NULL
            OR NEW.stop_confirmed OR NEW.cleanup<>'PENDING'
            OR NEW.resources_json<>'[]'::jsonb OR NEW.authorized_json<>'[]'::jsonb OR NEW.recovery_json<>'{}'::jsonb THEN
            RAISE EXCEPTION 'edit initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF OLD.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'edit finished' USING ERRCODE='check_violation';
        END IF;
        IF (NEW.edit_id,NEW.owner_id,NEW.app_id,NEW.import_task_id,NEW.run_id,NEW.source_hash,
            NEW.config_revision,NEW.context_epoch,NEW.executor_id,NEW.executor_pid,NEW.executor_start,
            NEW.expires_at,NEW.created_at) IS DISTINCT FROM
           (OLD.edit_id,OLD.owner_id,OLD.app_id,OLD.import_task_id,OLD.run_id,OLD.source_hash,
            OLD.config_revision,OLD.context_epoch,OLD.executor_id,OLD.executor_pid,OLD.executor_start,
            OLD.expires_at,OLD.created_at) OR NEW.revision<>OLD.revision+1
            OR (OLD.cancel_requested AND NOT NEW.cancel_requested)
            OR (OLD.stop_confirmed AND NOT NEW.stop_confirmed)
            OR (OLD.candidate_hash IS NOT NULL AND
                (NEW.candidate_hash IS DISTINCT FROM OLD.candidate_hash OR NEW.authorized_json IS DISTINCT FROM OLD.authorized_json)) THEN
            RAISE EXCEPTION 'edit frozen identity changed' USING ERRCODE='check_violation';
        END IF;
        IF NEW.state<>OLD.state AND NOT (
            (OLD.state='PREPARING' AND NEW.state IN ('EDITING','CANCELLING','FAILED','STALE','RECOVERY_REQUIRED')) OR
            (OLD.state='EDITING' AND NEW.state IN ('SEALING','CANCELLING','FAILED','STALE','RECOVERY_REQUIRED')) OR
            (OLD.state='SEALING' AND NEW.state IN ('COMMITTING','CANCELLING','FAILED','STALE','RECOVERY_REQUIRED')) OR
            (OLD.state='COMMITTING' AND NEW.state IN ('SUCCEEDED','RECOVERY_REQUIRED')) OR
            (OLD.state='CANCELLING' AND NEW.state IN ('CANCELLED','STOP_FAILED','RECOVERY_REQUIRED')) OR
            (OLD.state='STOP_FAILED' AND NEW.state='RECOVERY_REQUIRED') OR
            (OLD.state='RECOVERY_REQUIRED' AND NEW.state IN ('FAILED','CANCELLED','STALE','SUCCEEDED'))) THEN
            RAISE EXCEPTION 'edit transition invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF (TG_OP='INSERT' OR NEW.state IN ('EDITING','COMMITTING','SUCCEEDED')) AND NOT EXISTS (
        SELECT 1 FROM biz.project_import_publication p
        JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
        JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
        JOIN biz.project_import_startup_configuration c ON c.app_id=p.app_id AND c.owner_id=p.owner_id AND c.import_task_id=p.task_id
        WHERE p.app_id=NEW.app_id AND p.owner_id=NEW.owner_id AND p.task_id=NEW.import_task_id AND p.state='READY'
            AND c.config_revision=NEW.config_revision AND c.context_epoch=NEW.context_epoch
            AND NOT NEW.cancel_requested AND NEW.expires_at>now()) THEN
        RAISE EXCEPTION 'edit qualification revoked' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_guard();
