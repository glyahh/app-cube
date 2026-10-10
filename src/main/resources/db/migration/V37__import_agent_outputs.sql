-- 执行产物独立于源码候选和临时编辑卷，绑定冻结业务身份。
CREATE TABLE biz.project_import_agent_edit_outputs (
    edit_id uuid PRIMARY KEY REFERENCES biz.project_import_agent_edit(edit_id),
    owner_id bigint NOT NULL,
    app_id bigint NOT NULL,
    run_id bigint NOT NULL,
    source_hash varchar(64) NOT NULL,
    resource_path text NOT NULL,
    files_json jsonb NOT NULL CHECK (jsonb_typeof(files_json)='object'),
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes BETWEEN 0 AND 8589934592),
    phase varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK (phase IN ('PLANNED','READY','RELEASED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION biz.project_import_agent_edit_outputs_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'outputs audit cannot be deleted'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.phase<>'PLANNED' OR NOT EXISTS (
            SELECT 1 FROM biz.project_import_agent_edit e
            JOIN biz.project_import_agent_edit_candidate c USING(edit_id)
            WHERE e.edit_id=NEW.edit_id AND e.owner_id=NEW.owner_id AND e.app_id=NEW.app_id
                AND e.run_id=NEW.run_id AND e.source_hash=NEW.source_hash
                AND e.state='SEALING' AND e.stop_confirmed AND NOT e.cancel_requested
                AND c.phase='VERIFIED' AND c.verification_json->'retainedOutputs'=NEW.files_json
        ) THEN RAISE EXCEPTION 'outputs identity or verification invalid'; END IF;
    ELSE
        IF (NEW.edit_id,NEW.owner_id,NEW.app_id,NEW.run_id,NEW.source_hash,NEW.resource_path,NEW.files_json,NEW.reserved_bytes,NEW.created_at)
            IS DISTINCT FROM (OLD.edit_id,OLD.owner_id,OLD.app_id,OLD.run_id,OLD.source_hash,OLD.resource_path,OLD.files_json,OLD.reserved_bytes,OLD.created_at)
            OR OLD.phase='RELEASED' OR NOT ((OLD.phase='PLANNED' AND NEW.phase IN ('READY','RELEASED'))
                OR (OLD.phase='READY' AND NEW.phase='RELEASED')) THEN RAISE EXCEPTION 'outputs transition invalid'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_outputs_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.project_import_agent_edit_outputs FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_outputs_guard();

-- 保留原有候选资格校验，额外冻结Java独立识别的产物清单。
CREATE OR REPLACE FUNCTION biz.project_import_agent_edit_candidate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
        IF jsonb_typeof(NEW.verification_json->'retainedOutputs') IS DISTINCT FROM 'object'
            OR (NEW.verification_json-'retainedOutputs') IS DISTINCT FROM jsonb_build_object('sourceHash',e.source_hash,
             'candidateHash',NEW.receipt_json->>'candidateHash','candidateManifestHash',NEW.receipt_json->>'candidateManifestHash',
             'authorizedStateHash',NEW.authorized_state_hash) THEN
            RAISE EXCEPTION 'edit candidate Java verification invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
