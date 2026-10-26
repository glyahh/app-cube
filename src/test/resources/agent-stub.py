"""Java 侧测试用的 Agent stub。

实现与 agent/app.py 完全相同的 stdio 协议，但**不调用模型**。

为什么不直接用真的 app.py：进程池与传输层的测试关心的是
「借出/归还/取消/伸缩/事件映射」，不是 agent 会不会写文件。用真脚本会让这些
测试依赖网络、模型密钥和余额，慢且不稳。真实执行的验证在 Python 侧和端到端脚本里做。

刻意保留 stdout 保护 —— 让测试仍然覆盖「协议通道不被污染」这条约束。
"""

import sys

_PROTOCOL_OUT = sys.stdout
sys.stdout = sys.stderr

import json  # noqa: E402


def emit(run_id: str, seq: int, event: str, **payload) -> None:
    frame = {"v": 1, "runId": run_id, "seq": seq, "ts": 0, "event": event}
    frame.update(payload)
    _PROTOCOL_OUT.write(json.dumps(frame, ensure_ascii=False) + "\n")
    _PROTOCOL_OUT.flush()


def handle(line: str) -> None:
    request = json.loads(line)
    run_id = request["runId"]

    workspace = request.get("workspace") or {}
    root = workspace.get("root")
    name = workspace.get("name")
    if not root or not name:
        emit(run_id, 0, "error", code="WORKSPACE_INIT_FAILED", message="缺少 workspace 字段")
        emit(run_id, 1, "__end__")
        return

    # 与真实实现同样的越界校验
    import pathlib
    root_path = pathlib.Path(root).resolve()
    resolved = (root_path / name).resolve()
    if (root_path / name).resolve().is_relative_to(root_path) is False:
        emit(run_id, 0, "error", code="WORKSPACE_INIT_FAILED", message="工作区路径越界")
        emit(run_id, 1, "__end__")
        return

    message = ((request.get("input") or {}).get("message") or "").strip()
    if not message:
        emit(run_id, 0, "error", code="INTERNAL", message="缺少 input.message")
        emit(run_id, 1, "__end__")
        return

    if request.get("input", {}).get("taskKind") == "title":
        emit(run_id, 0, "text.message", messageId="title", content="离线验收应用")
        emit(run_id, 1, "run.completed", status="succeeded", summary="")
        emit(run_id, 2, "__end__")
        return

    resolved.mkdir(parents=True, exist_ok=True)
    generated = "<h1>E2E fixture</h1>\n<p>Initial generated content</p>\n"
    (resolved / "index.html").write_text(generated, encoding="utf-8")
    seq = 0
    emit(run_id, seq, "run.started", threadId=request.get("threadId", ""),
         workspaceName=name, resumedFromCheckpoint=False)
    seq += 1
    emit(run_id, seq, "status", phase="stub", detail="测试 stub，未调用模型")
    seq += 1
    emit(run_id, seq, "text.delta", messageId=f"{run_id}-a0",
         content=f"[stub] 已收到需求：{message}\n", mode="append")
    seq += 1
    emit(run_id, seq, "tool.started", callId="c1", toolName="write_file",
         inputPreview=json.dumps({"path": "/index.html", "content": generated}), inputDigest="abc123")
    seq += 1
    emit(run_id, seq, "tool.finished", callId="c1", toolName="write_file",
         status="succeeded", durationMs=5, outputPreview="index.html", truncated=False)
    seq += 1
    answer = "离线模型替身已生成 index.html"
    emit(run_id, seq, "text.message", messageId=f"{run_id}-a0", content=answer, mode="append")
    seq += 1
    emit(run_id, seq, "run.completed", status="succeeded",
         summary=answer, usage={"inputTokens": 0, "outputTokens": 0, "modelCalls": 0})
    seq += 1
    emit(run_id, seq, "__end__")


def main() -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            handle(line)
        except Exception as exc:  # 与真实实现一致：坏输入不弄死主循环
            print(f"stub 处理失败：{exc}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
