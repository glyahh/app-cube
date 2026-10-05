-- 先持久化私有上传任务，不创建可用 App；文件发布与应用资格在后续提交阶段接入。
CREATE TABLE biz.project_import_task (
    task_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    definition_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    next_file INTEGER NOT NULL DEFAULT 0 CHECK (next_file >= 0),
    receipts_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_bytes BIGINT NOT NULL CHECK (source_bytes >= 0),
    uploaded_bytes BIGINT NOT NULL DEFAULT 0 CHECK (uploaded_bytes >= 0 AND uploaded_bytes <= source_bytes),
    content_hash VARCHAR(64),
    error_code VARCHAR(64),
    cleanup_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
    executor_id VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    update_time TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_import_task_status CHECK (status IN (
        'READING_CONTENT','UPLOADING','WAITING_CONFIRMATION','CANCELLING','CANCELLED',
        'FAILED','STOP_FAILED','RECOVERY_REQUIRED'
    ))
);
CREATE INDEX idx_project_import_task_owner ON biz.project_import_task(owner_id, create_time);
CREATE INDEX idx_project_import_task_state ON biz.project_import_task(status, update_time);
