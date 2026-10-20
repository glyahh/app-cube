-- 会话归档需要能清理其同类授权，否则外键会永久挡住会话删除。
-- 只允许随已关闭会话归档；未关闭会话的授权仍不可删。
CREATE OR REPLACE FUNCTION biz.agent_session_grant_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT EXISTS (SELECT 1 FROM biz.agent_edit_session
            WHERE session_id=OLD.session_id AND closed_at IS NOT NULL) THEN
            RAISE EXCEPTION 'agent session grant immutable' USING ERRCODE='check_violation';
        END IF;
        RETURN OLD;
    END IF;
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