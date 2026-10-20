-- 普通应用的变更账本与候选审阅。
-- 导入应用用卷内封存（写前不落盘）；普通应用直接写工作区，
-- 因此改为「先落盘 + 记录 before/after + 整批可回滚」。两者语义不同，不共用表。
CREATE TABLE biz.agent_change_ledger (
    session_id UUID NOT NULL REFERENCES biz.agent_edit_session(session_id),
    path TEXT NOT NULL CHECK (length(path) BETWEEN 1 AND 4096),
    change TEXT NOT NULL CHECK (change IN ('ADDED','MODIFIED','DELETED')),
    before_hash TEXT CHECK (before_hash IS NULL OR before_hash ~ '^[0-9a-f]{64}$'),
    after_hash TEXT CHECK (after_hash IS NULL OR after_hash ~ '^[0-9a-f]{64}$'),
    before_blob TEXT,
    after_blob TEXT,
    -- 内容超出上限时不存正文，只留摘要，审阅必须如实标注无法展示完整差异。
    truncated BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, path),
    CHECK ((truncated IS TRUE) OR (before_blob IS NOT NULL OR after_blob IS NOT NULL))
);

CREATE TABLE biz.agent_candidate_review (
    review_id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE REFERENCES biz.agent_edit_session(session_id),
    review_hash TEXT NOT NULL CHECK (review_hash ~ '^[0-9a-f]{64}$'),
    candidate_hash TEXT NOT NULL CHECK (candidate_hash ~ '^[0-9a-f]{64}$'),
    changes_json JSONB NOT NULL CHECK (jsonb_typeof(changes_json)='array'),
    risk TEXT NOT NULL CHECK (risk IN ('ORDINARY','HIGH')),
    ai_review_json JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(ai_review_json)='object'),
    status TEXT NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','APPROVED','REJECTED','EXPIRED','CANCELLED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    decision_source TEXT CHECK (decision_source IN ('USER','AI','AUTO')),
    decision_request_id UUID UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status<>'APPROVED' OR decision_source IS NOT NULL)
);

CREATE FUNCTION biz.agent_candidate_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s biz.agent_edit_session;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'agent candidate review audit immutable' USING ERRCODE='check_violation';
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
CREATE TRIGGER agent_candidate_review_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.agent_candidate_review FOR EACH ROW EXECUTE FUNCTION biz.agent_candidate_review_guard();