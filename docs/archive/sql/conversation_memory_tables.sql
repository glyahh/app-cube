-- 会话记忆（memory_state / ref / snapshot）建表脚本
-- 表结构以此 SQL 为唯一来源；新环境请先执行本脚本（或 DBA 等价迁移）。
--
-- 【执行前必读】
-- 1) 若报 [1046] No database selected：必须在客户端选中库，或先执行下面 USE（库名与 spring.datasource.url 一致；本地以 application.yml 为准）。
-- 2) 不要对 CREATE TABLE 使用 EXPLAIN FORMAT=TREE，会报 [1064] 语法错误；建表语句直接执行即可。

USE gly_ai_generate_code;

-- 1. memory_state：每个 app 仅 1 行，记录快照/摘要/changedFiles 等会话状态
CREATE TABLE IF NOT EXISTS conversation_memory_state (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  appId BIGINT NOT NULL,
  latestRoundId BIGINT NULL,
  latestSnapshotId BIGINT NULL,
  softSummary TEXT NULL,
  hardSummary TEXT NULL,
  changedFilesJson LONGTEXT NULL,
  fileNotesJson LONGTEXT NULL COMMENT 'path->{note,roundId,updatedAt}',
  updatedAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_id (appId),
  KEY idx_updated_at (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. memory_ref：归档大文本段，供后续按需恢复与排障
CREATE TABLE IF NOT EXISTS conversation_memory_ref (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  appId BIGINT NOT NULL,
  roundId BIGINT NOT NULL,
  refId VARCHAR(128) NOT NULL,
  filePath VARCHAR(512) NULL,
  content LONGTEXT NOT NULL,
  contentBytes BIGINT NOT NULL DEFAULT 0,
  createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_app_created (appId, createdAt),
  KEY idx_app_round (appId, roundId),
  UNIQUE KEY uk_ref_id (refId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. snapshot_history：保留每轮 manifest 真相
CREATE TABLE IF NOT EXISTS snapshot_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  appId BIGINT NOT NULL,
  roundId BIGINT NOT NULL,
  manifestJson LONGTEXT NOT NULL,
  filesCount INT NOT NULL DEFAULT 0,
  createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_app_created (appId, createdAt),
  KEY idx_app_round (appId, roundId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
