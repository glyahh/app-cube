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
import stat
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
from backends.secret_files import SecretSafeFilesMixin
from env_utils import get_env

logger = logging.getLogger(__name__)
MAX_OUTPUT_CHARS = 30_000
DEFAULT_RUNTIME_IMAGE = "glyahh-sandbox-runtime:24.04"
RUNTIME_CHECK = "set -e; node --version; npm --version; python3 --version; java -version; mvn --version; sqlite3 --version"


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
    stop_failed: bool = False


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
        self.image = image or get_env("AGENT_SANDBOX_IMAGE", DEFAULT_RUNTIME_IMAGE).strip() or DEFAULT_RUNTIME_IMAGE
        self.idle_cpu_seconds = idle_cpu_minutes * 60
        self.stop_idle_seconds = stop_idle_minutes * 60
        self.destroy_idle_seconds = destroy_idle_minutes * 60
        self.max_lifetime_seconds = max_lifetime_hours * 3600
        self._records: dict[str, SandboxRecord] = {}
        self._lock = threading.RLock()
        # docker info 在 WSL 下要起一次 CLI，每次 acquire 都调会白白多几秒。
        self._healthy_at = 0.0
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
            return ["wsl.exe", "-d", distro, "--exec", "timeout", str(timeout), "docker"]
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
            detail = cls._decode(exc.stderr or exc.stdout).strip()[:500]
            raise SandboxError(detail or "Docker 操作失败") from exc

    def verify_image_runtime(self) -> None:
        """不挂载工程，确认所选镜像的运行时；不自动拉取或安装。"""
        self._docker("image", "inspect", self.image)
        name = "glyahh-runtime-check-" + uuid.uuid4().hex
        try:
            self._docker("run", "--rm", "--pull", "never", "--name", name,
                         "--network", "none", "--cap-drop", "ALL",
                         "--security-opt", "no-new-privileges", "--pids-limit", "64",
                         "--memory", "512m", "--cpus", "1", "--entrypoint", "bash",
                         self.image, "-lc", RUNTIME_CHECK, timeout=30)
        except Exception:
            self._docker("rm", "-f", name, check=False)
            result = self._docker("inspect", name, check=False)
            if not self._confirmed_absent(result):
                raise SandboxError("运行时预检容器清理未确认：" + name)
            raise

    def _verify_runtime(self, container_id: str) -> None:
        # 从根目录检查工具版本，避免读取工程里的 Maven 启动配置。
        self._docker("exec", "-w", "/", container_id, "bash", "-lc", RUNTIME_CHECK, timeout=30)

    @staticmethod
    def _decode(raw: bytes | None) -> str:
        if not raw:
            return ""
        # WSL警告是UTF-16LE，后接的Linux stderr是UTF-8；不能按整段长度猜编码。
        if raw.startswith('wsl:'.encode('utf-16-le')):
            end = raw.find(b'\n\x00')
            if end >= 0:
                return raw[:end + 2].decode('utf-16-le', errors='replace') + DockerSandboxManager._decode(raw[end + 2:])
        for encoding in ("utf-8", "utf-16", "gbk"):
            try:
                return raw.decode(encoding).replace("\x00", "")
            except UnicodeDecodeError:
                continue
        return raw.decode("utf-8", errors="replace")

    def _healthy(self) -> None:
        # 30 秒内复用上次结果：容器配置与 swap 仍每次核对，这里只省掉重复探活。
        now = time.time()
        if now - self._healthy_at < 30:
            return
        self._docker("info", "--format", "{{.ServerVersion}}", timeout=10)
        self._healthy_at = now

    def _swap_is_zero(self, container_id: str) -> bool:
        result = self._docker("exec", container_id, "cat",
                              "/sys/fs/cgroup/memory.swap.max", check=False)
        return result.returncode == 0 and result.stdout.strip() == "0"

    def _pin_hard_swap(self, container_id: str) -> None:
        """仅修改已核对容器的 cgroup；不能强制限制时拒绝执行。"""
        if not re.fullmatch(r"[0-9a-f]{64}", container_id):
            raise SandboxError("MEMORY_LIMIT_UNENFORCEABLE")
        prefix = self._docker_command()
        # 补救只面向同一个本地引擎；Linux平台Worker需核对root与Unix端点。
        wsl = Path(prefix[0]).name.lower() == "wsl.exe"
        if not wsl and not self._native_root_engine(prefix):
            raise SandboxError("MEMORY_LIMIT_UNENFORCEABLE")
        pid = self._docker("inspect", "--format", "{{.State.Pid}}", container_id).stdout.strip()
        if not re.fullmatch(r"[1-9][0-9]*", pid):
            raise SandboxError("MEMORY_LIMIT_UNENFORCEABLE")
        script = ('set -eu; cg=$(sed -n "s/^0:://p" /proc/' + pid + '/cgroup); '
                  '[ -n "$cg" ] && [ "$cg" != / ]; '
                  'case "$cg" in /*) ;; *) exit 1;; esac; '
                  'case "$cg" in *' + container_id + '*) ;; *) exit 1;; esac; '
                  'echo 0 > "/sys/fs/cgroup${cg}/memory.swap.max"; '
                  '[ "$(cat "/sys/fs/cgroup${cg}/memory.swap.max")" = 0 ]')
        try:
            command = [prefix[0], "-d", prefix[2], "-u", "root", "--exec", "sh", "-c", script] if wsl else ["sh", "-c", script]
            result = subprocess.run(command,
                                    capture_output=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SandboxError("MEMORY_LIMIT_UNENFORCEABLE") from exc
        if result.returncode != 0:
            raise SandboxError("MEMORY_LIMIT_UNENFORCEABLE")

    def _native_root_engine(self, prefix) -> bool:
        if Path(prefix[0]).name != 'docker' or getattr(os, 'geteuid', lambda: -1)() != 0:
            return False
        endpoint = os.environ.get('DOCKER_HOST')
        if not endpoint:
            result = self._docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}', check=False)
            if result.returncode != 0:
                return False
            endpoint = result.stdout.strip()
        if not endpoint.startswith('unix:///'):
            return False
        try:
            return stat.S_ISSOCK(Path(endpoint[7:]).stat().st_mode)
        except OSError:
            return False

    def _ensure_hard_swap(self, container_id: str) -> None:
        if not self._swap_is_zero(container_id):
            self._pin_hard_swap(container_id)
            if not self._swap_is_zero(container_id):
                raise SandboxError("MEMORY_LIMIT_UNENFORCEABLE")

    def _stop_after_guard_failure(self, record: SandboxRecord) -> None:
        """停止未获执行资格的容器；停止未确认则占住槽位。"""
        record.stop_failed = True
        record.active = True
        self._docker("stop", "-t", "2", record.container_id, check=False)
        result = self._docker("inspect", "--format", "{{.State.Running}}",
                              record.container_id, check=False)
        if result.returncode == 0 and result.stdout.strip() == "false":
            record.active = False
            record.stop_failed = False
            return
        raise SandboxError("STOP_FAILED/RECOVERY_REQUIRED")

    @staticmethod
    def _confirmed_absent(result: subprocess.CompletedProcess[str]) -> bool:
        # Docker socket 的 No such file 不是容器不存在。
        return result.returncode != 0 and bool(re.search(
            r"\bno such (?:container|object)\b", result.stderr, re.IGNORECASE))

    def _remove_confirmed(self, record: SandboxRecord) -> None:
        record.stop_failed = True
        record.active = True
        self._docker("rm", "-f", record.container_id, check=False)
        result = self._docker("inspect", record.container_id, check=False)
        if self._confirmed_absent(result):
            record.stop_failed = False
            record.active = False
            return
        raise SandboxError("STOP_FAILED/RECOVERY_REQUIRED")

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
            result = self._docker("create", "--pull", "never", "--name", name, "--workdir", "/workspace",
                                  "--label", f"glyahh.session_id={session_id}",
                                  "--network", "bridge", "--pids-limit", "512",
                                  "--cpus", os.getenv("AGENT_SANDBOX_CPUS", "2"),
                                  "--memory", os.getenv("AGENT_SANDBOX_MEMORY", "4g"),
                                  "--memory-swap", os.getenv("AGENT_SANDBOX_MEMORY", "4g"),
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
            if record and record.stop_failed:
                raise SandboxError("STOP_FAILED/RECOVERY_REQUIRED")
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
            try:
                self._ensure_hard_swap(container_id)
                self._verify_runtime(container_id)
            except Exception:
                self._stop_after_guard_failure(new_record)
                raise
            return new_record

    def release(self, session_id: str) -> None:
        with self._lock:
            record = self._records.get(session_id)
            if record and not record.stop_failed:
                record.active = False
                record.last_active_at = time.time()

    def execute(self, record: SandboxRecord, command: str, timeout: int) -> subprocess.CompletedProcess[str]:
        with self._lock:
            if record.stop_failed or not record.active or self._records.get(record.session_id) is not record:
                raise SandboxError("SANDBOX_EXECUTION_REVOKED")
        try:
            self._ensure_hard_swap(record.container_id)
        except Exception:
            with self._lock:
                self._stop_after_guard_failure(record)
            raise
        try:
            return self.execute_confirmed(self._docker, record.container_id, command, timeout)
        except (SandboxError, subprocess.TimeoutExpired):
            # 执行状态不明时先确认容器停止；停止未确认必须继续占住资源。
            with self._lock:
                self._stop_after_guard_failure(record)
            raise

    @staticmethod
    def execute_confirmed(docker, container_id: str, command: str, timeout: int) -> subprocess.CompletedProcess[str]:
        """普通与导入容器共用执行回执，不推测 Docker 非零退出的来源。"""
        # 1. 用独立回执确认 Shell 已执行结束，不能把 Docker 自身失败当命令退出。
        marker = "GLYAHH_EXEC_DONE_" + uuid.uuid4().hex
        script = command + '\n__glyahh_status=$?\nprintf "\\n' + marker + ':%s\\n" "$__glyahh_status" >&2\nexit "$__glyahh_status"'
        result = docker("exec", "-i", "-w", "/workspace", container_id,
                              "bash", "-lc", script, check=False, timeout=timeout)
        receipt = f"\n{marker}:{result.returncode}\n"
        if not result.stderr.endswith(receipt):
            raise SandboxError("命令执行状态未确认：" + mask(result.stderr or result.stdout, 500))
        # 2. 回执仅用于执行确认；真实退出码和错误正文原样交回工具层。
        return subprocess.CompletedProcess(result.args, result.returncode, result.stdout,
                                           result.stderr[:-len(receipt)])

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
                    if self._confirmed_absent(inspect):
                        self._records.pop(session_id, None)
                        destroyed.append(session_id)
                    continue

                status = inspect.stdout.strip().lower()
                if status in {"", "dead", "removing"}:
                    self._remove_confirmed(record)
                    self._records.pop(session_id, None)
                    destroyed.append(session_id)
                    continue

                # 2. 按空闲和存活时间执行生命周期操作
                idle = now - record.last_active_at
                age = now - record.created_at
                if idle >= self.destroy_idle_seconds or age >= self.max_lifetime_seconds:
                    self._remove_confirmed(record)
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
            record = self._records.get(session_id)
            if record:
                self._remove_confirmed(record)
                self._records.pop(session_id, None)

    def shutdown(self) -> None:
        self._stop_reaper.set()


_MANAGER = DockerSandboxManager()


class DockerSandboxBackend(SecretSafeFilesMixin, LocalShellBackend):
    """兼容 DeepAgents 文件工具、通过 Session 容器执行 shell 的 backend。"""

    def __init__(self, workspace: Path | str, *, session_id: str, run_id: str = "",
                 timeout: int = 120, manager: DockerSandboxManager | None = None, **kwargs):
        self.workspace = Path(workspace).resolve()
        self.session_id = session_id
        self.manager = manager or _MANAGER
        acquire_started = time.monotonic()
        self.record = self.manager.acquire(session_id, self.workspace, run_id=run_id)
        logger.info("sandbox acquire ms=%d reused=%s session=%s",
                    (time.monotonic() - acquire_started) * 1000, self.record.reused, session_id)
        super().__init__(str(self.workspace), virtual_mode=True, timeout=timeout, **kwargs)

    def _prepare_command(self, command: str) -> str:
        ensure_safe_command(command, self.workspace)
        # 既有 prompt/测试使用 Windows 命令名；容器内统一为 POSIX 等价命令。
        command = re.sub(r"\btype\s+", "cat ", command)
        command = re.sub(r"(?m)^(\s*)dir(?=\s|$)", r"\1ls", command)
        return command

    # ── 工具许可门禁 ──────────────────────────────────────────
    # 与 GuardedShellBackend 同语义，但必须各自实现：裸 super() 在 mixin
    # 里无法推断宿主类，显式 super(类名, self) 才能拿到正确的方法解析顺序。

    def _permission_path(self, file_path: str) -> str:
        text = str(file_path).strip().replace("\\", "/")
        while text.startswith("/"):
            text = text[1:]
        target = Path(text)
        if target.is_absolute():
            target = target.resolve().relative_to(self.workspace)
        return target.as_posix()

    def _perform(self, tool, parameters, operation):
        permissions = getattr(self, "_permissions", None)
        if permissions is None:
            return operation()
        return permissions.perform(lambda: {"tool": tool, **parameters}, operation)

    def write(self, file_path, content):
        relative = self._permission_path(file_path)
        return self._perform("write", {"path": relative, "content": content},
                             lambda: super(DockerSandboxBackend, self).write(file_path, content))

    def edit(self, file_path, old_string, new_string, replace_all=False):
        relative = self._permission_path(file_path)
        return self._perform(
            "edit", {"path": relative, "oldString": old_string, "newString": new_string,
                     "replaceAll": replace_all},
            lambda: super(DockerSandboxBackend, self).edit(file_path, old_string, new_string, replace_all))

    def delete(self, file_path):
        relative = self._permission_path(file_path)
        return self._perform("delete", {"path": relative},
                             lambda: super(DockerSandboxBackend, self).delete(file_path))

    def upload_files(self, files):
        parameters = {"files": [{"path": self._permission_path(path)} for path, _ in files]}
        return self._perform("upload_files", parameters,
                             lambda: super(DockerSandboxBackend, self).upload_files(files))

    async def _permission_blocking(self, fn, *args, **kwargs):
        import asyncio
        return await asyncio.to_thread(fn, *args, **kwargs)

    async def awrite(self, file_path, content):
        return await self._permission_blocking(self.write, file_path, content)

    async def aedit(self, file_path, old_string, new_string, replace_all=False):
        return await self._permission_blocking(self.edit, file_path, old_string, new_string, replace_all)

    async def adelete(self, file_path):
        return await self._permission_blocking(self.delete, file_path)

    async def aupload_files(self, files):
        return await self._permission_blocking(self.upload_files, files)

    async def aexecute(self, command, *, timeout=None):
        return await self._permission_blocking(self.execute, command, timeout=timeout)

    def execute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        command = self._prepare_command(command)
        # 命令也是副作用，必须过同一道门禁。
        return self._perform("execute", {"argv": command.split(), "cwd": "."},
                             lambda: self._execute_checked(command, timeout))

    def _execute_checked(self, command: str, timeout: int | None = None) -> ExecuteResponse:
        self._refresh_secrets()
        effective_timeout = timeout or getattr(self, "_default_timeout", 120)
        proc = self.manager.execute(self.record, command, effective_timeout)
        output = (proc.stdout or "")
        if proc.stderr:
            output += "\n" + "\n".join(line if line.startswith("[stderr]") else f"[stderr] {line}"
                                    for line in proc.stderr.splitlines())
        output = self._mask_output(output)
        truncated = len(output) > MAX_OUTPUT_CHARS
        if truncated:
            output = output[:MAX_OUTPUT_CHARS] + "\n…（输出已截断）"
        if truncated or proc.returncode != 0:
            output += f"\n\nExit code: {proc.returncode}"
        if proc.returncode != 0:
            # 非零退出码是业务结果，但现场需要知道具体命令与码值。
            logger.info("命令失败 command=%s exit=%s", command, proc.returncode)
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
