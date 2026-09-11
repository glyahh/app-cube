"""Emitter / 请求解析 / 主循环协议。"""

from __future__ import annotations

import io
import json

import pytest

from core.events import Emitter, ErrorCode, Event, parse_request


def make_emitter(run_id: str = "r-1") -> tuple[Emitter, io.StringIO]:
    buf = io.StringIO()
    return Emitter(buf, run_id), buf


def frames(buf: io.StringIO) -> list[dict]:
    return [json.loads(line) for line in buf.getvalue().splitlines() if line.strip()]


def test_每帧都带信封且_seq_单调递增():
    emitter, buf = make_emitter()
    emitter.emit(Event.RUN_STARTED, threadId="app:1")
    emitter.emit(Event.TEXT_DELTA, content="hi")
    emitter.emit(Event.RUN_COMPLETED, status="succeeded")

    got = frames(buf)
    assert [f["seq"] for f in got] == [0, 1, 2]
    assert all(f["v"] == 1 for f in got)
    assert all(f["runId"] == "r-1" for f in got)
    assert all(isinstance(f["ts"], int) for f in got)
    assert [f["event"] for f in got] == ["run.started", "text.delta", "run.completed"]


def test_匿名枚举与字符串都能传事件名():
    emitter, buf = make_emitter()
    emitter.emit(Event.HEARTBEAT)
    emitter.emit("custom.event")
    assert [f["event"] for f in frames(buf)] == ["heartbeat", "custom.event"]


def test_error_帧结构固定():
    emitter, buf = make_emitter()
    emitter.error(ErrorCode.WORKSPACE_INIT_FAILED, "路径越界")
    frame = frames(buf)[0]
    assert frame["event"] == "error"
    assert frame["code"] == "WORKSPACE_INIT_FAILED"
    assert frame["message"] == "路径越界"
    assert frame["retryable"] is False


def test_终止帧是独立事件且可反复调用():
    emitter, buf = make_emitter()
    emitter.end()
    got = frames(buf)
    assert len(got) == 1
    assert got[0]["event"] == "__end__"


def test_中文不被转义():
    """ensure_ascii=False —— Java 侧直接读 UTF-8，转义后反而难排查。"""
    emitter, buf = make_emitter()
    emitter.emit(Event.TEXT_DELTA, content="登录页")
    assert "登录页" in buf.getvalue()


def test_不可序列化的值走_default_str_而不是抛异常():
    """工具返回值里混进 Path / datetime 是常态，不能因此炸掉整条流。

    只断言「被转成了字符串且认得出来」，不断言分隔符 —— Windows 上
    str(Path("a/b.py")) 是 a\\b.py。路径分隔符的统一是 Backend 的职责。
    """
    from pathlib import Path

    emitter, buf = make_emitter()
    emitter.emit(Event.TOOL_FINISHED, outputPreview=Path("a") / "b.py")
    value = frames(buf)[0]["outputPreview"]
    assert isinstance(value, str)
    assert value.endswith("b.py")


def test_parse_request_要求_runId():
    with pytest.raises(ValueError, match="runId"):
        parse_request('{"workspace":{}}')


def test_parse_request_拒绝非对象():
    with pytest.raises(ValueError, match="JSON 对象"):
        parse_request("[1,2,3]")


def test_parse_request_透传_camelCase_字段():
    """字段名与 Java DTO 对齐，不做下划线转换。"""
    request = parse_request('{"runId":"r-9","threadId":"app:1","workspace":{"root":"/tmp"}}')
    assert request["runId"] == "r-9"
    assert request["threadId"] == "app:1"
    assert request["workspace"]["root"] == "/tmp"
