-- 普通应用的模型偏好；导入应用继续使用 V40 的会话设置表。
-- 本表只记录"选哪个模型、用什么思考力度"，不承载权限模式与授权票据。
CREATE TABLE biz.agent_model_preference (
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.app(id),
    profile_id TEXT NOT NULL CHECK (profile_id ~ '^[a-zA-Z0-9_-]{1,64}$'),
    effort TEXT CHECK (effort IN ('low','medium','high')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, app_id)
);

CREATE FUNCTION biz.agent_model_preference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent model preference cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM biz.app a
        WHERE a.id = NEW.app_id AND a.user_id = NEW.owner_id AND a.is_delete = 0) THEN
        RAISE EXCEPTION 'agent model preference ownership revoked' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.revision <> 0 THEN
            RAISE EXCEPTION 'agent model preference initial revision invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.owner_id, NEW.app_id) IS DISTINCT FROM (OLD.owner_id, OLD.app_id)
            OR NEW.revision <> OLD.revision + 1 THEN
            RAISE EXCEPTION 'agent model preference identity or revision changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER agent_model_preference_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.agent_model_preference FOR EACH ROW EXECUTE FUNCTION biz.agent_model_preference_guard();