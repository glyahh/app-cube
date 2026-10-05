-- 已完成的只读识别证据；不是运行任务、安装授权或执行快照。
CREATE TABLE biz.project_import_startup_inspection (
    scan_id UUID PRIMARY KEY,
    app_id BIGINT NOT NULL REFERENCES biz.project_import_publication(app_id),
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    import_task_id UUID NOT NULL REFERENCES biz.project_import_task(task_id),
    config_revision BIGINT NOT NULL CHECK (config_revision >= 0),
    context_epoch BIGINT NOT NULL CHECK (context_epoch >= 0),
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    scope TEXT NOT NULL,
    rules_version TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'SUCCEEDED' CHECK (state IN ('SUCCEEDED','STALE')),
    inspection_json JSONB NOT NULL CHECK (jsonb_typeof(inspection_json) = 'object'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX project_import_startup_inspection_app_idx
    ON biz.project_import_startup_inspection(app_id, owner_id, created_at DESC);
