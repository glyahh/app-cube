-- 普通应用的会话授权设置；与 V40 的导入设置分开存储。
-- 导入设置的 app_id 外键指向发布记录，普通应用没有发布记录，不能共用一张表。
CREATE TABLE biz.agent_edit_settings (
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.app(id),
    thread_id TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'MANUAL' CHECK (mode IN ('MANUAL','GUARDED_AUTO','AUTO')),
    policy_revision BIGINT NOT NULL DEFAULT 0 CHECK (policy_revision >= 0),
    session_epoch UUID NOT NULL,
    session_binding TEXT NOT NULL CHECK (session_binding ~ '^[0-9a-f]{64}$'),
    server_generation UUID NOT NULL,
    session_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, app_id, thread_id),
    CHECK (thread_id = 'app:' || app_id::text)
);

CREATE FUNCTION biz.agent_edit_settings_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent edit settings cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    -- 归属必须始终成立：应用仍属于该用户且未删除。
    IF NOT EXISTS (SELECT 1 FROM biz.app a
        WHERE a.id = NEW.app_id AND a.user_id = NEW.owner_id AND a.is_delete = 0) THEN
        RAISE EXCEPTION 'agent edit settings ownership revoked' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.policy_revision <> 0 OR NEW.session_expires_at <= now()
            OR NEW.session_expires_at > now() + interval '8 hours' THEN
            RAISE EXCEPTION 'agent edit settings initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.owner_id, NEW.app_id, NEW.thread_id, NEW.created_at) IS DISTINCT FROM
           (OLD.owner_id, OLD.app_id, OLD.thread_id, OLD.created_at)
            OR NEW.policy_revision <> OLD.policy_revision + 1 THEN
            RAISE EXCEPTION 'agent edit settings identity or revision changed' USING ERRCODE='check_violation';
        END IF;
        -- 会话身份一旦换代才允许延长有效期；同一会话不得悄悄续期。
        IF NEW.session_epoch = OLD.session_epoch
            AND (NEW.session_binding, NEW.server_generation, NEW.session_expires_at) IS DISTINCT FROM
                (OLD.session_binding, OLD.server_generation, OLD.session_expires_at) THEN
            RAISE EXCEPTION 'agent edit session cannot be extended' USING ERRCODE='check_violation';
        END IF;
        IF NEW.session_epoch <> OLD.session_epoch
            AND (NEW.session_expires_at <= now() OR NEW.session_expires_at > now() + interval '8 hours') THEN
            RAISE EXCEPTION 'agent edit session lifetime invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER agent_edit_settings_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.agent_edit_settings FOR EACH ROW EXECUTE FUNCTION biz.agent_edit_settings_guard();