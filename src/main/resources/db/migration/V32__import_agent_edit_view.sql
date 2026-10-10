CREATE TABLE biz.project_import_agent_edit_view (
    edit_id UUID PRIMARY KEY REFERENCES biz.project_import_agent_edit(edit_id),
    phase TEXT NOT NULL CHECK (phase IN ('PLANNED','SEALED','RELEASED')),
    view_path TEXT NOT NULL,
    private_state_path TEXT NOT NULL,
    receipt_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION biz.project_import_agent_edit_view_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e biz.project_import_agent_edit; r JSONB;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'edit view audit cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO e FROM biz.project_import_agent_edit WHERE edit_id=NEW.edit_id FOR UPDATE;
    IF e.edit_id IS NULL OR e.closed_at IS NOT NULL
        OR NEW.view_path IS DISTINCT FROM '/var/lib/glyahh/import-disks/edit-'||NEW.edit_id::text||'/work/view'
        OR NEW.private_state_path IS DISTINCT FROM '/var/lib/glyahh/import-disks/edit-'||NEW.edit_id::text||'/work/private-state.json' THEN
        RAISE EXCEPTION 'edit view identity invalid' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.phase<>'PLANNED' OR NEW.receipt_json<>'{}'::jsonb THEN
            RAISE EXCEPTION 'edit view initial phase invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.edit_id,NEW.view_path,NEW.private_state_path,NEW.created_at) IS DISTINCT FROM
           (OLD.edit_id,OLD.view_path,OLD.private_state_path,OLD.created_at)
           OR OLD.phase='RELEASED' OR (NEW.phase='RELEASED' AND NEW.receipt_json IS DISTINCT FROM OLD.receipt_json)
           OR NOT ((OLD.phase='PLANNED' AND NEW.phase IN ('SEALED','RELEASED')) OR
                   (OLD.phase='SEALED' AND NEW.phase='RELEASED')) THEN
            RAISE EXCEPTION 'edit view frozen identity or phase changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.phase IN ('PLANNED','SEALED') THEN
        IF e.state<>'PREPARING' OR e.cancel_requested OR e.expires_at<=now()
            OR e.resources_json->0->>'state' IS DISTINCT FROM 'READY' OR NOT EXISTS (
                SELECT 1 FROM biz.project_import_publication p
                JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration c ON c.app_id=p.app_id AND c.owner_id=p.owner_id AND c.import_task_id=p.task_id
                WHERE p.app_id=e.app_id AND p.owner_id=e.owner_id AND p.task_id=e.import_task_id AND p.state='READY'
                  AND c.config_revision=e.config_revision AND c.context_epoch=e.context_epoch) THEN
            RAISE EXCEPTION 'edit view preparation revoked' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF e.resources_json->0->>'state' IS DISTINCT FROM 'RELEASED' THEN
            RAISE EXCEPTION 'edit view volume release not confirmed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.phase='SEALED' THEN
        r:=NEW.receipt_json;
        IF jsonb_typeof(r) IS DISTINCT FROM 'object' THEN
            RAISE EXCEPTION 'edit view receipt invalid' USING ERRCODE='check_violation';
        END IF;
        IF EXISTS (
            SELECT 1 FROM unnest(ARRAY['viewHash','viewSourceHash','viewStateHash']) key
            WHERE jsonb_typeof(r->key) IS DISTINCT FROM 'string' OR NOT coalesce(r->>key ~ '^[0-9a-f]{64}$',false))
            OR (SELECT count(*) FROM jsonb_object_keys(r))<>3 THEN
            RAISE EXCEPTION 'edit view receipt invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_view_guard BEFORE INSERT OR UPDATE OR DELETE ON biz.project_import_agent_edit_view
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_view_guard();

CREATE FUNCTION biz.project_import_agent_edit_view_parent_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.closed_at IS NOT NULL AND EXISTS (
        SELECT 1 FROM biz.project_import_agent_edit_view WHERE edit_id=NEW.edit_id AND phase<>'RELEASED') THEN
        RAISE EXCEPTION 'edit view still registered' USING ERRCODE='check_violation';
    END IF;
    IF NEW.state='EDITING' AND NOT EXISTS (
        SELECT 1 FROM biz.project_import_agent_edit_view WHERE edit_id=NEW.edit_id AND phase='SEALED') THEN
        RAISE EXCEPTION 'edit view not sealed' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_view_parent_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_view_parent_guard();
