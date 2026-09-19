-- 看板功能开关（监控 / 日志 / 知识图谱）。
-- 存 JSON 数组文本，如 ["metrics","logs"]；NULL 或空数组表示全不选（新建默认）。
-- 用 TEXT 而不是 JSONB：这一列走普通 CRUD，JSONB 需要 CAST 自定义 SQL，没有必要。
ALTER TABLE app ADD COLUMN IF NOT EXISTS dashboard_features TEXT NULL;
