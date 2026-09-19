-- 会话记忆：按路径中文说明（fileNote）
-- 执行前请确认已 USE 目标库（与 spring.datasource.url 一致）

USE gly_ai_generate_code;

ALTER TABLE conversation_memory_state
  ADD COLUMN fileNotesJson LONGTEXT NULL COMMENT 'path->{note,roundId,updatedAt}' AFTER changedFilesJson;
