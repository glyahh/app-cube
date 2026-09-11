"""分层运行预算。

模型调用次数由 `ModelCallLimitMiddleware` 管；工具次数与耗时由这里管 ——
两者管的东西不同，不能合并。

**当前实现的边界**：耗时检查发生在事件到达时。如果 agent 卡在一个不发事件的
调用上，这里不会触发。外层必须有硬超时兜底（Java 侧的 runDeadline）。
"""

from __future__ import annotations

import time
from dataclasses import dataclass

# 按任务类型给预算。只读任务给得紧，写入任务给得松。
TASK_KIND_LIMITS: dict[str, tuple[int, int]] = {
    "qa": (60, 600),
    "inspect": (30, 300),
    "sync": (40, 300),
    "analysis": (120, 900),
    "planning": (120, 900),
    "review": (180, 1200),
    "coding": (300, 1800),
}

DEFAULT_TOOL_CALLS = 240
DEFAULT_SECONDS = 1800


class RunLimitExceeded(RuntimeError):
    """超出预算。不是崩溃 —— 是设计好的止损。"""


@dataclass
class RunLimits:
    max_tool_calls: int
    max_seconds: int
    task_kind: str = "default"

    @classmethod
    def for_task(cls, task_kind: str | None) -> "RunLimits":
        calls, seconds = TASK_KIND_LIMITS.get(task_kind or "", (DEFAULT_TOOL_CALLS, DEFAULT_SECONDS))
        return cls(max_tool_calls=calls, max_seconds=seconds, task_kind=task_kind or "default")


class RunLimitTracker:
    """数工具调用与耗时。由事件消费层驱动。"""

    def __init__(self, limits: RunLimits | None = None) -> None:
        self.limits = limits or RunLimits.for_task(None)
        self._tool_calls = 0
        self._started = time.monotonic()

    @property
    def tool_calls(self) -> int:
        return self._tool_calls

    @property
    def elapsed_seconds(self) -> float:
        return time.monotonic() - self._started

    def record_tool_call(self) -> None:
        """记一次工具调用。超预算抛 RunLimitExceeded。"""
        self._tool_calls += 1
        if self._tool_calls > self.limits.max_tool_calls:
            raise RunLimitExceeded(
                f"工具调用超过上限（{self.limits.max_tool_calls} 次，task_kind={self.limits.task_kind}）"
            )

    def check_deadline(self) -> None:
        """检查耗时。在事件边界调用。"""
        if self.elapsed_seconds > self.limits.max_seconds:
            raise RunLimitExceeded(
                f"运行超过时限（{self.limits.max_seconds} 秒，task_kind={self.limits.task_kind}）"
            )
