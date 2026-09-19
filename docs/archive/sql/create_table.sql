-- 创建库
create database if not exists gly_ai_generate_code;

-- 切换库
use gly_ai_generate_code;

-- 用户表
-- 以下是建表语句

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256) collate utf8mb4_bin   not null comment '账号',
    userPassword varchar(512)                       not null comment '密码',
    userName     varchar(256)                       null comment '用户昵称',
    userAvatar   varchar(1024)                      null comment '用户头像',
    userProfile  varchar(512)                       null comment '用户简介',
    userRole     varchar(256) default 'user'        not null comment '用户角色: user/admin',
    editTime     datetime   default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint    default 0               not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
    ) comment '用户' collate = utf8mb4_unicode_ci;

ALTER TABLE user MODIFY userAccount varchar(256) COLLATE utf8mb4_bin NOT NULL COMMENT '账号';


-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    is_beta      tinyint  default 0                 not null comment '是否 beta 应用：0-否，1-是（workflow beta）',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;



-- 用户申请信息表：记录用户申请精选应用或申请成为管理员的记录
create table if not exists user_app_apply
(
    id            bigint auto_increment comment 'id' primary key,
    userId        bigint                             not null comment '申请用户 id',
    appId         bigint                             null comment '申请关联的应用 id，申请管理员时可为空',
    appPropriety  int                                null comment '应用展示优先级，模拟申请将应用设置为精选时的目标优先级（越大越靠前）',
    operate       tinyint                            not null comment '操作类型：1-申请将自己的应用设置为精选应用；2-申请成为管理员',
    applyReason   varchar(512)                       null comment '申请理由',
    status        tinyint  default 0                 not null comment '处理状态：0-待处理；1-通过；2-拒绝',
    reviewUserId  bigint                             null comment '审核管理员用户 id',
    reviewRemark  varchar(512)                       null comment '审核备注',
    reviewTime    datetime                           null comment '审核时间',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    -- 约束与索引
    CONSTRAINT fk_user_app_apply_userId FOREIGN KEY (userId) REFERENCES user (id),
    CONSTRAINT fk_user_app_apply_appId FOREIGN KEY (appId) REFERENCES app (id),
    CONSTRAINT ck_user_app_apply_operate CHECK (operate in (1, 2)),
    CONSTRAINT ck_user_app_apply_appPropriety CHECK (appPropriety is null or appPropriety >= 0),
    INDEX idx_user_app_apply_userId (userId),
    INDEX idx_user_app_apply_appId (appId),
    INDEX idx_user_app_apply_status (status),
    INDEX idx_user_app_apply_operate (operate),
    INDEX idx_user_app_apply_user_app_operate (userId, appId, operate)
) comment '用户应用 / 权限申请记录表' collate = utf8mb4_unicode_ci;


-- 对话历史表
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     longtext                           not null comment '消息',
    messageType varchar(32)                        not null comment '消息类型：user/ai/error',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    auditAction varchar(16) default 'SKIP'         not null comment '审查动作：ALLOW/REJECT/SKIP',
    auditHitRule varchar(64) default 'NONE'        not null comment '命中审查规则编码',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;

alter table chat_history add column if not exists auditAction varchar(16) default 'SKIP' not null comment '审查动作：ALLOW/REJECT/SKIP' after userId;
alter table chat_history add column if not exists auditHitRule varchar(64) default 'NONE' not null comment '命中审查规则编码' after auditAction;
-- 历史库若仍为 text(约 64KB)，升级为 longtext，避免长 AI 回复写库失败
alter table chat_history modify column message longtext not null comment '消息';

