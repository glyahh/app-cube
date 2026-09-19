"""只读 backend 的行为约束。

知识问答与知识生成共用这条路径：写操作必须在**执行侧**被拒绝，
而不是靠提示词请求模型不要改 —— 那是建议，不是约束。

这里还锁住一个踩过的坑：框架会做**类级**能力探测
（`hasattr(type(backend), 'delete')`）。只靠 `__getattr__` 拦截的话类上查不到方法，
整轮任务会以 `type object 'ReadOnlyBackend' has no attribute 'delete'` 失败。
所以被禁的方法必须显式定义在类上。
"""

from __future__ import annotations

import asyncio

import pytest

from backends.read_only import ReadOnlyBackend, ReadOnlyViolation


class _FakeBackend:
    """记录调用的假 backend。"""

    def __init__(self):
        self.calls: list[str] = []

    def read(self, path, *args, **kwargs):
        self.calls.append(f"read:{path}")
        return "content"

    def ls(self, path="."):
        self.calls.append(f"ls:{path}")
        return ["a.py"]

    def glob(self, pattern, path=None):
        self.calls.append(f"glob:{pattern}")
        return []

    def grep(self, pattern, path=None, glob=None, **kwargs):
        self.calls.append(f"grep:{pattern}")
        return []

    def write(self, path, content, *args, **kwargs):  # pragma: no cover - 不应被调到
        self.calls.append(f"write:{path}")
        return "written"

    def edit(self, path, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"edit:{path}")
        return "edited"

    def execute(self, command, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"execute:{command}")
        return "output"

    # ── 异步变体：框架的 protocol 同步与异步两套都有 ──────────
    async def aread(self, path, *args, **kwargs):
        self.calls.append(f"aread:{path}")
        return "content"

    async def als(self, path="."):
        self.calls.append(f"als:{path}")
        return ["a.py"]

    async def awrite(self, path, content, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"awrite:{path}")
        return "written"

    async def aedit(self, path, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"aedit:{path}")
        return "edited"

    async def adelete(self, path, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"adelete:{path}")
        return "deleted"

    async def aexecute(self, command, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"aexecute:{command}")
        return "output"


@pytest.mark.parametrize("method,args", [
    ("write", ("a.py", "x")),
    ("edit", ("a.py", "old", "new")),
    ("delete", ("a.py",)),
    ("upload_files", ([("a.py", b"x")],)),
    ("download_files", (["a.py"],)),
    ("execute", ("rm -rf /",)),
])
def test_write_operations_are_refused(method, args):
    """所有会产生副作用的调用都必须在执行侧失败。"""
    backend = ReadOnlyBackend(_FakeBackend())

    with pytest.raises(ReadOnlyViolation):
        getattr(backend, method)(*args)


@pytest.mark.parametrize("method", [
    "write", "edit", "delete", "upload_files", "download_files", "execute",
])
def test_blocked_methods_exist_on_the_class(method):
    """被禁的方法必须在类上可见。

    框架用它探测 backend 的能力；类上查不到会直接让任务崩掉，
    而不是给模型一个「这个操作不允许」的可修正反馈。
    """
    assert hasattr(ReadOnlyBackend, method), f"{method} 必须显式定义在类上"


def test_read_operations_pass_through():
    """读操作原样转发，只读不等于不能用。"""
    inner = _FakeBackend()
    backend = ReadOnlyBackend(inner)

    assert backend.read("a.py") == "content"
    assert backend.ls(".") == ["a.py"]
    assert backend.glob("*.py") == []
    assert backend.grep("needle") == []

    assert inner.calls == ["read:a.py", "ls:.", "glob:*.py", "grep:needle"]


def test_inner_backend_never_receives_writes():
    """拒绝要发生在包装层：内层 backend 不该看到写调用。"""
    inner = _FakeBackend()
    backend = ReadOnlyBackend(inner)

    with pytest.raises(ReadOnlyViolation):
        backend.write("a.py", "x")

    assert inner.calls == [], "写调用泄漏到了内层 backend"


def test_unknown_members_fall_through_to_inner():
    """没显式定义的成员转给内层，避免包装层把协议补全成残废。"""
    class _WithExtra(_FakeBackend):
        def close(self):
            return "closed"

    backend = ReadOnlyBackend(_WithExtra())
    assert backend.close() == "closed"


# ── 异步变体 ──────────────────────────────────────────────────
#
# 踩过的坑：框架的 protocol 同时定义了 write 和 awrite。只拦同步那套时，
# `awrite` 在类上找不到 → 走 __getattr__ 透传给内层真实 backend，
# 上面所有限制被整个绕过去。实测里知识任务就是用 awrite / adelete
# 建了文件又删掉、还能 `await aread("/.env")` 拿到明文。

ASYNC_WRITE_CALLS = [
    ("awrite", ("a.py", "x")),
    ("aedit", ("a.py", "old", "new")),
    ("adelete", ("a.py",)),
    ("aupload_files", ([("a.py", b"x")],)),
    ("adownload_files", (["a.py"],)),
    ("aexecute", ("rm -rf /",)),
]


@pytest.mark.parametrize("method,args", ASYNC_WRITE_CALLS)
def test_async_write_operations_are_refused(method, args):
    """异步写操作同样必须在执行侧失败。"""
    backend = ReadOnlyBackend(_FakeBackend())

    with pytest.raises(ReadOnlyViolation):
        asyncio.run(getattr(backend, method)(*args))


@pytest.mark.parametrize("method", [m for m, _ in ASYNC_WRITE_CALLS])
def test_async_blocked_methods_exist_on_the_class(method):
    """异步被禁的方法也要在类上可见，理由同同步那批（框架做类级探测）。"""
    assert hasattr(ReadOnlyBackend, method), f"{method} 必须显式定义在类上"


def test_async_read_operations_pass_through():
    """异步读操作原样转发。"""
    inner = _FakeBackend()
    backend = ReadOnlyBackend(inner)

    assert asyncio.run(backend.aread("a.py")) == "content"
    assert asyncio.run(backend.als(".")) == ["a.py"]

    assert inner.calls == ["aread:a.py", "als:."]


def test_inner_backend_never_receives_async_writes():
    """异步写也要在包装层就断掉，别漏到内层。"""
    inner = _FakeBackend()
    backend = ReadOnlyBackend(inner)

    with pytest.raises(ReadOnlyViolation):
        asyncio.run(backend.awrite("a.py", "x"))

    assert inner.calls == [], "异步写调用泄漏到了内层 backend"


def test_future_async_write_names_are_guarded(monkeypatch):
    """框架以后新增 aXXX 写方法时，__getattr__ 的兜底还要能挡一层。

    这里临时往已知清单里塞一个名字，模拟「同步方法有了，我还没补异步变体」
    的中间状态 —— 那种情况下不能直通。
    """
    from backends import read_only

    monkeypatch.setattr(
        read_only, "_WRITE_METHODS", read_only._WRITE_METHODS + ("frobnicate",)
    )
    backend = ReadOnlyBackend(_FakeBackend())

    with pytest.raises(ReadOnlyViolation):
        asyncio.run(backend.afrobnicate("x"))
