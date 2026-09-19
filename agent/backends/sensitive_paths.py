"""敏感路径防护：只读任务连**读**都不该碰到凭据与数据库文件。

为什么需要它：快照的排除清单只能保证「我送给模型的内容」干净，
但知识任务里的 agent 有文件工具，它可以自己去 `read .env`。
实测里模型确实把一个 `.env` 列成了图谱节点 —— 内容这次没泄漏是运气，
不是保证。所以要在执行侧再挡一层，而不是只靠快照过滤与提示词。

与 ReadOnlyBackend 分开是刻意的：那个管「不许写」，这个管「不许读这些」，
两件事各自独立，组合使用。
"""

from __future__ import annotations

import logging
import posixpath

logger = logging.getLogger(__name__)


class SensitivePathDenied(RuntimeError):
    """只读任务试图访问凭据或数据文件。"""


class SensitivePathGuard:
    """拦掉对凭据/数据库/依赖/日志等路径的读取。"""

    EXCLUDED_DIRS = frozenset(
        {
            "node_modules", "vendor", ".venv", "venv", "env",
            "dist", "build", "target", "out", ".next", ".nuxt", ".output",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            ".cache", "coverage", ".nyc_output",
            "logs", "log", "tmp", "temp",
            ".git", ".glyahh", ".idea", ".vscode",
            ".gradle", ".mvn",
            # 凭据类目录：名字本身就是强信号，连列目录都不该允许
            "keys", "secrets", "certs", "ssl", ".ssh",
        }
    )

    EXCLUDED_FILE_NAMES = frozenset(
        {
            ".env", ".env.local", ".env.production", ".env.development",
            "credentials", "credentials.json", "secrets.json", "id_rsa", "id_dsa",
            ".netrc", ".npmrc", ".pypirc",
        }
    )

    EXCLUDED_EXTENSIONS = frozenset(
        {
            "pem", "key", "p12", "pfx", "jks", "keystore", "secret",
            "db", "sqlite", "sqlite3", "db-journal", "db-wal", "db-shm",
            "log", "tmp", "cache",
        }
    )

    # 这些方法接受路径参数，需要逐个检查。同步与异步变体都要覆盖：
    # 只定义同步的话，`await guard.aread("/.env")` 在类上找不到 aread，
    # 会走 __getattr__ 直通内层，检查被整个跳过（实测能读到 .env 明文）。
    PATH_METHODS = frozenset({"read", "ls", "grep", "glob"})

    # 读操作的异步变体：同样先查路径再转发。
    PATH_METHODS_ASYNC = frozenset("a" + m for m in PATH_METHODS)

    def __init__(self, inner):
        self._inner = inner

    def _check(self, method: str, path) -> None:
        if path is None:
            return
        if self.is_denied(str(path)):
            logger.warning("只读任务尝试访问受限路径 %s（%s），已拒绝", path, method)
            raise SensitivePathDenied(
                f"{path} 属于凭据/数据/依赖文件，只读分析不允许访问。"
            )

    @classmethod
    def is_denied(cls, path: str) -> bool:
        """路径是否落在排除范围内。

        命中任一段目录名、或文件名/扩展名在白名单外，都算受限。
        `.env` 这类以点开头的凭据前缀也一并挡住。
        """
        normalized = path.replace("\\", "/").strip()
        if not normalized or normalized in (".", "/"):
            return False

        parts = [segment for segment in posixpath.normpath(normalized).split("/") if segment not in ("", ".")]
        # 检查**所有**路径段，不只中间段：`ls("keys")` 的最后一段本身就是目标目录，
        # 只查 parts[:-1] 会把它漏掉，换个方法就绕过去了。
        if any(segment.lower() in cls.EXCLUDED_DIRS for segment in parts):
            return True

        name = parts[-1].lower() if parts else ""
        if name in cls.EXCLUDED_FILE_NAMES or name.startswith(".env"):
            return True

        dot = name.rfind(".")
        if dot > 0 and dot < len(name) - 1:
            return name[dot + 1:] in cls.EXCLUDED_EXTENSIONS
        return False

    # ── 读操作：先查路径再转发 ────────────────────────────────
    def read(self, path, *args, **kwargs):
        self._check("read", path)
        return self._inner.read(path, *args, **kwargs)

    def ls(self, path=".", *args, **kwargs):
        self._check("ls", path)
        return self._inner.ls(path, *args, **kwargs)

    def grep(self, pattern, path=None, *args, **kwargs):
        self._check("grep", path)
        return self._inner.grep(pattern, path, *args, **kwargs)

    def glob(self, pattern, path=None, *args, **kwargs):
        self._check("glob", path)
        return self._inner.glob(pattern, path, *args, **kwargs)

    # ── 读操作的异步变体：先查路径再转发 ──────────────────────
    async def aread(self, path, *args, **kwargs):
        self._check("aread", path)
        return await self._inner.aread(path, *args, **kwargs)

    async def als(self, path=".", *args, **kwargs):
        self._check("als", path)
        return await self._inner.als(path, *args, **kwargs)

    async def agrep(self, pattern, path=None, *args, **kwargs):
        self._check("agrep", path)
        return await self._inner.agrep(pattern, path, *args, **kwargs)

    async def aglob(self, pattern, path=None, *args, **kwargs):
        self._check("aglob", path)
        return await self._inner.aglob(pattern, path, *args, **kwargs)

    def __getattr__(self, name):
        # 其余成员（含被 ReadOnlyBackend 拦下的写操作）转给内层。
        return getattr(self._inner, name)

    def __repr__(self) -> str:  # pragma: no cover - 仅用于日志
        return f"SensitivePathGuard({self._inner!r})"
