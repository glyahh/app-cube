"""压缩的两处接入点。

- **第 0 层**挂在 `wrap_tool_call` 上：工具输出进上下文**之前**约束，成本只付一次。
- **第 1~3 层**挂在 `before_model` 上：每次模型调用前检查占用，超阈值才压。

分开挂是有意的：第 0 层是入口约束，与「桌子快满了」无关；后三层是占用水位
驱动的。混在一起会导致每一轮都跑一遍判断，白花时间。

**决策点后移**：压缩切点只能在触发那一刻定（那时才知道要保留多长的尾部）。
所以这里不做任何预计算。
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Callable

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from core.compaction import _SUMMARY_PROMPT, OverheadEstimator, Thresholds, apply_layer0, compact

logger = logging.getLogger(__name__)


def make_summarizer(model, *, temperature: float = 0.0) -> Callable[[str, int], str]:
    """把模型包成 `(待压缩文本, 预算) -> 摘要`。

    摘要模型必须与主模型同源或更强 —— 容量小于主模型时会吃 context-length
    错误并返回空，被压缩的那段就「无摘要丢弃」了。
    """

    def summarize(content: str, budget: int) -> str:
        prompt = _SUMMARY_PROMPT.format(content=content)
        response = model.invoke(
            [SystemMessage(content="你在做对话压缩。只输出摘要正文。"),
             HumanMessage(content=prompt)],
            max_tokens=max(512, min(int(budget), 8000)),
            temperature=temperature,
        )
        return response.content if isinstance(response.content, str) else str(response.content)

    return summarize


class ToolOutputBudgetMiddleware(AgentMiddleware):
    """第 0 层：工具输出进历史之前先过预算。

    **绝不动已经在上下文里的东西** —— 这一层只在入口工作。
    """

    def __init__(self, *, char_budget: int, spill_threshold: int, spill_dir: Path):
        self.char_budget = char_budget
        self.spill_threshold = spill_threshold
        self.spill_dir = spill_dir
        self.stats = {"spilled": 0, "truncated": 0}

    def wrap_tool_call(self, request, handler):
        return self._budget(handler(request))

    async def awrap_tool_call(self, request, handler):
        return self._budget(await handler(request))

    def _budget(self, message):
        # 不是 ToolMessage（比如 Command）就原样放过
        if not isinstance(message, ToolMessage):
            return message
        text = message.content if isinstance(message.content, str) else str(message.content or "")
        result = apply_layer0(
            text,
            char_budget=self.char_budget,
            spill_threshold=self.spill_threshold,
            spill_dir=self.spill_dir,
            label=str(getattr(message, "name", None) or "tool"),
        )
        if result.content == text:
            return message

        if result.spilled:
            self.stats["spilled"] += 1
        else:
            self.stats["truncated"] += 1
        return message.model_copy(update={"content": result.content})


class CompactionMiddleware(AgentMiddleware):
    """第 1~3 层：占用水位驱动，超阈值才压。

    铁律由 `core.compaction.compact` 内部保证（不许越层、不许倒序、不许碰不该碰的），
    这里只负责触发时机与留痕。
    """

    def __init__(self, *, window: int, overhead: OverheadEstimator | None = None,
                 summarize: Callable[[str, int], str] | None = None):
        self.window = window
        self.overhead = overhead or OverheadEstimator()
        self.summarize = summarize
        self.stats: dict[str, Any] = {
            "compactions": 0,
            "lastLayer": "none",
            "lastBeforeTokens": 0,
            "lastAfterTokens": 0,
            "notes": [],
        }

    def before_model(self, state, runtime):
        messages = list(state.get("messages") or [])
        if not messages:
            return None

        # 窗口里除了消息还有 system prompt 与工具 schema，它们不在 messages 里。
        # 不算进去的话触发点永远到不了 —— 表现就是「压缩配了但没生效」。
        result = compact(
            messages,
            self.window,
            summarize=self.summarize,
            overhead=self.overhead.current(),
        )
        if not result.changed:
            return None

        self.stats["compactions"] += 1
        self.stats["lastLayer"] = result.layer
        self.stats["lastBeforeTokens"] = result.before_tokens
        self.stats["lastAfterTokens"] = result.after_tokens
        self.stats["notes"] = result.notes

        # 留痕：压了哪层、前后多少、丢了什么
        logger.info(
            "上下文压缩 layer=%s tokens=%d→%d（固定开销 %d）%s",
            result.layer, result.before_tokens, result.after_tokens,
            self.overhead.current(),
            "；".join(result.notes),
        )
        return {"messages": result.messages}

    async def abefore_model(self, state, runtime):
        return self.before_model(state, runtime)

    def summary(self) -> dict:
        return dict(self.stats)


def thresholds_for(window: int) -> Thresholds:
    """给外部（观测 / 测试）拿阈值的便捷入口。"""
    return Thresholds(window)
