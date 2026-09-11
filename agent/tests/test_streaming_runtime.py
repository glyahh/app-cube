"""事件映射的纯函数。

只测不依赖模型的部分 —— 事件解析的坑基本都在这几个转换函数里：
content 可能是字符串也可能是 block 列表；工具输出可能是对象、ToolMessage 或 dict。
"""

from __future__ import annotations

import pytest

from core.events import ErrorCode
from core.runtime import TaskKind, _classify_error, is_read_only, resolve_workspace
from core.streaming_runtime import PREVIEW_CHARS, _RunState, _chunk_text, _digest, _stringify


# ─────────────────────────── content 形状 ───────────────────────────

class _Chunk:
    def __init__(self, content):
        self.content = content


def test_chunk_text_from_plain_string():
    assert _chunk_text(_Chunk("你好")) == "你好"


def test_chunk_text_from_block_list():
    """新版本模型会把 content 拆成 block 列表。"""
    chunk = _Chunk([
        {"type": "text", "text": "前"},
        {"type": "text_delta", "text": "后"},
        {"type": "tool_call_chunk", "name": "ls"},   # 非文本块要忽略
    ])
    assert _chunk_text(chunk) == "前后"


def test_chunk_text_from_dict():
    assert _chunk_text({"content": "内容"}) == "内容"


def test_chunk_text_ignores_tool_only_chunks():
    """模型调工具时会吐大量空内容块 —— 不能把它们当文本增量发给前端。"""
    assert _chunk_text(_Chunk([])) == ""
    assert _chunk_text(_Chunk(None)) == ""
    assert _chunk_text(_Chunk([{"type": "tool_call_chunk", "name": "ls"}])) == ""


# ─────────────────────────── 工具输出形状 ───────────────────────────

class _ToolMessage:
    def __init__(self, content):
        self.content = content


def test_stringify_passes_through_string():
    assert _stringify("done") == "done"


def test_stringify_prefers_content_attribute():
    assert _stringify(_ToolMessage("文件已写入")) == "文件已写入"


def test_stringify_serializes_dict():
    out = _stringify({"path": "/a", "ok": True})
    assert "/a" in out and "true" in out


def test_stringify_none_is_empty():
    assert _stringify(None) == ""


def test_digest_is_stable_and_short():
    a = _digest({"x": 1})
    b = _digest({"x": 1})
    c = _digest({"x": 2})
    assert a == b
    assert a != c
    assert len(a) == 12


# ─────────────────────────── run 状态 ───────────────────────────

def test_segment_advances_message_id():
    state = _RunState()
    assert state.message_id == "a0"
    state.segment += 1
    assert state.message_id == "a1"


def test_summary_reports_real_counters():
    state = _RunState()
    state.text = "产出"
    state.model_calls = 3
    state.tool_calls = 2
    state.input_tokens = 100
    state.output_tokens = 20

    summary = state.summary(status="succeeded")
    assert summary["status"] == "succeeded"
    assert summary["text"] == "产出"
    assert summary["usage"]["modelCalls"] == 3, "模型调用数必须真实统计，不能写死"
    assert summary["usage"]["toolCalls"] == 2
    assert summary["usage"]["inputTokens"] == 100


def test_preview_budget_is_sane():
    assert 200 <= PREVIEW_CHARS <= 2000


# ─────────────────────────── 固定开销测量 ───────────────────────────

def test_on_model_start_handles_nested_message_list():
    """事件载荷是 [[msg, msg, ...]] 这种嵌套形状。

    不拍平的话取到的元素是 list 而不是消息对象，content 读出来是 None，
    固定开销就永远是 0 —— 表现是「压缩配了但从不触发」。
    """
    from core.streaming_runtime import _on_model_start

    state = _RunState()
    event = {"data": {"input": {"messages": [[{"content": "x" * 400}]]}}}

    _on_model_start(event, state)

    assert state.last_message_tokens > 0, "嵌套的消息列表必须被拍平"


def test_on_model_start_handles_flat_list():
    from core.streaming_runtime import _on_model_start

    state = _RunState()
    _on_model_start({"data": {"input": {"messages": [{"content": "y" * 400}]}}}, state)
    assert state.last_message_tokens > 0


def test_on_model_start_survives_missing_input():
    from core.streaming_runtime import _on_model_start

    state = _RunState()
    _on_model_start({}, state)
    _on_model_start({"data": {}}, state)
    assert state.last_message_tokens == 0


# ─────────────────────────── 任务分类 ───────────────────────────

@pytest.mark.parametrize("kind,readonly", [
    ("coding", False),
    ("analysis", True),
    ("planning", True),
    ("qa", True),
    ("inspect", True),
    ("review", True),
    # sync 会拉取/切换代码，会改动工作区，不算只读
    ("sync", False),
])
def test_read_only_classification(kind, readonly):
    assert is_read_only(TaskKind(kind)) is readonly


# ─────────────────────────── 错误归类 ───────────────────────────

def test_classifies_context_length_error():
    exc = Exception("This model's maximum context length is 65536 tokens")
    assert _classify_error(exc) == ErrorCode.MODEL_QUOTA_EXCEEDED


def test_classifies_rate_limit():
    assert _classify_error(Exception("Rate limit exceeded, 429")) == ErrorCode.MODEL_QUOTA_EXCEEDED


def test_classifies_timeout():
    assert _classify_error(TimeoutError("timed out")) == ErrorCode.RUN_DEADLINE_EXCEEDED


def test_classifies_connection_error():
    assert _classify_error(Exception("APIConnectionError: connection refused")) == ErrorCode.MODEL_CALL_FAILED


def test_classifies_checkpoint_error():
    assert _classify_error(Exception("checkpoint write failed")) == ErrorCode.CHECKPOINT_ERROR


def test_unknown_error_falls_back_to_internal():
    assert _classify_error(ValueError("随便什么")) == ErrorCode.INTERNAL


# ─────────────────────────── 工作区解析 ───────────────────────────

def test_resolve_workspace_creates_dir(tmp_path):
    ws = resolve_workspace({"workspace": {"root": str(tmp_path), "name": "html_1"}})
    assert ws.name == "html_1"
    assert ws.is_dir(), "不存在的产物目录应被创建出来"


def test_resolve_workspace_rejects_escape(tmp_path):
    from core.runtime import WorkspaceError

    with pytest.raises(WorkspaceError, match="越界"):
        resolve_workspace({"workspace": {"root": str(tmp_path), "name": "../../etc"}})


def test_resolve_workspace_requires_fields():
    from core.runtime import WorkspaceError

    with pytest.raises(WorkspaceError):
        resolve_workspace({"workspace": {"root": "D:/x"}})
