-- 本迁移是权限闭环的基础；中间实现不可作为可发布版本。
CREATE TABLE biz.project_import_agent_settings (
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    thread_id TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'MANUAL' CHECK (mode IN ('MANUAL','GUARDED_AUTO','AUTO')),
    profile_id TEXT NOT NULL DEFAULT 'default' CHECK (profile_id ~ '^[a-zA-Z0-9_-]{1,64}$'),
    effort TEXT CHECK (effort IN ('low','medium','high')),
    policy_revision BIGINT NOT NULL DEFAULT 0 CHECK (policy_revision >= 0),
    session_epoch UUID NOT NULL,
    session_binding TEXT NOT NULL CHECK (session_binding ~ '^[0-9a-f]{64}$'),
    server_generation UUID NOT NULL,
    session_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id,app_id,thread_id),
    CHECK (thread_id='app:'||app_id::text)
);

CREATE FUNCTION biz.project_import_agent_settings_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'agent settings cannot be deleted' USING ERRCODE='check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM biz.project_import_publication p
        JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
        WHERE p.app_id=NEW.app_id AND p.owner_id=NEW.owner_id AND p.state='READY') THEN
        RAISE EXCEPTION 'agent settings ownership revoked' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.mode<>'MANUAL' OR NEW.policy_revision<>0 OR NEW.profile_id<>'default' OR NEW.effort IS NOT NULL
            OR NEW.session_expires_at<=now() OR NEW.session_expires_at>now()+interval '8 hours' THEN
            RAISE EXCEPTION 'agent settings initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (NEW.owner_id,NEW.app_id,NEW.thread_id,NEW.created_at) IS DISTINCT FROM
           (OLD.owner_id,OLD.app_id,OLD.thread_id,OLD.created_at) OR NEW.policy_revision<>OLD.policy_revision+1 THEN
            RAISE EXCEPTION 'agent settings identity or revision changed' USING ERRCODE='check_violation';
        END IF;
        IF (NEW.mode,NEW.profile_id,NEW.effort) IS DISTINCT FROM (OLD.mode,OLD.profile_id,OLD.effort)
            AND EXISTS (SELECT 1 FROM biz.project_import_agent_edit WHERE app_id=NEW.app_id AND closed_at IS NULL) THEN
            RAISE EXCEPTION 'agent settings edit unresolved' USING ERRCODE='check_violation';
        END IF;
        IF NEW.session_epoch=OLD.session_epoch AND
           (NEW.session_binding,NEW.server_generation,NEW.session_expires_at) IS DISTINCT FROM
           (OLD.session_binding,OLD.server_generation,OLD.session_expires_at) THEN
            RAISE EXCEPTION 'agent session cannot be extended' USING ERRCODE='check_violation';
        END IF;
        IF NEW.session_epoch<>OLD.session_epoch AND
           (NEW.session_expires_at<=now() OR NEW.session_expires_at>now()+interval '8 hours') THEN
            RAISE EXCEPTION 'agent session lifetime invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_settings_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.project_import_agent_settings FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_settings_guard();

-- 旧未发布执行先撤销；发布中和已提交任务仍由原恢复链处理。
UPDATE biz.project_import_agent_edit SET state='RECOVERY_REQUIRED',cancel_requested=true,
    revision=revision+1,reason='PERMISSION_MIGRATION',update_time=now()
    WHERE closed_at IS NULL AND publish_phase='NONE' AND state<>'RECOVERY_REQUIRED';
UPDATE biz.project_import_agent_edit SET cancel_requested=true,revision=revision+1,
    reason='PERMISSION_MIGRATION',update_time=now()
    WHERE closed_at IS NULL AND publish_phase='NONE' AND state='RECOVERY_REQUIRED' AND NOT cancel_requested;

ALTER TABLE biz.project_import_agent_edit ADD COLUMN permission_mode TEXT
    CHECK (permission_mode IN ('MANUAL','GUARDED_AUTO','AUTO'));
ALTER TABLE biz.project_import_agent_edit ADD COLUMN policy_revision BIGINT CHECK (policy_revision>=0);
ALTER TABLE biz.project_import_agent_edit ADD COLUMN model_profile_id TEXT
    CHECK (model_profile_id ~ '^[a-zA-Z0-9_-]{1,64}$');
ALTER TABLE biz.project_import_agent_edit ADD COLUMN thinking_effort TEXT CHECK (thinking_effort IN ('low','medium','high'));
ALTER TABLE biz.project_import_agent_edit ADD COLUMN session_epoch UUID;
ALTER TABLE biz.project_import_agent_edit ADD COLUMN server_generation UUID;
ALTER TABLE biz.project_import_agent_edit ADD COLUMN permission_phase TEXT NOT NULL DEFAULT 'EXECUTING'
    CHECK (permission_phase IN ('EXECUTING','WAITING_PERMISSION','WAITING_REVIEW','CLOSED'));
ALTER TABLE biz.project_import_agent_edit DROP CONSTRAINT project_import_agent_edit_state_check;
ALTER TABLE biz.project_import_agent_edit ADD CONSTRAINT project_import_agent_edit_state_check CHECK (state IN (
    'PREPARING','EDITING','SEALING','WAITING_REVIEW','COMMITTING','SUCCEEDED','FAILED',
    'CANCELLING','CANCELLED','STALE','STOP_FAILED','RECOVERY_REQUIRED'));
ALTER TABLE biz.project_import_agent_edit ADD CONSTRAINT project_import_agent_edit_waiting_stopped
    CHECK (state<>'WAITING_REVIEW' OR (stop_confirmed AND publish_phase='NONE' AND NOT cancel_requested));

CREATE TABLE biz.project_import_agent_permission (
    permission_id UUID PRIMARY KEY,
    edit_id UUID NOT NULL REFERENCES biz.project_import_agent_edit(edit_id),
    action_hash TEXT NOT NULL CHECK (action_hash ~ '^[0-9a-f]{64}$'),
    action_json JSONB NOT NULL CHECK (jsonb_typeof(action_json)='object'),
    tool TEXT NOT NULL CHECK (tool IN ('write','edit','delete','upload_files','execute')),
    risk TEXT NOT NULL CHECK (risk IN ('ORDINARY','HIGH')),
    scope_hash TEXT NOT NULL CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    review_json JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(review_json)='object'),
    display_json JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(display_json)='object'),
    status TEXT NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
        'REQUESTED','APPROVED','REJECTED','CONSUMED','RESULT_CONFIRMED','RESULT_UNCONFIRMED','EXPIRED','CANCELLED','FAILED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    decision_source TEXT CHECK (decision_source IN ('USER','SESSION','AI','AUTO')),
    decision_request_id UUID UNIQUE,
    approve_similar BOOLEAN NOT NULL DEFAULT false,
    consumption_proof UUID UNIQUE,
    consume_request_id UUID UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status IN ('CONSUMED','RESULT_CONFIRMED','RESULT_UNCONFIRMED'))=(consumption_proof IS NOT NULL)),
    CHECK ((consumption_proof IS NOT NULL)=(consume_request_id IS NOT NULL)),
    CHECK (status NOT IN ('APPROVED','CONSUMED','RESULT_CONFIRMED','RESULT_UNCONFIRMED') OR decision_source IS NOT NULL),
    CHECK (NOT approve_similar OR decision_source='USER')
);
CREATE INDEX project_import_agent_permission_edit_idx ON biz.project_import_agent_permission(edit_id,created_at);
CREATE UNIQUE INDEX project_import_agent_permission_wait_idx ON biz.project_import_agent_permission(edit_id)
    WHERE status IN ('REQUESTED','APPROVED');

CREATE TABLE biz.project_import_agent_grant (
    grant_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    app_id BIGINT NOT NULL,
    thread_id TEXT NOT NULL,
    session_epoch UUID NOT NULL,
    server_generation UUID NOT NULL,
    tool TEXT NOT NULL CHECK (tool IN ('write','edit','delete','upload_files','execute')),
    risk TEXT NOT NULL CHECK (risk IN ('ORDINARY','HIGH')),
    scope_hash TEXT NOT NULL CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    permission_id UUID NOT NULL UNIQUE REFERENCES biz.project_import_agent_permission(permission_id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(owner_id,app_id,thread_id) REFERENCES biz.project_import_agent_settings(owner_id,app_id,thread_id)
);
CREATE INDEX project_import_agent_grant_match_idx ON biz.project_import_agent_grant(
    owner_id,app_id,thread_id,session_epoch,server_generation,tool,risk,scope_hash);

CREATE FUNCTION biz.project_import_agent_grant_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'agent grant immutable' USING ERRCODE='check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM biz.project_import_agent_permission p
        JOIN biz.project_import_agent_edit e ON e.edit_id=p.edit_id
        JOIN biz.project_import_agent_settings s ON s.owner_id=e.owner_id AND s.app_id=e.app_id AND s.thread_id='app:'||e.app_id::text
        WHERE p.permission_id=NEW.permission_id AND p.status='APPROVED' AND p.decision_source='USER' AND p.approve_similar
            AND p.tool=NEW.tool AND p.risk=NEW.risk AND p.scope_hash=NEW.scope_hash
            AND e.owner_id=NEW.owner_id AND e.app_id=NEW.app_id AND NEW.thread_id=s.thread_id
            AND e.session_epoch=NEW.session_epoch AND s.session_epoch=NEW.session_epoch
            AND e.server_generation=NEW.server_generation AND s.server_generation=NEW.server_generation
            AND NEW.expires_at=s.session_expires_at AND NEW.expires_at>now() AND NOT e.cancel_requested) THEN
        RAISE EXCEPTION 'agent grant not approved' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_grant_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.project_import_agent_grant FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_grant_guard();

CREATE FUNCTION biz.project_import_agent_permission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e biz.project_import_agent_edit; s biz.project_import_agent_settings;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'agent permission audit immutable' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO e FROM biz.project_import_agent_edit WHERE edit_id=NEW.edit_id FOR UPDATE;
    SELECT * INTO s FROM biz.project_import_agent_settings WHERE owner_id=e.owner_id AND app_id=e.app_id AND thread_id='app:'||e.app_id::text;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'REQUESTED' OR NEW.revision<>0 OR NEW.decision_source IS NOT NULL
            OR NEW.decision_request_id IS NOT NULL OR NEW.approve_similar OR NEW.consumption_proof IS NOT NULL OR NEW.consume_request_id IS NOT NULL
            OR NEW.expires_at<=now() OR NEW.expires_at>least(e.expires_at,now()+interval '5 minutes') THEN
            RAISE EXCEPTION 'agent permission initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['status','revision','decision_source','decision_request_id','approve_similar','consumption_proof','consume_request_id'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','revision','decision_source','decision_request_id','approve_similar','consumption_proof','consume_request_id'])
            OR NEW.revision<>OLD.revision+1 OR NOT (
                (OLD.status='REQUESTED' AND NEW.status IN ('APPROVED','REJECTED','EXPIRED','CANCELLED','FAILED')) OR
                (OLD.status='APPROVED' AND NEW.status IN ('CONSUMED','EXPIRED','CANCELLED')) OR
                (OLD.status='CONSUMED' AND NEW.status IN ('RESULT_CONFIRMED','RESULT_UNCONFIRMED'))) THEN
            RAISE EXCEPTION 'agent permission identity or transition invalid' USING ERRCODE='check_violation';
        END IF;
        IF OLD.status<>'REQUESTED' AND (NEW.decision_source,NEW.decision_request_id,NEW.approve_similar)
            IS DISTINCT FROM (OLD.decision_source,OLD.decision_request_id,OLD.approve_similar) THEN
            RAISE EXCEPTION 'agent permission decision changed' USING ERRCODE='check_violation';
        END IF;
        IF OLD.consumption_proof IS NOT NULL AND (NEW.consumption_proof,NEW.consume_request_id)
            IS DISTINCT FROM (OLD.consumption_proof,OLD.consume_request_id) THEN
            RAISE EXCEPTION 'agent permission consumption changed' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF TG_OP='INSERT' OR NEW.status IN ('APPROVED','CONSUMED') THEN
        IF e.edit_id IS NULL OR e.state<>'EDITING' OR e.cancel_requested OR e.closed_at IS NOT NULL
            OR e.expires_at<=now() OR NEW.expires_at<=now() OR s.session_expires_at<=now()
            OR e.session_epoch IS DISTINCT FROM s.session_epoch OR e.server_generation IS DISTINCT FROM s.server_generation
            OR e.policy_revision IS DISTINCT FROM s.policy_revision OR e.permission_mode IS DISTINCT FROM s.mode THEN
            RAISE EXCEPTION 'agent permission qualification revoked' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.status='APPROVED' THEN
        IF NEW.decision_source='AUTO' AND e.permission_mode<>'AUTO'
            OR NEW.decision_source='AI' AND (e.permission_mode<>'GUARDED_AUTO' OR NEW.risk<>'ORDINARY')
            OR NEW.approve_similar AND NEW.decision_request_id IS NULL THEN
            RAISE EXCEPTION 'agent permission decision source invalid' USING ERRCODE='check_violation';
        END IF;
        IF e.permission_mode='GUARDED_AUTO' AND NOT coalesce(
            NEW.review_json->>'objectHash'=NEW.action_hash AND NEW.review_json->>'risk' IN ('ORDINARY','HIGH'),false) THEN
            RAISE EXCEPTION 'agent permission independent review missing' USING ERRCODE='check_violation';
        END IF;
        IF NEW.decision_source='SESSION' AND NOT EXISTS (SELECT 1 FROM biz.project_import_agent_grant g
            WHERE g.owner_id=e.owner_id AND g.app_id=e.app_id AND g.thread_id=s.thread_id
                AND g.session_epoch=e.session_epoch AND g.server_generation=e.server_generation AND g.expires_at>now()
                AND g.tool=NEW.tool AND g.risk=NEW.risk AND g.scope_hash=NEW.scope_hash) THEN
            RAISE EXCEPTION 'agent permission session grant missing' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_permission_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.project_import_agent_permission FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_permission_guard();

CREATE TABLE biz.project_import_agent_review (
    edit_id UUID PRIMARY KEY REFERENCES biz.project_import_agent_edit(edit_id),
    review_hash TEXT NOT NULL CHECK (review_hash ~ '^[0-9a-f]{64}$'),
    candidate_hash TEXT NOT NULL CHECK (candidate_hash ~ '^[0-9a-f]{64}$'),
    manifest_hash TEXT NOT NULL CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    authorized_hash TEXT NOT NULL CHECK (authorized_hash ~ '^[0-9a-f]{64}$'),
    source_hash TEXT NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    review_json JSONB NOT NULL CHECK (jsonb_typeof(review_json)='object'),
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
CREATE FUNCTION biz.project_import_agent_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e biz.project_import_agent_edit;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'agent review audit immutable' USING ERRCODE='check_violation';
    END IF;
    SELECT * INTO e FROM biz.project_import_agent_edit WHERE edit_id=NEW.edit_id FOR UPDATE;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'WAITING' OR NEW.revision<>0 OR NEW.decision_source IS NOT NULL OR NEW.decision_request_id IS NOT NULL
            OR e.state<>'SEALING' OR NOT e.stop_confirmed OR e.cancel_requested OR e.expires_at<=now()
            OR NEW.source_hash<>e.source_hash OR NEW.expires_at<>e.expires_at OR NOT EXISTS (
                SELECT 1 FROM biz.project_import_agent_edit_candidate c WHERE c.edit_id=e.edit_id AND c.phase='VERIFIED'
                    AND c.receipt_json->>'candidateHash'=NEW.candidate_hash AND c.receipt_json->>'candidateManifestHash'=NEW.manifest_hash
                    AND c.authorized_state_hash=NEW.authorized_hash) THEN
            RAISE EXCEPTION 'agent review candidate not independently verified' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['status','revision','decision_source','decision_request_id']) IS DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['status','revision','decision_source','decision_request_id'])
            OR NEW.revision<>OLD.revision+1 OR OLD.status<>'WAITING' OR NEW.status='WAITING' THEN
            RAISE EXCEPTION 'agent review identity or transition invalid' USING ERRCODE='check_violation';
        END IF;
        IF NEW.status='APPROVED' AND (e.state NOT IN ('SEALING','WAITING_REVIEW') OR e.cancel_requested OR e.expires_at<=now()
            OR NOT EXISTS (SELECT 1 FROM biz.project_import_agent_settings s
                WHERE s.owner_id=e.owner_id AND s.app_id=e.app_id AND s.thread_id='app:'||e.app_id::text
                    AND s.policy_revision=e.policy_revision AND s.mode=e.permission_mode
                    AND s.session_epoch=e.session_epoch AND s.server_generation=e.server_generation AND s.session_expires_at>now())) THEN
            RAISE EXCEPTION 'agent review qualification revoked' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF NEW.status='APPROVED' THEN
        IF e.permission_mode='MANUAL' AND NEW.decision_source<>'USER'
            OR NEW.decision_source='AI' AND (e.permission_mode<>'GUARDED_AUTO' OR NEW.risk<>'ORDINARY')
            OR NEW.decision_source='AUTO' AND e.permission_mode<>'AUTO' THEN
            RAISE EXCEPTION 'agent review decision source invalid' USING ERRCODE='check_violation';
        END IF;
        IF e.permission_mode IN ('GUARDED_AUTO','AUTO') AND NOT coalesce(
            NEW.ai_review_json->>'objectHash'=NEW.review_hash AND NEW.ai_review_json->>'risk' IN ('ORDINARY','HIGH'),false) THEN
            RAISE EXCEPTION 'agent candidate independent review missing' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_review_guard BEFORE INSERT OR UPDATE OR DELETE
    ON biz.project_import_agent_review FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_review_guard();

-- 延续原冻结身份、发布恢复和提交后代次规则，仅增加合法等待转换。
CREATE OR REPLACE FUNCTION biz.project_import_agent_edit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'PREPARING' OR NEW.revision<>0 OR NEW.cancel_requested
            OR NEW.publish_phase<>'NONE' OR NEW.candidate_hash IS NOT NULL
            OR NEW.stop_confirmed OR NEW.cleanup<>'PENDING'
            OR NEW.resources_json<>'[]'::jsonb OR NEW.authorized_json<>'[]'::jsonb OR NEW.recovery_json<>'{}'::jsonb THEN
            RAISE EXCEPTION 'edit initial state invalid' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF OLD.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'edit finished' USING ERRCODE='check_violation';
        END IF;
        IF (NEW.edit_id,NEW.owner_id,NEW.app_id,NEW.import_task_id,NEW.run_id,NEW.source_hash,
            NEW.config_revision,NEW.context_epoch,NEW.executor_id,NEW.executor_pid,NEW.executor_start,
            NEW.expires_at,NEW.created_at,NEW.permission_mode,NEW.policy_revision,NEW.model_profile_id,
            NEW.thinking_effort,NEW.session_epoch,NEW.server_generation) IS DISTINCT FROM
           (OLD.edit_id,OLD.owner_id,OLD.app_id,OLD.import_task_id,OLD.run_id,OLD.source_hash,
            OLD.config_revision,OLD.context_epoch,OLD.executor_id,OLD.executor_pid,OLD.executor_start,
            OLD.expires_at,OLD.created_at,OLD.permission_mode,OLD.policy_revision,OLD.model_profile_id,
            OLD.thinking_effort,OLD.session_epoch,OLD.server_generation) OR NEW.revision<>OLD.revision+1
            OR (OLD.cancel_requested AND NOT NEW.cancel_requested)
            OR (OLD.stop_confirmed AND NOT NEW.stop_confirmed)
            OR (OLD.candidate_hash IS NOT NULL AND
                (NEW.candidate_hash IS DISTINCT FROM OLD.candidate_hash OR NEW.authorized_json IS DISTINCT FROM OLD.authorized_json)) THEN
            RAISE EXCEPTION 'edit frozen identity changed' USING ERRCODE='check_violation';
        END IF;
        IF NEW.state<>OLD.state AND NOT (
            (OLD.state='PREPARING' AND NEW.state IN ('EDITING','CANCELLING','FAILED','STALE','RECOVERY_REQUIRED')) OR
            (OLD.state='EDITING' AND NEW.state IN ('SEALING','CANCELLING','FAILED','STALE','RECOVERY_REQUIRED')) OR
            (OLD.state='SEALING' AND NEW.state IN ('WAITING_REVIEW','COMMITTING','CANCELLING','FAILED','STALE','RECOVERY_REQUIRED')) OR
            (OLD.state='WAITING_REVIEW' AND NEW.state IN ('COMMITTING','CANCELLING','RECOVERY_REQUIRED')) OR
            (OLD.state='COMMITTING' AND NEW.state IN ('SUCCEEDED','RECOVERY_REQUIRED')) OR
            (OLD.state='CANCELLING' AND NEW.state IN ('CANCELLED','STOP_FAILED','RECOVERY_REQUIRED')) OR
            (OLD.state='STOP_FAILED' AND NEW.state='RECOVERY_REQUIRED') OR
            (OLD.state='RECOVERY_REQUIRED' AND NEW.state IN ('FAILED','CANCELLED','STALE','SUCCEEDED'))) THEN
            RAISE EXCEPTION 'edit transition invalid' USING ERRCODE='check_violation';
        END IF;
    END IF;
    IF (TG_OP='INSERT' OR NEW.state IN ('EDITING','COMMITTING','SUCCEEDED')) AND NOT EXISTS (
        SELECT 1 FROM biz.project_import_publication p
        JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
        JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
        JOIN biz.project_import_startup_configuration c ON c.app_id=p.app_id AND c.owner_id=p.owner_id AND c.import_task_id=p.task_id
        WHERE p.app_id=NEW.app_id AND p.owner_id=NEW.owner_id AND p.task_id=NEW.import_task_id AND p.state='READY'
            AND c.config_revision=NEW.config_revision AND c.context_epoch=NEW.context_epoch+CASE WHEN TG_OP='UPDATE' AND OLD.publish_phase='COMMITTED' THEN 1 ELSE 0 END
            AND NOT NEW.cancel_requested AND (NEW.expires_at>now() OR NEW.publish_phase='COMMITTED')) THEN
        RAISE EXCEPTION 'edit qualification revoked' USING ERRCODE='check_violation';
    END IF;
    IF TG_OP='INSERT' AND NOT EXISTS (SELECT 1 FROM biz.project_import_agent_settings s
        WHERE s.owner_id=NEW.owner_id AND s.app_id=NEW.app_id AND s.thread_id='app:'||NEW.app_id::text
            AND s.mode=NEW.permission_mode AND s.profile_id=NEW.model_profile_id
            AND s.effort IS NOT DISTINCT FROM NEW.thinking_effort AND s.policy_revision=NEW.policy_revision
            AND s.session_epoch=NEW.session_epoch AND s.server_generation=NEW.server_generation AND s.session_expires_at>now()) THEN
        RAISE EXCEPTION 'edit frozen permission settings missing' USING ERRCODE='check_violation';
    END IF;
    IF NEW.state='WAITING_REVIEW' AND NOT EXISTS (SELECT 1 FROM biz.project_import_agent_review r
        WHERE r.edit_id=NEW.edit_id AND r.status='WAITING' AND r.expires_at>now()) THEN
        RAISE EXCEPTION 'edit review handoff missing' USING ERRCODE='check_violation';
    END IF;
    -- 已进入发布的历史任务允许原样恢复；所有新发布都必须携带匹配的整批决定。
    IF NEW.state='COMMITTING' AND TG_OP='UPDATE' AND OLD.state<>'COMMITTING' AND OLD.publish_phase='NONE'
        AND NOT EXISTS (SELECT 1 FROM biz.project_import_agent_review r
            WHERE r.edit_id=NEW.edit_id AND r.status='APPROVED' AND r.candidate_hash=NEW.candidate_hash
                AND r.source_hash=NEW.source_hash AND r.expires_at>now()
                AND r.review_json->'authorized'=NEW.authorized_json) THEN
        RAISE EXCEPTION 'edit publish decision missing' USING ERRCODE='check_violation';
    END IF;
    IF NEW.state='WAITING_REVIEW' AND (NEW.resources_json->0->>'state' IS DISTINCT FROM 'READY'
        OR NOT EXISTS (SELECT 1 FROM biz.project_import_agent_edit_candidate c WHERE c.edit_id=NEW.edit_id AND c.phase='VERIFIED')
        OR EXISTS (SELECT 1 FROM biz.project_import_agent_edit_worker w WHERE w.edit_id=NEW.edit_id AND w.phase<>'STOPPED')
        OR EXISTS (SELECT 1 FROM biz.project_import_agent_edit_container c WHERE c.edit_id=NEW.edit_id AND c.phase<>'REMOVED')) THEN
        RAISE EXCEPTION 'edit review resources unsafe' USING ERRCODE='check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION biz.project_import_agent_decision_floor_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='project_import_agent_permission' THEN
        IF NEW.review_json->>'risk'='HIGH' AND NEW.risk<>'HIGH' THEN
            RAISE EXCEPTION 'agent risk floor lowered' USING ERRCODE='check_violation';
        END IF;
        IF NEW.status IN ('APPROVED','REJECTED') AND NEW.decision_source='USER' AND NEW.decision_request_id IS NULL THEN
            RAISE EXCEPTION 'agent user decision request missing' USING ERRCODE='check_violation';
        END IF;
    ELSE
        IF NEW.ai_review_json->>'risk'='HIGH' AND NEW.risk<>'HIGH' THEN
            RAISE EXCEPTION 'agent candidate risk floor lowered' USING ERRCODE='check_violation';
        END IF;
        IF NEW.status IN ('APPROVED','REJECTED') AND NEW.decision_source='USER' AND NEW.decision_request_id IS NULL THEN
            RAISE EXCEPTION 'agent candidate decision request missing' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_decision_floor_guard BEFORE INSERT OR UPDATE
    ON biz.project_import_agent_permission FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_decision_floor_guard();
CREATE TRIGGER project_import_agent_decision_floor_guard BEFORE INSERT OR UPDATE
    ON biz.project_import_agent_review FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_decision_floor_guard();

CREATE FUNCTION biz.project_import_agent_publish_decision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state='COMMITTING' AND OLD.state<>'COMMITTING' AND OLD.publish_phase='NONE' THEN
        IF NOT EXISTS (SELECT 1 FROM biz.project_import_agent_review r
            JOIN biz.project_import_agent_settings s ON s.owner_id=NEW.owner_id AND s.app_id=NEW.app_id AND s.thread_id='app:'||NEW.app_id::text
            JOIN biz.project_import_agent_edit_candidate c ON c.edit_id=NEW.edit_id AND c.phase='VERIFIED'
            WHERE r.edit_id=NEW.edit_id AND r.status='APPROVED' AND r.candidate_hash=NEW.candidate_hash
                AND r.manifest_hash=c.receipt_json->>'candidateManifestHash' AND r.authorized_hash=c.authorized_state_hash
                AND r.source_hash=NEW.source_hash AND r.expires_at>now() AND r.review_json->'authorized'=NEW.authorized_json
                AND s.policy_revision=NEW.policy_revision AND s.mode=NEW.permission_mode AND s.profile_id=NEW.model_profile_id
                AND s.effort IS NOT DISTINCT FROM NEW.thinking_effort AND s.session_epoch=NEW.session_epoch
                AND s.server_generation=NEW.server_generation AND s.session_expires_at>now()) THEN
            RAISE EXCEPTION 'agent publication decision revoked' USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_import_agent_publish_decision_guard BEFORE UPDATE
    ON biz.project_import_agent_edit FOR EACH ROW EXECUTE FUNCTION biz.project_import_agent_publish_decision_guard();

ALTER TABLE biz.agent_run DROP CONSTRAINT ck_agent_run_status;
ALTER TABLE biz.agent_run ADD CONSTRAINT ck_agent_run_status CHECK (
    status IN ('PENDING','RUNNING','WAITING_REVIEW','SUCCEEDED','FAILED','CANCELLED','UNREACHABLE','UNKNOWN'));
