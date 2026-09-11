"""事件协议：Java ↔ Python 走 stdio 的 NDJSON 帧。

每一帧都是 JSON 对象，带统一信封 `{v, runId, seq, ts}`：

    Java → Python（一行）
    {"runId":"1876...","threadId":"app:1876...","workspace":{...},"input":{...}}

    Python → Java（多行，最后一行是终止帧）
    {"v":1,"runId":"1876...","seq":0,"ts":...,"event":"run.started",...}
    {"v":1,"runId":"1876...","seq":1,"ts":...,"event":"text.delta","content":"<div>"}
    {"v":1,"runId":"1876...","seq":9,"ts":...,"event":"__end__"}

`seq` 是 per-run 单调递增，Java 侧用来去重和检测乱序。

**不用 SSE 分帧** —— stdio 上按行读就够了，`\n` 是天然的分帧符，不需要
`event:` / `data:` / 空行那一套。
"""

from __future__ import annotations

import json
import time
from enum import Enum
from typing import Any, TextIO

PROTOCOL_VERSION = 1


class Event(str, Enum):
    """Python → Java 的事件名。Java 侧 `AgentEvent` 必须与这份列表一致。"""

    RUN_STARTED = "run.started"
    STATUS = "status"
    HEARTBEAT = "heartbeat"
    TEXT_DELTA = "text.delta"
    TEXT_MESSAGE = "text.message"
    TODO_DELTA = "todo.delta"
    STEP = "step"
    TOOL_STARTED = "tool.started"
    TOOL_FINISHED = "tool.finished"
    ARTIFACT_CHANGED = "artifact.changed"
    ARTIFACT_MANIFEST = "artifact.manifest"
    ERROR = "error"
    RUN_COMPLETED = "run.completed"
    # 终止帧。Java 读到它就归还进程；不转成对前端的事件。
    END = "__end__"


class ErrorCode(str, Enum):
    WORKSPACE_INIT_FAILED = "WORKSPACE_INIT_FAILED"
    MODEL_CALL_FAILED = "MODEL_CALL_FAILED"
    MODEL_QUOTA_EXCEEDED = "MODEL_QUOTA_EXCEEDED"
    RUN_DEADLINE_EXCEEDED = "RUN_DEADLINE_EXCEEDED"
    TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED"
    SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE"
    CHECKPOINT_ERROR = "CHECKPOINT_ERROR"
    INTERNAL = "INTERNAL"


class Emitter:
    """往协议通道写 NDJSON。

    写入走 `_protocol_out`（app.py 在重定向 stdout 之前抓住的真 stdout），
    所以库里的 `print` 不会污染协议 —— 它们会进 stderr。
    """

    def __init__(self, stream: TextIO, run_id: str) -> None:
        self._stream = stream
        self._run_id = run_id
        self._seq = 0

    @property
    def seq(self) -> int:
        return self._seq

    def emit(self, event: Event | str, **payload: Any) -> None:
        frame = {
            "v": PROTOCOL_VERSION,
            "runId": self._run_id,
            "seq": self._seq,
            "ts": int(time.time() * 1000),
            "event": event.value if isinstance(event, Event) else event,
        }
        frame.update(payload)
        self._seq += 1
        self._stream.write(json.dumps(frame, ensure_ascii=False, default=str) + "\n")
        # 每帧都 flush：Java 侧是按行阻塞读的，攒着会卡住首屏。
        self._stream.flush()

    def error(self, code: ErrorCode, message: str, retryable: bool = False) -> None:
        self.emit(Event.ERROR, code=code.value, message=message, retryable=retryable)

    def end(self) -> None:
        """终止帧。**必须保证发出去** —— 无论前面成功还是抛异常。

        没发的话 Java 会一直阻塞在读上，只能靠超时兜底。
        """
        self.emit(Event.END)


def parse_request(line: str) -> dict[str, Any]:
    """解析 Java 发来的一行请求。字段名用 camelCase，与 Java DTO 对齐。"""
    request = json.loads(line)
    if not isinstance(request, dict):
        raise ValueError("请求必须是一个 JSON 对象")
    if not request.get("runId"):
        raise ValueError("请求缺少 runId")
    return request
