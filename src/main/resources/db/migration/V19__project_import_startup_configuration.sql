-- 手动声明按应用保存；版本与上下文只授予结果资格，不授予执行权限。
CREATE TABLE biz.project_import_startup_configuration (
    app_id BIGINT PRIMARY KEY REFERENCES biz.project_import_publication(app_id),
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    import_task_id UUID NOT NULL REFERENCES biz.project_import_task(task_id),
    config_revision BIGINT NOT NULL DEFAULT 0 CHECK (config_revision >= 0),
    context_epoch BIGINT NOT NULL DEFAULT 0 CHECK (context_epoch >= 0),
    scope TEXT NOT NULL DEFAULT '.',
    services_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(services_json) = 'array'),
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
