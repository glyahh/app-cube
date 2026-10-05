"""启动补充分支的隔离、严格结构与失败边界；模型使用明确替身，不算真实模型验收。"""
import io
import json
import sys
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core.events import Emitter
from core.runtime import run_one, is_read_only, TaskKind
from core.settings import ModelSettings
from core.startup import parse_context, parse_result


def context():
    return {"approvalId": str(uuid.uuid4()), "scanId": str(uuid.uuid4()), "appId": "42", "importTaskId": str(uuid.uuid4()),
            "contentHash": "a" * 64, "configRevision": "2", "contextEpoch": "3",
            "directories": ["backend"], "ranges": [{"directory": "backend", "phase": "ALGORITHM", "diagnostic": "NO_ENTRY"}], "known": [],
            "files": {"backend/app.py": "from fastapi import FastAPI\napp=FastAPI()"}}


def result():
    return {"services": [{"name": "服务", "directory": "backend", "command": "python -m uvicorn app:app", "build": None,
                          "evidence": ["backend/app.py"]}], "unidentified": []}


def run(data, monkeypatch, response=None, api_key="test-only", app="42"):
    import core.model
    import core.runtime
    calls = []
    def factory(settings, route):
        assert not route.allow_fallback
        assert settings.max_output_tokens == 4096
        class Model:
            def invoke(self, messages):
                calls.append(messages)
                if isinstance(response, Exception):
                    raise response
                if hasattr(response, "content"):
                    return response
                return SimpleNamespace(content=json.dumps(result()) if response is None else response, tool_calls=[])
        return Model()
    monkeypatch.setattr(core.model, "make_main_model", factory)
    def forbidden(*args, **kwargs):
        pytest.fail("startup must not resolve workspace or enter the coding Agent")
    monkeypatch.setattr(core.runtime, "resolve_workspace", forbidden)
    monkeypatch.setattr(core.runtime, "_run_agent", forbidden)
    stream = io.StringIO()
    request = {"runId": str(uuid.uuid4()), "appId": app, "input": {"taskKind": "startup", "message": json.dumps(data)},
               "workspace": {"root": "must-not-access", "name": "must-not-create"}, "allowProxyFallback": True}
    settings = SimpleNamespace(model=ModelSettings(api_key=api_key))
    run_one(request, settings, Emitter(stream, request["runId"]))
    return [json.loads(line) for line in stream.getvalue().splitlines()], calls


def test_success_has_frozen_identity_and_inferred_evidence_hashes(monkeypatch):
    data = context()
    frames, calls = run(data, monkeypatch)
    output = json.loads(next(frame["content"] for frame in frames if frame["event"] == "text.message"))
    assert output["approvalId"] == data["approvalId"] and output["contextEpoch"] == "3"
    assert output["services"][0]["certainty"] == "INFERRED"
    assert len(output["services"][0]["evidenceHashes"]["backend/app.py"]) == 64
    assert frames[-1]["event"] == "run.completed" and len(calls) == 1
    assert is_read_only(TaskKind.STARTUP)
    assert data["approvalId"] not in calls[0][1]["content"]


@pytest.mark.parametrize("path", [".env", "backend/.env.example", "backend/secrets/key.txt", "node_modules/app.js", "../app.py", "/app.py", "C:/app.py", "backend\\app.py", "backend//app.py", ".git/config"])
def test_denied_input_never_calls_model(path, monkeypatch):
    data = context(); data["files"] = {path: "private"}
    frames, calls = run(data, monkeypatch)
    assert frames[-1]["event"] == "error" and not calls


@pytest.mark.parametrize("api_key,app", [("", "42"), ("test-only", "43")])
def test_missing_model_or_wrong_app_no_placeholder(api_key, app, monkeypatch):
    frames, calls = run(context(), monkeypatch, api_key=api_key, app=app)
    assert frames[-1]["event"] == "error" and not calls


@pytest.mark.parametrize("change", ["extra", "outside", "missing-evidence", "no-coverage", "ambiguous", "control", "duplicate"])
def test_unqualified_model_output_is_failure(change, monkeypatch):
    data = result()
    if change == "extra": data["services"][0]["install"] = "pip install anything"
    if change == "outside": data["services"][0]["directory"] = "."
    if change == "missing-evidence": data["services"][0]["evidence"] = ["backend/imaginary.py"]
    if change == "no-coverage": data["services"] = []
    if change == "ambiguous": data["unidentified"] = ["backend"]
    if change == "control": data["services"][0]["command"] = "python\nmalicious"
    if change == "duplicate": data["services"].append(data["services"][0])
    frames, calls = run(context(), monkeypatch, response=json.dumps(data))
    assert len(calls) == 1 and frames[-1]["event"] == "error"
    assert not any(frame["event"] in ("text.message", "run.completed") for frame in frames)


def test_duplicate_json_fields_are_not_normalized_away():
    with pytest.raises(ValueError): parse_context('{"scanId":"first","scanId":"second"}')
    with pytest.raises(ValueError): parse_result('{"services":[],"services":[],"unidentified":["backend"]}', context())


def test_partial_unidentified_is_explicit_not_guessed():
    data = context(); data["directories"].append("frontend")
    data["ranges"].append({"directory": "frontend", "phase": "FIXED", "diagnostic": "NO_START_SCRIPT"})
    answer = result(); answer["unidentified"] = ["frontend"]
    parsed = parse_result(json.dumps(answer), data)
    assert parsed["unidentified"] == ["frontend"] and len(parsed["services"]) == 1


def test_multiple_services_in_same_directory_are_not_reduced_to_one():
    data = context(); answer = result()
    answer["services"].append({**answer["services"][0], "name": "后台任务", "command": "python -m worker"})
    parsed = parse_result(json.dumps(answer), data)
    assert len(parsed["services"]) == 2 and parsed["unidentified"] == []


def test_limit_rejected_without_truncation(monkeypatch):
    data = context(); data["files"]["backend/app.py"] = "x" * (16 * 1024 + 1)
    frames, calls = run(data, monkeypatch)
    assert frames[-1]["event"] == "error" and not calls


def test_supplier_failure_does_not_echo_secret_or_success(monkeypatch):
    frames, calls = run(context(), monkeypatch, response=RuntimeError("secret-never-echo"))
    assert len(calls) == 1 and frames[-1]["event"] == "error"
    assert "RuntimeError" in json.dumps(frames) and "secret-never-echo" not in json.dumps(frames)


def test_provider_tool_call_is_not_executed_or_returned_as_candidate(monkeypatch):
    response = SimpleNamespace(content=json.dumps(result()), tool_calls=[{"name": "execute", "args": {"command": "must-not-run"}}])
    frames, calls = run(context(), monkeypatch, response=response)
    assert len(calls) == 1 and frames[-1]["event"] == "error"
    assert not any(frame["event"] == "run.completed" for frame in frames)


def test_same_directory_ranges_are_sent_separately_and_known_candidate_is_not_repeated(monkeypatch):
    data = context()
    data["ranges"].append({"directory": "backend", "phase": "FIXED", "diagnostic": "PACKAGE_MANAGER_UNKNOWN"})
    data["known"] = [{"name": "backend", "directory": "backend", "command": "npm run dev"}]
    frames, calls = run(data, monkeypatch)
    sent = json.loads(calls[0][1]["content"])
    assert len(sent["ranges"]) == 2 and sent["known"][0]["command"] == "npm run dev" and frames[-1]["event"] == "run.completed"
    duplicate = result(); duplicate["services"][0]["command"] = "npm run dev"
    with pytest.raises(ValueError): parse_result(json.dumps(duplicate), parse_context(json.dumps(data)))


@pytest.mark.parametrize("change", ["missing", "outside", "uncovered", "extra-key", "known-outside", "control"])
def test_invalid_ranges_or_known_never_call_model(change, monkeypatch):
    data = context()
    if change == "missing": del data["ranges"]
    if change == "outside": data["ranges"].append({"directory": "frontend", "phase": "FIXED", "diagnostic": None})
    if change == "uncovered": data["directories"].append("frontend")
    if change == "extra-key": data["ranges"][0]["command"] = "run"
    if change == "known-outside": data["known"] = [{"name": "x", "directory": "frontend", "command": None}]
    if change == "control": data["ranges"][0]["diagnostic"] = "a\nb"
    frames, calls = run(data, monkeypatch)
    assert frames[-1]["event"] == "error" and not calls
