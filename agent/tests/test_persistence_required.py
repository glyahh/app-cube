"""导入执行持久化门禁；数据库替身不代表真实SQL验收。"""
import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock

from core import persistence


@pytest.fixture(autouse=True)
def reset(monkeypatch):
    monkeypatch.setattr(persistence, '_checkpointer_cache', {})
    monkeypatch.setattr(persistence, '_store_cache', {})


@pytest.mark.parametrize('method', ['get_checkpointer', 'get_store'])
def test_empty_dsn_is_explicit_failure_without_cached_memory(method):
    with pytest.raises(persistence.PersistenceUnavailable):
        asyncio.run(getattr(persistence, method)('', require_persistent=True))
    assert persistence._checkpointer_cache == {} and persistence._store_cache == {}


@pytest.mark.parametrize('method', ['get_checkpointer', 'get_store'])
def test_previous_memory_fallback_cannot_be_reused_for_import(method):
    asyncio.run(getattr(persistence, method)(''))
    with pytest.raises(persistence.PersistenceUnavailable):
        asyncio.run(getattr(persistence, method)('', require_persistent=True))


@pytest.mark.parametrize('method', ['get_checkpointer', 'get_store'])
def test_database_failure_does_not_create_memory_or_expose_private_error(monkeypatch, method):
    def fail(*_):
        raise RuntimeError('private-dsn-marker')
    async def async_fail(*_):
        fail()
    monkeypatch.setattr(persistence, '_init_pg_checkpointer', async_fail)
    monkeypatch.setattr(persistence, '_init_pg_store', fail)
    with pytest.raises(persistence.PersistenceUnavailable) as raised:
        asyncio.run(getattr(persistence, method)('fixture-dsn', require_persistent=True))
    assert 'private-dsn-marker' not in str(raised.value)
    assert persistence._checkpointer_cache == {} and persistence._store_cache == {}


def test_successful_persistent_components_are_cached(monkeypatch):
    saver = object()
    store = object()
    async def initialize(_):
        return saver
    monkeypatch.setattr(persistence, '_init_pg_checkpointer', initialize)
    monkeypatch.setattr(persistence, '_init_pg_store', lambda _: store)
    assert asyncio.run(persistence.get_checkpointer('fixture', require_persistent=True)) is saver
    assert asyncio.run(persistence.get_checkpointer('fixture', require_persistent=True)) is saver
    assert asyncio.run(persistence.get_store('fixture', require_persistent=True)) is store


def test_failed_checkpoint_setup_closes_its_actual_pool_registration(monkeypatch):
    import psycopg_pool
    import langgraph.checkpoint.postgres.aio
    pool = MagicMock()
    pool.open = AsyncMock()
    pool.close = AsyncMock()
    saver = MagicMock()
    saver.setup = AsyncMock(side_effect=RuntimeError('private-setup-error'))
    monkeypatch.setattr(persistence, '_pools_to_close', [])
    monkeypatch.setattr(psycopg_pool, 'AsyncConnectionPool', lambda **_: pool)
    monkeypatch.setattr(langgraph.checkpoint.postgres.aio, 'AsyncPostgresSaver', lambda _: saver)
    with pytest.raises(RuntimeError):
        asyncio.run(persistence._init_pg_checkpointer('fixture'))
    pool.close.assert_awaited_once()
    assert persistence._pools_to_close == []


def test_failed_store_setup_closes_its_actual_pool_registration(monkeypatch):
    import psycopg_pool
    import langgraph.store.postgres
    pool = MagicMock()
    store = MagicMock()
    store.setup.side_effect = RuntimeError('private-setup-error')
    monkeypatch.setattr(persistence, '_pools_to_close', [])
    monkeypatch.setattr(psycopg_pool, 'ConnectionPool', lambda **_: pool)
    monkeypatch.setattr(langgraph.store.postgres, 'PostgresStore', lambda _: store)
    with pytest.raises(RuntimeError):
        persistence._init_pg_store('fixture')
    pool.close.assert_called_once()
    assert persistence._pools_to_close == []
