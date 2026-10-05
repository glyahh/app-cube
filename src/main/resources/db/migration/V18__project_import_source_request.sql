-- 请求标识只负责找回原来源；不作为任务资格或跨用户授权。
ALTER TABLE biz.project_import_source ADD COLUMN client_request_id UUID;
ALTER TABLE biz.project_import_source ADD COLUMN client_request_hash VARCHAR(64);
ALTER TABLE biz.project_import_source ADD CONSTRAINT ck_project_import_source_request
    CHECK ((client_request_id IS NULL AND client_request_hash IS NULL)
        OR (client_request_id IS NOT NULL AND client_request_hash IS NOT NULL AND client_request_hash ~ '^[0-9a-f]{64}$'));
CREATE UNIQUE INDEX uq_project_import_source_request ON biz.project_import_source(owner_id,client_request_id)
    WHERE client_request_id IS NOT NULL;
