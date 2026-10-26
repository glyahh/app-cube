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
import contextlib
import json
import logging
import re
import sys
import threading
import time
from typing import Any

from core.events import Emitter, ErrorCode, Event
from core.masking import mask
from core.middleware import RunLimitExceeded, RunLimits, RunLimitTracker
from core.tool_permissions import ToolPermissionStopped

logger = logging.getLogger(__name__)

def _prompt_summary(messages: list[dict]) -> str:
    text = str((messages[-1] if messages else {}).get("content", "")).strip()
    # 只取第一行：_build_prompt 在首轮会把「应用设定」接在后面，
    # 整段塞进 summary 会和 details 重复显示。
    text = " ".join(text.split("\n", 1)[0].split())
    return f"正在分析：{text[:80]}" if text else "等待本轮需求"

# 工具输出预览的长度上限。太长了会把事件流撑爆，前端也不需要。
PREVIEW_CHARS = 500

# 静默多久补一条心跳。比 Java 的读超时小一个数量级，
# 让读超时只反映真实故障，而不是推理模型想得久。
_KEEPALIVE_SECONDS = 15.0

# 会改动工作区的工具；只有成功执行才计入本轮变更。
_WRITE_TOOLS = {"write", "write_file", "edit", "edit_file", "delete", "delete_file"}

_loop: asyncio.AbstractEventLoop | None = None


class ImportEditStopped(RuntimeError):
    """导入止损原因只使用固定错误码。"""

    def __init__(self, failure_key):
        self.failure_key = failure_key
        super().__init__(failure_key)

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
    import_edit: bool = False,
) -> dict:
    """跑一轮，事件直接写进 emitter。返回本轮摘要信息。

    `overhead` 是固定开销估计器。每次模型调用结束后用真实 usage 校正它 ——
    system prompt 与工具 schema 不在 messages 里，但它确实占窗口。
    """
    limits = RunLimits.for_task(task_kind)
    tracker = RunLimitTracker(limits)
    state = _RunState(import_edit=import_edit)
    # 每轮都先建立一个面向用户的阶段，即使模型最终不调用工具也有可见进度。
    initial_id = f"{emitter._run_id}:thinking:initial"
    state.current_thinking_id = initial_id
    state.current_thinking_summary = _prompt_summary(messages)
    state.request_summary = state.current_thinking_summary
    # details 只放应用设定：本轮需求已经在 summary 里，
    # 整段 prompt 再列一次会让同一个思考块出现两遍应用设定。
    content = str((messages[-1] if messages else {}).get("content", ""))
    init_detail = ""
    if "\n应用设定：" in content:
        init_detail = "应用设定：" + content.split("\n应用设定：", 1)[1].strip()
    emitter.emit(Event.THINKING_STARTED, thinkingId=initial_id,
                 summary=state.current_thinking_summary, details=init_detail[:500], status="running")
    config = {
        "configurable": {"thread_id": thread_id},
        # LangGraph 的 recursion_limit 按 super-step 计，一次模型调用加一次工具调用
        # 大约占 2 步。默认值 25 对带工具调用的 agent 远远不够 ——
        # 实测会撞在「写了文件但还没收尾」的位置，前功尽弃。
        # 这里给足余量；真正的模型调用上限由 ModelCallLimitMiddleware 管。
        "recursion_limit": max(200, limits.max_tool_calls * 4),
    }

    from core.model_timing import bind_run
    with bind_run(emitter._run_id), _keepalive(emitter) as beat:
        return _run_locked(agent, messages, config, emitter, tracker, state, overhead,
                           import_edit, beat)


def _run_locked(agent, messages, config, emitter, tracker, state, overhead, import_edit, beat):
    try:
        _event_loop().run_until_complete(
            _drive(agent, messages, config, emitter, tracker, state, overhead, beat)
        )
    except ImportEditStopped as exc:
        _complete_thinking(emitter, state, status="failed")
        emitter.emit(Event.ERROR, code=ErrorCode.TOOL_EXECUTION_FAILED.value, message="同类命令已被拒绝两次，本轮已停止。", retryable=False,
                      stage="IMPORT_EDIT_MODEL_RUN", failureKey=exc.failure_key)
        return {**state.summary(status="failed"), "failureKey": exc.failure_key}
    except RunLimitExceeded as exc:
        # 预算是设计好的止损，不是崩溃
        logger.warning("触及运行预算：%s", exc)
        _complete_thinking(emitter, state, status="failed")
        emitter.error(ErrorCode.RUN_DEADLINE_EXCEEDED, str(exc), retryable=False)
        return state.summary(status="failed")
    except ToolPermissionStopped as exc:
        # 审批超时、命令执行失败等：本轮停止，但必须给用户一句明确的收尾。
        code = str(exc)
        if code == 'TOOL_PERMISSION_TIMEOUT':
            text = "等待审批超时，本轮已暂停。"
        elif code in ('TOOL_PERMISSION_EXECUTION_FAILED', 'TOOL_PERMISSION_UNCONFIRMED'):
            text = "命令执行未完成，本轮已停止；已完成的文件改动保留。"
        else:
            text = "本轮已停止。"
        logger.warning("工具许可终止本轮：%s", code)
        _complete_thinking(emitter, state, status="failed")
        state.text = text
        # 完整收尾是展示出口的确认边界；不能只发会被终态丢弃的 delta。
        emitter.emit(Event.TEXT_MESSAGE, messageId=state.message_id, content=text)
        status = "cancelled" if code in ('TOOL_PERMISSION_REJECTED', 'TOOL_PERMISSION_CANCELLED', 'TOOL_PERMISSION_REVOKED') else "failed"
        summary = state.summary(status=status)
        summary["text"] = text
        return summary
    except Exception as exc:
        _complete_thinking(emitter, state, status="failed")
        if import_edit:
            from langchain.agents.middleware.model_call_limit import ModelCallLimitExceededError
            if isinstance(exc, ModelCallLimitExceededError):
                key = "IMPORT_EDIT_MODEL_BUDGET_EXHAUSTED"
                emitter.emit(Event.ERROR, code=ErrorCode.MODEL_QUOTA_EXCEEDED.value, message="模型调用预算已耗尽，本轮未完成修改。", retryable=False,
                              stage="IMPORT_EDIT_MODEL_RUN", failureKey=key)
                return {**state.summary(status="failed"), "failureKey": key}
        raise

    _complete_thinking(emitter, state)

    return state.summary(status="succeeded")


class _RunState:
    """一轮里要攒的东西。"""

    def __init__(self, *, import_edit=False) -> None:
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
        self.import_edit = import_edit
        self.command_rejections: dict[str, int] = {}
        # 单次模型调用的起点，用于定位「为什么这一轮等这么久」。
        self.model_started_at = 0.0
        # 工具入参按 call_id 暂存，结束时才能拿到路径。
        self.tool_arguments: dict[str, Any] = {}
        # 本轮成功写/改/删的路径，用于在没有最终文本时给出一句事实收尾。
        self.changed_paths: list[str] = []
        self.command_failures = 0

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


@contextlib.contextmanager
def _keepalive(emitter: Emitter, interval: float = _KEEPALIVE_SECONDS):
    """整轮期间的心跳看门狗。

    Java 侧按行阻塞读，读超时按「进程卡住」处理。推理模型一段思考可能
    几十秒不出事件，建图与恢复 checkpoint 也要十几秒 —— 光靠业务事件
    保活不够，这里在静默超过 interval 时补一条 heartbeat。

    Emitter.emit 已加锁，跨线程写协议安全。
    """
    state = {"last": time.monotonic(), "stop": threading.Event()}

    def watch() -> None:
        while not state["stop"].wait(interval / 2):
            if time.monotonic() - state["last"] < interval:
                continue
            try:
                emitter.emit(Event.HEARTBEAT)
            except Exception:
                logger.exception("心跳发送失败，停止保活")
                return
            state["last"] = time.monotonic()

    thread = threading.Thread(target=watch, name="agent-keepalive", daemon=True)
    thread.start()
    try:
        yield state
    finally:
        state["stop"].set()
        thread.join(timeout=2)


async def _drive(agent, messages, config, emitter: Emitter, tracker: RunLimitTracker,
                 state: _RunState, overhead=None, beat=None) -> None:
    payload = {"messages": messages}
    async for ev in agent.astream_events(payload, config, version="v2"):
        kind = ev.get("event")
        tracker.check_deadline()
        # 有真实事件就把心跳推迟，避免与业务事件挤在同一秒。
        if beat is not None:
            beat["last"] = time.monotonic()

        if kind == "on_chat_model_stream":
            _on_model_stream(ev, emitter, state)
        elif kind == "on_chat_model_start":
            _on_model_start(ev, state, emitter)
        elif kind == "on_tool_start":
            _on_tool_start(ev, emitter, tracker, state)
        elif kind == "on_tool_end":
            _on_tool_end(ev, emitter, state)
        elif kind == "on_tool_error":
            _on_tool_error(ev, emitter, state)
        elif kind == "on_chat_model_end":
            _on_model_end(ev, state, overhead, emitter)

    # 整轮结束：把累积的正文作为终稿发一次，前端可以据此纠偏
    if state.text:
        emitter.emit(Event.TEXT_MESSAGE, messageId=state.message_id, content=state.text)
    elif state.tool_calls:
        # 模型没给终稿：用本轮实际执行结果补一句，避免前端只剩空占位。
        state.text = _fallback_summary(state)
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
    state.model_started_at = time.monotonic()
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
    input_tokens = 0
    output_tokens = 0
    if isinstance(usage, dict):
        input_tokens = int(usage.get("input_tokens") or 0)
        state.input_tokens += input_tokens
        output_tokens = int(usage.get("output_tokens") or 0)
        state.output_tokens += output_tokens

        # 用真实用量校正固定开销估计。不校正的话压缩触发点永远到不了 ——
        # 表现就是「压缩配了但没生效」。
        if overhead is not None and input_tokens > 0 and state.last_message_tokens > 0:
            overhead.observe(input_tokens, state.last_message_tokens)

    # 一段模型输出结束 = 一个 assistant 段落结束，下一段用新的 messageId
    state.segment += 1
    if state.model_started_at:
        logger.info("model call #%d ms=%d inputTokens=%d outputTokens=%d cumulativeInputTokens=%d cumulativeOutputTokens=%d", state.model_calls,
                    (time.monotonic() - state.model_started_at) * 1000,
                    input_tokens, output_tokens, state.input_tokens, state.output_tokens)
        state.model_started_at = 0.0


def _on_tool_start(ev: dict, emitter: Emitter, tracker: RunLimitTracker, state: _RunState) -> None:
    tracker.record_tool_call()
    state.tool_calls += 1

    call_id = str(ev.get("run_id") or "")
    name = str(ev.get("name") or "unknown")
    args = (ev.get("data") or {}).get("input")
    state.tool_arguments[call_id] = args
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
    artifact = getattr(output, "artifact", None)
    exit_code = artifact.get("exit_code") if name == "execute" and isinstance(artifact, dict) else None
    if type(exit_code) is not int:
        exit_code = None
    failed = getattr(output, "status", None) == "error" or (exit_code is not None and exit_code != 0)
    if name == "execute" and failed:
        state.command_failures += 1

    started = state.tool_started_at.pop(call_id, None)
    duration_ms = int((time.monotonic() - started) * 1000) if started else 0

    text = _stringify(output)
    truncated = len(text) > PREVIEW_CHARS
    # 只识别本项目定义的拒绝协议，不从任意工具输出猜异常原因。
    rejection = None
    if state.import_edit and name == "execute" and getattr(output, "status", None) == "error":
        match = re.match(r"Error: Invalid parameter\. IMPORT_COMMAND_REJECTED:(EMPTY|QUOTING|OPERATORS|SUBSTITUTION|EXECUTABLE|DANGEROUS|PATH)。", str(getattr(output, "content", "")))
        if match:
            rejection = match.group(1)

    emitter.emit(
        Event.TOOL_FINISHED,
        callId=call_id,
        toolName=name,
        status="failed" if failed else "succeeded",
        **({"exitCode": exit_code} if exit_code is not None else {}),
        durationMs=duration_ms,
        outputPreview=mask(text, PREVIEW_CHARS),
        truncated=truncated,
        **({"failureKey": "IMPORT_COMMAND_REJECTED", "rejectionReason": rejection} if rejection else {}),
    )
    args_status = "失败" if failed else "已返回"
    # 记录本轮真实改动；模型没写收尾时用它生成事实性总结。
    tool_args = state.tool_arguments.pop(call_id, None)
    if getattr(output, "status", None) != "error" and name in _WRITE_TOOLS:
        path = _tool_path(tool_args)
        if path and path not in state.changed_paths:
            state.changed_paths.append(path)
    state.last_observation = f"{name} 的{args_status}结果"
    _count_command_rejection(state, rejection)


def _count_command_rejection(state, rejection):
    if rejection:
        state.command_rejections[rejection] = state.command_rejections.get(rejection, 0) + 1
        if state.command_rejections[rejection] >= 2:
            raise ImportEditStopped("IMPORT_EDIT_REPEATED_COMMAND_REJECTION")


def _on_tool_error(ev: dict, emitter: Emitter, state: _RunState) -> None:
    """框架层异常（绕过 ToolErrorMiddleware）时补发 tool.finished。"""
    call_id = str(ev.get("run_id") or "")
    state.tool_arguments.pop(call_id, None)
    name = str(ev.get("name") or "unknown")
    error = (ev.get("data") or {}).get("error")
    from backends.permissions import ImportCommandRejected
    rejection = error.reason if state.import_edit and name == "execute" and isinstance(error, ImportCommandRejected) else None

    started = state.tool_started_at.pop(call_id, None)
    duration_ms = int((time.monotonic() - started) * 1000) if started else 0

    error_text = mask(str(error)[:600], PREVIEW_CHARS) if error else ""

    emitter.emit(
        Event.TOOL_FINISHED,
        callId=call_id,
        toolName=name,
        status="failed",
        durationMs=duration_ms,
        outputPreview=error_text,
        truncated=False,
        **({"failureKey": "IMPORT_COMMAND_REJECTED", "rejectionReason": rejection} if rejection else {}),
    )
    state.last_observation = f"{name} 的失败结果"
    _count_command_rejection(state, rejection)


def _tool_path(args: Any) -> str | None:
    """从工具入参里取相对路径；“不同工具的参数名不一样”。"""
    if not isinstance(args, dict):
        return None
    for key in ("path", "file_path", "relativeFilePath"):
        value = args.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip().replace("\\", "/")
    return None


def _fallback_summary(state: _RunState) -> str:
    """模型没有收尾文本时，用本轮实际执行结果补一句。

    不复用 `state.candidate`：那是「接下来要做什么」的行动说明，
    当成「已经完成什么」的总结会误导。
    """
    failure = f"其中 {state.command_failures} 次命令失败，请查看执行结果；未确认相关检查通过。" if state.command_failures else ""
    if state.changed_paths:
        names = "、".join(state.changed_paths[:3])
        suffix = f" 等 {len(state.changed_paths)} 个文件" if len(state.changed_paths) > 3 else ""
        return f"本轮已更新 {names}{suffix}，共执行 {state.tool_calls} 次操作。" + failure
    return f"本轮执行了 {state.tool_calls} 次工具调用，未改动文件。" + failure


def _publish_progress(emitter: Emitter, state: _RunState) -> None:    # 只使用面向用户的行动说明，不读取 reasoning_content 等内部推理字段。
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
