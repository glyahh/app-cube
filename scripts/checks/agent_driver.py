"""基准驱动：按 Java↔Python 契约直接驱动 agent/app.py，不经过 Java 与登录。

只用于固定任务基准。它衡量 Python 执行面，不覆盖 Java 鉴权、权限门禁、候选审阅与持久化。
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import signal
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
AGENT_DIR = REPO_ROOT / "agent"
TERMINAL = "__end__"


def build_request(workspace: Path, prompt: str, mode: str = "edit") -> dict:
    # agent/core/runtime.py 用 root / name 解析实际工作区，所以 root 必须是父目录。
    workspace = workspace.resolve()
    return {
        "v": 1,
        "runId": str(uuid.uuid4().int % 10 ** 18),
        "threadId": f"app:bench:{uuid.uuid4()}",
        "appId": "bench",
        "userId": "bench",
        "roundId": str(uuid.uuid4().int % 10 ** 18),
        "traceId": str(uuid.uuid4()),
        "modelRoute": "configured",
        "allowProxyFallback": False,
        "workspace": {
            "root": str(workspace.parent),
            "name": workspace.name,
            "codeGenType": "project",
            "mode": mode,
        },
        "input": {
            "message": prompt,
            "initPrompt": "",
            "historyTail": [],
            "historyTailTruncated": False,
        },
    }


def terminate(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
    else:
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except ProcessLookupError:
            pass


def run(command: list[str], workspace: Path, prompt: str, timeout: int, mode: str = "edit") -> dict:
    request = build_request(workspace, prompt, mode)
    started = time.monotonic()
    process = subprocess.Popen(
        command, cwd=AGENT_DIR, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        start_new_session=(os.name != "nt"),
    )
    events: list[dict] = []
    protocol_error = None
    stderr_lines: list[str] = []

    def drain_stderr() -> None:
        # stderr 必须从一开始就被排空：Agent 的日志全在这里，管道写满会阻塞子进程。
        for line in process.stderr:
            stderr_lines.append(line)
            if len(stderr_lines) > 2000:
                del stderr_lines[:1000]

    def reader() -> None:
        nonlocal protocol_error
        for line in process.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                protocol_error = "non-json frame"
                continue
            events.append(frame)
            if frame.get("event") == TERMINAL:
                return

    stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
    stderr_thread.start()
    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    try:
        process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        process.stdin.flush()
        thread.join(timeout)
        timed_out = thread.is_alive()
    finally:
        terminate(process)
        stderr_thread.join(timeout=5)
        stderr = "".join(stderr_lines)[-4000:]
        exit_code = process.wait(timeout=10)

    return {
        "ok": not timed_out and protocol_error is None
              and any(frame.get("event") == TERMINAL for frame in events),
        "timedOut": timed_out,
        "protocolError": protocol_error,
        "exitCode": exit_code,
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "request": request,
        "events": events,
        "stderr": stderr[-4000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--mode", default="edit")
    parser.add_argument("--agent-command", default="", help="覆盖默认的 python -m app，用于测试")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    command = shlex.split(args.agent_command) if args.agent_command else [sys.executable, "-m", "app"]
    result = run(command, args.workspace.resolve(), args.prompt, args.timeout, args.mode)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
