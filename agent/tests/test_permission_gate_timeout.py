"""数据库不可达时必须快速失败。

没有连接超时时 psycopg 会一直等，整轮 run 挂死，前端只看到「分析中」永不结束。
"""
from __future__ import annotations

import os
import time

import pytest


def test_unreachable_database_fails_fast(monkeypatch):
    # 指向一个必然拒绝连接的端口，避免依赖真实数据库状态。
    monkeypatch.setenv("AGENT_POSTGRES_DSN", "postgresql://user:pass@127.0.0.1:59999/nodb")

    from core.runtime import _attach_permissions
    from backends.local_shell import GuardedShellBackend
    from pathlib import Path
    import tempfile

    class Emitter:
        def emit(self, *args, **kwargs):
            pass

    backend = GuardedShellBackend(Path(tempfile.mkdtemp()))
    started = time.monotonic()
    with pytest.raises(RuntimeError) as raised:
        _attach_permissions(backend, {}, "00000000-0000-0000-0000-000000000001", Emitter())
    elapsed = time.monotonic() - started

    assert "PERMISSION_GATE_UNAVAILABLE" in str(raised.value)
    # 连接超时设的是 10 秒；留足余量但必须远小于「一直等」。
    assert elapsed < 30, f"连接超时没生效，耗时 {elapsed:.1f}s"
