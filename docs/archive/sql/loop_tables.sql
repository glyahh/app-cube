-- Loop 技能市场表

CREATE TABLE `loop` (
  `id` bigint NOT NULL COMMENT '雪花ID',
  `loop_name` varchar(128) NOT NULL COMMENT '名称',
  `description` varchar(512) DEFAULT '' COMMENT '市场简介',
  `cover` varchar(256) DEFAULT '' COMMENT '封面',
  `user_id` bigint NOT NULL COMMENT '创建者',
  `priority` int NOT NULL DEFAULT 0 COMMENT '精选阈值 >=99',
  `workflow_json` text COMMENT '标准模板步骤JSON',
  `compiled_prompt` text COMMENT '编译后的注入文本',
  `source_type` varchar(32) NOT NULL DEFAULT 'created' COMMENT 'created/imported',
  `visibility` varchar(32) NOT NULL DEFAULT 'private' COMMENT 'private/public',
  `is_delete` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_priority` (`priority`, `is_delete`, `visibility`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Loop技能表';

CREATE TABLE `app_loop` (
  `app_id` bigint NOT NULL,
  `loop_id` bigint NOT NULL,
  `added_from` varchar(32) NOT NULL DEFAULT 'creation' COMMENT 'creation/chat/market',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`app_id`, `loop_id`),
  KEY `idx_loop_id` (`loop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用Loop库';

CREATE TABLE `user_loop_apply` (
  `id` bigint NOT NULL COMMENT '雪花ID',
  `loop_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `operate` tinyint NOT NULL DEFAULT 1 COMMENT '1=申请精选',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2拒绝',
  `apply_reason` varchar(512) DEFAULT '',
  `review_user_id` bigint DEFAULT NULL,
  `review_remark` varchar(512) DEFAULT '',
  `review_time` datetime DEFAULT NULL,
  `is_delete` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_loop_id` (`loop_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Loop精选申请表';
