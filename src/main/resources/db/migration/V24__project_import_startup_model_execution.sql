-- 独占执行者与实际资源独立登记；不使批准自动获得工程执行权限。
ALTER TABLE biz.project_import_startup_approval
    ADD CONSTRAINT project_import_startup_approval_identity UNIQUE(approval_id,owner_id,app_id);
CREATE TABLE biz.project_import_startup_model_execution (
    approval_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    app_id BIGINT NOT NULL,
    approval_revision BIGINT NOT NULL CHECK (approval_revision>0),
    executor_id UUID NOT NULL,
    executor_pid BIGINT NOT NULL CHECK (executor_pid>0),
    executor_start TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    resources_json JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(resources_json)='array' AND jsonb_array_length(resources_json)<=128),
    cleanup TEXT NOT NULL DEFAULT 'PENDING' CHECK (cleanup IN ('PENDING','DONE','UNVERIFIED','FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    FOREIGN KEY(approval_id,owner_id,app_id)
        REFERENCES biz.project_import_startup_approval(approval_id,owner_id,app_id)
);
