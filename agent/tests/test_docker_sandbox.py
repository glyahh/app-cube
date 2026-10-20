from __future__ import annotations

import json


import subprocess
import threading
import time
from pathlib import Path

import pytest

from backends.docker_sandbox import (
    ConfigCheckResult,
    DockerSandboxManager,
    SandboxBusy,
    SandboxError,
    SandboxRecord,
)


@pytest.fixture(autouse=True)
def kernel_swap_readback(monkeypatch):
    # 生命周期夹具不模拟内核；硬 swap 正反例另见 test_docker_hard_swap.py。
    monkeypatch.setattr(DockerSandboxManager, "_swap_is_zero", lambda self, cid: True)
    monkeypatch.setattr(DockerSandboxManager, "_verify_runtime", lambda self, cid: None)


def _make_inspect_json(
    name: str,
    session_id: str,
    workspace: Path,
    image: str = "glyahh-sandbox-runtime:24.04",
    status: str = "running",
    cpus: float = 2.0,
    mount_dest: str = "/workspace",
    mount_mode: str = "rw",
    wrong_source: str | None = None,
    container_id: str | None = None,
    labels: dict[str, str] | None = None,
) -> str:
    cid = container_id or ("cid-" + name)
    wsl_src = wrong_source or DockerSandboxManager._wsl_path(workspace)
    if labels is None:
        labels = {"glyahh.session_id": session_id}
    data = [{
        "Id": cid,
        "Name": "/" + name,
        "State": {
            "Status": status,
            "Running": status == "running",
        },
        "Config": {
            "Image": image,
            "WorkingDir": "/workspace",
            "Labels": labels,
        },
        "HostConfig": {
            "NanoCpus": int(cpus * 1_000_000_000),
            "Binds": [
                f"{wsl_src}:{mount_dest}:{mount_mode}",
            ],
        },
        "Mounts": [
            {
                "Type": "bind",
                "Source": wsl_src,
                "Destination": mount_dest,
                "Mode": mount_mode,
                "RW": "ro" not in mount_mode,
            }
        ],
    }]
    return json.dumps(data)


def test_1_create_when_not_exists(monkeypatch, tmp_path):
    """1. 同名容器不存在时创建成功。"""
    manager = DockerSandboxManager()
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 1, "", "Error: No such container")
        if args[0] == "create":
            return subprocess.CompletedProcess(args, 0, "container-new\n", "")
        if args[0] == "start":
            return subprocess.CompletedProcess(args, 0, "", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    record = manager.acquire("session-1", tmp_path, run_id="run-1")

    assert record.container_id == "container-new"
    assert record.reused is False
    assert any(call[0] == "create" for call in calls)
    assert any(call[0] == "start" and "container-new" in call for call in calls)
    manager.shutdown()


def test_2_reuse_when_running_and_config_matches(monkeypatch, tmp_path):
    """2. 同名运行中容器配置匹配时复用。"""
    manager = DockerSandboxManager()
    name = manager._container_name("session-1")
    inspect_json = _make_inspect_json(name, "session-1", tmp_path, status="running")
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 0, inspect_json, "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    record = manager.acquire("session-1", tmp_path, run_id="run-1")

    assert record.container_id == "cid-" + name
    assert record.reused is True
    assert not any(call[0] == "create" for call in calls)
    assert not any(call[0] == "start" for call in calls)
    manager.shutdown()


def test_3_start_and_reuse_when_stopped_and_config_matches(monkeypatch, tmp_path):
    """3. 同名已停止容器配置匹配时启动并复用。"""
    manager = DockerSandboxManager()
    name = manager._container_name("session-1")
    inspect_json = _make_inspect_json(name, "session-1", tmp_path, status="exited")
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 0, inspect_json, "")
        if args[0] == "start":
            return subprocess.CompletedProcess(args, 0, "", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    record = manager.acquire("session-1", tmp_path, run_id="run-1")

    assert record.container_id == "cid-" + name
    assert record.reused is True
    assert not any(call[0] == "create" for call in calls)
    assert any(call[0] == "start" and ("cid-" + name) in call for call in calls)
    manager.shutdown()


def test_4_rebuild_when_config_mismatch_and_same_session(monkeypatch, tmp_path):
    """4. 同名容器配置不匹配时安全重建。"""
    manager = DockerSandboxManager()
    name = manager._container_name("session-1")
    # 容器镜像为旧镜像 ubuntu:22.04，期望为 ubuntu:24.04
    old_inspect = _make_inspect_json(name, "session-1", tmp_path, image="ubuntu:22.04", status="running")
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 0, old_inspect, "")
        if args[0] == "create":
            return subprocess.CompletedProcess(args, 0, "new-rebuilt-container\n", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    record = manager.acquire("session-1", tmp_path, run_id="run-1")

    assert record.container_id == "new-rebuilt-container"
    assert record.reused is False
    # 确认旧容器被停止并删除
    assert any(call[0] == "stop" and ("cid-" + name) in call for call in calls)
    assert any(call[0] == "rm" and ("cid-" + name) in call for call in calls)
    # 确认使用原名称重新创建
    assert any(call[0] == "create" and name in call for call in calls)
    manager.shutdown()


def test_5_reuse_on_create_conflict(monkeypatch, tmp_path):
    """5. docker create 发生 Conflict 时重新 inspect 后成功复用。"""
    manager = DockerSandboxManager()
    name = manager._container_name("session-1")
    inspect_json = _make_inspect_json(name, "session-1", tmp_path, status="running")
    calls = []
    inspect_count = 0

    def fake(*args, check=True, timeout=30):
        nonlocal inspect_count
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            inspect_count += 1
            if inspect_count == 1:
                # 初始 inspect 容器未就绪（模拟并发创建竞态）
                return subprocess.CompletedProcess(args, 1, "", "No such container")
            # 第二次 inspect（Conflict 重试）返回已建好的匹配容器
            return subprocess.CompletedProcess(args, 0, inspect_json, "")
        if args[0] == "create":
            # 抛出 Docker Conflict 错误
            raise SandboxError(f'Error response from daemon: Conflict. The container name "/{name}" is already in use')
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    record = manager.acquire("session-1", tmp_path, run_id="run-1")

    assert record.container_id == "cid-" + name
    assert record.reused is True
    manager.shutdown()


def test_6_no_delete_when_attribution_unconfirmed(monkeypatch, tmp_path):
    """6. 无法确认归属时不删除容器。"""
    manager = DockerSandboxManager()
    name = manager._container_name("session-1")
    # 容器存在同名，但 session 标签属于 other-session
    foreign_inspect = _make_inspect_json(name, "other-session", tmp_path, status="running")
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 0, foreign_inspect, "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)

    with pytest.raises(SandboxError) as exc_info:
        manager.acquire("session-1", tmp_path, run_id="run-1")

    assert "归属无法确认" in str(exc_info.value) or "容器配置冲突" in str(exc_info.value)
    # 绝不删除未确认归属的容器
    assert not any(call[0] == "rm" for call in calls)
    manager.shutdown()


def test_7_concurrent_create_single_container(tmp_path):
    """7. 并发创建同一 session 时最终只存在一个容器。"""
    session_id = "session-concurrent"
    name = DockerSandboxManager._container_name(session_id)
    cid = "cid-" + name

    container_state: dict[str, dict] = {}
    lock = threading.Lock()
    created_count = 0

    def fake_docker(manager_self, *args, check=True, timeout=30):
        nonlocal created_count
        cmd = args[0]
        if cmd == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if cmd == "inspect":
            target = args[1] if len(args) > 1 else ""
            with lock:
                if target in container_state or (target and target.lstrip("/") in container_state):
                    return subprocess.CompletedProcess(args, 0, _make_inspect_json(name, session_id, tmp_path, status="running"), "")
                return subprocess.CompletedProcess(args, 1, "", "No such container")
        if cmd == "create":
            with lock:
                if name in container_state:
                    raise SandboxError(f'Error response from daemon: Conflict. The container name "/{name}" is already in use')
                created_count += 1
                container_state[name] = {"id": cid, "status": "running"}
                return subprocess.CompletedProcess(args, 0, f"{cid}\n", "")
        if cmd == "start":
            return subprocess.CompletedProcess(args, 0, "", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    # 模拟不同 worker 进程独立持有的 manager 实例
    manager1 = DockerSandboxManager()
    manager2 = DockerSandboxManager()
    manager1._docker = lambda *a, **kw: fake_docker(manager1, *a, **kw)
    manager2._docker = lambda *a, **kw: fake_docker(manager2, *a, **kw)

    results = []
    errors = []

    def worker(mgr, run_id):
        try:
            rec = mgr.acquire(session_id, tmp_path, run_id=run_id)
            results.append(rec)
        except Exception as e:
            errors.append(e)

    t1 = threading.Thread(target=worker, args=(manager1, "run-1"))
    t2 = threading.Thread(target=worker, args=(manager2, "run-2"))
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    assert not errors, f"并发 acquire 出现异常: {errors}"
    assert len(results) == 2
    # 两个 manager 最终引用同一个 container_id
    assert results[0].container_id == results[1].container_id == cid
    # 最终只调用了一次 docker create
    assert created_count == 1

    manager1.shutdown()
    manager2.shutdown()


def test_8_workspace_mount_and_container_name(monkeypatch, tmp_path):
    """8. 验证 workspace 挂载路径和容器名称保持正确。"""
    manager = DockerSandboxManager()
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 1, "", "No such container")
        if args[0] == "create":
            return subprocess.CompletedProcess(args, 0, "container-test-8\n", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    manager.acquire("session-contract", tmp_path, run_id="run-1")

    create_calls = [c for c in calls if c[0] == "create"]
    assert len(create_calls) == 1
    create_args = list(create_calls[0])

    expected_name = manager._container_name("session-contract")
    expected_mount = f"{manager._wsl_path(tmp_path)}:/workspace:rw"

    assert "--name" in create_args
    assert create_args[create_args.index("--name") + 1] == expected_name
    assert "--workdir" in create_args
    assert create_args[create_args.index("--workdir") + 1] == "/workspace"
    assert "-v" in create_args
    assert create_args[create_args.index("-v") + 1] == expected_mount
    assert "--label" in create_args
    assert f"glyahh.session_id=session-contract" in create_args
    assert create_args[create_args.index("--memory-swap") + 1] == create_args[create_args.index("--memory") + 1]

    manager.shutdown()


def test_9_reap_syncs_records_with_docker_state(monkeypatch, tmp_path):
    """9. reaper 清理时同步清理容器记录，避免内存状态与 Docker 实际状态不一致。"""
    manager = DockerSandboxManager(idle_cpu_minutes=1, stop_idle_minutes=2, destroy_idle_minutes=3)
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "inspect":
            # 模拟容器已被外部删除，inspect 失败
            return subprocess.CompletedProcess(args, 1, "", "No such container")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    now = time.time()
    manager._records["dead-session"] = SandboxRecord("dead-session", "c-dead", tmp_path, "image", now, now)

    destroyed = manager.reap(now=now)
    assert "dead-session" in destroyed
    assert "dead-session" not in manager._records

    manager.shutdown()


def test_session_reuses_container_and_releases(monkeypatch, tmp_path):
    """会话复用与释放端到端测试。"""
    manager = DockerSandboxManager(idle_cpu_minutes=10, stop_idle_minutes=30, destroy_idle_minutes=120)
    name = manager._container_name("session-1")
    inspect_json = _make_inspect_json(name, "session-1", tmp_path, status="running")
    calls = []
    inspect_count = 0

    def fake(*args, check=True, timeout=30):
        nonlocal inspect_count
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            inspect_count += 1
            if inspect_count == 1:
                return subprocess.CompletedProcess(args, 1, "", "No such container")
            return subprocess.CompletedProcess(args, 0, inspect_json, "")
        if args[0] == "create":
            return subprocess.CompletedProcess(args, 0, "cid-" + name + "\n", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    first = manager.acquire("session-1", tmp_path, run_id="run-1")
    manager.release("session-1")
    second = manager.acquire("session-1", tmp_path, run_id="run-2")

    assert first.container_id == second.container_id == "cid-" + name
    assert second.reused is True
    # 第二次 acquire 没有再次调用 create
    assert len([call for call in calls if call[0] == "create"]) == 1
    manager.shutdown()


@pytest.mark.parametrize("legacy", [True, False])
def test_idle_container_restores_cpu_without_recreation(monkeypatch, tmp_path, legacy):
    manager = DockerSandboxManager()
    name = manager._container_name("session-idle")
    info = json.loads(_make_inspect_json(name, "session-idle", tmp_path, cpus=0.5))[0]
    if legacy:
        info["Config"]["Labels"] = {}
        info["Config"]["Cmd"] = ["sleep", "infinity"]
        info["HostConfig"].update(Privileged=False, CapDrop=["ALL"], SecurityOpt=["no-new-privileges"])
    calls = []
    def fake(*args, **kwargs):
        calls.append(args)
        return subprocess.CompletedProcess(args, 0, json.dumps([info]) if args[0] == "inspect" else "", "")
    monkeypatch.setattr(manager, "_docker", fake)
    try:
        record = manager.acquire("session-idle", tmp_path)
        assert record.reused and record.container_id == info["Id"]
        assert ("update", "--cpus", "2", info["Id"]) in calls
        assert not any(c[0] in {"rm", "stop", "create"} for c in calls)
        if legacy:
            info["Mounts"][0]["Source"] = "/unrelated"
            assert not manager._validate_container_config(info, "session-idle", tmp_path).belongs_to_session
    finally:
        manager.shutdown()
