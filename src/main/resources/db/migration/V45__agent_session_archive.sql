-- 修正 V44 会话守卫：已关闭的会话允许删除，否则长期运行会无限堆积。
-- 未关闭会话仍然禁止删除，避免绕过准入使在途编辑失联。
CREATE OR REPLACE FUNCTION biz.agent_edit_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.closed_at IS NULL THEN
            RAISE EXCEPTION 'open agent edit session cannot be deleted' USING ERRCODE='check_violation';
        END IF;
        RETURN OLD;
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