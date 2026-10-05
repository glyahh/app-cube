"""实际一次性模型 helper 的封存摘要与失败协议；不批准或调用真实模型。"""
import hashlib
import json
import subprocess
import time
import uuid
from pathlib import Path


def main():
    evidence = Path("temp/import-startup-model-20261004") / ("helper-"+str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "modelSuccess": "NOT_RUN", "businessApproval": "NOT_IMPLEMENTED", "evidence": str(evidence)}
    python = Path("agent/.venv/Scripts/python.exe").resolve()
    helper = Path("scripts/import-startup-agent.py").resolve()
    context = {"approvalId": str(uuid.uuid4()), "scanId": str(uuid.uuid4()), "appId": "42", "importTaskId": str(uuid.uuid4()),
               "contentHash": "a"*64, "configRevision": "0", "contextEpoch": "0", "directories": ["backend"],
               "ranges": [{"directory": "backend", "phase": "ALGORITHM", "diagnostic": "NO_ENTRY"}], "known": [],
               "files": {"backend/app.py": "raise RuntimeError('must not execute')"}}
    try:
        for index, app in enumerate(("43", "42")):
            request = {"runId": str(uuid.uuid4()), "appId": app, "workspace": {"root": str(evidence.resolve()), "name": "must-not-create"},
                       "input": {"taskKind": "startup", "message": json.dumps(context) if index == 0 else "{}"}}
            raw = json.dumps(request).encode("utf-8"); source = evidence / f"input-{index}.json"; source.write_bytes(raw)
            result = subprocess.run([str(python), "-I", str(helper), str(source), hashlib.sha256(raw).hexdigest()], capture_output=True, timeout=45)
            frames = [json.loads(line) for line in result.stdout.decode("utf-8").splitlines()]
            assert result.returncode == 0 and [frame["event"] for frame in frames] == ["error", "__end__"]
            assert all(frame["runId"] == request["runId"] for frame in frames)
            assert [frame["seq"] for frame in frames] == [0, 1]
            (evidence / f"frames-{index}.jsonl").write_bytes(result.stdout)
            report["checks"].append({"name": "一次性 helper 拒绝另一应用" if index == 0 else "一次性 helper 拒绝缺批准上下文", "status": "PASS", "exitCode": result.returncode})
        source = evidence / "input-0.json"
        rejected = subprocess.run([str(python), "-I", str(helper), str(source), "0"*64], capture_output=True, timeout=45)
        assert rejected.returncode == 2 and not rejected.stdout
        assert b"STARTUP_HELPER_FAILED:ValueError" in rejected.stderr
        report["checks"].append({"name": "封存摘要不同在模型装配前拒绝", "status": "PASS", "exitCode": 2})
        assert not (evidence / "must-not-create").exists()
        report.update(status="PASS", createdWorkspace=False, modelCalls=0)
    except Exception as error:
        report.update(status="FAIL", errorType=type(error).__name__)
        raise RuntimeError("模型 helper 检查失败，见脱敏报告") from None
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
