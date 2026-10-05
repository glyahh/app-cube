-- 服务端来源标记保证来源记录缺失时不退化为普通文件夹提交。
ALTER TABLE biz.project_import_task ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'FOLDER'
    CHECK(source_kind IN ('FOLDER','GIT'));
