"""Git 状态快照 —— 检索结果的版本基准。

调研报告里那条「不把旧索引当当前事实」要靠它落地：每条检索结果都绑定一个
commit SHA，SHA 变了就说明结果是旧的。

对没有 git 的工作区返回 `tracked=False`，而不是报错 —— 产物目录默认不是仓库，
这很正常。
"""

from __future__ import annotations

import logging
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)

# git 命令很短，不该拖。超了说明仓库有问题，按「拿不到状态」处理。
_GIT_TIMEOUT = 10


@dataclass(frozen=True)
class GitEpoch:
    """某个工作区在某个时刻的版本基准。"""

    tracked: bool
    sha: str = ""
    branch: str = ""
    # 已跟踪文件的未提交改动
    dirty: bool = False
    changed_files: list[str] = field(default_factory=list)
    error: str = ""

    def to_dict(self) -> dict:
        return {
            "tracked": self.tracked,
            "sha": self.sha,
            "branch": self.branch,
            "dirty": self.dirty,
            "changedFiles": self.changed_files,
            "error": self.error,
        }

    @property
    def label(self) -> str:
        """放进 Context Projection 里给人看的短标识。"""
        if not self.tracked:
            return "untracked"
        suffix = "+dirty" if self.dirty else ""
        return f"{self.sha[:12]}{suffix}"


def snapshot(workspace: Path | str) -> GitEpoch:
    """取工作区当前的 git 状态。"""
    cwd = Path(workspace)
    if not cwd.is_dir():
        return GitEpoch(tracked=False, error="工作区不存在")

    # 先确认在不在仓库里 —— 不在就没必要跑后面几条
    inside = _git(cwd, "rev-parse", "--is-inside-work-tree")
    if inside.returncode != 0 or inside.stdout.strip() != "true":
        return GitEpoch(tracked=False)

    sha = _git(cwd, "rev-parse", "HEAD")
    branch = _git(cwd, "rev-parse", "--abbrev-ref", "HEAD")
    status = _git(cwd, "status", "--porcelain")

    changed: list[str] = []
    if status.returncode == 0:
        for line in status.stdout.splitlines():
            # porcelain 格式：XY <path>，取后 3 个字符起
            path = line[3:].strip()
            if path:
                changed.append(path)

    return GitEpoch(
        tracked=True,
        sha=sha.stdout.strip() if sha.returncode == 0 else "",
        branch=branch.stdout.strip() if branch.returncode == 0 else "",
        dirty=bool(changed),
        changed_files=changed[:200],
    )


def _git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    """跑一条 git 命令。失败不抛异常，交给调用方看 returncode。"""
    try:
        return subprocess.run(
            ["git", *args],
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=_GIT_TIMEOUT,
            stdin=subprocess.DEVNULL,
        )
    except FileNotFoundError:
        return subprocess.CompletedProcess(args, returncode=127, stdout="", stderr="git 未安装")
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, returncode=124, stdout="", stderr="git 超时")
    except Exception as exc:  # noqa: BLE001
        logger.debug("git 执行失败：%s", exc)
        return subprocess.CompletedProcess(args, returncode=1, stdout="", stderr=str(exc))
