"""运行配置。

一条硬规则贯穿本文件：**所有与上下文占用相关的阈值都是「窗口的函数」，不能是常量。**

原因是设计里那条失效条件 —— 纯绝对阈值在小窗口上会算出大于窗口的值，
于是该层永久不触发，整条链路静默失效，最后以供应商侧的上限错误暴露，
而错误现象和压缩机制看不出任何关联。实际踩过的例子：
OpenCode #45168、Hermes #14690 都是这么挂的。

所以统一走 `clamp_to_window()`，形式固定为 `min(绝对值, 窗口 × 比例)`。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

from env_utils import REPO_ROOT, get_env, get_env_bool

# 窗口解析全部失败时的兜底。宁可低估：低估只会提前压缩，多花一次摘要钱；
# 高估则会永不触发，直接撞供应商上限。
CONSERVATIVE_WINDOW = 32_768


def clamp_to_window(absolute: int, ratio: float, window: int) -> int:
    """把绝对阈值折算到窗口可承受的范围。

    用 min 而不是 max —— max 在小窗口上会得到大于窗口的阈值，
    导致该层永远不触发。见模块 docstring。
    """
    return int(min(absolute, window * ratio))


@dataclass(frozen=True)
class ModelSettings:
    model_id: str = "deepseek-v4-flash"
    base_url: str = "https://api.deepseek.com"
    api_key: str = ""
    temperature: float = 1.0
    # 输出上限。注意这不是窗口 —— 现有 Java 侧把两者混用过。
    max_output_tokens: int = 32_768
    # 显式指定窗口；留 0 表示走动态解析链。
    context_window_override: int = 0
    # 后台整理会话快照用的模型。设计要求：上下文容量必须 >= 主模型。
    summarizer_model_id: str = ""
    summarizer_base_url: str = ""
    summarizer_api_key: str = ""
    # 模型出口二选一：关闭时明确直连，开启时统一走指定入口。
    proxy_enabled: bool = False
    proxy_url: str = ""


@dataclass(frozen=True)
class Limits:
    """单次 run 的硬约束。超时由 Java 侧兜底，这里只做软约束和早停。"""

    deadline_ms: int = 1_800_000
    max_model_calls: int = 60
    # 工具输出进入上下文前的单条预算（字符，非 token）
    tool_output_max_chars: int = 2_000
    # 超过这个字符数就外置写盘、上下文里只留引用
    tool_output_spill_chars: int = 40_000
    # 来源：OpenCode TOOL_OUTPUT_MAX_CHARS / Gemini DEFAULT_TRUNCATE_TOOL_OUTPUT_THRESHOLD


@dataclass(frozen=True)
class WorkspaceSettings:
    """工作区路径**由 Java 侧下发**，这里只是本进程的默认值。

    分工：Java 是路径真相源，Python 是内容真相源。
    Python 不该有自己的路径拼接逻辑 —— 收到什么用什么。
    """

    root: Path = field(default_factory=lambda: Path(get_env("WORKSPACE_ROOT", str(REPO_ROOT / "temp" / "code_output"))))


@dataclass(frozen=True)
class Settings:
    model: ModelSettings
    limits: Limits
    workspace: WorkspaceSettings
    log_level: str
    postgres_dsn: str


def validate_proxy_config(enabled: bool, proxy_url: str) -> None:
    """开启指定代理时必须提供合法的 http(s) URL。"""
    if not enabled:
        return
    try:
        parsed = urlparse(proxy_url)
        hostname = parsed.hostname
        parsed.port
    except ValueError as exc:
        raise ValueError("AGENT_MODEL_PROXY_URL 必须是带主机名的 http(s) URL") from exc
    if parsed.scheme not in {"http", "https"} or not hostname:
        raise ValueError("AGENT_MODEL_PROXY_URL 必须是带主机名的 http(s) URL")


def load_settings() -> Settings:
    proxy_enabled = get_env_bool("AGENT_MODEL_PROXY_ENABLED", False)
    proxy_url = get_env("AGENT_MODEL_PROXY_URL", "").strip()
    validate_proxy_config(proxy_enabled, proxy_url)

    return Settings(
        model=ModelSettings(
            model_id=get_env("AGENT_MODEL_ID", "deepseek-v4-flash"),
            base_url=get_env("AGENT_MODEL_BASE_URL", "https://api.deepseek.com"),
            api_key=get_env("AGENT_MODEL_API_KEY") or get_env("DEEPSEEK_API_KEY"),
            temperature=float(get_env("AGENT_MODEL_TEMPERATURE", "1.0")),
            max_output_tokens=int(get_env("AGENT_MAX_OUTPUT_TOKENS", "32768")),
            context_window_override=int(get_env("AGENT_CONTEXT_WINDOW", "0")),
            summarizer_model_id=get_env("AGENT_SUMMARIZER_MODEL_ID"),
            summarizer_base_url=get_env("AGENT_SUMMARIZER_BASE_URL"),
            summarizer_api_key=get_env("AGENT_SUMMARIZER_API_KEY"),
            proxy_enabled=proxy_enabled,
            proxy_url=proxy_url,
        ),
        limits=Limits(
            deadline_ms=int(get_env("AGENT_DEADLINE_MS", "1800000")),
            max_model_calls=int(get_env("AGENT_MAX_MODEL_CALLS", "60")),
        ),
        workspace=WorkspaceSettings(
            root=Path(get_env("WORKSPACE_ROOT", "") or str(REPO_ROOT / "temp" / "code_output")),
        ),
        log_level=get_env("AGENT_LOG_LEVEL", "INFO"),
        postgres_dsn=os.environ.get("AGENT_POSTGRES_DSN", ""),
    )
