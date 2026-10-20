-- 普通应用的权限门禁：沙箱档位（硬边界）+ 审批策略（软策略）。
-- 两轴独立：沙箱拒绝时不进入审批环节，批准也不能突破沙箱。
ALTER TABLE biz.agent_edit_settings ADD COLUMN sandbox_mode TEXT NOT NULL DEFAULT 'WORKSPACE_WRITE'
    CHECK (sandbox_mode IN ('READ_ONLY','WORKSPACE_WRITE'));

CREATE TABLE biz.agent_edit_session (
    session_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    app_id BIGINT NOT NULL REFERENCES biz.app(id),
    run_id BIGINT NOT NULL,
    source_hash TEXT NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    sandbox_mode TEXT NOT NULL CHECK (sandbox_mode IN ('READ_ONLY','WORKSPACE_WRITE')),
    approval_mode TEXT NOT NULL CHECK (approval_mode IN ('MANUAL','GUARDED_AUTO','AUTO')),
    policy_revision BIGINT NOT NULL CHECK (policy_revision>=0),
    session_epoch UUID NOT NULL,
    server_generation UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('EDITING','WAITING_REVIEW','SUCCEEDED','CANCELLED','FAILED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ
);
-- 同一应用同时只允许一个未关闭会话，避免并发写同一工作区。
CREATE UNIQUE INDEX agent_edit_session_open_idx ON biz.agent_edit_session(app_id) WHERE closed_at IS NULL;

CREATE TABLE biz.agent_tool_permission (
    permission_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES biz.agent_edit_session(session_id),
    tool TEXT NOT NULL CHECK (tool IN ('write','edit','delete','upload_files','execute')),
    action_hash TEXT NOT NULL CHECK (action_hash ~ '^[0-9a-f]{64}$'),
    action_json JSONB NOT NULL CHECK (jsonb_typeof(action_json)='object'),
    risk TEXT NOT NULL CHECK (risk IN ('ORDINARY','HIGH')),
    scope_hash TEXT NOT NULL CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    display_json JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(display_json)='object'),
    status TEXT NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
        'REQUESTED','APPROVED','REJECTED','CONSUMED',
        'RESULT_CONFIRMED','RESULT_UNCONFIRMED','EXPIRED','CANCELLED','FAILED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    decision_source TEXT CHECK (decision_source IN ('USER','SESSION','AI','AUTO')),
    decision_request_id UUID UNIQUE,
    consumption_proof UUID UNIQUE,
    consume_request_id UUID UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 只有已决定且消费过才允许有消费证明；反过来有证明必须有请求号。
    CHECK ((status IN ('CONSUMED','RESULT_CONFIRMED','RESULT_UNCONFIRMED'))=(consumption_proof IS NOT NULL)),
    CHECK ((consumption_proof IS NOT NULL)=(consume_request_id IS NOT NULL)),
    CHECK (status NOT IN ('APPROVED','CONSUMED','RESULT_CONFIRMED','RESULT_UNCONFIRMED')
        OR decision_source IS NOT NULL)
);
-- 同一会话同时只允许一个待决票据，防止并发批准堆叠。
CREATE UNIQUE INDEX agent_tool_permission_wait_idx
    ON biz.agent_tool_permission(session_id) WHERE status IN ('REQUESTED','APPROVED');

CREATE TABLE biz.agent_session_grant (
    grant_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES biz.agent_edit_session(session_id),
    tool TEXT NOT NULL CHECK (tool IN ('write','edit','delete','upload_files','execute')),
    risk TEXT NOT NULL CHECK (risk IN ('ORDINARY','HIGH')),
    scope_hash TEXT NOT NULL CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    permission_id UUID NOT NULL UNIQUE REFERENCES biz.agent_tool_permission(permission_id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX agent_session_grant_match_idx
    ON biz.agent_session_grant(session_id,tool,risk,scope_hash);

CREATE FUNCTION biz.agent_edit_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s biz.agent_edit_settings;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'agent edit session cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    -- 身份与冻结的两轴取值一经写入不可改；只允许状态与关闭时间推进。
    IF TG_OP='UPDATE' THEN
        IF (NEW.session_id,NEW.owner_id,NEW.app_id,NEW.run_id,NEW.source_hash,NEW.sandbox_mode,
            NEW.approval_mode,NEW.policy_revision,NEW.session_epoch,NEW.server_generation,NEW.created_at)
           IS DISTINCT FROM
           (OLD.session_id,OLD.owner_id,OLD.app_id,OLD.run_id,OLD.source_hash,OLD.sandbox_mode,
            OLD.approval_mode,OLD.policy_revision,OLD.session_epoch,OLD.server_generation,OLD.created_at) THEN
            RAISE EXCEPTION 'agent edit session identity changed' USING ERRCODE='check_violation';
        END IF;
        IF OLD.closed_at IS NOT NULL AND NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
            RAISE EXCEPTION 'agent edit session already closed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    -- 归属必须始终成立。
    IF NOT EXISTS (SELECT 1 FROM biz.app a
        WHERE a.id=NEW.app_id AND a.user_id=NEW.owner_id AND a.is_delete=0) THEN
        RAISE EXCEPTION 'agent edit session ownership revoked' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_edit_session_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.agent_edit_session FOR EACH ROW EXECUTE FUNCTION biz.agent_edit_session_guard();

CREATE FUNCTION biz.agent_tool_permission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s biz.agent_edit_session;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'agent tool permission audit immutable' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO s FROM biz.agent_edit_session WHERE session_id=NEW.session_id FOR UPDATE;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'REQUESTED' OR NEW.revision<>0 OR NEW.decision_source IS NOT NULL
            OR NEW.expires_at<=now() THEN
            RAISE EXCEPTION 'agent tool permission initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        -- 动作对象不可改：summary 变化必须重新申请，不能沿用旧票。
        IF NEW.action_hash<>OLD.action_hash OR NEW.tool<>OLD.tool OR NEW.risk<>OLD.risk
            OR NEW.scope_hash<>OLD.scope_hash OR NEW.session_id<>OLD.session_id
            OR (to_jsonb(NEW)->'action_json') IS DISTINCT FROM (to_jsonb(OLD)->'action_json') THEN
            RAISE EXCEPTION 'agent tool permission object changed' USING ERRCODE='check_violation';
        END IF;
        IF NEW.revision<>OLD.revision+1 THEN
            RAISE EXCEPTION 'agent tool permission revision not advanced' USING ERRCODE='check_violation';
        END IF;
        -- 决定来源必须与冻结的审批模式一致，自动模式不能冒充用户批准。
        IF NEW.status='APPROVED' THEN
            IF s.approval_mode='MANUAL' AND NEW.decision_source<>'USER'
                OR NEW.decision_source='AI' AND (s.approval_mode<>'GUARDED_AUTO' OR NEW.risk<>'ORDINARY')
                OR NEW.decision_source='AUTO' AND s.approval_mode<>'AUTO'
                OR NEW.decision_source='SESSION' AND NOT EXISTS (
                    SELECT 1 FROM biz.agent_session_grant g
                    WHERE g.session_id=s.session_id AND g.tool=NEW.tool AND g.risk=NEW.risk
                        AND g.scope_hash=NEW.scope_hash AND g.expires_at>now()) THEN
                RAISE EXCEPTION 'agent tool permission decision source invalid' USING ERRCODE='check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_tool_permission_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.agent_tool_permission FOR EACH ROW EXECUTE FUNCTION biz.agent_tool_permission_guard();

CREATE FUNCTION biz.agent_session_grant_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'agent session grant immutable' USING ERRCODE='check_violation';
    END IF;
    -- 同类授权只能由用户亲手批准的那张票派生，不能凭空创建。
    IF NOT EXISTS (SELECT 1 FROM biz.agent_tool_permission p
        JOIN biz.agent_edit_session s ON s.session_id=p.session_id
        WHERE p.permission_id=NEW.permission_id AND p.status='APPROVED' AND p.decision_source='USER'
            AND p.tool=NEW.tool AND p.risk=NEW.risk AND p.scope_hash=NEW.scope_hash
            AND p.session_id=NEW.session_id AND NEW.expires_at>now()) THEN
        RAISE EXCEPTION 'agent session grant not approved' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_session_grant_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.agent_session_grant FOR EACH ROW EXECUTE FUNCTION biz.agent_session_grant_guard();