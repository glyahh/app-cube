-- 扫描过程与完成证据分开。该表不授予安装、模型或工程执行权限。
CREATE TABLE biz.project_import_startup_scan (
    scan_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    import_task_id UUID NOT NULL REFERENCES biz.project_import_task(task_id),
    config_revision BIGINT NOT NULL CHECK (config_revision >= 0),
    context_epoch BIGINT NOT NULL CHECK (context_epoch >= 0),
    scope TEXT NOT NULL,
    request_hash TEXT NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('QUEUED','SCANNING','CANCELLING','CANCELLED','SUCCEEDED','FAILED','STALE','STOP_FAILED','RECOVERY_REQUIRED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    executor_id UUID NOT NULL,
    executor_pid BIGINT NOT NULL CHECK (executor_pid > 0),
    executor_start TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resources_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(resources_json) = 'array'),
    content_hash TEXT CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    cleanup TEXT NOT NULL DEFAULT 'PENDING' CHECK (cleanup IN ('PENDING','DONE','UNVERIFIED','FAILED')),
    error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE(owner_id,request_id),
    CHECK (expires_at > executor_start),
    CHECK (status NOT IN ('SUCCEEDED','CANCELLED','FAILED','STALE') OR cleanup='DONE'),
    CHECK (status <> 'SUCCEEDED' OR content_hash IS NOT NULL)
);
CREATE INDEX project_import_startup_scan_owner_idx ON biz.project_import_startup_scan(owner_id,status);
