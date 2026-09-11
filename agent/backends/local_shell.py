"""受控的本地 shell backend。

直接复用框架的 `LocalShellBackend` —— 它已经实现了 ls / read / write / edit /
glob / grep / delete / upload / download，而且 `virtual_mode=True` 时**文件操作**
是真的隔离的：实测 `../` 报 `Path traversal not allowed`，工作区外的绝对路径报
`outside root directory`。那部分没必要重写。

只接管 `execute`，因为框架那版有两个实测出来的问题：

1. **完全无隔离**。文档原话「Access any file on the filesystem (regardless of
   virtual_mode)」。实测 `type "C:\\\\...\\\\outside\\\\secret.txt"` 返回 exit 0 并泄露内容。
2. **非 UTF-8 输出会崩读取线程**。中文 Windows 上 `dir` 的输出是 GBK，
   框架用 `text=True` 按默认编码解，抛 `UnicodeDecodeError`，
   结果被吞成 `<no output>`。

Phase 3 换成容器沙箱时，替换的是这个类，工具名和参数语义保持不变。
"""

from __future__ import annotations

import logging
import subprocess
import sys
import time
from pathlib import Path

from deepagents.backends.local_shell import LocalShellBackend
from deepagents.backends.protocol import ExecuteResponse

from backends.permissions import CommandRejected, ensure_safe_command
from core.masking import mask

logger = logging.getLogger(__name__)

# 命令输出的硬上限。超过就截断 —— 否则一条 `dir /s` 能把上下文冲垮。
MAX_OUTPUT_CHARS = 30_000


class GuardedShellBackend(LocalShellBackend):
    """框架 backend + 命令白名单 + 编码安全 + 输出脱敏 + 检索留痕。

    **检索留痕为什么放在 backend 而不是中间件**：glob / grep / read 这些调用
    全部流经这里，无论模型用哪个工具发起。包中间件要替换掉框架的
    FilesystemMiddleware，代价大得多，还容易漏掉某条路径。
    """

    def __init__(self, workspace: Path | str, *, timeout: int = 120, **kwargs):
        self.workspace = Path(workspace).resolve()
        super().__init__(str(self.workspace), virtual_mode=True, timeout=timeout, **kwargs)

    # ── 检索留痕 ──────────────────────────────────────────────
    def glob(self, pattern: str, path: str | None = None):
        started = time.monotonic()
        result = super().glob(pattern, path)
        paths = [(m.get("path"), 0, 0) for m in _matches(result) if not m.get("is_dir")]
        _record("glob", pattern, path or "/", paths, started, result)
        return result

    def grep(self, pattern: str, path: str | None = None, glob: str | None = None,
             *, max_count: int | None = None, context_lines: int = 0):
        started = time.monotonic()
        result = super().grep(pattern, path, glob, max_count=max_count, context_lines=context_lines)
        hits = [(m.get("path"), int(m.get("line") or 0), int(m.get("line") or 0))
                for m in _matches(result)]
        _record("grep", pattern, glob or path or "/", hits, started, result)
        return result

    def read(self, file_path: str, offset: int = 0, limit: int = 2000):
        started = time.monotonic()
        result = super().read(file_path, offset, limit)
        start = int(getattr(result, "start_line", 0) or 0)
        end = int(getattr(result, "end_line", 0) or 0)
        _record("read", file_path, file_path, [(file_path, start, end)], started, result)
        return result

    # ── 命令执行 ──────────────────────────────────────────────
    def execute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        # 白名单不过就直接拒。这是可恢复错误：Python 侧会把它转成 ToolMessage，
        # 模型能看到原因并换一种做法，而不是以为工具坏了。
        ensure_safe_command(command, self.workspace)

        effective_timeout = timeout or getattr(self, "_default_timeout", 120)
        if effective_timeout <= 0:
            raise ValueError(f"timeout 必须为正数，收到 {effective_timeout}")

        env = getattr(self, "_env", None)
        try:
            proc = subprocess.run(  # noqa: S602 — 命令已过白名单
                command,
                check=False,
                shell=True,
                capture_output=True,   # 拿 bytes，自己解码
                stdin=subprocess.DEVNULL,  # 防住会读 stdin 的命令卡死
                timeout=effective_timeout,
                cwd=str(self.workspace),
                env=env,
                start_new_session=(sys.platform != "win32"),
            )
        except subprocess.TimeoutExpired:
            return ExecuteResponse(
                output=f"命令超时（{effective_timeout} 秒）已终止。请缩小范围重试。",
                exit_code=124,
                truncated=False,
            )

        parts = []
        if proc.stdout:
            parts.append(_decode(proc.stdout))
        if proc.stderr:
            # 逐行加前缀，和框架的输出约定保持一致
            stderr_text = _decode(proc.stderr)
            parts.append("\n".join(
                line if line.startswith("[stderr]") else f"[stderr] {line}"
                for line in stderr_text.splitlines()
            ))

        output = "".join(parts)
        truncated = len(output) > MAX_OUTPUT_CHARS
        if truncated:
            output = output[:MAX_OUTPUT_CHARS] + "\n…（输出已截断）"

        output = mask(output)
        if truncated or proc.returncode != 0:
            output += f"\n\nExit code: {proc.returncode}"

        logger.debug("execute 完成：cmd=%r exit=%s", command[:80], proc.returncode)
        return ExecuteResponse(output=output, exit_code=proc.returncode, truncated=truncated)


def _decode(raw: bytes) -> str:
    """按 utf-8 → gbk 顺序试，都不行再宽松解。

    中文 Windows 上 cmd 的输出是 GBK（`dir` 的表格线就是典型），
    而 Python 侧默认按 UTF-8 解 —— 不处理会直接抛异常。
    """
    for encoding in ("utf-8", "gbk"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _matches(result) -> list[dict]:
    """从各类结果对象里取出命中列表。形状不一致，统一在这里收敛。"""
    for attr in ("matches", "entries"):
        value = getattr(result, attr, None)
        if isinstance(value, list):
            return [m for m in value if isinstance(m, dict)]
    return []


def _record(kind: str, query: str, scope: str, hits: list[tuple], started: float, result) -> None:
    """记一条 SearchTrace。

    **拿不到运行上下文就静默跳过** —— 工具不该因为「没有 trace 收集器」而失败。
    检索留痕是排障用的，不是执行的必要条件。
    """
    try:
        from retrieval.runtime_context import current
        from retrieval.search_trace import SearchResult

        ctx = current()
    except Exception:
        return

    negative = []
    if not hits:
        negative.append(f"{kind} 在 {scope} 下没有命中")
    if getattr(result, "truncated", False):
        negative.append(f"{kind} 结果被截断，可能还有更多")

    ctx.trace_log.record(
        kind=kind,
        query=query,
        scope=scope,
        results=[SearchResult(path=p, start=s, end=e) for p, s, e in hits],
        negative_checks=negative,
        elapsed_ms=int((time.monotonic() - started) * 1000),
    )


__all__ = ["GuardedShellBackend", "CommandRejected"]
