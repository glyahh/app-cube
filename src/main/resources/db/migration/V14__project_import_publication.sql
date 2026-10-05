ALTER TABLE biz.project_import_task DROP CONSTRAINT ck_project_import_task_status;
ALTER TABLE biz.project_import_task ADD CONSTRAINT ck_project_import_task_status CHECK (status IN (
    'READING_CONTENT','UPLOADING','WAITING_CONFIRMATION','COMMITTING','SUCCEEDED',
    'CANCELLING','CANCELLED','FAILED','STOP_FAILED','RECOVERY_REQUIRED'
));
CREATE TABLE biz.project_import_publication (
    task_id UUID PRIMARY KEY REFERENCES biz.project_import_task(task_id),
    app_id BIGINT NOT NULL UNIQUE,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    state VARCHAR(32) NOT NULL CHECK (state IN ('PUBLISHING','FILES_READY','READY','ABORTED')),
    content_hash VARCHAR(64) NOT NULL,
    executor_pid BIGINT NOT NULL,
    executor_start TIMESTAMP WITH TIME ZONE NOT NULL,
    update_time TIMESTAMP NOT NULL DEFAULT now()
);
