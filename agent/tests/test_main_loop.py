"""跑真实子进程验证协议。

为什么不在进程内 mock：stdout 保护是在 import 期做的（`sys.stdout = sys.stderr`），
进程内测试既会污染 pytest 的捕获，又测不出真实行为。这些用例跑真进程，
顺带把「终止帧一定发得出去」这条硬要求一起盯住 —— 漏发终止帧时
Java 侧会一直阻塞在读上，只能靠超时兜底，是最难排查的一类故障。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parents[1]
WORKSPACE_ROOT = AGENT_DIR.parent / "temp" / "code_output"

BASE_REQUEST = {
    "v": 1,
    "runId": "r-it-1",
    "threadId": "app:1",
    "appId": "1",
    "userId": "1",
    "roundId": "2",
    "traceId": "t-1",
    "workspace": {"root": str(WORKSPACE_ROOT), "name": "html_1", "codeGenType": "html"},
    "input": {"message": "做一个登录页"},
}


def run_agent(lines: list[str], timeout: int = 60) -> tuple[list[dict], str, int]:
    """喂若干行给真实进程，返回 (协议帧, stderr, 退出码)。

    把 AGENT_ENV_FILE 指向一个不存在的文件：这样即使仓库里有 .env（配了模型密钥），
    这些测试也走「无密钥」的确定性占位路径 —— 不联网、不烧 token、结果稳定。
    真实执行的验证在单独的端到端脚本里做。
    """
    env = dict(
        os.environ,
        PYTHONIOENCODING="utf-8",
        AGENT_ENV_FILE=str(AGENT_DIR / "tests" / ".no-such-env-file"),
    )
    proc = subprocess.run(
        [sys.executable, "app.py"],
        cwd=AGENT_DIR,
        input="\n".join(lines) + "\n",
        capture_output=True,
        text=True,
        encoding="utf-8",
        env=env,
        timeout=timeout,
    )
    frames = [json.loads(line) for line in proc.stdout.splitlines() if line.strip()]
    return frames, proc.stderr, proc.returncode


def test_正常一轮产生完整事件流并以终止帧收尾():
    frames, _stderr, code = run_agent([json.dumps(BASE_REQUEST)])
    assert code == 0

    names = [f["event"] for f in frames]
    assert names[0] == "run.started"
    assert names[-1] == "__end__"
    assert "run.completed" in names
    assert "text.delta" in names
    assert [f["seq"] for f in frames] == list(range(len(frames)))


def test_stdout_只有协议帧没有库的_print():
    """把每条输出都按 JSON 解析一遍。任何一行解析失败就说明协议被污染了。"""
    frames, stderr, _code = run_agent([json.dumps(BASE_REQUEST)])
    assert len(frames) > 0
    assert all(set(("v", "runId", "seq", "ts", "event")) <= set(f) for f in frames)
    # 日志确实走了 stderr —— 说明重定向生效，不是「碰巧没有 print」
    assert "agent 运行时已就绪" in stderr


def test_坏_JSON_不弄死主循环_后续请求照常处理():
    second = dict(BASE_REQUEST, runId="r-it-2")
    frames, _stderr, code = run_agent(["这不是 json", json.dumps(second)])
    assert code == 0
    assert [f["runId"] for f in frames if f["event"] == "__end__"] == ["r-it-2"]


def test_缺少_runId_的行被丢弃且不影响后续():
    second = dict(BASE_REQUEST, runId="r-it-3")
    frames, _stderr, code = run_agent(['{"workspace":{}}', json.dumps(second)])
    assert code == 0
    assert {f["runId"] for f in frames} == {"r-it-3"}


def test_空行被跳过():
    frames, _stderr, code = run_agent(["", "   ", json.dumps(BASE_REQUEST)])
    assert code == 0
    assert frames[-1]["event"] == "__end__"


def test_工作区缺字段时发_error_且仍然以终止帧收尾():
    bad = json.loads(json.dumps(BASE_REQUEST))
    bad["workspace"] = {}
    frames, _stderr, code = run_agent([json.dumps(bad)])
    assert code == 0

    assert [f["event"] for f in frames] == ["error", "__end__"]
    assert frames[0]["code"] == "WORKSPACE_INIT_FAILED"


def test_缺_message_时发_error_且仍然以终止帧收尾():
    bad = json.loads(json.dumps(BASE_REQUEST))
    bad["input"] = {}
    frames, _stderr, _code = run_agent([json.dumps(bad)])
    assert frames[0]["event"] == "error"
    assert frames[-1]["event"] == "__end__"


def test_工作区路径越界被拒():
    """路径来自 Java，但它是从 HTTP 参数拼出来的，Python 侧仍要校验。"""
    evil = json.loads(json.dumps(BASE_REQUEST))
    evil["workspace"] = {
        "root": str(WORKSPACE_ROOT),
        "name": "../../../../etc",
        "codeGenType": "html",
    }
    frames, _stderr, _code = run_agent([json.dumps(evil)])
    assert frames[0]["event"] == "error"
    assert frames[0]["code"] == "WORKSPACE_INIT_FAILED"
    assert frames[-1]["event"] == "__end__"


def test_一次进程内多轮_run_各自独立计数_seq():
    a = dict(BASE_REQUEST, runId="r-multi-a")
    b = dict(BASE_REQUEST, runId="r-multi-b")
    frames, _stderr, _code = run_agent([json.dumps(a), json.dumps(b)])

    for run_id in ("r-multi-a", "r-multi-b"):
        seqs = [f["seq"] for f in frames if f["runId"] == run_id]
        assert seqs == list(range(len(seqs))), f"{run_id} 的 seq 不是从 0 连续"
        assert frames[-1]["event"] == "__end__" or run_id == "r-multi-a"
