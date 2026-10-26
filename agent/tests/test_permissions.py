"""命令白名单与转义防护。

这是安全控制，重点在「拒绝」用例。

背景：框架自带的 `LocalShellBackend.execute` 是无限制的，实测
`type "C:\\...\\outside\\secret.txt"` 能 exit 0 并原样读出内容。
虚拟模式只保护文件操作，管不到 shell。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from backends.permissions import CommandRejected, ensure_safe_command

WORKSPACE = Path("D:/ws/demo").resolve()


def ok(command: str) -> None:
    ensure_safe_command(command, WORKSPACE)


def rejected(command: str) -> str:
    with pytest.raises(CommandRejected) as exc:
        ensure_safe_command(command, WORKSPACE)
    return str(exc.value)


# ─────────────────────────── 放行 ───────────────────────────

@pytest.mark.parametrize("command", [
    "dir",
    "dir src",
    "type src/index.html",
    "ls",
    "cat src/index.html",
    "python script.py",
    "python -m pytest",
    "pip install -r requirements.txt",
    "npm install",
    "npm run build",
    "git status",
    "git diff HEAD",
    "echo hello",
])
def test_allows_expected_commands(command):
    ok(command)


def test_allows_path_prefixed_executable():
    """允许 .\\.venv\\Scripts\\python.exe 这种写法，只比对文件名。"""
    ok(r".\.venv\Scripts\python.exe script.py")


def test_allows_workspace_absolute_path_argument():
    ok(f'type "{WORKSPACE / "src" / "a.py"}"')


# ─────────────────────────── 拒绝：执行流操作符 ───────────────────────────

@pytest.mark.parametrize("command", [
    "dir && type secret.txt",
    "dir || echo x",
    "dir | findstr x",
    "type a.txt > b.txt",
    "type a.txt >> b.txt",
    "type < a.txt",
    "dir ; echo x",
    "echo `whoami`",
    "echo $(whoami)",
])
def test_rejects_shell_operators(command):
    message = rejected(command)
    assert "不允许出现" in message


# ─────────────────────────── 拒绝：命令不在白名单 ───────────────────────────

@pytest.mark.parametrize("command", [
    "curl http://evil.example",
    "powershell -c Get-Process",
    "cmd /c dir",
    "whoami",
    "netstat -an",
    "python2 x.py",
])
def test_rejects_commands_outside_allowlist(command):
    assert "不在允许列表里" in rejected(command)


# ─────────────────────────── 拒绝：危险片段 ───────────────────────────

@pytest.mark.parametrize("command", [
    "taskkill /f /im python.exe",
    "reg delete HKLM\\Software",
    "net user hacker pass /add",
    "format D:",
    "shutdown /s",
    "diskpart",
    "bcdedit",
])
def test_rejects_dangerous_fragments(command):
    assert "被禁止的片段" in rejected(command)


# ─────────────────────────── 拒绝：路径逃逸 ───────────────────────────

@pytest.mark.parametrize("command", [
    "type ..\\..\\secret.txt",
    "type ..\\secret.txt",
    "dir ../..",
])
def test_rejects_parent_traversal(command):
    assert "上级目录引用" in rejected(command)


@pytest.mark.parametrize("command", [
    'type "C:\\Windows\\System32\\drivers\\etc\\hosts"',
    'type "D:\\other\\secret.txt"',
    'dir "C:\\Users"',
])
def test_rejects_absolute_paths_outside_workspace(command):
    assert "指向工作区之外" in rejected(command)


def test_rejects_empty_command():
    assert "命令为空" in rejected("   ")


# ─────────────────────────── 路径含空格 ───────────────────────────

# 本项目的仓库路径里就有空格（"all Code"），所以这条必须对。
SPACED_WORKSPACE = Path("D:/mainJava/all Code/program/demo").resolve()


def test_allows_quoted_workspace_path_with_spaces():
    """指向工作区内部的绝对路径，即使含空格也要放行。

    之前用 shlex 分词，会在空格处把参数拆开，导致工作区内的合法路径被误拒。
    """
    inside = SPACED_WORKSPACE / "check.py"
    ensure_safe_command(f'python3 "{inside}"', SPACED_WORKSPACE)


def test_rejects_quoted_path_outside_workspace_with_spaces():
    with pytest.raises(CommandRejected, match="指向工作区之外"):
        ensure_safe_command(r'python3 "D:\other place\secret.py"', SPACED_WORKSPACE)


def test_quoted_relative_path_with_spaces_is_allowed():
    ensure_safe_command('python3 "my scripts/check.py"', SPACED_WORKSPACE)


def test_rejects_unquoted_traversal_even_with_spaces():
    with pytest.raises(CommandRejected):
        ensure_safe_command("type ..\\my secret.txt", SPACED_WORKSPACE)
