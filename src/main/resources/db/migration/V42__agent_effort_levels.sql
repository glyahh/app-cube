-- 思考力度新增 xhigh / max 两档；V40 会话设置与编辑冻结、V41 偏好三处 CHECK 需要同步放宽。
-- 只放宽枚举范围，不放宽任何权限或状态约束。
ALTER TABLE biz.project_import_agent_settings DROP CONSTRAINT project_import_agent_settings_effort_check;
ALTER TABLE biz.project_import_agent_settings ADD CONSTRAINT project_import_agent_settings_effort_check
    CHECK (effort IN ('low','medium','high','xhigh','max'));

ALTER TABLE biz.project_import_agent_edit DROP CONSTRAINT project_import_agent_edit_thinking_effort_check;
ALTER TABLE biz.project_import_agent_edit ADD CONSTRAINT project_import_agent_edit_thinking_effort_check
    CHECK (thinking_effort IN ('low','medium','high','xhigh','max'));

ALTER TABLE biz.agent_model_preference DROP CONSTRAINT agent_model_preference_effort_check;
ALTER TABLE biz.agent_model_preference ADD CONSTRAINT agent_model_preference_effort_check
    CHECK (effort IN ('low','medium','high','xhigh','max'));