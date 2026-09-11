"""执行状态与长期记忆的持久化。

三类状态分开放，职责不同、寿命不同：

| 层 | 载体 | 生命周期 | 压缩时怎么处理 |
|---|---|---|---|
| 会话执行状态 | checkpointer | thread 级 | 是压缩的对象 |
| 会话状态快照 | 派生视图，可重建 | 会话级 | 作摘要骨架，本身不参与摘要 |
| 跨会话记忆 | store | app 级，跨 thread | 压缩后重新注入，不进入摘要 |

**混成单一「知识库」是明确要避免的** —— 本次会话的临时状态（未定决策、
试错假设、阶段性路径）会渗进后续所有会话。

PostgreSQL 不可用时降级到进程内内存实现。降级是刻意的：Phase 1 要能在没有
PG 的机器上跑通闭环，而内存 checkpoint 的代价只是「进程重启后会话历史丢失」，
不影响单轮执行。降级会打 WARN，不要让它静默发生。
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def make_checkpointer(dsn: str):
    """构造 checkpointer。优先 PostgreSQL，连不上则降级到内存。

    PG 路径的表由 LangGraph 自己建（`setup()` 幂等），不需要 Alembic 声明。
    """
    if dsn:
        try:
            from langgraph.checkpoint.postgres import PostgresSaver
            from psycopg_pool import ConnectionPool

            pool = ConnectionPool(
                conninfo=dsn,
                min_size=1,
                max_size=8,
                # 建表要在连接建立后做，交给 setup
                open=True,
                kwargs={"autocommit": True, "prepare_threshold": 0},
            )
            saver = PostgresSaver(pool)
            saver.setup()
            logger.info("checkpointer：PostgreSQL")
            return saver
        except Exception as exc:
            logger.warning(
                "PostgreSQL checkpointer 不可用（%s），降级为内存 checkpointer。"
                "进程重启后会话历史会丢，单轮执行不受影响。",
                exc,
            )
    else:
        logger.info("未配置 AGENT_POSTGRES_DSN，使用内存 checkpointer")

    from langgraph.checkpoint.memory import InMemorySaver

    return InMemorySaver()


def make_store(dsn: str):
    """跨会话长期记忆。走 LangGraph Store，作用域按 appId 隔离。"""
    if dsn:
        try:
            from langgraph.store.postgres import PostgresStore
            from psycopg_pool import ConnectionPool

            pool = ConnectionPool(conninfo=dsn, min_size=1, max_size=4, open=True,
                                  kwargs={"autocommit": True, "prepare_threshold": 0})
            store = PostgresStore(pool)
            store.setup()
            logger.info("store：PostgreSQL")
            return store
        except Exception as exc:
            logger.warning("PostgreSQL store 不可用（%s），降级为内存 store", exc)

    from langgraph.store.memory import InMemoryStore

    return InMemoryStore()


def memory_namespace(app_id: str) -> tuple[str, ...]:
    """长期记忆的命名空间。

    按 appId 隔离，和现有系统的「一 app 一对话」语义对齐。
    注意 namespace 不是授权机制 —— 多租户场景必须另加校验。
    """
    return ("glyahh", "app-memory", str(app_id))
