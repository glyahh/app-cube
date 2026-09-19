-- sql/user_personalization.sql
-- 增量 DDL：用户个性化配置表
-- 每用户一条记录，userId 唯一约束。支持逻辑删除。
CREATE TABLE IF NOT EXISTS user_personalization (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '主键（Snowflake）',
    userId      BIGINT       NOT NULL COMMENT '用户ID',
    app_style   TEXT                  COMMENT '应用风格 prompt：控制生成的前端视觉与结构偏好',
    answer_style TEXT                 COMMENT '回答风格 prompt：控制 AI 自然语言回复的语气与格式',
    createTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    UNIQUE KEY uk_userId (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户个性化配置';
