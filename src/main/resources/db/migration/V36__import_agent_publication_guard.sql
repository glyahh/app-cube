-- 已提交发布的上下文已失效；清理和重启收口核对提交后的代次。
CREATE OR REPLACE FUNCTION biz.project_import_agent_edit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
            AND c.config_revision=NEW.config_revision AND c.context_epoch=NEW.context_epoch+CASE WHEN TG_OP='UPDATE' AND OLD.publish_phase='COMMITTED' THEN 1 ELSE 0 END
            AND NOT NEW.cancel_requested AND (NEW.expires_at>now() OR NEW.publish_phase='COMMITTED')) THEN
        RAISE EXCEPTION 'edit qualification revoked' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;

-- 阶段只向前推进；恢复不改动冻结发布路径或已提交摘要。
CREATE FUNCTION biz.project_import_agent_publication_phase_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.publish_phase<>OLD.publish_phase AND NOT (
            (OLD.publish_phase='NONE' AND NEW.publish_phase='CANDIDATE_READY' AND NEW.state='COMMITTING') OR
            (OLD.publish_phase='CANDIDATE_READY' AND NEW.publish_phase='OLD_MOVED' AND NEW.state='COMMITTING') OR
            (OLD.publish_phase='OLD_MOVED' AND NEW.publish_phase='NEW_MOVED' AND NEW.state='COMMITTING') OR
            (OLD.publish_phase='NEW_MOVED' AND NEW.publish_phase='COMMITTED' AND NEW.state='COMMITTING')) THEN
            RAISE EXCEPTION 'publication phase invalid' USING ERRCODE='check_violation';
        END IF;
        IF OLD.publish_phase<>'NONE' AND
            (NEW.recovery_json-'publishedHash') IS DISTINCT FROM (OLD.recovery_json-'publishedHash') THEN
            RAISE EXCEPTION 'publication paths changed' USING ERRCODE='check_violation';
        END IF;
        IF OLD.recovery_json ? 'publishedHash' AND
            NEW.recovery_json->'publishedHash' IS DISTINCT FROM OLD.recovery_json->'publishedHash' THEN
            RAISE EXCEPTION 'publication content changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.publish_phase<>'NONE' THEN
        IF NEW.recovery_json->>'originalHash' IS DISTINCT FROM NEW.source_hash OR EXISTS (
            SELECT 1 FROM unnest(ARRAY['source','folder','candidate','backup']) key
            WHERE jsonb_typeof(NEW.recovery_json->key) IS DISTINCT FROM 'string'
                OR length(NEW.recovery_json->>key)=0) THEN
            RAISE EXCEPTION 'publication journal invalid' USING ERRCODE='check_violation';
        END IF;
        IF NEW.publish_phase IN ('OLD_MOVED','NEW_MOVED','COMMITTED') AND NOT
            coalesce(NEW.recovery_json->>'publishedHash' ~ '^[0-9a-f]{64}$',false) THEN
            RAISE EXCEPTION 'publication content missing' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_publication_phase_guard BEFORE INSERT OR UPDATE ON biz.project_import_agent_edit
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_publication_phase_guard();
