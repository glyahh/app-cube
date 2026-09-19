ALTER TABLE biz."user" ADD COLUMN auto_name_project BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE biz.app ADD COLUMN auto_title_attempted BOOLEAN NOT NULL DEFAULT FALSE;
-- 存量对话不补命名，避免覆盖用户已经使用的项目标题。
UPDATE biz.app a SET auto_title_attempted = TRUE
WHERE EXISTS (SELECT 1 FROM biz.chat_history h WHERE h.app_id = a.id AND h.message_type = 'user');
