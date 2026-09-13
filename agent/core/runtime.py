"""任务分类与 run 生命周期。

分层理由：**产品规则不靠 Prompt 自觉遵守**。「先方案后实施」这类约束要在代码里
有真实分支，不能只写在提示词里靠模型自觉。这个文件就是那个分支所在。

三种改动面因此分开：
- 改审批流程 → 动这里
- 换模型/工具组合 → 动 server.py
- 改事件格式 → 动 streaming_runtime.py
"""

from __future__ import annotations

import logging
from enum import Enum
from pathlib import Path
from typing import Any

from core.events import Emitter, ErrorCode, Event
from core.settings import Settings

logger = logging.getLogger(__name__)


class TaskKind(str, Enum):
    """任务类型。决定走只读分支还是写入分支。"""

    CODING = "coding"
    ANALYSIS = "analysis"
    PLANNING = "planning"
    QA = "qa"
    SYNC = "sync"
    INSPECT = "inspect"
    REVIEW = "review"


# 只读任务不允许产生副作用。参考项目踩过的坑：某个发布工具绕过了只读检查
# 直接调了写接口，所以权限要在执行侧验证，不能只信这个集合。
READ_ONLY_KINDS = frozenset(
    {TaskKind.ANALYSIS, TaskKind.PLANNING, TaskKind.QA, TaskKind.INSPECT, TaskKind.REVIEW}
)


class RunStatus(str, Enum):
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELLED = "cancelled"


def is_read_only(kind: TaskKind) -> bool:
    return kind in READ_ONLY_KINDS


class WorkspaceError(ValueError):
    """工作区不可用。映射成 WORKSPACE_INIT_FAILED。"""


def resolve_workspace(request: dict[str, Any]) -> Path:
    """从请求里取工作区路径并确保它存在。

    **路径由 Java 下发，Python 不做任何拼接** —— Java 是路径真相源，
    Python 是内容真相源。这样现有代码里「三处路径常量不统一」的问题
    在新架构里结构上不可能发生。
    """
    workspace = request.get("workspace") or {}
    root = workspace.get("root")
    name = workspace.get("name")
    if not root or not name:
        raise WorkspaceError("请求缺少 workspace.root 或 workspace.name")

    root_path = Path(root).resolve()
    resolved = (root_path / name).resolve()
    # 即使路径来自 Java 也要校验：它是从 HTTP 请求参数拼出来的
    if not str(resolved).startswith(str(root_path)):
        raise WorkspaceError(f"工作区路径越界：{resolved}")

    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def run_one(request: dict[str, Any], settings: Settings, emitter: Emitter) -> None:
    """跑一次 run。"""
    run_id = request["runId"]
    thread_id = request.get("threadId") or ""
    message = ((request.get("input") or {}).get("message") or "").strip()

    try:
        workspace = resolve_workspace(request)
    except WorkspaceError as exc:
        emitter.error(ErrorCode.WORKSPACE_INIT_FAILED, str(exc))
        return

    if not message:
        emitter.error(ErrorCode.INTERNAL, "请求缺少 input.message")
        return

    task_kind = _classify(request, message)

    emitter.emit(
        Event.RUN_STARTED,
        threadId=thread_id,
        appId=str(request.get("appId") or ""),
        roundId=str(request.get("roundId") or ""),
        workspaceName=workspace.name,
        modelId=settings.model.model_id,
        taskKind=task_kind.value,
        resumedFromCheckpoint=False,
    )

    if not settings.model.api_key:
        # 没有模型凭据时退回占位流。
        # 这不是「假装跑通」—— 它的用途是让协议层、进程池、Java 侧的事件映射
        # 在无凭据环境（CI、新机器）也能被完整验证。真实执行需要密钥。
        logger.warning("未配置模型密钥，run 走占位路径：runId=%s", run_id)
        _run_placeholder(message, workspace, emitter)
        return

    _run_agent(request, settings, emitter, workspace, thread_id, task_kind, message)


def _classify(request: dict[str, Any], message: str) -> TaskKind:
    """任务分类。

    Phase 1 先用最简规则：请求里带了就信它，否则按写入类处理。
    模型分类（结构化输出 + 关键词兜底）留到后面 —— 现在没有足够的样本证明
    它比默认值更准，先不引入一次额外的模型调用。
    """
    raw = (request.get("input") or {}).get("taskKind")
    if raw:
        try:
            return TaskKind(str(raw))
        except ValueError:
            logger.warning("未知的 taskKind=%r，按 coding 处理", raw)
    return TaskKind.CODING


def _run_agent(
    request: dict[str, Any],
    settings: Settings,
    emitter: Emitter,
    workspace: Path,
    thread_id: str,
    task_kind: TaskKind,
    message: str,
) -> None:
    from backends.local_shell import GuardedShellBackend
    from env_utils import get_env
    from core.compaction import OverheadEstimator
    from core.persistence import get_checkpointer, get_store
    from core.streaming_runtime import _event_loop, run_agent_with_events
    from retrieval.git_epoch import snapshot
    from retrieval.runtime_context import RunContext, clear_context, set_context
    from retrieval.search_trace import EvidenceLedger, SearchTraceLog
    from server import build_agent
    from tools import CUSTOM_TOOLS

    backend = None
    sandbox_busy_types = ()
    try:
        backend_kind = get_env("AGENT_SANDBOX_BACKEND", get_env("AGENT_SANDBOX_TYPE", "local_shell")).strip().lower()
        if backend_kind == "docker":
            from backends.docker_sandbox import DockerSandboxBackend, SandboxBusy
            sandbox_busy_types = (SandboxBusy,)

            backend = DockerSandboxBackend(workspace, session_id=thread_id,
                                           run_id=str(request.get("runId") or ""))
        else:
            backend = GuardedShellBackend(workspace)
    except Exception as exc:
        logger.exception("工作区初始化失败")
        if get_env("AGENT_SANDBOX_BACKEND", get_env("AGENT_SANDBOX_TYPE", "local_shell")).lower() == "docker":
            code = ErrorCode.SANDBOX_BUSY if isinstance(exc, sandbox_busy_types) else ErrorCode.SANDBOX_UNAVAILABLE
        else:
            code = ErrorCode.WORKSPACE_INIT_FAILED
        emitter.error(code, str(exc), retryable=True)
        return

    # 版本基准先取一次：后面每条 SearchTrace 都绑这个 SHA，
    # 便于判断「这条检索结果是哪个版本的」。
    epoch = snapshot(workspace)
    run_id = str(request.get("runId") or "")
    context = RunContext(
        run_id=run_id,
        app_id=str(request.get("appId") or ""),
        workspace=workspace,
        trace_log=SearchTraceLog(project_id=context_project_id(request), git_sha=epoch.sha),
        ledger=EvidenceLedger(),
    )
    set_context(context)

    checkpointer = _event_loop().run_until_complete(get_checkpointer(settings.postgres_dsn))
    store = _event_loop().run_until_complete(get_store(settings.postgres_dsn))

    # 固定开销估计器由这里持有：装配时给中间件初值，跑起来后用真实 usage 校正。
    # 它不在 messages 里，但不计进去压缩触发点永远到不了。
    overhead = OverheadEstimator()
    # 中间件实例交回来，好把压缩统计挂到终态事件上 —— 否则「压没压」只能翻日志
    compaction_sink: list = []

    try:
        agent = build_agent(
            settings=settings,
            backend=backend,
            checkpointer=checkpointer,
            store=store,
            task_kind=task_kind.value,
            tools=CUSTOM_TOOLS,
            # 给第 0 层压缩指定外置目录：超阈值的大输出写到这里，原位只留引用
            workspace=workspace,
            overhead=overhead,
            compaction_out=compaction_sink,
        )
    except Exception as exc:
        logger.exception("装配 agent 失败")
        emitter.error(ErrorCode.INTERNAL, f"装配 agent 失败：{exc}")
        clear_context()
        return

    emitter.emit(
        Event.STATUS,
        phase="agent_running",
        detail=f"task_kind={task_kind.value}",
        gitEpoch=epoch.label,
        sandbox={
            "mode": "session" if get_env("AGENT_SANDBOX_BACKEND", get_env("AGENT_SANDBOX_TYPE", "local_shell")).lower() == "docker" else "local",
            "reused": bool(getattr(getattr(backend, "record", None), "reused", False)),
            "containerId": getattr(getattr(backend, "record", None), "container_id", None),
        },
    )

    try:
        summary = run_agent_with_events(
            agent=agent,
            messages=[{"role": "user", "content": _build_prompt(request, message, task_kind)}],
            thread_id=thread_id,
            emitter=emitter,
            task_kind=task_kind.value,
            overhead=overhead,
        )
    except Exception as exc:
        logger.exception("run 执行失败")
        emitter.error(_classify_error(exc), str(exc), retryable=False)
        clear_context()
        return
    finally:
        clear_context()
        close = getattr(backend, "close", None)
        if callable(close):
            close()

    # 检索与压缩统计挂到终态事件上，便于回看这一轮到底查了什么、压了什么
    summary.setdefault("usage", {})["search"] = context.trace_log.summary()
    summary["usage"]["evidence"] = len(context.ledger.all())
    summary["usage"]["compaction"] = compaction_sink[0].summary() if compaction_sink else {}
    # 固定开销是压缩能否触发的前提，暴露出来便于核对窗口估计是否合理
    summary["usage"]["fixedOverheadTokens"] = overhead.current()

    emitter.emit(
        Event.RUN_COMPLETED,
        status=summary["status"],
        summary=(summary["text"] or "")[:400],
        usage=summary["usage"],
    )


def context_project_id(request: dict[str, Any]) -> str:
    """知识作用域标识。用工作区名而不是 appId —— 它同时标出产物目录的形状。"""
    workspace = request.get("workspace") or {}
    return str(workspace.get("name") or request.get("appId") or "unknown")


def _build_prompt(request: dict[str, Any], message: str, task_kind: TaskKind) -> str:
    """拼给模型的用户消息。

    工作区信息不写进来 —— 模型用 `ls` 自己看。把目录清单塞进提示词只会制造
    一份很快就会过期的副本。
    """
    parts = [message.strip()]

    init_prompt = ((request.get("input") or {}).get("initPrompt") or "").strip()
    workspace = request.get("workspace") or {}
    if init_prompt and workspace.get("mode") == "create":
        # 首轮才带应用设定；后续轮它已经在对话历史里了
        parts.append(f"\n应用设定：{init_prompt}")

    return "\n".join(parts)


def _classify_error(exc: Exception) -> ErrorCode:
    """把异常归到契约里的错误码。"""
    name = type(exc).__name__
    lowered = str(exc).lower()

    if "context length" in lowered or "maximum context" in lowered:
        return ErrorCode.MODEL_QUOTA_EXCEEDED
    if "rate limit" in lowered or "quota" in lowered or "429" in lowered:
        return ErrorCode.MODEL_QUOTA_EXCEEDED
    if name in ("TimeoutError", "TimeoutException") or "timeout" in lowered:
        return ErrorCode.RUN_DEADLINE_EXCEEDED
    if "checkpoint" in lowered:
        return ErrorCode.CHECKPOINT_ERROR
    if "connection" in lowered or "apiconnection" in lowered:
        return ErrorCode.MODEL_CALL_FAILED
    return ErrorCode.INTERNAL


def _run_placeholder(message: str, workspace: Path, emitter: Emitter) -> None:
    """无密钥时的占位执行。见 _run_agent 调用处的说明。"""
    emitter.emit(Event.STATUS, phase="placeholder", detail="未配置模型密钥，走占位路径")
    emitter.emit(
        Event.TEXT_DELTA,
        messageId="a0",
        content=f"[占位] 已收到需求：{message}\n",
        mode="append",
    )
    emitter.emit(
        Event.TEXT_DELTA,
        messageId="a0",
        content=f"目标工作区：{workspace.name}（存在={workspace.exists()}）\n",
        mode="append",
    )
    emitter.emit(
        Event.RUN_COMPLETED,
        status=RunStatus.SUCCEEDED.value,
        summary="占位路径，未调用模型",
        usage={"inputTokens": 0, "outputTokens": 0, "modelCalls": 0},
    )
