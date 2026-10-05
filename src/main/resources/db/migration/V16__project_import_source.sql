-- 来源准备先于完整清单；与后续主任务复用同一 UUID，不创建占位文件或 App。
CREATE TABLE biz.project_import_source (
    task_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    definition_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RESOLVING_SOURCE','WAITING_BRANCH','READING_CONTENT',
        'WAITING_CONFIRMATION','CANCELLING','CANCELLED','FAILED','STOP_FAILED','RECOVERY_REQUIRED','SUCCEEDED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    credential_id UUID,
    credential_version BIGINT,
    branch_name TEXT,
    commit_id VARCHAR(40),
    branches_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    resources_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    manifest_json JSONB,
    content_hash VARCHAR(64),
    main_task_created BOOLEAN NOT NULL DEFAULT false,
    reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes>=0),
    executor_id UUID NOT NULL,
    executor_pid BIGINT NOT NULL,
    executor_start TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(80),
    cleanup_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (credential_id,credential_version) REFERENCES biz.project_import_credential_version(credential_id,version),
    CHECK ((credential_id IS NULL AND credential_version IS NULL) OR (credential_id IS NOT NULL AND credential_version IS NOT NULL)),
    CHECK (commit_id IS NULL OR commit_id ~ '^[0-9a-f]{40}$'),
    CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_import_source_owner ON biz.project_import_source(owner_id,status);
CREATE INDEX idx_import_source_expiry ON biz.project_import_source(expires_at,status);
CREATE INDEX idx_import_source_credential ON biz.project_import_source(credential_id,credential_version,status);
