-- 安装确认与运行结果。确认安装不代表已启动；拒绝、未核对依赖和准入失败都不能产生运行中的服务。
CREATE TABLE biz.project_import_startup_preparation (
    preparation_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    plan_id UUID NOT NULL REFERENCES biz.project_import_startup_plan(plan_id),
    plan_revision TEXT NOT NULL CHECK (plan_revision ~ '^[0-9a-f]{64}$'),
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    kind TEXT NOT NULL CHECK (kind IN ('INSTALL','RUN')),
    decision TEXT NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    state TEXT NOT NULL CHECK (state IN ('REJECTED','NOT_REQUIRED','BLOCKED','REFUSED','FAILED','SUCCEEDED','INSTALL_DECLINED','WAITING_INSTALL','PARTIAL','NONE_READY','READY')),
    reason TEXT,
    services_json JSONB NOT NULL CHECK (jsonb_typeof(services_json)='array'),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (state NOT IN ('REJECTED','BLOCKED','REFUSED','FAILED','INSTALL_DECLINED','NONE_READY') OR reason IS NOT NULL)
);
-- 同一计划版本的安装或运行只保留一行；重试更新这一行，不另开成功记录。
CREATE UNIQUE INDEX project_import_startup_preparation_idx
    ON biz.project_import_startup_preparation(owner_id,app_id,plan_id,plan_revision,kind);
CREATE INDEX project_import_startup_preparation_app_idx
    ON biz.project_import_startup_preparation(app_id,owner_id,created_at DESC);
-- 计划身份冻结。状态可以推进，但版本必须加一；不能把旧版本写回。
CREATE FUNCTION biz.project_import_startup_preparation_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.preparation_id<>OLD.preparation_id OR NEW.owner_id<>OLD.owner_id OR NEW.app_id<>OLD.app_id OR NEW.plan_id<>OLD.plan_id
        OR NEW.plan_revision<>OLD.plan_revision OR NEW.content_hash<>OLD.content_hash OR NEW.kind<>OLD.kind OR NEW.created_at<>OLD.created_at THEN
        RAISE EXCEPTION 'startup preparation identity is immutable' USING ERRCODE='check_violation';
    END IF;
    IF NEW.revision<>OLD.revision+1 THEN
        RAISE EXCEPTION 'startup preparation revision must advance' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_startup_preparation_frozen_trg BEFORE UPDATE ON biz.project_import_startup_preparation
    FOR EACH ROW EXECUTE FUNCTION biz.project_import_startup_preparation_frozen();
