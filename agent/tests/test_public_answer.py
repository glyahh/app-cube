from types import SimpleNamespace
from unittest.mock import Mock
from core.streaming_runtime import _RunState, _on_model_stream, _on_model_end


def test_tool_preamble_is_not_public_answer():
    state = _RunState()
    emitter = Mock()
    _on_model_stream({'data': {'chunk': SimpleNamespace(content='workspace secret')}}, emitter, state)
    emitter.emit.assert_not_called()
    _on_model_end({'data': {'output': SimpleNamespace(tool_calls=[{'name': 'ls'}], usage_metadata=None)}}, state)
    assert state.text == ''


def test_final_answer_and_overflow():
    state = _RunState()
    emitter = Mock()
    _on_model_stream({'data': {'chunk': SimpleNamespace(content='游戏已完成')}}, emitter, state)
    _on_model_end({'data': {'output': SimpleNamespace(tool_calls=[], usage_metadata=None)}}, state)
    assert state.text == '游戏已完成'
    _on_model_stream({'data': {'chunk': SimpleNamespace(content='x' * 65537)}}, emitter, state)
    _on_model_stream({'data': {'chunk': SimpleNamespace(content='should not pass')}}, emitter, state)
    assert state.candidate == '部分内部执行信息已省略'


def test_tool_events_do_not_create_thinking_stages():
    from core.streaming_runtime import _on_tool_start, _on_tool_end
    from core.events import Emitter
    import io, json
    out = io.StringIO()
    emitter = Emitter(out, "run")
    state = _RunState()
    event = {"run_id": "tool-1", "name": "read_file", "data": {"input": {"file_path": "/index.html"}}}
    _on_tool_start(event, emitter, Mock(), state)
    _on_tool_end({**event, "data": {"output": SimpleNamespace(content="ok", status="success")}}, emitter, state)
    events = [json.loads(line) for line in out.getvalue().splitlines()]
    assert [e["event"] for e in events] == ["tool.started", "tool.finished"]


def test_initial_summary_uses_actual_request_not_keyword_dictionary():
    from core.streaming_runtime import _prompt_summary
    assert "星空" in _prompt_summary([{"content": "制作星空主题校徽"}])
    assert "齿轮" in _prompt_summary([{"content": "制作齿轮主题校徽"}])


def test_model_stages_have_stable_ids_and_dynamic_public_details():
    import io, json
    from core.events import Emitter
    from core.streaming_runtime import run_agent_with_events
    class Agent:
        async def astream_events(self, *args, **kwargs):
            yield {"event": "on_chat_model_start"}
            yield {"event": "on_chat_model_stream", "data": {"chunk": SimpleNamespace(content="正在构思星空校徽的视觉结构。\n采用星轨环绕的构图。")}}
            yield {"event": "on_chat_model_end", "data": {"output": SimpleNamespace(tool_calls=[{"name": "ls"}], usage_metadata=None)}}
            for i in range(2):
                yield {"event": "on_tool_start", "run_id": str(i), "name": "ls"}
                yield {"event": "on_tool_end", "run_id": str(i), "name": "ls", "data": {"output": "ok"}}
            yield {"event": "on_chat_model_start"}
            yield {"event": "on_chat_model_stream", "data": {"chunk": SimpleNamespace(content="校徽已完成")}}
            yield {"event": "on_chat_model_end", "data": {"output": SimpleNamespace(tool_calls=[], usage_metadata=None)}}
    out = io.StringIO()
    result = run_agent_with_events(agent=Agent(), messages=[{"content": "制作星空校徽"}], thread_id="test", emitter=Emitter(out,"123"), task_kind="coding")
    events = [json.loads(line) for line in out.getvalue().splitlines()]
    starts = [e for e in events if e["event"] == "thinking.started"]
    ends = [e for e in events if e["event"] == "thinking.completed"]
    assert len(starts) == len(ends) == 2
    assert [e["thinkingId"] for e in starts] == [e["thinkingId"] for e in ends]
    delta = next(e for e in events if e["event"] == "thinking.delta")
    assert delta["thinkingId"] == starts[0]["thinkingId"]
    assert "星轨" in delta["details"] and "星空" in delta["summary"]
    assert result["text"] == "校徽已完成"


def test_public_progress_arrives_when_tool_arguments_start_not_after_large_file_finishes():
    from core.events import Event
    state = _RunState()
    state.current_thinking_id = "run:thinking:1"
    emitter = Mock()
    text = "正在构思星空校徽。" + "补充可见细节。" * 30
    _on_model_stream({"data": {"chunk": SimpleNamespace(content=text)}}, emitter, state)
    emitter.emit.assert_not_called()
    _on_model_stream({"data": {"chunk": SimpleNamespace(content="", tool_call_chunks=[{"name": "write_file"}])}}, emitter, state)
    event, = emitter.emit.call_args.args
    assert event == Event.THINKING_DELTA
    assert emitter.emit.call_args.kwargs["summary"] == "正在构思星空校徽。"
    assert emitter.emit.call_args.kwargs["details"] == text
