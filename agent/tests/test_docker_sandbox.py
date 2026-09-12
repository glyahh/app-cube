from __future__ import annotations

import subprocess
import time

from backends.docker_sandbox import DockerSandboxManager, SandboxRecord


def test_session_reuses_container_and_releases(monkeypatch, tmp_path):
    manager = DockerSandboxManager(idle_cpu_minutes=10, stop_idle_minutes=30,
                                   destroy_idle_minutes=120)
    calls = []

    def fake(*args, check=True, timeout=30):
        calls.append(args)
        if args[0] == "info":
            return subprocess.CompletedProcess(args, 0, "29.1.3\n", "")
        if args[0] == "inspect":
            return subprocess.CompletedProcess(args, 0, "running\n", "")
        if args[0] == "create":
            return subprocess.CompletedProcess(args, 0, "container-1\n", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(manager, "_docker", fake)
    first = manager.acquire("session-1", tmp_path, run_id="run-1")
    manager.release("session-1")
    second = manager.acquire("session-1", tmp_path, run_id="run-2")
    assert first.container_id == second.container_id == "container-1"
    assert second.reused is True
    assert not any(call[0] == "create" for call in calls[3:])
    manager.shutdown()


def test_reap_stops_and_then_destroys_idle_container(monkeypatch, tmp_path):
    manager = DockerSandboxManager(idle_cpu_minutes=1, stop_idle_minutes=2,
                                   destroy_idle_minutes=3)
    calls = []
    monkeypatch.setattr(manager, "_docker", lambda *args, **kwargs: calls.append(args) or
                         subprocess.CompletedProcess(args, 0, "", ""))
    now = time.time()
    manager._records["s"] = SandboxRecord("s", "c", tmp_path, "image", now - 500,
                                          now - 500)
    assert manager.reap(now=now) == ["s"]
    assert any(call[0] == "rm" for call in calls)
    manager.shutdown()
