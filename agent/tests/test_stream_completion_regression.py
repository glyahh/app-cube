"""F10: complete public messages must precede terminal events, without model calls."""
import io
import json

import pytest

from core.events import Emitter, Event
from core.streaming_runtime import run_agent_with_events
from core.tool_permissions import ToolPermissionStopped


class ScriptedAgent:
    def __init__(self, events=(), stop=None):
        self.events, self.stop = events, stop

    async def astream_events(self, *_args, **_kwargs):
        for event in self.events:
            yield event
        if self.stop:
            raise ToolPermissionStopped(self.stop)


def collect(agent):
    stream = io.StringIO()
    emitter = Emitter(stream, "completion-regression")
    summary = run_agent_with_events(agent=agent, messages=[{"content": "检查文件"}],
                                    thread_id="test", emitter=emitter, task_kind="coding")
    emitter.emit(Event.RUN_COMPLETED, status=summary["status"], summary=summary["text"])
    emitter.end()
    return summary, [json.loads(line) for line in stream.getvalue().splitlines()]


@pytest.mark.parametrize("code,status,text", [
    ("TOOL_PERMISSION_TIMEOUT", "failed", "等待审批超时，本轮已暂停。"),
    ("TOOL_PERMISSION_REJECTED", "cancelled", "本轮已停止。"),
    ("TOOL_PERMISSION_CANCELLED", "cancelled", "本轮已停止。"),
    ("TOOL_PERMISSION_REVOKED", "cancelled", "本轮已停止。"),
    ("TOOL_PERMISSION_EXECUTION_FAILED", "failed", "命令执行未完成，本轮已停止；已完成的文件改动保留。"),
])
def test_permission_stop_publishes_complete_message_before_terminal(code, status, text):
    summary, frames = collect(ScriptedAgent(stop=code))
    finals = [frame for frame in frames if frame["event"] == "text.message"]
    assert len(finals) == 1
    assert finals[0]["content"] == text == summary["text"]
    assert summary["status"] == status
    assert frames[-2]["event"] == "run.completed"
    assert frames.index(finals[0]) < len(frames) - 2


def test_tool_without_final_answer_publishes_actual_summary_not_candidate():
    summary, frames = collect(ScriptedAgent([
        {"event": "on_chat_model_stream", "data": {"chunk": {"content": "unconfirmed private plan"}}},
        {"event": "on_tool_start", "name": "read_file", "run_id": "read-1", "data": {"input": {"file_path": "index.html"}}},
        {"event": "on_tool_end", "name": "read_file", "run_id": "read-1", "data": {"output": "file contents"}},
    ]))
    finals = [frame for frame in frames if frame["event"] == "text.message"]
    assert len(finals) == 1
    assert finals[0]["content"] == "本轮执行了 1 次工具调用，未改动文件。"
    assert finals[0]["content"] == summary["text"]
    assert "unconfirmed private plan" not in finals[0]["content"]
