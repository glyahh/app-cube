"""后端启动前确认 Docker 可用；WSL 无服务管理器时直接拉起守护进程。"""

import subprocess
import time

from backends.docker_sandbox import DockerSandboxManager, SandboxError
from env_utils import REPO_ROOT, get_env


def ensure_sandbox_ready() -> None:
    # 1. 使用与 Agent 相同的配置和 Docker 入口，健康时不重复启动。
    backend = get_env("AGENT_SANDBOX_BACKEND", get_env("AGENT_SANDBOX_TYPE", "local_shell"))
    if backend.strip().lower() != "docker":
        return
    try:
        DockerSandboxManager._docker("info", "--format", "{{.ServerVersion}}", timeout=5)
        print("Docker ready", flush=True)
        return
    except SandboxError:
        if DockerSandboxManager._docker_command()[0] != "wsl.exe":
            raise SandboxError("Docker 不可用，请先启动配置的 Docker 服务；后端启动已中止")

    # 2. 只启动尚未运行的 WSL dockerd，不切换 Docker 入口或修改系统配置。
    distro = get_env("AGENT_WSL_DISTRO", "Ubuntu")
    probe = subprocess.run(
        ["wsl.exe", "-d", distro, "--", "sh", "-c",
         "command -v dockerd >/dev/null || exit 2; pgrep -x dockerd >/dev/null"],
        capture_output=True, timeout=15,
    )
    log_path = REPO_ROOT / "temp" / "logs" / "wsl-dockerd.log"
    if probe.returncode == 1:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("ab") as log:
            subprocess.Popen(
                ["wsl.exe", "-d", distro, "-u", "root", "--", "dockerd"],
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
    elif probe.returncode != 0:
        raise SandboxError(f"无法启动 WSL {distro} 的 Docker，请检查发行版及 dockerd 安装")

    # 3. 等待真实 API 就绪；启动进程成功不等于 Docker 已可用。
    deadline = time.monotonic() + 40
    while True:
        try:
            DockerSandboxManager._docker("info", "--format", "{{.ServerVersion}}", timeout=2)
            print("WSL Docker ready", flush=True)
            return
        except SandboxError as exc:
            if time.monotonic() >= deadline:
                raise SandboxError(f"WSL Docker 未就绪，后端启动已中止；日志：{log_path}") from exc
            time.sleep(1)


if __name__ == "__main__":
    ensure_sandbox_ready()
