-- 运行期异常的两个全局弹窗开关。
--
-- 取值语义（Plan 明确）：启动异常默认「开」、运行异常默认「关」。
-- 因此这里 DEFAULT 分别给 TRUE / FALSE，且列 NOT NULL —— 与 V3 的
-- expand_execution_process 同一写法（用户表布尔偏好的既有先例）。
ALTER TABLE biz."user" ADD COLUMN IF NOT EXISTS alert_on_startup_failure BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE biz."user" ADD COLUMN IF NOT EXISTS alert_on_runtime_failure BOOLEAN NOT NULL DEFAULT FALSE;
