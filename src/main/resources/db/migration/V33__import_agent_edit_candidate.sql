-- 候选必须创建前登记；封存回执不等于Java独立校验或最终发布成功。
CREATE TABLE biz.project_import_agent_edit_candidate (
    edit_id UUID PRIMARY KEY REFERENCES biz.project_import_agent_edit(edit_id),
    phase TEXT NOT NULL CHECK (phase IN ('PLANNED','SEALED','VERIFIED','RELEASED')),
    candidate_path TEXT NOT NULL,
    manifest_path TEXT NOT NULL,
    authorized_state_hash TEXT NOT NULL CHECK (authorized_state_hash ~ '^[0-9a-f]{64}$'),
    receipt_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    verification_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION biz.project_import_agent_edit_candidate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e biz.project_import_agent_edit; r JSONB;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'edit candidate audit cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO e FROM biz.project_import_agent_edit WHERE edit_id=NEW.edit_id FOR UPDATE;
    IF e.edit_id IS NULL OR e.closed_at IS NOT NULL
        OR NEW.candidate_path IS DISTINCT FROM '/var/lib/glyahh/import-disks/edit-'||NEW.edit_id::text||'/work/candidate'
        OR NEW.manifest_path IS DISTINCT FROM '/var/lib/glyahh/import-disks/edit-'||NEW.edit_id::text||'/work/candidate-manifest.json' THEN
        RAISE EXCEPTION 'edit candidate identity invalid' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.phase<>'PLANNED' OR NEW.receipt_json<>'{}'::jsonb OR NEW.verification_json<>'{}'::jsonb THEN
            RAISE EXCEPTION 'edit candidate initial phase invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.edit_id,NEW.candidate_path,NEW.manifest_path,NEW.authorized_state_hash,NEW.created_at) IS DISTINCT FROM
           (OLD.edit_id,OLD.candidate_path,OLD.manifest_path,OLD.authorized_state_hash,OLD.created_at)
            OR NOT ((OLD.phase='PLANNED' AND NEW.phase IN ('SEALED','RELEASED')) OR
                    (OLD.phase='SEALED' AND NEW.phase IN ('VERIFIED','RELEASED')) OR
                    (OLD.phase='VERIFIED' AND NEW.phase='RELEASED'))
            OR (OLD.phase<>'PLANNED' AND NEW.receipt_json IS DISTINCT FROM OLD.receipt_json)
            OR (NEW.phase='RELEASED' AND (NEW.receipt_json IS DISTINCT FROM OLD.receipt_json OR
                                         NEW.verification_json IS DISTINCT FROM OLD.verification_json)) THEN
            RAISE EXCEPTION 'edit candidate frozen identity or phase changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.phase<>'RELEASED' THEN
        IF e.state<>'SEALING' OR NOT e.stop_confirmed OR e.cancel_requested OR e.expires_at<=now()
            OR e.resources_json->0->>'state' IS DISTINCT FROM 'READY'
            OR NOT EXISTS (SELECT 1 FROM biz.project_import_agent_edit_view v WHERE v.edit_id=e.edit_id AND v.phase='SEALED')
            OR NOT EXISTS (
                SELECT 1 FROM biz.project_import_publication p
                JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration c ON c.app_id=p.app_id AND c.owner_id=p.owner_id AND c.import_task_id=p.task_id
                WHERE p.app_id=e.app_id AND p.owner_id=e.owner_id AND p.task_id=e.import_task_id AND p.state='READY'
                  AND c.config_revision=e.config_revision AND c.context_epoch=e.context_epoch) THEN
            RAISE EXCEPTION 'edit candidate qualification revoked' USING ERRCODE='check_violation';
        END IF;
    ELSIF e.resources_json->0->>'state' IS DISTINCT FROM 'RELEASED' THEN
        RAISE EXCEPTION 'edit candidate volume release not confirmed' USING ERRCODE='check_violation';
    END IF;
    IF NEW.phase='PLANNED' AND NEW.verification_json<>'{}'::jsonb OR
       NEW.phase='SEALED' AND NEW.verification_json<>'{}'::jsonb THEN
        RAISE EXCEPTION 'edit candidate prematurely verified' USING ERRCODE='check_violation';
    END IF;
    IF NEW.phase IN ('SEALED','VERIFIED') THEN
        r:=NEW.receipt_json;
        IF jsonb_typeof(r) IS DISTINCT FROM 'object' THEN
            RAISE EXCEPTION 'edit candidate receipt invalid' USING ERRCODE='check_violation';
        END IF;
        IF (SELECT count(*) FROM jsonb_object_keys(r))<>2 OR EXISTS (
            SELECT 1 FROM unnest(ARRAY['candidateHash','candidateManifestHash']) key
            WHERE jsonb_typeof(r->key) IS DISTINCT FROM 'string' OR NOT coalesce(r->>key ~ '^[0-9a-f]{64}$',false)) THEN
            RAISE EXCEPTION 'edit candidate receipt invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.phase='VERIFIED' THEN
        IF NEW.verification_json IS DISTINCT FROM jsonb_build_object('sourceHash',e.source_hash,
             'candidateHash',NEW.receipt_json->>'candidateHash','candidateManifestHash',NEW.receipt_json->>'candidateManifestHash',
             'authorizedStateHash',NEW.authorized_state_hash) THEN
            RAISE EXCEPTION 'edit candidate Java verification invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_candidate_guard BEFORE INSERT OR UPDATE OR DELETE ON biz.project_import_agent_edit_candidate
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_candidate_guard();

CREATE FUNCTION biz.project_import_agent_edit_candidate_parent_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.closed_at IS NOT NULL AND EXISTS (
        SELECT 1 FROM biz.project_import_agent_edit_candidate WHERE edit_id=NEW.edit_id AND phase<>'RELEASED') THEN
        RAISE EXCEPTION 'edit candidate still registered' USING ERRCODE='check_violation';
    END IF;
    IF NEW.candidate_hash IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM biz.project_import_agent_edit_candidate WHERE edit_id=NEW.edit_id
          AND receipt_json->>'candidateHash'=NEW.candidate_hash AND phase IN ('SEALED','VERIFIED','RELEASED')) THEN
        RAISE EXCEPTION 'edit candidate receipt not sealed' USING ERRCODE='check_violation';
    END IF;
    IF NEW.state IN ('COMMITTING','SUCCEEDED') AND NOT EXISTS (
        SELECT 1 FROM biz.project_import_agent_edit_candidate WHERE edit_id=NEW.edit_id
          AND receipt_json->>'candidateHash'=NEW.candidate_hash
          AND verification_json->>'sourceHash'=NEW.source_hash
          AND phase=CASE WHEN NEW.state='SUCCEEDED' THEN 'RELEASED' ELSE 'VERIFIED' END) THEN
        RAISE EXCEPTION 'edit candidate not independently verified' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_candidate_parent_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_candidate_parent_guard();
