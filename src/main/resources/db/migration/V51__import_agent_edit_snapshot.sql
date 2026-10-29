-- 导入编辑合入前的文件级快照标识；内容保存在工作区 .glyahh/snapshots/{id}/。
ALTER TABLE biz.project_import_agent_edit ADD COLUMN snapshot_id UUID;
