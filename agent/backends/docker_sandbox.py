"""Session 级 Docker 沙箱 backend。

文件工具继续使用 DeepAgents 的受控 workspace 实现；shell 命令通过同一个
Session 容器执行。容器只挂载当前 workspace 到 /workspace，不暴露 docker.sock。
"""

from __future__ import annotations

import json
import logging
import os
import re
import subprocess
import threading
import time
import uuid
import shutil
from dataclasses import dataclass
from pathlib import Path

from deepagents.backends.local_shell import LocalShellBackend
from deepagents.backends.protocol import ExecuteResponse

from backends.permissions import ensure_safe_command
from core.masking import mask

logger = logging.getLogger(__name__)
MAX_OUTPUT_CHARS = 30_000


class SandboxError(RuntimeError):
    """Docker 沙箱不可用或生命周期操作失败。"""


class SandboxBusy(SandboxError):
    """同一 Session 已有 Run 正在执行。"""


@dataclass
class SandboxRecord:
    session_id: str
    container_id: str
    workspace: Path
    image: str
    created_at: float
    last_active_at: float
    last_run_id: str = ""
    active: bool = False
    reused: bool = False


class DockerSandboxManager:
    """进程内 Session→容器注册表；宿主机 workspace 是持久化真相源。"""

    def __init__(self, *, image: str | None = None, idle_cpu_minutes: int = 10,
                 stop_idle_minutes: int = 30, destroy_idle_minutes: int = 120,
                 max_lifetime_hours: int = 8):
        self.image = image or os.getenv("AGENT_SANDBOX_IMAGE", "ubuntu:24.04")
        self.idle_cpu_seconds = idle_cpu_minutes * 60
        self.stop_idle_seconds = stop_idle_minutes * 60
        self.destroy_idle_seconds = destroy_idle_minutes * 60
        self.max_lifetime_seconds = max_lifetime_hours * 3600
        self._records: dict[str, SandboxRecord] = {}
        self._lock = threading.RLock()
        self._stop_reaper = threading.Event()
        self._reaper = threading.Thread(target=self._reap_loop, name="docker-sandbox-reaper",
                                         daemon=True)
        self._reaper.start()

    def _reap_loop(self) -> None:
        while not self._stop_reaper.wait(60):
            try:
                self.reap()
            except Exception:
                logger.exception("Docker 沙箱回收失败")

    @staticmethod
    def _docker_command(timeout: int = 30) -> list[str]:
        if shutil.which("docker"):
            return ["docker"]
        if shutil.which("wsl.exe"):
            distro = os.getenv("AGENT_WSL_DISTRO", "Ubuntu")
            # wsl.exe 超时只杀宿主进程，docker 子进程可能继续等待 registry；
            # 用 Linux timeout 包住实际 CLI，保证创建/拉取不会悬挂。
            return ["wsl.exe", "-d", distro, "--", "timeout", str(timeout), "docker"]
        raise SandboxError("Docker CLI 不可用；请安装 docker.exe 或配置 WSL")

    @staticmethod
    def _wsl_path(path: Path) -> str:
        text = str(path.resolve()).replace("\\", "/")
        if len(text) >= 2 and text[1] == ":":
            return "/mnt/" + text[0].lower() + text[2:]
        return text

    @classmethod
    def _docker(cls, *args: str, check: bool = True, timeout: int = 30) -> subprocess.CompletedProcess[str]:
        try:
            raw = subprocess.run([*cls._docker_command(timeout), *args], check=check,
                                  capture_output=True, text=False, timeout=timeout + 5)
            result = subprocess.CompletedProcess(raw.args, raw.returncode,
                                                  cls._decode(raw.stdout), cls._decode(raw.stderr))
            if result.returncode == 124 and check:
                raise SandboxError("Docker 操作超时")
            return result
        except FileNotFoundError as exc:
            raise SandboxError("Docker CLI 不可用") from exc
        except subprocess.TimeoutExpired as exc:
            raise SandboxError("Docker 操作超时") from exc
        except subprocess.CalledProcessError as exc:
            detail = (exc.stderr or exc.stdout or "").strip()[:500]
            raise SandboxError(detail or "Docker 操作失败") from exc

    @staticmethod
    def _decode(raw: bytes | None) -> str:
        if not raw:
            return ""
        for encoding in ("utf-8", "utf-16", "gbk"):
            try:
                return raw.decode(encoding).replace("\x00", "")
            except UnicodeDecodeError:
                continue
        return raw.decode("utf-8", errors="replace")

    def _healthy(self) -> None:
        self._docker("info", "--format", "{{.ServerVersion}}", timeout=10)

    def acquire(self, session_id: str, workspace: Path, *, run_id: str = "") -> SandboxRecord:
        if not session_id:
            raise SandboxError("缺少 threadId/sessionId，无法复用 Session 沙箱")
        workspace = Path(workspace).resolve()
        workspace.mkdir(parents=True, exist_ok=True)
        with self._lock:
            self._healthy()
            now = time.time()
            record = self._records.get(session_id)
            if record:
                if record.active:
                    raise SandboxBusy(f"Session {session_id} 已有任务执行中")
                inspect = self._docker("inspect", "-f", "{{.State.Status}}", record.container_id,
                                       check=False)
                status = inspect.stdout.strip()
                if status in {"", "dead", "removing"}:
                    self._records.pop(session_id, None)
                    record = None
                elif status != "running":
                    self._docker("start", record.container_id)
            if record is None:
                name = "glyahh-sbx-" + uuid.uuid5(uuid.NAMESPACE_DNS, session_id).hex[:16]
                existing = self._docker("inspect", "-f", "{{.Id}}|{{.State.Status}}", name, check=False)
                if existing.returncode == 0 and "|" in existing.stdout.strip():
                    container_id, status = existing.stdout.strip().split("|", 1)
                    if status != "running":
                        self._docker("start", container_id)
                    reused = True
                else:
                    result = self._docker("create", "--name", name, "--workdir", "/workspace",
                                          "--network", "bridge", "--pids-limit", "512",
                                          "--cpus", os.getenv("AGENT_SANDBOX_CPUS", "2"),
                                          "--memory", os.getenv("AGENT_SANDBOX_MEMORY", "4g"),
                                          "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                                          "-v", f"{self._wsl_path(workspace)}:/workspace:rw", self.image,
                                          "sleep", "infinity")
                    container_id = result.stdout.strip()
                    self._docker("start", container_id)
                    reused = False
                record = SandboxRecord(session_id, container_id, workspace, self.image, now, now,
                                       reused=reused)
                self._records[session_id] = record
            elif record.workspace != workspace:
                raise SandboxError("同一 Session 不允许切换 workspace")
            # 记录本次 acquire 之前是否已有 run，不能在覆盖 last_run_id 后再判断。
            was_reused = bool(record.last_run_id)
            record.active = True
            record.reused = was_reused
            record.last_active_at = now
            record.last_run_id = run_id
            return record

    def release(self, session_id: str) -> None:
        with self._lock:
            record = self._records.get(session_id)
            if record:
                record.active = False
                record.last_active_at = time.time()

    def execute(self, record: SandboxRecord, command: str, timeout: int) -> subprocess.CompletedProcess[str]:
        return self._docker("exec", "-i", "-w", "/workspace", record.container_id,
                            "bash", "-lc", command, timeout=timeout)

    def reap(self, now: float | None = None) -> list[str]:
        """清理空闲容器；应由应用定时任务调用。"""
        now = now or time.time()
        destroyed: list[str] = []
        with self._lock:
            for session_id, record in list(self._records.items()):
                if record.active:
                    continue
                idle = now - record.last_active_at
                age = now - record.created_at
                if idle >= self.destroy_idle_seconds or age >= self.max_lifetime_seconds:
                    self._docker("rm", "-f", record.container_id, check=False)
                    self._records.pop(session_id, None)
                    destroyed.append(session_id)
                elif idle >= self.stop_idle_seconds:
                    self._docker("stop", "-t", "2", record.container_id, check=False)
                elif idle >= self.idle_cpu_seconds:
                    self._docker("update", "--cpus", "0.5", record.container_id, check=False)
        return destroyed

    def close(self, session_id: str) -> None:
        with self._lock:
            record = self._records.pop(session_id, None)
            if record:
                self._docker("rm", "-f", record.container_id, check=False)

    def shutdown(self) -> None:
        self._stop_reaper.set()


_MANAGER = DockerSandboxManager()


class DockerSandboxBackend(LocalShellBackend):
    """兼容 DeepAgents 文件工具、通过 Session 容器执行 shell 的 backend。"""

    def __init__(self, workspace: Path | str, *, session_id: str, run_id: str = "",
                 timeout: int = 120, manager: DockerSandboxManager | None = None, **kwargs):
        self.workspace = Path(workspace).resolve()
        self.session_id = session_id
        self.manager = manager or _MANAGER
        self.record = self.manager.acquire(session_id, self.workspace, run_id=run_id)
        super().__init__(str(self.workspace), virtual_mode=True, timeout=timeout, **kwargs)

    def execute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        ensure_safe_command(command, self.workspace)
        effective_timeout = timeout or getattr(self, "_default_timeout", 120)
        # 既有 prompt/测试使用 Windows 命令名；容器内统一为 POSIX 等价命令。
        command = re.sub(r"\btype\s+", "cat ", command)
        command = re.sub(r"(?m)^(\s*)dir(?=\s|$)", r"\1ls", command)
        try:
            proc = self.manager.execute(self.record, command, effective_timeout)
        except (subprocess.TimeoutExpired, SandboxError) as exc:
            if "超时" not in str(exc):
                raise
            return ExecuteResponse(output=f"命令超时（{effective_timeout} 秒）已终止。",
                                   exit_code=124, truncated=False)
        output = (proc.stdout or "")
        if proc.stderr:
            output += "\n".join(line if line.startswith("[stderr]") else f"[stderr] {line}"
                                    for line in proc.stderr.splitlines())
        truncated = len(output) > MAX_OUTPUT_CHARS
        if truncated:
            output = output[:MAX_OUTPUT_CHARS] + "\n…（输出已截断）"
        output = mask(output)
        if truncated or proc.returncode != 0:
            output += f"\n\nExit code: {proc.returncode}"
        return ExecuteResponse(output=output, exit_code=proc.returncode, truncated=truncated)

    def close(self) -> None:
        self.manager.release(self.session_id)


__all__ = ["DockerSandboxBackend", "DockerSandboxManager", "SandboxError", "SandboxBusy"]
