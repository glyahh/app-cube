-- MANUAL 模式此前只允许 decision_source='USER'，把「本会话批准同类请求」
-- 派生出的 SESSION 票据一并拒掉：守卫第一项先成立，后面的 grant 校验永远走不到。
-- 这里把 MANUAL 的允许范围扩到 USER / SESSION；SESSION 仍必须对上有效 grant。
CREATE OR REPLACE FUNCTION biz.agent_tool_permission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s biz.agent_edit_session;
BEGIN
    IF TG_OP='DELETE' THEN
        -- 允许随已关闭会话归档清理；未关闭会话的票据永久保留。
        IF NOT EXISTS (SELECT 1 FROM biz.agent_edit_session
            WHERE session_id=OLD.session_id AND closed_at IS NOT NULL) THEN
            RAISE EXCEPTION 'agent tool permission audit immutable' USING ERRCODE='check_violation';
        END IF;
        RETURN OLD;
    END IF;
    SELECT * INTO s FROM biz.agent_edit_session WHERE session_id=NEW.session_id FOR UPDATE;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'REQUESTED' OR NEW.revision<>0 OR NEW.decision_source IS NOT NULL
            OR NEW.expires_at<=now() THEN
            RAISE EXCEPTION 'agent tool permission initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF NEW.action_hash<>OLD.action_hash OR NEW.tool<>OLD.tool OR NEW.risk<>OLD.risk
            OR NEW.scope_hash<>OLD.scope_hash OR NEW.session_id<>OLD.session_id
            OR (to_jsonb(NEW)->'action_json') IS DISTINCT FROM (to_jsonb(OLD)->'action_json') THEN
            RAISE EXCEPTION 'agent tool permission object changed' USING ERRCODE='check_violation';
        END IF;
        IF NEW.revision<>OLD.revision+1 THEN
            RAISE EXCEPTION 'agent tool permission revision not advanced' USING ERRCODE='check_violation';
        END IF;
        IF NEW.status='APPROVED' THEN
            IF s.approval_mode='MANUAL' AND NEW.decision_source NOT IN ('USER','SESSION')
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
