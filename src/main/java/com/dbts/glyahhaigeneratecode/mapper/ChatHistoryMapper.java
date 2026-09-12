package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.mybatisflex.core.BaseMapper;

/**
 * 对话历史 映射层。
 *
 * <p>原先这里挂着一组 MySQL 方言的运行时 DDL（{@code information_schema ... DATABASE()}、
 * {@code ADD COLUMN ... AFTER}、{@code MODIFY COLUMN ... LONGTEXT}），是旧链路
 * 「启动时自动补列」的残留。实测**零调用**，且迁到 PostgreSQL 后这些语法一条都跑不通，
 * 已删除。schema 现在由 Flyway 统一管理。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {
}
