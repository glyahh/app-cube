"""受控 shell backend。

两件事要盯住：
1. 命令守卫真的接上了（不是写了没调用）
2. 非 UTF-8 输出不再崩 —— 中文 Windows 上 `dir` 的输出是 GBK，
   框架原版会抛 UnicodeDecodeError 把结果吞成 `<no output>`
"""

from __future__ import annotations

import pytest

from backends.local_shell import GuardedShellBackend
from backends.permissions import CommandRejected


@pytest.fixture
def workspace(tmp_path):
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "index.html").write_text("<h1>页面</h1>\n", encoding="utf-8")
    # 中文名目录，用来逼出 GBK 输出
    (tmp_path / "中文目录").mkdir()
    (tmp_path / "中文目录" / "说明.txt").write_text("内容", encoding="utf-8")
    return tmp_path


@pytest.fixture
def backend(workspace):
    return GuardedShellBackend(workspace, timeout=30)


# ─────────────────────────── 守卫接上了 ───────────────────────────

def test_rejects_command_outside_allowlist(backend):
    with pytest.raises(CommandRejected):
        backend.execute("curl http://example.com")


def test_rejects_shell_operator(backend):
    with pytest.raises(CommandRejected):
        backend.execute("dir && echo x")


def test_rejects_directory_traversal(backend):
    with pytest.raises(CommandRejected):
        backend.execute("type ..\\..\\secret.txt")


def test_rejects_absolute_path_outside_workspace(backend):
    with pytest.raises(CommandRejected):
        backend.execute('type "C:\\Windows\\System32\\drivers\\etc\\hosts"')


# ─────────────────────────── 正常执行 ───────────────────────────

def test_runs_allowed_command(backend):
    result = backend.execute("echo hello")
    assert result.exit_code == 0
    assert "hello" in result.output


def test_reads_workspace_file(backend):
    result = backend.execute("type src\\index.html")
    assert result.exit_code == 0
    assert "页面" in result.output


def test_reports_nonzero_exit(backend):
    result = backend.execute("type does_not_exist.txt")
    assert result.exit_code != 0
    assert "Exit code" in result.output


# ─────────────────────────── 编码 ───────────────────────────

def test_non_utf8_output_does_not_crash(backend):
    """中文 Windows 上 cmd 的输出是 GBK。

    框架原版用 text=True 按默认编码解，会抛 UnicodeDecodeError 把结果吞掉。
    这里必须能把中文原样带回来。
    """
    result = backend.execute("dir")

    assert result.exit_code == 0
    assert result.output != "<no output>", "输出被编码错误吞掉了"
    # GBK 里的中文目录名应该能读出来
    assert "中文目录" in result.output or "说明" in result.output


# ─────────────────────────── 输出治理 ───────────────────────────

def test_truncates_huge_output(backend, monkeypatch):
    import backends.local_shell as ls
    monkeypatch.setattr(ls, "MAX_OUTPUT_CHARS", 50)

    result = backend.execute("dir")

    assert result.truncated is True
    assert "输出已截断" in result.output


def test_masks_secrets_in_output(backend, monkeypatch):
    monkeypatch.setenv("AGENT_MODEL_API_KEY", "sk-super-secret-value-123456")
    result = backend.execute("echo sk-super-secret-value-123456")

    assert "sk-super-secret-value-123456" not in result.output
    assert "***" in result.output


# ─────────────────────────── 继承的文件操作仍然隔离 ───────────────────────────

def test_inherited_file_ops_still_sandboxed(backend, tmp_path):
    """execute 我们接管了，但 read/write 用的是框架实现，虚拟模式必须仍然生效。"""
    import pytest as _pytest

    with _pytest.raises(Exception):
        backend.read("../../etc/passwd")
