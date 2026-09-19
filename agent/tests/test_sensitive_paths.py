"""敏感路径防护：只读任务连读都不该碰到凭据与数据文件。

背景：快照的排除清单只保证「平台送给模型的内容」干净，而 agent 有文件工具，
可以自己 `read .env`。实测里模型确实把一个 `.env` 列成了图谱节点 ——
那次内容没泄漏是运气，不是保证。
"""

from __future__ import annotations

import pytest

from backends.sensitive_paths import SensitivePathDenied, SensitivePathGuard


class _FakeBackend:
    def __init__(self):
        self.calls: list[str] = []

    def read(self, path, *args, **kwargs):
        self.calls.append(f"read:{path}")
        return "content"

    def ls(self, path=".", *args, **kwargs):
        self.calls.append(f"ls:{path}")
        return ["a.py"]

    def grep(self, pattern, path=None, *args, **kwargs):
        self.calls.append(f"grep:{pattern}:{path}")
        return []

    def glob(self, pattern, path=None, *args, **kwargs):
        self.calls.append(f"glob:{pattern}:{path}")
        return []

    def write(self, path, content, *args, **kwargs):  # pragma: no cover
        self.calls.append(f"write:{path}")
        return "written"


@pytest.mark.parametrize("path", [
    ".env",
    ".env.production",
    "config/.env",
    "id_rsa",
    "credentials.json",
    "data/app.db",
    "app.sqlite3",
    "server.log",
    "certs/server.pem",
    "keys/private.key",
    "node_modules/pkg/index.js",
    ".glyahh/runner.sh",
])
def test_denied_paths_are_blocked_for_read(path):
    guard = SensitivePathGuard(_FakeBackend())

    with pytest.raises(SensitivePathDenied):
        guard.read(path)


@pytest.mark.parametrize("path", [
    "src/main.js",
    "README.md",
    "package.json",
    "src/app/tasks.py",
    "./src/main.js",
])
def test_normal_source_paths_pass(path):
    inner = _FakeBackend()
    guard = SensitivePathGuard(inner)

    assert guard.read(path) == "content"
    assert inner.calls == [f"read:{path}"]


def test_ls_and_search_also_checked():
    """ls / grep / glob 带路径参数时同样要检查，否则换个方法就绕过去了。"""
    guard = SensitivePathGuard(_FakeBackend())

    with pytest.raises(SensitivePathDenied):
        guard.ls(".env")
    with pytest.raises(SensitivePathDenied):
        guard.grep("SECRET", "config/.env")
    with pytest.raises(SensitivePathDenied):
        guard.glob("*", "keys")


def test_workspace_root_listing_is_allowed():
    """列根目录要能用 —— 防护是挡具体文件，不是把工作区整个封掉。"""
    guard = SensitivePathGuard(_FakeBackend())

    assert guard.ls(".") == ["a.py"]
    assert guard.ls("/") == ["a.py"]


def test_unknown_members_fall_through():
    """没显式定义的成员转给内层，避免把协议补全成残废。"""
    guard = SensitivePathGuard(_FakeBackend())
    # write 不在这里拦（那是 ReadOnlyBackend 的职责），应原样转发
    assert guard.write("a.py", "x") == "written"


# 框架做的是类级能力探测。这条测试锁住「满足协议的那层必须在最外面」——
# 同一个坑踩过两次（先是 ReadOnlyBackend 单独用、再是把它套在里面），
# 每次都表现为整轮任务直接崩在 `type object 'X' has no attribute 'delete'`。
_PROTOCOL_METHODS = (
    "ls", "read", "grep", "glob",
    "write", "edit", "delete", "upload_files", "download_files", "execute",
)


def test_read_only_backend_exposes_full_protocol_on_class():
    from backends.read_only import ReadOnlyBackend

    missing = [name for name in _PROTOCOL_METHODS if not hasattr(ReadOnlyBackend, name)]
    assert missing == [], f"ReadOnlyBackend 类上缺这些协议方法：{missing}"


def test_composed_guard_keeps_protocol_on_the_outermost_class():
    """组合后的最外层必须仍然暴露完整协议。"""
    from backends.read_only import ReadOnlyBackend

    composed = ReadOnlyBackend(SensitivePathGuard(_FakeBackend()))

    missing = [name for name in _PROTOCOL_METHODS if not hasattr(type(composed), name)]
    assert missing == [], f"最外层包装缺这些协议方法：{missing}"


def test_composed_guard_still_blocks_reads_and_writes():
    """组合之后两层防护都要生效：写被拒、敏感读被拒、正常读放行。"""
    from backends.read_only import ReadOnlyBackend, ReadOnlyViolation

    composed = ReadOnlyBackend(SensitivePathGuard(_FakeBackend()))

    with pytest.raises(ReadOnlyViolation):
        composed.write("a.py", "x")
    with pytest.raises(SensitivePathDenied):
        composed.read(".env")
    assert composed.read("src/main.js") == "content"
