"""主 Agent 硬 swap 准入；真实层显式启用，不用夹具替代内核证据。"""
import json
import io
import os
import subprocess
import uuid
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from backends.docker_sandbox import DockerSandboxManager, SandboxError
from backends.docker_sandbox import SandboxRecord


@pytest.fixture
def manager():
    value = DockerSandboxManager()
    yield value
    value.shutdown()


def result(args, code=0, out="", err=""):
    return subprocess.CompletedProcess(args, code, out, err)


@pytest.mark.parametrize("out,code", [("max", 0), ("1", 0), ("", 1), ("0", 1)])
def test_nonzero_or_unreadable_swap_cannot_pass(manager, monkeypatch, out, code):
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, code, out))
    pin = Mock()
    monkeypatch.setattr(manager, "_pin_hard_swap", pin)
    with pytest.raises(SandboxError, match="MEMORY_LIMIT_UNENFORCEABLE"):
        manager._ensure_hard_swap("a" * 64)
    pin.assert_called_once()


def test_native_kernel_zero_does_not_need_host_access(manager, monkeypatch):
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, out="0\n"))
    pin = Mock(side_effect=AssertionError("must not pin"))
    monkeypatch.setattr(manager, "_pin_hard_swap", pin)
    manager._ensure_hard_swap("a" * 64)
    pin.assert_not_called()


@pytest.mark.parametrize('tail', [b'error: no such object: ' + b'a' * 64 + b'\n', b'error: no such object: fixture\n'])
def test_mixed_wsl_warning_and_linux_error_remain_readable(tail):
    raw = 'wsl: 检测到代理配置。\r\n'.encode('utf-16-le') + tail
    decoded = DockerSandboxManager._decode(raw)
    assert decoded.endswith(tail.decode())
    assert DockerSandboxManager._confirmed_absent(result([], code=1, err=decoded))
    socket = DockerSandboxManager._decode('wsl: 代理配置。\r\n'.encode('utf-16-le') + b'error: no such file or directory\n')
    assert not DockerSandboxManager._confirmed_absent(result([], code=1, err=socket))


def test_native_pin_requires_root_and_rejects_remote_engine(manager, monkeypatch):
    monkeypatch.setattr(os, 'geteuid', lambda: 1000, raising=False)
    assert manager._native_root_engine(['docker']) is False
    monkeypatch.setattr(os, 'geteuid', lambda: 0)
    monkeypatch.setenv('DOCKER_HOST', 'tcp://127.0.0.1:2375')
    assert manager._native_root_engine(['docker']) is False


def test_native_pin_requires_actual_local_socket(manager, monkeypatch, tmp_path):
    monkeypatch.setattr(os, 'geteuid', lambda: 0, raising=False)
    path = tmp_path / 'not-a-socket'
    path.write_text('fixture')
    monkeypatch.setenv('DOCKER_HOST', 'unix://' + str(path).replace('\\', '/'))
    assert manager._native_root_engine(['docker']) is False


def test_native_pin_checks_context_failure(manager, monkeypatch):
    monkeypatch.setattr(os, 'geteuid', lambda: 0, raising=False)
    monkeypatch.delenv('DOCKER_HOST', raising=False)
    monkeypatch.setattr(manager, '_docker', lambda *a, **k: result(a, code=1))
    assert manager._native_root_engine(['docker']) is False


def test_native_pin_uses_same_pid_and_cgroup_guards(manager, monkeypatch):
    monkeypatch.setattr(manager, '_docker_command', lambda *a: ['docker'])
    monkeypatch.setattr(manager, '_native_root_engine', lambda prefix: True)
    monkeypatch.setattr(manager, '_docker', lambda *a, **k: result(a, out='123'))
    spawn = Mock(return_value=SimpleNamespace(returncode=0))
    monkeypatch.setattr(subprocess, 'run', spawn)
    manager._pin_hard_swap('a' * 64)
    command = spawn.call_args.args[0]
    assert command[:2] == ['sh', '-c']
    assert '/proc/123/cgroup' in command[2] and 'a' * 64 in command[2]
    assert '"$cg" != /' in command[2]


def test_pin_requires_independent_kernel_readback(manager, monkeypatch):
    values = iter(["max", "0"])
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, out=next(values)))
    pin = Mock()
    monkeypatch.setattr(manager, "_pin_hard_swap", pin)
    manager._ensure_hard_swap("a" * 64)
    pin.assert_called_once()


@pytest.mark.parametrize("cid,pid", [("bad;command", "1"), ("a" * 64, "0"), ("a" * 64, "1;echo bad")])
def test_host_pin_rejects_untrusted_identity(manager, monkeypatch, cid, pid):
    monkeypatch.setattr(manager, "_docker_command", lambda *a: ["wsl.exe", "-d", "Ubuntu"])
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, out=pid))
    spawn = Mock(side_effect=AssertionError("must not invoke host"))
    monkeypatch.setattr(subprocess, "run", spawn)
    with pytest.raises(SandboxError, match="MEMORY_LIMIT_UNENFORCEABLE"):
        manager._pin_hard_swap(cid)
    spawn.assert_not_called()


def test_host_pin_uses_exec_and_only_numeric_pid(manager, monkeypatch):
    monkeypatch.setattr(manager, "_docker_command", lambda *a: ["wsl.exe", "-d", "Ubuntu"])
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, out="123"))
    spawn = Mock(return_value=SimpleNamespace(returncode=0))
    monkeypatch.setattr(subprocess, "run", spawn)
    manager._pin_hard_swap("a" * 64)
    command = spawn.call_args.args[0]
    assert command[:8] == ["wsl.exe", "-d", "Ubuntu", "-u", "root", "--exec", "sh", "-c"]
    assert "/proc/123/cgroup" in command[8]
    assert '"$cg" != /' in command[8]


@pytest.mark.parametrize("state,code,blocked", [("false", 0, False), ("true", 0, True), ("", 1, True)])
def test_acquire_guard_failure_stops_or_blocks_retry(manager, monkeypatch, tmp_path, state, code, blocked):
    cid = "a" * 64
    monkeypatch.setattr(manager, "_healthy", lambda: None)
    monkeypatch.setattr(manager, "_inspect_container", lambda name: None)
    monkeypatch.setattr(manager, "_create_and_start_container", lambda *a: (cid, False))
    monkeypatch.setattr(manager, "_ensure_hard_swap", Mock(side_effect=SandboxError("MEMORY_LIMIT_UNENFORCEABLE")))
    calls = []
    def docker(*args, **kwargs):
        calls.append(args)
        return result(args, code, state)
    monkeypatch.setattr(manager, "_docker", docker)
    with pytest.raises(SandboxError, match="RECOVERY_REQUIRED" if blocked else "MEMORY_LIMIT_UNENFORCEABLE"):
        manager.acquire("swap-test", tmp_path)
    record = manager._records["swap-test"]
    assert record.stop_failed is blocked and record.active is blocked
    assert ("stop", "-t", "2", cid) in calls
    assert not any(a[0] == "exec" for a in calls)
    if blocked:
        manager.release("swap-test")
        assert record.active
        with pytest.raises(SandboxError, match="RECOVERY_REQUIRED"):
            manager.acquire("swap-test", tmp_path)


def test_command_is_not_sent_after_guard_failure(manager, monkeypatch):
    record = SimpleNamespace(session_id="command-test", container_id="a" * 64, active=True, stop_failed=False)
    manager._records[record.session_id] = record
    monkeypatch.setattr(manager, "_ensure_hard_swap", Mock(side_effect=SandboxError("MEMORY_LIMIT_UNENFORCEABLE")))
    calls = []
    def docker(*args, **kwargs):
        calls.append(args)
        return result(args, out="false")
    monkeypatch.setattr(manager, "_docker", docker)
    with pytest.raises(SandboxError, match="MEMORY_LIMIT_UNENFORCEABLE"):
        manager.execute(record, "echo FORBIDDEN", 10)
    assert not any(a[0] == "exec" for a in calls)


@pytest.mark.parametrize("active,blocked,registered", [(False, False, True), (True, True, True), (True, False, False)])
def test_revoked_record_cannot_execute(manager, monkeypatch, active, blocked, registered):
    record = SimpleNamespace(session_id="revoked", container_id="a" * 64, active=active, stop_failed=blocked)
    if registered:
        manager._records[record.session_id] = record
    docker = Mock(side_effect=AssertionError("must not contact Docker"))
    monkeypatch.setattr(manager, "_docker", docker)
    with pytest.raises(SandboxError, match="SANDBOX_EXECUTION_REVOKED"):
        manager.execute(record, "echo FORBIDDEN", 10)
    docker.assert_not_called()


@pytest.mark.parametrize("err,code,removed", [("No such container", 1, True), ("No such object", 1, True),
    ("docker socket: No such file or directory", 1, False), ("daemon unavailable", 1, False), ("", 0, False)])
def test_close_does_not_forget_unconfirmed_resource(manager, monkeypatch, tmp_path, err, code, removed):
    record = SandboxRecord("close-test", "a" * 64, tmp_path, "image", 0, 0)
    manager._records[record.session_id] = record
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, code, err=err))
    if removed:
        manager.close(record.session_id)
        assert record.session_id not in manager._records
    else:
        with pytest.raises(SandboxError, match="RECOVERY_REQUIRED"):
            manager.close(record.session_id)
        assert manager._records[record.session_id] is record
        assert record.active and record.stop_failed
        manager.release(record.session_id)
        assert record.active


def test_daemon_failure_is_not_absence_during_reap(manager, monkeypatch, tmp_path):
    record = SandboxRecord("reap-test", "a" * 64, tmp_path, "image", 0, 0)
    manager._records[record.session_id] = record
    monkeypatch.setattr(manager, "_docker", lambda *a, **k: result(a, 1, err="daemon unavailable"))
    assert manager.reap(now=100) == []
    assert manager._records[record.session_id] is record


@pytest.mark.external
@pytest.mark.skipif(os.getenv("AGENT_TEST_REAL_DOCKER") != "1", reason="real Docker explicitly required")
@pytest.mark.parametrize("deny_pin", [False, True])
def test_real_kernel_admission_and_cleanup(manager, monkeypatch, tmp_path, deny_pin):
    manager.image = "glyahh-sandbox-runtime:24.04"
    session = "item1-hard-swap-" + uuid.uuid4().hex
    name = manager._container_name(session)
    marker = "kernel-" + uuid.uuid4().hex
    cid = None
    if deny_pin:
        # 仅撤销宿主补救权限，实际 Docker 与实际内核读回均不替换。
        monkeypatch.setattr(manager, "_pin_hard_swap", Mock(side_effect=SandboxError("MEMORY_LIMIT_UNENFORCEABLE")))
    try:
        if deny_pin:
            with pytest.raises(SandboxError, match="MEMORY_LIMIT_UNENFORCEABLE"):
                manager.acquire(session, tmp_path)
            record = manager._records[session]
            cid = record.container_id
            assert not record.active and not record.stop_failed
            inspected = manager._docker("inspect", record.container_id)
            assert json.loads(inspected.stdout)[0]["State"]["Running"] is False
            assert not (tmp_path / marker).exists()
        else:
            record = manager.acquire(session, tmp_path)
            cid = record.container_id
            assert manager._swap_is_zero(record.container_id)
            command = manager.execute(record, "printf '%s' " + marker, 10)
            assert command.returncode == 0 and command.stdout == marker
            manager.release(session)
            # 恢复/复用同样必须重新核对实际限额。
            reused = manager.acquire(session, tmp_path)
            assert reused.reused and reused.container_id == record.container_id
            assert manager._swap_is_zero(reused.container_id)
        manager.close(session)
        absent = manager._docker("inspect", name, check=False)
        assert absent.returncode != 0 and "no such" in absent.stderr.lower()
    finally:
        manager._docker("rm", "-f", name, check=False)
        inspected = manager._docker("inspect", name, check=False)
        assert inspected.returncode != 0 and "no such" in inspected.stderr.lower()
        print(json.dumps({"sessionId": session, "containerId": cid, "containerName": name,
                          "denyPin": deny_pin, "cleanup": "CONFIRMED_ABSENT"}))


@pytest.mark.external
@pytest.mark.skipif(os.getenv("AGENT_TEST_REAL_DOCKER") != "1", reason="real Docker explicitly required")
def test_real_formal_runtime_refuses_before_model(manager, monkeypatch, tmp_path):
    # get_env 会加载整份 .env；该安全反例不得给后续协议子进程带入真实模型凭据。
    monkeypatch.setenv("AGENT_ENV_FILE", str(tmp_path / "no-env-file"))
    import backends.docker_sandbox as docker
    import server
    from core.events import Emitter
    from core.runtime import run_one

    manager.image = "ubuntu:24.04"
    session = "item1-runtime-swap-" + uuid.uuid4().hex
    name = manager._container_name(session)
    monkeypatch.setattr(docker, "_MANAGER", manager)
    monkeypatch.setattr(manager, "_pin_hard_swap", Mock(side_effect=SandboxError("MEMORY_LIMIT_UNENFORCEABLE")))
    monkeypatch.setenv("AGENT_SANDBOX_BACKEND", "docker")
    build = Mock(side_effect=AssertionError("model must not be initialized"))
    monkeypatch.setattr(server, "build_agent", build)
    stream = io.StringIO()
    settings = SimpleNamespace(model=SimpleNamespace(api_key="fixture-never-sent", model_id="fixture"))
    try:
        run_one({"runId": "201", "threadId": session, "appId": "101", "roundId": "202",
                 "workspace": {"root": str(tmp_path.parent), "name": tmp_path.name},
                 "input": {"message": "write forbidden.py", "taskKind": "coding"}},
                settings, Emitter(stream, "201"))
        events = [json.loads(line) for line in stream.getvalue().splitlines()]
        assert any(e["event"] == "error" and e["code"] == "SANDBOX_UNAVAILABLE" for e in events)
        assert not any(e["event"] == "run.completed" for e in events)
        assert not (tmp_path / "forbidden.py").exists()
        build.assert_not_called()
        assert manager._docker("inspect", "--format", "{{.State.Running}}", name).stdout.strip() == "false"
        cid = manager._records[session].container_id
        manager.close(session)
        print(json.dumps({"sessionId": session, "containerId": cid, "containerName": name,
                          "formalRuntime": "SANDBOX_UNAVAILABLE", "modelInitialized": False}))
    finally:
        manager._docker("rm", "-f", name, check=False)
        absent = manager._docker("inspect", name, check=False)
        assert absent.returncode != 0 and "no such" in absent.stderr.lower()
        print(json.dumps({"containerName": name, "cleanup": "CONFIRMED_ABSENT"}))
