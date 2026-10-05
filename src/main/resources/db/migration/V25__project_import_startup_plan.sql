-- 冻结的运行计划与本次确认；确认不等于已安装、已构建或已运行，执行入口仍关闭。
CREATE TABLE biz.project_import_startup_plan (
    plan_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    scan_id UUID NOT NULL REFERENCES biz.project_import_startup_inspection(scan_id),
    import_task_id UUID NOT NULL REFERENCES biz.project_import_task(task_id),
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    config_revision BIGINT NOT NULL CHECK (config_revision>=0),
    context_epoch BIGINT NOT NULL CHECK (context_epoch>=0),
    rules_version TEXT NOT NULL,
    plan_revision TEXT NOT NULL CHECK (plan_revision ~ '^[0-9a-f]{64}$'),
    plan_json JSONB NOT NULL CHECK (jsonb_typeof(plan_json)='object'),
    state TEXT NOT NULL CHECK (state IN ('DRAFT','CONFIRMED','STALE','SUPERSEDED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    aggregate_acknowledged BOOLEAN NOT NULL DEFAULT false,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    stale_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (state<>'DRAFT' OR (confirmed_at IS NULL AND NOT aggregate_acknowledged)),
    CHECK (state<>'CONFIRMED' OR confirmed_at IS NOT NULL),
    CHECK (state<>'STALE' OR (stale_json IS NOT NULL AND jsonb_typeof(stale_json)='object'))
);
-- 同一内容只保留一份有效计划；每个应用同时只有一份已确认计划。
CREATE UNIQUE INDEX project_import_startup_plan_active_idx ON biz.project_import_startup_plan(owner_id,app_id,plan_revision) WHERE state IN ('DRAFT','CONFIRMED');
CREATE UNIQUE INDEX project_import_startup_plan_confirmed_idx ON biz.project_import_startup_plan(app_id) WHERE state='CONFIRMED';
CREATE INDEX project_import_startup_plan_app_idx ON biz.project_import_startup_plan(app_id,owner_id,created_at DESC);
-- 计划内容与身份冻结；失效或被替代的计划不能恢复。
CREATE FUNCTION biz.project_import_startup_plan_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.plan_id<>OLD.plan_id OR NEW.owner_id<>OLD.owner_id OR NEW.app_id<>OLD.app_id OR NEW.scan_id<>OLD.scan_id OR NEW.import_task_id<>OLD.import_task_id
        OR NEW.content_hash<>OLD.content_hash OR NEW.config_revision<>OLD.config_revision OR NEW.context_epoch<>OLD.context_epoch
        OR NEW.rules_version<>OLD.rules_version OR NEW.plan_revision<>OLD.plan_revision OR NEW.plan_json IS DISTINCT FROM OLD.plan_json
        OR NEW.created_at<>OLD.created_at THEN
        RAISE EXCEPTION 'startup plan content is immutable' USING ERRCODE='check_violation';
    END IF;
    IF OLD.state IN ('STALE','SUPERSEDED') OR (OLD.state='CONFIRMED' AND NEW.state='DRAFT') THEN
        RAISE EXCEPTION 'startup plan state cannot be restored' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_startup_plan_frozen_trg BEFORE UPDATE ON biz.project_import_startup_plan
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_startup_plan_frozen();
