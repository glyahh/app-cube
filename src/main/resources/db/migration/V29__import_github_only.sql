-- 只限制新业务资格；旧来源和凭据仍可读取、删除，已导入应用不受影响。
CREATE FUNCTION biz.project_import_source_provider_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.definition_json->'source'->>'provider'
        IS DISTINCT FROM OLD.definition_json->'source'->>'provider' THEN
        RAISE EXCEPTION 'import provider immutable' USING ERRCODE='check_violation';
    END IF;
    IF NEW.definition_json->'source'->>'provider' IS DISTINCT FROM 'GitHub' THEN
        IF TG_OP='INSERT' THEN
            RAISE EXCEPTION 'import provider retired' USING ERRCODE='check_violation';
        END IF;
        IF NEW.status NOT IN ('CANCELLING','CANCELLED','FAILED','STOP_FAILED','RECOVERY_REQUIRED')
            AND NOT (OLD.status='SUCCEEDED' AND NEW.status='SUCCEEDED') THEN
            RAISE EXCEPTION 'import provider retired' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_source_provider_guard BEFORE INSERT OR UPDATE ON biz.project_import_source
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_source_provider_guard();

CREATE FUNCTION biz.project_import_task_provider_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_kind='GIT' AND EXISTS (
        SELECT 1 FROM biz.project_import_source s WHERE s.task_id=NEW.task_id
            AND s.definition_json->'source'->>'provider' IS DISTINCT FROM 'GitHub'
    ) THEN
        IF TG_OP='INSERT' THEN
            RAISE EXCEPTION 'import provider retired' USING ERRCODE='check_violation';
        END IF;
        IF NEW.status NOT IN ('CANCELLING','CANCELLED','FAILED','STOP_FAILED','RECOVERY_REQUIRED')
            AND NOT (OLD.status='SUCCEEDED' AND NEW.status='SUCCEEDED') THEN
            RAISE EXCEPTION 'import provider retired' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_task_provider_guard BEFORE INSERT OR UPDATE ON biz.project_import_task
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_task_provider_guard();

CREATE FUNCTION biz.project_import_credential_provider_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.provider IS DISTINCT FROM OLD.provider THEN
        RAISE EXCEPTION 'import provider immutable' USING ERRCODE='check_violation';
    END IF;
    IF NEW.provider IS DISTINCT FROM 'GitHub' THEN
        IF TG_OP='INSERT' THEN
            RAISE EXCEPTION 'import provider retired' USING ERRCODE='check_violation';
        END IF;
        IF NEW.current_version<>OLD.current_version OR NEW.name IS DISTINCT FROM OLD.name
            OR NEW.provider IS DISTINCT FROM OLD.provider OR (OLD.state='DELETED' AND NEW.state<>'DELETED') THEN
            RAISE EXCEPTION 'import provider retired' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_credential_provider_guard BEFORE INSERT OR UPDATE ON biz.project_import_credential
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_credential_provider_guard();
