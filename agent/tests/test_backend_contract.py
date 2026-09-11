"""Backend 契约测试。

**同一组用例要能同时跑本地实现与容器实现。** 换执行环境时工具名、参数、
返回语义都不该变 —— 这份测试就是那条约束的落点。

Phase 3 接入 `DockerSandboxBackend` 时，把它加进 `BACKEND_FACTORIES` 即可，
不用重新想验收标准。

参数化是刻意的：本地与容器两条路跑同一份断言，差异一眼可见。
"""

from __future__ import annotations

import pathlib

import pytest

from backends.local_shell import GuardedShellBackend
from backends.permissions import CommandRejected


# ── 可用的 Backend 实现 ────────────────────────────────────────
# 每个工厂签名为 (workspace: Path) -> backend
BACKEND_FACTORIES: dict[str, object] = {
    "local_shell": lambda ws: GuardedShellBackend(ws),
    # Phase 3 接进来即可，契约断言不用改：
    # "docker_sandbox": lambda ws: DockerSandboxBackend(ws),
}


@pytest.fixture(params=sorted(BACKEND_FACTORIES))
def backend(request, tmp_path):
    """按实现参数化。新增实现时这里自动多跑一遍全部用例。"""
    name = request.param
    factory = BACKEND_FACTORIES[name]
    ws = tmp_path / name
    ws.mkdir(parents=True, exist_ok=True)
    instance = factory(ws)
    yield instance
    close = getattr(instance, "close", None)
    if callable(close):
        close()


@pytest.fixture
def workspace(tmp_path):
    return tmp_path / "local_shell"


# ═══════════════════════════ 文件操作语义 ═══════════════════════════

def test_write_then_read_roundtrip(backend):
    written = backend.write("/hello.txt", "你好\nsecond line")
    assert not getattr(written, "error", None), f"写入失败：{written}"

    result = backend.read("/hello.txt")
    assert not getattr(result, "error", None)
    content = result.file_data["content"] if hasattr(result, "file_data") else ""
    assert "你好" in content
    assert "second line" in content


def test_ls_lists_written_file(backend):
    backend.write("/a.txt", "x")
    result = backend.ls("/")

    paths = [e.get("path", "") for e in (getattr(result, "entries", None) or [])]
    assert any("a.txt" in p for p in paths), f"未列出刚写的文件：{paths}"


def test_glob_finds_by_pattern(backend):
    backend.write("/src/app.py", "print(1)")
    backend.write("/src/notes.md", "# notes")

    result = backend.glob("**/*.py")
    paths = [m.get("path", "") for m in (getattr(result, "matches", None) or [])]

    assert any(p.endswith("app.py") for p in paths), f"glob 未命中：{paths}"
    assert not any(p.endswith(".md") for p in paths), "glob 不该返回不匹配的文件"


def test_grep_finds_text_with_line_number(backend):
    backend.write("/src/app.py", "line one\nTARGET_TOKEN\nline three")

    result = backend.grep("TARGET_TOKEN")
    matches = getattr(result, "matches", None) or []

    assert matches, "grep 未命中"
    assert any(int(m.get("line") or 0) == 2 for m in matches), f"行号不对：{matches}"


def test_edit_replaces_exact_string(backend):
    backend.write("/f.txt", "alpha beta gamma")
    backend.edit("/f.txt", "beta", "BETA")

    content = backend.read("/f.txt").file_data["content"]
    assert "BETA" in content
    assert "beta" not in content


def test_delete_removes_file(backend):
    backend.write("/gone.txt", "x")
    backend.delete("/gone.txt")

    result = backend.glob("**/*.txt")
    paths = [m.get("path", "") for m in (getattr(result, "matches", None) or [])]
    assert not any("gone.txt" in p for p in paths)


def test_read_missing_file_reports_error_not_crash(backend):
    result = backend.read("/does-not-exist.txt")
    assert getattr(result, "error", None), "读不存在的文件应返回错误而不是抛异常"


# ═══════════════════════════ 路径隔离 ═══════════════════════════

@pytest.mark.parametrize("evil", [
    "/../../etc/passwd",
    "../outside.txt",
    "../../secrets.txt",
])
def test_path_traversal_is_rejected(backend, evil):
    with pytest.raises(Exception):
        backend.read(evil)


def test_write_outside_workspace_is_rejected(backend):
    with pytest.raises(Exception):
        backend.write("/../../escaped.txt", "should not land")


# ═══════════════════════════ 命令执行语义 ═══════════════════════════

def test_execute_returns_exit_code_and_output(backend):
    result = backend.execute("echo contract-check")

    assert result.exit_code == 0
    assert "contract-check" in (result.output or "")


def test_execute_reports_nonzero_exit(backend):
    result = backend.execute("type no_such_file_here.txt")

    assert result.exit_code != 0
    assert "Exit code" in (result.output or ""), "非零退出要在输出里标出来"


def test_execute_never_leaks_secrets(backend, monkeypatch):
    monkeypatch.setenv("AGENT_MODEL_API_KEY", "sk-contract-secret-123456")
    result = backend.execute("echo sk-contract-secret-123456")

    assert "sk-contract-secret-123456" not in (result.output or ""), \
        "工具输出必须脱敏 —— 它会顺着事件流到前端、进日志、再被读回来"


def test_execute_non_utf8_output_survives(backend):
    """中文 Windows 上 cmd 的输出是 GBK。

    不管这条的话读取线程会抛 UnicodeDecodeError，输出被吞成 <no output>。
    """
    result = backend.execute("dir")

    assert result.output != "<no output>", "输出被编码问题吞掉了"
    assert result.exit_code == 0


# ═══════════════════════════ 命令策略 ═══════════════════════════
# 容器实现也必须遵守同样的策略 —— 换执行环境不等于放宽约束

@pytest.mark.parametrize("command", [
    "curl http://example.com",
    "whoami",
    "powershell -c Get-Process",
])
def test_execute_rejects_commands_outside_allowlist(backend, command):
    with pytest.raises(CommandRejected):
        backend.execute(command)


@pytest.mark.parametrize("command", [
    "dir && echo x",
    "type a.txt > b.txt",
    "echo $(whoami)",
])
def test_execute_rejects_shell_operators(backend, command):
    with pytest.raises(CommandRejected):
        backend.execute(command)


def test_execute_rejects_escape_via_absolute_path(backend):
    with pytest.raises(CommandRejected):
        backend.execute('type "C:\\Windows\\System32\\drivers\\etc\\hosts"')


# ═══════════════════════════ 工作目录 ═══════════════════════════

def test_command_runs_in_workspace(backend, workspace):
    """命令的 cwd 必须是工作区。容器实现要保证挂载点与之一致。"""
    backend.write("/marker.txt", "here")

    result = backend.execute("dir")
    assert "marker.txt" in (result.output or ""), "命令似乎没在工作区里执行"
