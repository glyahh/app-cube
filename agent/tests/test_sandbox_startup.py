from unittest.mock import Mock

import pytest

import sandbox_startup as startup
from backends.docker_sandbox import SandboxError


@pytest.fixture(autouse=True)
def config(monkeypatch, tmp_path):
    monkeypatch.setattr(startup, "get_env", lambda key, default="": {"AGENT_SANDBOX_BACKEND": "docker"}.get(key, default))
    monkeypatch.setattr(startup, "REPO_ROOT", tmp_path)


def test_healthy_docker_is_not_restarted(monkeypatch):
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", Mock())
    spawn = Mock()
    monkeypatch.setattr(startup.subprocess, "Popen", spawn)
    startup.ensure_sandbox_ready()
    spawn.assert_not_called()


def test_missing_wsl_daemon_is_started_and_checked(monkeypatch):
    check = Mock(side_effect=[SandboxError("socket missing"), Mock()])
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", check)
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker_command", lambda: ["wsl.exe", "-d", "Ubuntu", "--", "docker"])
    monkeypatch.setattr(startup.subprocess, "run", Mock(return_value=Mock(returncode=1)))
    spawn = Mock()
    monkeypatch.setattr(startup.subprocess, "Popen", spawn)
    startup.ensure_sandbox_ready()
    assert check.call_count == 2
    assert spawn.call_args.args[0] == ["wsl.exe", "-d", "Ubuntu", "-u", "root", "--", "dockerd"]


def test_unhealthy_native_docker_fails_without_wsl_fallback(monkeypatch):
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", Mock(side_effect=SandboxError("offline")))
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker_command", lambda: ["docker"])
    with pytest.raises(SandboxError):
        startup.ensure_sandbox_ready()


def test_local_backend_skips_docker(monkeypatch):
    monkeypatch.setattr(startup, "get_env", lambda key, default="": "local_shell")
    check = Mock()
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", check)
    startup.ensure_sandbox_ready()
    check.assert_not_called()


def test_existing_wsl_daemon_is_waited_for_not_duplicated(monkeypatch):
    check = Mock(side_effect=[SandboxError("starting"), Mock()])
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", check)
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker_command", lambda: ["wsl.exe"])
    monkeypatch.setattr(startup.subprocess, "run", Mock(return_value=Mock(returncode=0)))
    spawn = Mock()
    monkeypatch.setattr(startup.subprocess, "Popen", spawn)
    startup.ensure_sandbox_ready()
    spawn.assert_not_called()


def test_unready_daemon_times_out_instead_of_allowing_backend_start(monkeypatch):
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", Mock(side_effect=SandboxError("offline")))
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker_command", lambda: ["wsl.exe"])
    monkeypatch.setattr(startup.subprocess, "run", Mock(return_value=Mock(returncode=0)))
    monkeypatch.setattr(startup.time, "monotonic", Mock(side_effect=[0, 41]))
    with pytest.raises(SandboxError, match="后端启动已中止"):
        startup.ensure_sandbox_ready()


def test_missing_dockerd_is_not_treated_as_ready(monkeypatch):
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker", Mock(side_effect=SandboxError("offline")))
    monkeypatch.setattr(startup.DockerSandboxManager, "_docker_command", lambda: ["wsl.exe"])
    monkeypatch.setattr(startup.subprocess, "run", Mock(return_value=Mock(returncode=2)))
    with pytest.raises(SandboxError, match="dockerd 安装"):
        startup.ensure_sandbox_ready()
