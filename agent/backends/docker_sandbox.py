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


@dataclass
class ConfigCheckResult:
    """容器配置校验结果。"""
    matches: bool
    belongs_to_session: bool
    diffs: list[str]


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
    def _container_name(session_id: str) -> str:
        return "glyahh-sbx-" + uuid.uuid5(uuid.NAMESPACE_DNS, session_id).hex[:16]

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

    @staticmethod
    def _normalize_mount_path(path: str) -> str:
        text = str(path).replace("\\", "/").rstrip("/")
        if len(text) >= 2 and text[1] == ":":
            return "/mnt/" + text[0].lower() + text[2:].lower()
        return text.lower()

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

    def _inspect_container(self, name_or_id: str) -> dict | None:
        """检查并返回指定容器的 inspect 字典；容器不存在或解析失败时返回 None。"""
        result = self._docker("inspect", name_or_id, check=False)
        if result.returncode != 0 or not result.stdout.strip():
            return None
        try:
            data = json.loads(result.stdout)
            if isinstance(data, list) and len(data) > 0 and isinstance(data[0], dict):
                return data[0]
            if isinstance(data, dict):
                return data
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.warning("解析 docker inspect 输出失败: %s", result.stdout[:200])
        return None

    def _validate_container_config(self, container_info: dict, session_id: str,
                                  workspace: Path) -> ConfigCheckResult:
        """校验容器关键属性，确认配置是否完全匹配且属于当前 session。"""
        diffs: list[str] = []

        # 1. 容器名称校验
        expected_name = self._container_name(session_id)
        actual_name = (container_info.get("Name") or "").lstrip("/")
        if actual_name != expected_name:
            diffs.append(f"容器名称不匹配: 实际={actual_name}, 期望={expected_name}")

        # 2. 镜像校验
        config = container_info.get("Config") or {}
        actual_image = config.get("Image") or ""
        if actual_image != self.image:
            diffs.append(f"镜像不匹配: 实际={actual_image}, 期望={self.image}")

        # 3. 工作目录校验
        actual_workdir = config.get("WorkingDir") or ""
        if actual_workdir != "/workspace":
            diffs.append(f"工作目录不匹配: 实际={actual_workdir}, 期望=/workspace")

        # 4. session 标识校验
        labels = config.get("Labels") or {}
        session_label = labels.get("glyahh.session_id") or labels.get("session_id")
        belongs_to_session = (session_label == session_id)

        # 5. workspace 挂载源与目标校验
        expected_wsl_norm = self._normalize_mount_path(self._wsl_path(workspace))
        expected_raw_norm = self._normalize_mount_path(str(workspace))
        mount_matched = False
        mount_diff = "未找到 /workspace 挂载点"

        mounts = container_info.get("Mounts")
        if isinstance(mounts, list) and len(mounts) > 0:
            for m in mounts:
                if m.get("Destination") == "/workspace":
                    actual_src = self._normalize_mount_path(m.get("Source", ""))
                    rw = m.get("RW", True) and ("ro" not in m.get("Mode", ""))
                    if actual_src in (expected_wsl_norm, expected_raw_norm):
                        if rw:
                            mount_matched = True
                        else:
                            mount_diff = f"workspace 挂载模式非读写: {m.get('Mode')}"
                    else:
                        mount_diff = f"workspace 挂载源不匹配: 实际={actual_src}, 期望={expected_wsl_norm}"
                    break

        if not mount_matched and "HostConfig" in container_info:
            binds = (container_info.get("HostConfig") or {}).get("Binds") or []
            for b in binds:
                if ":/workspace" in b:
                    idx = b.index(":/workspace")
                    src = self._normalize_mount_path(b[:idx])
                    parts = b.split(":")
                    mode = parts[-1] if len(parts) >= 3 and parts[-1] in ("rw", "ro") else "rw"
                    if src in (expected_wsl_norm, expected_raw_norm):
                        if mode == "rw":
                            mount_matched = True
                        else:
                            mount_diff = f"workspace 挂载模式非读写: {mode}"
                    else:
                        mount_diff = f"workspace 挂载源不匹配: 实际={src}, 期望={expected_wsl_norm}"
                    break

        if not mount_matched:
            diffs.append(mount_diff)

        # 6. 兼容旧版无标签容器：名称、挂载和旧版安全配置必须同时匹配。
        host_config = container_info.get("HostConfig") or {}
        legacy_matches = (
            "glyahh.session_id" not in labels and "session_id" not in labels
            and not diffs and mount_matched
            and isinstance(mounts, list) and len(mounts) == 1
            and mounts[0].get("Type") == "bind"
            and mounts[0].get("RW") is True
            and self._normalize_mount_path(mounts[0].get("Source", ""))
                in (expected_wsl_norm, expected_raw_norm)
            and config.get("Cmd") == ["sleep", "infinity"]
            and host_config.get("Privileged") is False
            and "ALL" in (host_config.get("CapDrop") or [])
            and "no-new-privileges" in (host_config.get("SecurityOpt") or [])
        )
        if legacy_matches:
            belongs_to_session = True
            logger.info("旧版容器 %s 指纹匹配，保留并复用", expected_name)
        if not belongs_to_session:
            diffs.append(f"session 标识不匹配: 实际={session_label}, 期望={session_id}")

        return ConfigCheckResult(
            matches=len(diffs) == 0,
            belongs_to_session=belongs_to_session,
            diffs=diffs,
        )

    def _resume_container(self, info: dict) -> str:
        """恢复空闲时降低的 CPU 配额，必要时启动容器。"""
        container_id = info["Id"]
        self._docker("update", "--cpus", os.getenv("AGENT_SANDBOX_CPUS", "2"), container_id)
        if (info.get("State") or {}).get("Status", "").lower() != "running":
            self._docker("start", container_id)
        return container_id

    def _create_and_start_container(self, name: str, session_id: str, workspace: Path) -> tuple[str, bool]:
        """创建并启动容器；若发生 Conflict 则重新 inspect 并安全复用。返回 (container_id, is_reused)。"""
        # 1. 执行 docker create 并注入 session 标识
        try:
            result = self._docker("create", "--name", name, "--workdir", "/workspace",
                                  "--label", f"glyahh.session_id={session_id}",
                                  "--network", "bridge", "--pids-limit", "512",
                                  "--cpus", os.getenv("AGENT_SANDBOX_CPUS", "2"),
                                  "--memory", os.getenv("AGENT_SANDBOX_MEMORY", "4g"),
                                  "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                                  "-v", f"{self._wsl_path(workspace)}:/workspace:rw", self.image,
                                  "sleep", "infinity")
            container_id = result.stdout.strip()
            self._docker("start", container_id)
            return container_id, False
        except SandboxError as exc:
            err_msg = str(exc)
            if "conflict" in err_msg.lower() or "already in use" in err_msg.lower():
                # 2. 对 Docker Conflict 做一次重新 inspect + 复用，避免并发竞态
                logger.info("创建容器 %s 遇到 Conflict，重新 inspect 并尝试复用", name)
                retry_info = self._inspect_container(name)
                if retry_info:
                    check = self._validate_container_config(retry_info, session_id, workspace)
                    if check.matches:
                        cid = self._resume_container(retry_info)
                        return cid, True
                    raise SandboxError(f"容器配置冲突：并发创建检测到容器配置不匹配（{', '.join(check.diffs)}）")
                raise SandboxBusy(f"Session {session_id} 容器正在并发初始化中，请重试") from exc
            raise

    def acquire(self, session_id: str, workspace: Path, *, run_id: str = "") -> SandboxRecord:
        if not session_id:
            raise SandboxError("缺少 threadId/sessionId，无法复用 Session 沙箱")
        workspace = Path(workspace).resolve()
        workspace.mkdir(parents=True, exist_ok=True)
        with self._lock:
            self._healthy()
            now = time.time()
            record = self._records.get(session_id)
            if record and record.active:
                raise SandboxBusy(f"Session {session_id} 已有任务执行中")
            if record and record.workspace != workspace:
                raise SandboxError("同一 Session 不允许切换 workspace")

            name = self._container_name(session_id)
            # 1. 创建容器前先按完整容器名执行 docker inspect
            container_info = self._inspect_container(name)

            if container_info is None:
                # 2. 容器不存在：正常执行 docker create
                container_id, reused = self._create_and_start_container(name, session_id, workspace)
            else:
                # 3. 容器存在：执行配置校验
                check = self._validate_container_config(container_info, session_id, workspace)
                if check.matches:
                    # 容器存在且配置匹配：复用现有容器
                    container_id = self._resume_container(container_info)
                    reused = True
                else:
                    # 容器存在但配置不匹配
                    if not check.belongs_to_session:
                        # 无法确认归属时不删除容器
                        logger.error("容器 %s 存在但无法确认归属当前 Session %s（%s），禁止删除",
                                     name, session_id, ", ".join(check.diffs))
                        raise SandboxError(f"容器配置冲突：容器 {name} 存在但归属无法确认")
                    # 确认属于当前 session 且配置不匹配：先记录差异，停止并删除该容器，使用原名称重新创建
                    logger.warning("容器 %s 属于当前 Session 但配置不匹配，差异：%s；停止并重建容器",
                                   name, ", ".join(check.diffs))
                    self._docker("stop", "-t", "2", container_info["Id"], check=False)
                    self._docker("rm", "-f", container_info["Id"], check=False)
                    self._records.pop(session_id, None)
                    container_id, reused = self._create_and_start_container(name, session_id, workspace)

            was_reused = reused or bool(record and record.last_run_id)
            created_at = record.created_at if (record and was_reused) else now
            new_record = SandboxRecord(
                session_id=session_id,
                container_id=container_id,
                workspace=workspace,
                image=self.image,
                created_at=created_at,
                last_active_at=now,
                last_run_id=run_id,
                active=True,
                reused=was_reused,
            )
            self._records[session_id] = new_record
            return new_record

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

                # 1. 检查容器在 Docker 中是否仍然有效，避免内存状态与实际脱节
                inspect = self._docker("inspect", "-f", "{{.State.Status}}", record.container_id, check=False)
                if inspect.returncode != 0:
                    self._records.pop(session_id, None)
                    destroyed.append(session_id)
                    continue

                status = inspect.stdout.strip().lower()
                if status in {"", "dead", "removing"}:
                    self._docker("rm", "-f", record.container_id, check=False)
                    self._records.pop(session_id, None)
                    destroyed.append(session_id)
                    continue

                # 2. 按空闲和存活时间执行生命周期操作
                idle = now - record.last_active_at
                age = now - record.created_at
                if idle >= self.destroy_idle_seconds or age >= self.max_lifetime_seconds:
                    self._docker("rm", "-f", record.container_id, check=False)
                    self._records.pop(session_id, None)
                    destroyed.append(session_id)
                elif idle >= self.stop_idle_seconds:
                    if status == "running":
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


__all__ = [
    "DockerSandboxBackend",
    "DockerSandboxManager",
    "SandboxError",
    "SandboxBusy",
    "ConfigCheckResult",
]
