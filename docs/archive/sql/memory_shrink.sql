-- memory_shrink：AI 上下文压缩态（与 chat_history 全文分离）
use gly_ai_generate_code;

create table if not exists memory_shrink
(
    id                   bigint                             not null comment 'id' primary key,
    appId                bigint                             not null comment '应用id',
    userId               bigint                             not null comment '用户id',
    message              longtext                           not null comment '压缩后消息',
    messageType          varchar(32)                        not null comment 'user/ai',
    shrinkType           varchar(64)                        not null comment 'conversation_summary/message_truncate',
    sourceChatHistoryIds json                               null comment '关联 chat_history.id 列表',
    chatHistoryId        bigint                             null comment 'truncate 源行 id',
    anchorCreateTime     datetime                           not null comment '时间轴锚点',
    createTime           datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime           datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete             tinyint  default 0                 not null comment '是否删除',
    INDEX idx_app_anchor (appId, anchorCreateTime),
    UNIQUE KEY uk_app_chat_shrink (appId, chatHistoryId, shrinkType)
) comment '会话记忆压缩表' collate = utf8mb4_unicode_ci;
