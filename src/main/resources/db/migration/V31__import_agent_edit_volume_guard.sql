-- 编辑卷的登记先于实际创建；资源记录不可丢失或跨运行复用。
CREATE FUNCTION biz.project_import_agent_edit_volume_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb; old_r jsonb; old_state text;
BEGIN
    IF jsonb_typeof(NEW.resources_json) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'edit resources must be array' USING ERRCODE='check_violation';
    END IF;
    IF jsonb_array_length(NEW.resources_json)>1 THEN
        RAISE EXCEPTION 'edit volume resource count invalid' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='UPDATE' AND OLD.resources_json<>'[]'::jsonb AND NEW.resources_json='[]'::jsonb THEN
        RAISE EXCEPTION 'edit resource removed' USING ERRCODE='check_violation';
    END IF;
    IF NEW.resources_json<>'[]'::jsonb THEN
        r:=NEW.resources_json->0;
        IF jsonb_typeof(r)<>'object' OR EXISTS (
            SELECT 1 FROM unnest(ARRAY['kind','editId','resourceId','ownerId','appId','runId','contentHash','mountPath','state']) key
            WHERE jsonb_typeof(r->key) IS DISTINCT FROM 'string') OR r->>'kind' IS DISTINCT FROM 'VOLUME'
            OR r->>'editId' IS DISTINCT FROM NEW.edit_id::text
            OR r->>'resourceId' IS DISTINCT FROM NEW.edit_id::text
            OR r->>'ownerId' IS DISTINCT FROM NEW.owner_id::text
            OR r->>'appId' IS DISTINCT FROM NEW.app_id::text
            OR r->>'runId' IS DISTINCT FROM NEW.run_id::text
            OR r->>'contentHash' IS DISTINCT FROM NEW.source_hash
            OR r->'formatVersion' IS DISTINCT FROM '3'::jsonb
            OR jsonb_typeof(r->'diskBytes') IS DISTINCT FROM 'number'
            OR NOT coalesce(r->>'diskBytes' ~ '^[0-9]{1,11}$',false)
            OR r->>'mountPath' IS DISTINCT FROM '/var/lib/glyahh/import-disks/edit-'||NEW.edit_id::text||'/work'
            OR NOT coalesce(r->>'state' IN ('PLANNED','READY','RELEASED'),false) THEN
            RAISE EXCEPTION 'edit resource identity invalid' USING ERRCODE='check_violation';
        END IF;
        IF (r->>'diskBytes')::bigint<16777216 OR (r->>'diskBytes')::bigint>8589934592 OR (r->>'diskBytes')::bigint%4096<>0 THEN
            RAISE EXCEPTION 'edit volume capacity invalid' USING ERRCODE='check_violation';
        END IF;
        IF TG_OP='UPDATE' AND OLD.resources_json<>'[]'::jsonb THEN
            old_r:=OLD.resources_json->0;old_state:=old_r->>'state';
            IF (r-'state'-'actualBytes'-'reservedBytes'-'loopDevice'-'journal') IS DISTINCT FROM
               (old_r-'state'-'actualBytes'-'reservedBytes'-'loopDevice'-'journal')
                OR (old_state='RELEASED' AND r IS DISTINCT FROM old_r)
                OR (old_state='READY' AND r->>'state' NOT IN ('READY','RELEASED'))
                OR (old_state='PLANNED' AND r->>'state' NOT IN ('PLANNED','READY','RELEASED')) THEN
                RAISE EXCEPTION 'edit resource identity or phase changed' USING ERRCODE='check_violation';
            END IF;
        ELSE
            IF NEW.state<>'PREPARING' OR r->>'state'<>'PLANNED' OR NEW.cancel_requested OR NOT EXISTS (
                SELECT 1 FROM biz.project_import_publication p
                JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration c ON c.app_id=p.app_id AND c.owner_id=p.owner_id AND c.import_task_id=p.task_id
                WHERE p.app_id=NEW.app_id AND p.owner_id=NEW.owner_id AND p.task_id=NEW.import_task_id AND p.state='READY'
                  AND c.config_revision=NEW.config_revision AND c.context_epoch=NEW.context_epoch AND NEW.expires_at>now()) THEN
                RAISE EXCEPTION 'edit resource admission revoked' USING ERRCODE='check_violation';
            END IF;
        END IF;
        IF r->>'state'='READY' THEN
            IF jsonb_typeof(r->'actualBytes') IS DISTINCT FROM 'number' OR r->>'actualBytes' IS DISTINCT FROM r->>'diskBytes'
                OR jsonb_typeof(r->'reservedBytes') IS DISTINCT FROM 'number'
                OR NOT coalesce(r->>'reservedBytes' ~ '^[0-9]{1,12}$',false)
                OR NOT coalesce(r->>'loopDevice' ~ '^/dev/loop[0-9]+$',false)
                OR r->'journal' IS DISTINCT FROM 'true'::jsonb THEN
                RAISE EXCEPTION 'edit volume hard limit not confirmed' USING ERRCODE='check_violation';
            END IF;
            IF (r->>'reservedBytes')::bigint<(r->>'diskBytes')::bigint THEN
                RAISE EXCEPTION 'edit host space not reserved' USING ERRCODE='check_violation';
            END IF;
        END IF;
        IF NEW.closed_at IS NOT NULL AND r->>'state'<>'RELEASED' THEN
            RAISE EXCEPTION 'edit resources not released' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.state='EDITING' AND (NEW.resources_json='[]'::jsonb OR NEW.resources_json->0->>'state'<>'READY') THEN
        RAISE EXCEPTION 'edit volume not ready' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_edit_volume_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_edit_volume_guard();
