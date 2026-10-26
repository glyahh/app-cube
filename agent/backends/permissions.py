"""命令与路径的约束。

框架自带的 `LocalShellBackend.execute` 是**无限制**的 —— 它的文档原话是
「Access any file on the filesystem (regardless of `virtual_mode`)」。
实测确认：`type "C:\\...\\outside\\secret.txt"` 返回 exit 0 并把内容原样吐了出来。

虚拟模式只保护 `read` / `write` / `glob` / `grep` 这些文件操作，管不到 shell。
所以 shell 这一条必须自己加闸。

这里的策略是**白名单 + 语法收敛**：只放行已知需要的命令，并且禁止会改变执行流的
shell 操作符。宁可严一点 —— 放开一条命令很容易，出事之后收窄就晚了。
"""

from __future__ import annotations

import re
import shlex
from pathlib import Path

# 命令分词。手写而不用 shlex：
# - posix 模式会把 Windows 路径里的反斜杠当转义符吃掉
# - 非 posix 模式又会在空格处拆开带空格的引号参数
# 而本项目的仓库路径里就有空格（"all Code"），必须正确处理。
_TOKEN_RE = re.compile(r'"([^"]*)"' r"|'([^']*)'" r"|(\S+)")

# 形如 C:\... 或 D:/... 的盘符绝对路径
_WINDOWS_ABS = re.compile(r"^[A-Za-z]:[\\/]")

# 放行的命令（取命令行的第一个词）。够 Phase 1 用：
# 看目录、跑 python、装依赖、构建工程、git 操作。
ALLOWED_COMMANDS = frozenset({
    # 查看
    "dir", "type", "where", "findstr", "tree",
    # Python 工具链
    "python", "python3", "py", "pip", "pytest", "uv",
    # Node 工具链（Vue 工程的 install / build）
    "node", "npm", "npx",
    # 版本控制
    "git",
    # 无害的
    "echo", "cd",
    # POSIX 等价命令；路径仍走同一套白名单与越界校验
    "ls", "cat",
})

# 会改变执行流的 shell 操作符。禁止它们，一条命令就只能做一件事。
# 少了这些，`type secret > out.txt`、`a && b`、反引号替换这类都进不来。
FORBIDDEN_OPERATORS = ("&&", "||", "|", ">", "<", ";", "`", "$(", "\n", "\r")

# 危险片段。即便首个词在白名单里，出现在别处也要拦。
FORBIDDEN_FRAGMENTS = (
    "format ", "shutdown", "restart-computer", "stop-computer",
    "reg delete", "reg add", "reg import", "reg save", "reg restore",
    "net user", "net localgroup",
    "taskkill", "diskpart", "bcdedit", "cipher /w",
    "remove-item", "rm -rf", "del /s", "del /q", "rmdir /s",
    "while true", "for /l", ":(){", "mkfifo",
)


class CommandRejected(ValueError):
    """命令被策略拒绝。**这是可恢复错误** —— 要让模型看到原因并改正，
    而不是让它以为工具坏了、然后换一种方式绕。"""


def ensure_safe_command(command: str, workspace: Path) -> None:
    """校验一条 shell 命令。不安全就抛 CommandRejected。

    Args:
        command: 原始命令文本
        workspace: 允许操作的根目录，绝对路径
    """
    if not command or not command.strip():
        raise CommandRejected("命令为空")

    text = command.strip()

    # 1. 执行流操作符一律禁止
    for op in FORBIDDEN_OPERATORS:
        if op in text:
            raise CommandRejected(
                f"命令里不允许出现 {op!r}。一次只做一件事，不要用管道、重定向或链接。"
            )

    # 2. 危险片段
    lowered = text.lower()
    for frag in FORBIDDEN_FRAGMENTS:
        if frag in lowered:
            raise CommandRejected(f"命令包含被禁止的片段：{frag.strip()}")

    # 3. 首个词必须在白名单里
    parts = _tokenize(text)
    if not parts:
        raise CommandRejected("命令为空")

    executable = Path(parts[0].replace("\\", "/")).name.lower()
    if executable.endswith(".exe"):
        executable = executable[:-4]
    if executable not in ALLOWED_COMMANDS:
        raise CommandRejected(
            f"命令 {parts[0]!r} 不在允许列表里。允许的是：{', '.join(sorted(ALLOWED_COMMANDS))}"
        )

    # 4. 参数里不允许出现工作区之外的绝对路径，也不允许 ..
    root = workspace.resolve()
    for token in parts[1:]:
        candidate = token.strip()
        if not candidate:
            continue
        normalized = candidate.replace("\\", "/")
        # Windows 与 POSIX 分隔符都要检查；Linux 上 Path 不会把反斜杠当分隔符。
        if ".." in Path(normalized).parts:
            raise CommandRejected(f"参数 {token!r} 含上级目录引用，不允许")
        # 盘符路径与 POSIX 风格绝对路径都要校验归属
        if _WINDOWS_ABS.match(candidate) or candidate.startswith(("/", "\\")):
            try:
                Path(candidate).resolve().relative_to(root)
            except ValueError as exc:
                raise CommandRejected(
                    f"参数 {token!r} 指向工作区之外，不允许"
                ) from exc


def _tokenize(text: str) -> list[str]:
    """按引号与空白分词。带空格的引号参数保持为一个 token。"""
    tokens = []
    for match in _TOKEN_RE.finditer(text):
        token = match.group(1) or match.group(2) or match.group(3) or ""
        if token:
            tokens.append(token)
    return tokens


class ImportCommandRejected(CommandRejected):
    """导入拒绝使用固定类别，供每轮止损与脱敏事件使用。"""

    def __init__(self, reason: str):
        self.reason = reason
        super().__init__(f"IMPORT_COMMAND_REJECTED:{reason}。请修正命令，不要重复同类操作。")


def prepare_import_command(command: str, workspace: Path) -> str:
    """解析导入容器的单条POSIX命令；参数重新引用，不改变Windows策略。"""
    if not isinstance(command, str) or not command.strip():
        raise ImportCommandRejected("EMPTY")
    # 1. 引号里的普通字符是参数；真正的Shell操作符与命令替换拒绝。
    quote = ""
    escaped = False
    for index, char in enumerate(command):
        if escaped:
            escaped = False
            continue
        if char == "\\" and quote != "'":
            escaped = True
            continue
        if quote == "'":
            if char == "'":
                quote = ""
            continue
        if char == '"':
            quote = "" if quote == '"' else '"'
            continue
        if char == "'" and not quote:
            quote = "'"
            continue
        if char == "`" or (char == "$" and command[index:index + 2] == "$("):
            raise ImportCommandRejected("SUBSTITUTION")
        if not quote and char in ";&|<>()\n\r":
            raise ImportCommandRejected("OPERATORS")
    if quote or escaped:
        raise ImportCommandRejected("QUOTING")
    try:
        parts = shlex.split(command, posix=True)
    except ValueError as error:
        raise ImportCommandRejected("QUOTING") from error
    # 2. 复用白名单与危险片段，不为导入增加命令权限。
    if not parts:
        raise ImportCommandRejected("EMPTY")
    if parts[0] not in ALLOWED_COMMANDS:
        raise ImportCommandRejected("EXECUTABLE")
    if any(fragment in command.lower() for fragment in FORBIDDEN_FRAGMENTS):
        raise ImportCommandRejected("DANGEROUS")
    # 导入编辑许可不能代替安装、启动、部署或远端 Git 的独立授权。
    executable, arguments = parts[0], parts[1:]
    if executable in {'pip', 'uv', 'npx'}:
        raise ImportCommandRejected('SEPARATE_AUTHORIZATION')
    if executable == 'npm' and (not arguments or arguments[0] not in {'run', 'test', '--version', '-v'}):
        raise ImportCommandRejected('SEPARATE_AUTHORIZATION')
    if executable == 'npm' and arguments[0] == 'run' and (len(arguments) < 2 or arguments[1] not in {'build', 'test', 'lint', 'check', 'type-check'}):
        raise ImportCommandRejected('SEPARATE_AUTHORIZATION')
    if executable == 'git' and (not arguments or arguments[0] not in {'status', 'diff', 'log', 'show', 'ls-files', 'rev-parse'}):
        raise ImportCommandRejected('SEPARATE_AUTHORIZATION')
    if executable in {'python', 'python3', 'py'} and '-m' in arguments:
        index = arguments.index('-m')
        if index + 1 >= len(arguments) or arguments[index + 1] not in {'pytest', 'compileall', 'unittest', 'py_compile'}:
            raise ImportCommandRejected('SEPARATE_AUTHORIZATION')
    if any(re.search(r'(^|[./_-])(install|start|serve|server|dev|deploy|publish|push)([./_-]|$)', token.lower())
           for token in arguments if not token.startswith('-')):
        raise ImportCommandRejected('SEPARATE_AUTHORIZATION')
    root = workspace.resolve()
    for token in parts[1:]:
        if ".." in Path(token).parts:
            raise ImportCommandRejected("PATH")
        if _WINDOWS_ABS.match(token) or token.startswith(("/", "\\")):
            try:
                Path(token).resolve().relative_to(root)
            except ValueError as error:
                raise ImportCommandRejected("PATH") from error
    # 3. 仅转换命令名，不改写Python源码等参数里的type文字。
    parts[0] = {"type": "cat", "dir": "ls", "where": "which"}.get(parts[0], parts[0])
    return shlex.join(parts)
