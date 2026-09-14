"""驱动 agent 并把 LangGraph 事件转成产品事件。

**适配层存在的意义**：框架升级只改这里，事件协议不变、前端不用动。

用 `astream_events(version="v2")` 而不是 raw 协议事件 —— v2 是稳定的公开 API，
给出 `on_chat_model_stream` / `on_tool_start` / `on_tool_end` 这些语义化事件，
不必自己去解析 content-block-delta 那一层。

事件循环：**进程内只建一个**，所有 run 提交到它上面跑。每次 run 新建一个
`asyncio.run` 会让 checkpointer 这类跨 run 的对象在不同 loop 之间漂移。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import sys
import time
from typing import Any

from core.events import Emitter, ErrorCode, Event
from core.masking import mask
from core.middleware import RunLimitExceeded, RunLimits, RunLimitTracker

logger = logging.getLogger(__name__)

def _prompt_summary(messages: list[dict]) -> str:
    text = str((messages[-1] if messages else {}).get("content", "")).strip()
    text = " ".join(text.split())
    return f"正在分析：{text[:80]}" if text else "等待本轮需求"

# 工具输出预览的长度上限。太长了会把事件流撑爆，前端也不需要。
PREVIEW_CHARS = 500

_loop: asyncio.AbstractEventLoop | None = None

# psycopg 的异步驱动不支持 Windows ProactorEventLoop；统一使用 Selector。
# 必须在创建本模块事件循环前设置，避免连接池拿到不兼容的 loop。
if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())


def _event_loop() -> asyncio.AbstractEventLoop:
    global _loop
    if _loop is None or _loop.is_closed():
        _loop = asyncio.new_event_loop()
        asyncio.set_event_loop(_loop)
    return _loop


def run_agent_with_events(
    *,
    agent: Any,
    messages: list[dict],
    thread_id: str,
    emitter: Emitter,
    task_kind: str,
    overhead: Any = None,
) -> dict:
    """跑一轮，事件直接写进 emitter。返回本轮摘要信息。

    `overhead` 是固定开销估计器。每次模型调用结束后用真实 usage 校正它 ——
    system prompt 与工具 schema 不在 messages 里，但它确实占窗口。
    """
    limits = RunLimits.for_task(task_kind)
    tracker = RunLimitTracker(limits)
    state = _RunState()
    # 每轮都先建立一个面向用户的阶段，即使模型最终不调用工具也有可见进度。
    initial_id = f"{emitter._run_id}:thinking:initial"
    state.current_thinking_id = initial_id
    state.current_thinking_summary = _prompt_summary(messages)
    state.request_summary = state.current_thinking_summary
    emitter.emit(Event.THINKING_STARTED, thinkingId=initial_id,
                 summary=state.current_thinking_summary, details="本轮需求：" + str((messages[-1] if messages else {}).get("content", ""))[:500], status="running")
    config = {
        "configurable": {"thread_id": thread_id},
        # LangGraph 的 recursion_limit 按 super-step 计，一次模型调用加一次工具调用
        # 大约占 2 步。默认值 25 对带工具调用的 agent 远远不够 ——
        # 实测会撞在「写了文件但还没收尾」的位置，前功尽弃。
        # 这里给足余量；真正的模型调用上限由 ModelCallLimitMiddleware 管。
        "recursion_limit": max(200, limits.max_tool_calls * 4),
    }

    try:
        _event_loop().run_until_complete(
            _drive(agent, messages, config, emitter, tracker, state, overhead)
        )
    except RunLimitExceeded as exc:
        # 预算是设计好的止损，不是崩溃
        logger.warning("触及运行预算：%s", exc)
        _complete_thinking(emitter, state, status="failed")
        emitter.error(ErrorCode.RUN_DEADLINE_EXCEEDED, str(exc), retryable=False)
        return state.summary(status="failed")
    except Exception:
        _complete_thinking(emitter, state, status="failed")
        raise

    _complete_thinking(emitter, state)

    return state.summary(status="succeeded")


class _RunState:
    """一轮里要攒的东西。"""

    def __init__(self) -> None:
        self.text = ""
        self.candidate = ""
        self.candidate_overflow = False
        self.published_progress = ""
        self.segment = 0
        self.tool_started_at: dict[str, float] = {}
        self.tool_calls = 0
        self.current_thinking_id = ""
        self.current_thinking_summary = ""
        self.request_summary = ""
        self.last_observation = ""
        self.model_calls = 0
        self.input_tokens = 0
        self.output_tokens = 0
        # 最近一次请求里我们能数到的消息 token，用于反推固定开销
        self.last_message_tokens = 0

    @property
    def message_id(self) -> str:
        return f"a{self.segment}"

    def summary(self, *, status: str) -> dict:
        return {
            "status": status,
            "text": self.text,
            "usage": {
                "inputTokens": self.input_tokens,
                "outputTokens": self.output_tokens,
                "modelCalls": self.model_calls,
                "toolCalls": self.tool_calls,
            },
        }


async def _drive(agent, messages, config, emitter: Emitter, tracker: RunLimitTracker,
                 state: _RunState, overhead=None) -> None:
    payload = {"messages": messages}
    async for ev in agent.astream_events(payload, config, version="v2"):
        kind = ev.get("event")
        tracker.check_deadline()

        if kind == "on_chat_model_stream":
            _on_model_stream(ev, emitter, state)
        elif kind == "on_chat_model_start":
            _on_model_start(ev, state, emitter)
        elif kind == "on_tool_start":
            _on_tool_start(ev, emitter, tracker, state)
        elif kind == "on_tool_end":
            _on_tool_end(ev, emitter, state)
        elif kind == "on_chat_model_end":
            _on_model_end(ev, state, overhead, emitter)

    # 整轮结束：把累积的正文作为终稿发一次，前端可以据此纠偏
    if state.text:
        emitter.emit(Event.TEXT_MESSAGE, messageId=state.message_id, content=state.text)


def _on_model_stream(ev: dict, emitter: Emitter, state: _RunState) -> None:
    chunk = (ev.get("data") or {}).get("chunk")
    if chunk is None:
        return
    tool_chunks = chunk.get("tool_call_chunks") if isinstance(chunk, dict) else getattr(chunk, "tool_call_chunks", None)
    if tool_chunks:
        # 工具参数开始输出时，前面的行动说明已可识别；无需等整份文件生成完。
        _publish_progress(emitter, state)
    content = _chunk_text(chunk)
    if not content:
        # 模型在调工具时会吐出大量空内容块，不要把它们当增量发出去
        return
    # 调用结束后才能知道这段话是否伴随工具操作，不提前公开过程描述。
    if state.candidate_overflow:
        return
    if len(state.candidate) + len(content) <= 65536:
        state.candidate += content
    else:
        state.candidate = "部分内部执行信息已省略"
        state.candidate_overflow = True


def _on_model_start(ev: dict, state: _RunState, emitter: Emitter | None = None) -> None:
    """记下这次请求里「我们自己能数到的」消息 token。

    模型调用结束后拿供应商回的真实 input_tokens 减掉它，差额就是固定开销
    （system prompt + 工具 schema）—— 那部分不在 messages 里，但确实占窗口。
    """
    if emitter is not None and not state.current_thinking_id:
        state.current_thinking_id = f"{emitter._run_id}:thinking:model:{state.model_calls + 1}"
        state.current_thinking_summary = state.request_summary
        if state.last_observation:
            state.current_thinking_summary = f"正在结合{state.last_observation}继续分析"
        emitter.emit(Event.THINKING_STARTED, thinkingId=state.current_thinking_id,
                     summary=state.current_thinking_summary, details=state.last_observation, status="running")
    state.candidate = ""
    state.candidate_overflow = False
    state.published_progress = ""
    from core.compaction import estimate_tokens

    data = ev.get("data") or {}
    payload = data.get("input")
    messages = None
    if isinstance(payload, dict):
        messages = payload.get("messages")
    elif isinstance(payload, (list, tuple)):
        messages = payload
    if not messages:
        return

    # 载荷可能是 [[msg, msg, ...]] 这种批量嵌套形状，拍平再数
    flat = _flatten_messages(messages)
    total = 0
    for message in flat:
        content = getattr(message, "content", None)
        if content is None and isinstance(message, dict):
            content = message.get("content")
        if isinstance(content, str):
            total += estimate_tokens(content)
        elif isinstance(content, list):
            total += estimate_tokens(
                "".join(str(b.get("text") or "") if isinstance(b, dict) else str(b) for b in content)
            )
    state.last_message_tokens = total


def _flatten_messages(items) -> list:
    """把嵌套的消息列表拍平。"""

    def walk(node):
        if isinstance(node, (list, tuple)):
            for child in node:
                yield from walk(child)
        elif node is not None:
            yield node

    return list(walk(items))


def _on_model_end(ev: dict, state: _RunState, overhead=None, emitter: Emitter | None = None) -> None:
    state.model_calls += 1
    output = (ev.get("data") or {}).get("output")
    tool_calls = getattr(output, "tool_calls", None)
    if isinstance(output, dict):
        tool_calls = output.get("tool_calls")
    if tool_calls and emitter is not None and state.candidate.strip():
        _publish_progress(emitter, state)
    if emitter is not None:
        _complete_thinking(emitter, state)
    # 工具调用前的说明只用于内部执行；最后一次无工具调用的回答才作为终稿。
    state.text = "" if tool_calls else state.candidate
    usage = getattr(output, "usage_metadata", None) if output is not None else None
    if isinstance(usage, dict):
        input_tokens = int(usage.get("input_tokens") or 0)
        state.input_tokens += input_tokens
        state.output_tokens += int(usage.get("output_tokens") or 0)

        # 用真实用量校正固定开销估计。不校正的话压缩触发点永远到不了 ——
        # 表现就是「压缩配了但没生效」。
        if overhead is not None and input_tokens > 0 and state.last_message_tokens > 0:
            overhead.observe(input_tokens, state.last_message_tokens)

    # 一段模型输出结束 = 一个 assistant 段落结束，下一段用新的 messageId
    state.segment += 1


def _on_tool_start(ev: dict, emitter: Emitter, tracker: RunLimitTracker, state: _RunState) -> None:
    tracker.record_tool_call()
    state.tool_calls += 1

    call_id = str(ev.get("run_id") or "")
    name = str(ev.get("name") or "unknown")
    args = (ev.get("data") or {}).get("input")
    state.tool_started_at[call_id] = time.monotonic()

    emitter.emit(
        Event.TOOL_STARTED,
        callId=call_id,
        toolName=name,
        inputPreview=mask(_stringify(args), PREVIEW_CHARS),
        inputDigest=_digest(args),
    )


def _on_tool_end(ev: dict, emitter: Emitter, state: _RunState) -> None:
    call_id = str(ev.get("run_id") or "")
    name = str(ev.get("name") or "unknown")
    output = (ev.get("data") or {}).get("output")

    started = state.tool_started_at.pop(call_id, None)
    duration_ms = int((time.monotonic() - started) * 1000) if started else 0

    text = _stringify(output)
    truncated = len(text) > PREVIEW_CHARS

    emitter.emit(
        Event.TOOL_FINISHED,
        callId=call_id,
        toolName=name,
        status="failed" if getattr(output, "status", None) == "error" else "succeeded",
        durationMs=duration_ms,
        outputPreview=mask(text, PREVIEW_CHARS),
        truncated=truncated,
    )
    args_status = "失败" if getattr(output, "status", None) == "error" else "已返回"
    state.last_observation = f"{name} 的{args_status}结果"


def _publish_progress(emitter: Emitter, state: _RunState) -> None:
    # 只使用面向用户的行动说明，不读取 reasoning_content 等内部推理字段。
    progress = state.candidate.strip()
    if not state.current_thinking_id or not progress or progress == state.published_progress:
        return
    state.published_progress = progress
    state.current_thinking_summary = re.split(r"(?<=[。！？\n])", progress, maxsplit=1)[0].strip()[:80]
    emitter.emit(Event.THINKING_DELTA, thinkingId=state.current_thinking_id,
                 summary=state.current_thinking_summary, details=progress[:4000], status="running")


def _complete_thinking(emitter: Emitter, state: _RunState, *, status: str = "completed") -> None:
    if state.current_thinking_id:
        emitter.emit(Event.THINKING_COMPLETED, thinkingId=state.current_thinking_id,
                     summary=state.current_thinking_summary, details="", status=status)
        state.current_thinking_id = ""


def _chunk_text(chunk: Any) -> str:
    """从 AIMessageChunk 里取纯文本。content 可能是 str，也可能是 block 列表。"""
    content = getattr(chunk, "content", None)
    if content is None and isinstance(chunk, dict):
        content = chunk.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("type") in ("text", "text_delta"):
                parts.append(str(block.get("text") or ""))
        return "".join(parts)
    return ""


def _stringify(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    # ToolMessage / AIMessage 这类对象优先取 content
    content = getattr(value, "content", None)
    if content is not None:
        return content if isinstance(content, str) else json.dumps(content, ensure_ascii=False, default=str)
    try:
        return json.dumps(value, ensure_ascii=False, default=str)
    except Exception:
        return str(value)


def _digest(value: Any) -> str:
    """参数的短摘要。放事件里给前端做展示，不占带宽。"""
    import hashlib

    return hashlib.sha256(_stringify(value).encode("utf-8", errors="replace")).hexdigest()[:12]
