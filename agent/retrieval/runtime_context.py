"""工具读取本轮运行上下文。

工具函数拿不到 agent 的 config，但需要知道「当前工作区在哪」。
一个进程同时只跑一轮（进程池保证），所以用 contextvar 传就够了 ——
不需要把 workspace 塞进每个工具的签名里。

**不要在这里放权限判断。** 权限在执行侧（backend）验证，这里只是取值。
参考项目踩过的坑：把「只读任务」的判断放在工具里，结果有一条路径绕过去了。
"""

from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass
from pathlib import Path

from retrieval.search_trace import EvidenceLedger, SearchTraceLog


@dataclass
class RunContext:
    """本轮 run 的上下文。"""

    run_id: str
    app_id: str
    workspace: Path
    trace_log: SearchTraceLog
    ledger: EvidenceLedger

    def as_dict(self) -> dict:
        return {"runId": self.run_id, "appId": self.app_id, "workspace": str(self.workspace)}


_context: ContextVar[RunContext | None] = ContextVar("agent_run_context", default=None)


def set_context(context: RunContext) -> None:
    _context.set(context)


def clear_context() -> None:
    _context.set(None)


def current() -> RunContext:
    """取本轮上下文。没设置就抛 —— 静默返回 None 会让工具悄悄失效。"""
    ctx = _context.get()
    if ctx is None:
        raise RuntimeError("运行上下文未设置：工具不该在 run 之外被调用")
    return ctx
