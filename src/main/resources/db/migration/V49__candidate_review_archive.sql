-- 候选审阅的外键指向编辑会话，而 V45 允许删除已关闭会话。
-- 原来的 DELETE 无条件拒绝，结果会话被永久锁死、无法归档。
-- 归档只能随已关闭会话进行；未关闭会话的审阅仍不可删。
CREATE OR REPLACE FUNCTION biz.agent_candidate_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s biz.agent_edit_session;
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT EXISTS (SELECT 1 FROM biz.agent_edit_session
            WHERE session_id=OLD.session_id AND closed_at IS NOT NULL) THEN
            RAISE EXCEPTION 'agent candidate review audit immutable' USING ERRCODE='check_violation';
        END IF;
        RETURN OLD;
    END IF;
    SELECT * INTO s FROM biz.agent_edit_session WHERE session_id=NEW.session_id FOR UPDATE;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'WAITING' OR NEW.revision<>0 OR NEW.decision_source IS NOT NULL
            OR NEW.expires_at<=now() THEN
            RAISE EXCEPTION 'agent candidate review initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.review_id,NEW.session_id,NEW.review_hash,NEW.candidate_hash,NEW.created_at)
           IS DISTINCT FROM
           (OLD.review_id,OLD.session_id,OLD.review_hash,OLD.candidate_hash,OLD.created_at)
            OR NEW.revision<>OLD.revision+1 THEN
            RAISE EXCEPTION 'agent candidate review identity or revision changed' USING ERRCODE='check_violation';
        END IF;
        -- 决定来源必须与冻结的审批模式一致：MANUAL 只认用户，AI 只认非高风险。
        IF NEW.status='APPROVED' THEN
            IF s.approval_mode='MANUAL' AND NEW.decision_source<>'USER'
                OR NEW.decision_source='AI' AND (s.approval_mode<>'GUARDED_AUTO' OR NEW.risk<>'ORDINARY')
                OR NEW.decision_source='AUTO' AND s.approval_mode<>'AUTO' THEN
                RAISE EXCEPTION 'agent candidate decision source invalid' USING ERRCODE='check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;