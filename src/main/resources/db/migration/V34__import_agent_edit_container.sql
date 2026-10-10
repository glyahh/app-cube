-- 容器创建前登记，停止证据随业务审计保留，不随容量卷删除。
CREATE TABLE biz.project_import_agent_edit_container (
    edit_id UUID PRIMARY KEY REFERENCES biz.project_import_agent_edit(edit_id),
    container_name TEXT NOT NULL UNIQUE,
    container_id TEXT UNIQUE CHECK (container_id ~ '^[0-9a-f]{64}$'),
    phase TEXT NOT NULL CHECK (phase IN ('PLANNED','CREATED','REMOVED')),
    receipt_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE FUNCTION biz.project_import_agent_edit_container_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e biz.project_import_agent_edit;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'edit container audit cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO e FROM biz.project_import_agent_edit WHERE edit_id=NEW.edit_id FOR UPDATE;
    IF e.edit_id IS NULL OR e.closed_at IS NOT NULL
       OR NEW.container_name IS DISTINCT FROM 'glyahh-edit-'||NEW.edit_id::text THEN
        RAISE EXCEPTION 'edit container identity invalid' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.phase<>'PLANNED' OR NEW.container_id IS NOT NULL OR NEW.receipt_json<>'{}'::jsonb THEN
            RAISE EXCEPTION 'edit container initial phase invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.edit_id,NEW.container_name,NEW.created_at) IS DISTINCT FROM (OLD.edit_id,OLD.container_name,OLD.created_at)
            OR (OLD.container_id IS NOT NULL AND NEW.container_id IS DISTINCT FROM OLD.container_id)
            OR NOT (OLD.phase='PLANNED' AND NEW.phase IN ('CREATED','REMOVED') OR OLD.phase='CREATED' AND NEW.phase='REMOVED') THEN
            RAISE EXCEPTION 'edit container immutable identity or phase changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.phase IN ('PLANNED','CREATED') THEN
        IF NEW.receipt_json<>'{}'::jsonb OR NEW.phase='CREATED' AND NEW.container_id IS NULL
            OR e.state NOT IN ('PREPARING','EDITING') OR e.cancel_requested OR e.expires_at<=now()
            OR e.resources_json->0->>'state' IS DISTINCT FROM 'READY' OR NOT EXISTS (
                SELECT 1 FROM biz.project_import_agent_edit_view WHERE edit_id=e.edit_id AND phase='SEALED')
            OR NOT EXISTS (
                SELECT 1 FROM biz.project_import_publication p
                JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration c ON c.app_id=p.app_id AND c.owner_id=p.owner_id AND c.import_task_id=p.task_id
                WHERE p.app_id=e.app_id AND p.owner_id=e.owner_id AND p.task_id=e.import_task_id AND p.state='READY'
                  AND c.config_revision=e.config_revision AND c.context_epoch=e.context_epoch) THEN
            RAISE EXCEPTION 'edit container creation qualification revoked' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF e.state NOT IN ('PREPARING','EDITING','SEALING','CANCELLING','RECOVERY_REQUIRED')
            OR NEW.receipt_json IS DISTINCT FROM jsonb_build_object('stopConfirmed',true,
                'containerName',NEW.container_name,'containerId',NEW.container_id) THEN
            RAISE EXCEPTION 'edit container stop proof invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_container_guard BEFORE INSERT OR UPDATE OR DELETE ON biz.project_import_agent_edit_container
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_container_guard();
CREATE FUNCTION biz.project_import_agent_edit_container_parent_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.closed_at IS NOT NULL OR NEW.state IN ('SEALING','COMMITTING','SUCCEEDED')
        OR NEW.resources_json->0->>'state'='RELEASED') AND EXISTS (
            SELECT 1 FROM biz.project_import_agent_edit_container WHERE edit_id=NEW.edit_id AND phase<>'REMOVED') THEN
        RAISE EXCEPTION 'edit container stop not confirmed' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_container_parent_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_container_parent_guard();
