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

**连接池生命周期**：进程级单例。所有 run 共用同一个池，进程退出时显式关闭。
每次 run 重建池会导致连接数无限增长 —— 3 个 Python 进程 × N 轮 × max_size
很快就会撞 PostgreSQL 的 max_connections。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

logger = logging.getLogger(__name__)

# ─────────────────── 进程级单例缓存 ───────────────────
# 键是 DSN 字符串，值是已初始化的 checkpointer / store。
# 一个进程只有一个 DSN，但用 dict 而不是全局变量：避免模块级副作用，测试时也好替换。
_checkpointer_cache: dict[str, Any] = {}
_store_cache: dict[str, Any] = {}

# 需要在退出时关闭的连接池。append-only，close_pools() 遍历关闭。
_pools_to_close: list[Any] = []


class PersistenceUnavailable(RuntimeError):
    """要求持久化的导入执行不能转入内存；消息不携带DSN或原始数据库错误。"""


async def get_checkpointer(dsn: str, *, require_persistent: bool = False):
    """获取 checkpointer（进程级缓存）。**必须在事件循环中调用。**

    首次调用时完成：
    1. 创建 AsyncConnectionPool（open=False）
    2. await pool.open()
    3. await checkpointer.setup()  — 幂等建表
    4. 最小读写验证
    失败则降级为 InMemorySaver。
    """
    if dsn in _checkpointer_cache:
        if require_persistent:
            from langgraph.checkpoint.memory import InMemorySaver
            if isinstance(_checkpointer_cache[dsn], InMemorySaver):
                raise PersistenceUnavailable('POSTGRES_CHECKPOINT_REQUIRED')
        return _checkpointer_cache[dsn]

    if dsn:
        try:
            saver = await _init_pg_checkpointer(dsn)
            _checkpointer_cache[dsn] = saver
            return saver
        except Exception as exc:
            if require_persistent:
                raise PersistenceUnavailable('POSTGRES_CHECKPOINT_UNAVAILABLE') from None
            logger.warning(
                "checkpointer：内存降级\n原因：%s",
                exc,
            )

    else:
        if require_persistent:
            raise PersistenceUnavailable('POSTGRES_CHECKPOINT_REQUIRED')
        logger.info("未配置 AGENT_POSTGRES_DSN，使用内存 checkpointer")

    from langgraph.checkpoint.memory import InMemorySaver

    fallback = InMemorySaver()
    _checkpointer_cache[dsn] = fallback
    return fallback


async def _init_pg_checkpointer(dsn: str):
    """在事件循环中创建连接池、打开、建表、验证。"""
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
    from psycopg_pool import AsyncConnectionPool

    # 1. 创建时不打开 —— open=True 会调 asyncio.get_running_loop()，
    #    在同步上下文中必然报错，这正是之前永远降级的根因。
    pool = AsyncConnectionPool(
        conninfo=dsn,
        min_size=1,
        max_size=8,
        open=False,
        kwargs={"autocommit": True, "prepare_threshold": 0},
    )
    _pools_to_close.append(pool)

    try:
        # 2. 在当前事件循环中打开，再建表并验证实际读取。
        await pool.open()
        saver = AsyncPostgresSaver(pool)
        await saver.setup()
        await _verify_checkpointer(saver)
    except BaseException:
        await pool.close()
        _pools_to_close.remove(pool)
        raise

    logger.info("checkpointer：PostgreSQL")
    logger.info("checkpointer setup：成功")
    return saver


async def _verify_checkpointer(saver) -> None:
    """对 checkpointer 做一次最小读操作。

    只读不写 —— 写入会留下垃圾数据。读 get_tuple 对空表返回 None，
    不会报错，但能验证 SQL 可以跑通。
    """
    config = {"configurable": {"thread_id": "__healthcheck__"}}
    result = await saver.aget_tuple(config)
    # result 为 None 是正常的（没有这个 thread 的数据）
    logger.debug("checkpointer 验证通过（result=%s）", type(result).__name__ if result else "None")


async def get_store(dsn: str, *, require_persistent: bool = False):
    """获取 store（进程级缓存）。

    store 用同步 PostgresStore —— 它不需要在事件循环中初始化，
    但为了统一入口和保证缓存语义，也走 async 接口。
    """
    if dsn in _store_cache:
        if require_persistent:
            from langgraph.store.memory import InMemoryStore
            if isinstance(_store_cache[dsn], InMemoryStore):
                raise PersistenceUnavailable('POSTGRES_STORE_REQUIRED')
        return _store_cache[dsn]

    if dsn:
        try:
            store = _init_pg_store(dsn)
            _store_cache[dsn] = store
            return store
        except Exception as exc:
            if require_persistent:
                raise PersistenceUnavailable('POSTGRES_STORE_UNAVAILABLE') from None
            logger.warning("store：内存降级\n原因：%s", exc)

    if require_persistent:
        raise PersistenceUnavailable('POSTGRES_STORE_REQUIRED')

    from langgraph.store.memory import InMemoryStore

    fallback = InMemoryStore()
    _store_cache[dsn] = fallback
    return fallback


def _init_pg_store(dsn: str):
    """同步创建 store 连接池并建表。"""
    from langgraph.store.postgres import PostgresStore
    from psycopg_pool import ConnectionPool

    pool = ConnectionPool(
        conninfo=dsn, min_size=1, max_size=4, open=True,
        kwargs={"autocommit": True, "prepare_threshold": 0},
    )
    _pools_to_close.append(pool)

    try:
        store = PostgresStore(pool)
        store.setup()
    except BaseException:
        pool.close()
        _pools_to_close.remove(pool)
        raise
    logger.info("store：PostgreSQL")
    return store


async def close_pools() -> None:
    """关闭所有连接池。进程退出时调用。"""
    for pool in _pools_to_close:
        try:
            if hasattr(pool, "close"):
                result = pool.close()
                # AsyncConnectionPool.close() 返回 coroutine
                if asyncio.iscoroutine(result) or asyncio.isfuture(result):
                    await result
        except Exception as exc:
            logger.warning("关闭连接池失败：%s", exc)
    _pools_to_close.clear()
    _checkpointer_cache.clear()
    _store_cache.clear()
    logger.info("所有持久化连接池已关闭")
