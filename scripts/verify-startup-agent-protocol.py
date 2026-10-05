"""真实 Python Worker 的启动补充分支失败协议；不调用模型、不执行导入工程。"""
import hashlib
import json
import subprocess
import time
import uuid
from pathlib import Path


def main():
    evidence = Path("temp/import-startup-agent-20261004") / ("protocol-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    workspace = evidence / "must-not-create"
    context = {"approvalId": str(uuid.uuid4()), "scanId": str(uuid.uuid4()), "appId": "42", "importTaskId": str(uuid.uuid4()),
               "contentHash": "a" * 64, "configRevision": "0", "contextEpoch": "0", "directories": ["backend"],
               "ranges": [{"directory": "backend", "phase": "ALGORITHM", "diagnostic": "NO_ENTRY"}], "known": [],
               "files": {"backend/app.py": "raise RuntimeError('must not execute')"}}
    requests = []
    for app, message in (("42", "{}"), ("43", json.dumps(context))):
        requests.append({"v": 1, "runId": str(uuid.uuid4()), "appId": app,
                         "workspace": {"root": str(evidence.resolve()), "name": workspace.name},
                         "input": {"taskKind": "startup", "message": message}})
    report = {"status": "RUNNING", "modelSuccess": "NOT_RUN", "businessApproval": "NOT_IMPLEMENTED", "evidence": str(evidence)}
    try:
        actual = subprocess.run([str(Path("agent/.venv/Scripts/python.exe").resolve()), "-u", str(Path("agent/app.py").resolve())],
                                input="\n".join(json.dumps(request) for request in requests)+"\n", capture_output=True,
                                text=True, encoding="utf-8", timeout=45)
        report["exitCode"] = actual.returncode
        report["stderrBytes"] = len(actual.stderr.encode("utf-8"))
        report["stderrHash"] = hashlib.sha256(actual.stderr.encode("utf-8")).hexdigest()
        (evidence / "frames.jsonl").write_text(actual.stdout, encoding="utf-8")
        assert actual.returncode == 0
        frames = [json.loads(line) for line in actual.stdout.splitlines()]
        for request in requests:
            owned = [frame for frame in frames if frame["runId"] == request["runId"]]
            assert [frame["event"] for frame in owned] == ["error", "__end__"]
            assert [frame["seq"] for frame in owned] == [0, 1]
        assert not workspace.exists()
        report.update(status="PASS", requestCount=len(requests), createdWorkspace=False, modelCalls=0)
    except Exception as error:
        report.update(status="FAIL", errorType=type(error).__name__)
        raise RuntimeError("启动补充真实协议检查失败，见脱敏报告") from None
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
