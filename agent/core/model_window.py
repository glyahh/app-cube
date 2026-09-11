"""动态解析模型的上下文窗口。

**不硬编码窗口值** —— 换任何模型都要能正确压缩。

解析优先级：

    1. 显式配置            最可靠，留给明确知道值的场景
    2. 供应商接口探测       OpenAI 兼容的 /v1/models 多数只返 ID 列表，不保证含窗口
    3. 内置模型表          前缀匹配。是「可更新的数据」而不是代码分支
    4. 保守默认            32_768。宁可低估

**安全方向是「宁可低估」**：低估 → 提前压缩 → 只多花一次摘要钱；
高估 → 永不触发 → 用户撞供应商上限、请求直接失败。

运行时自适应：捕获到上限类错误时，从错误消息里解析供应商给出的真实上限，
写回进程内缓存并告警。这样第一次撞墙之后就不会再撞。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass

from core.settings import CONSERVATIVE_WINDOW

logger = logging.getLogger(__name__)

# 内置表：前缀匹配。命中多个取最长前缀，避免 "deepseek-v4" 抢走 "deepseek-v4-pro"。
# 加模型改这里，不要去改判断逻辑。
KNOWN_WINDOWS: dict[str, int] = {
    # 配置里写的是 deepseek-flash（不带 v4），别名也要有，否则会退化到保守默认值。
    # 实测：漏了这一条时窗口被当成 32768，压缩阈值随之整体偏小。
    "deepseek-flash": 1_000_000,
    "deepseek-v4-flash": 1_000_000,
    "deepseek-v4-pro": 1_000_000,
    "deepseek-chat": 1_000_000,       # 旧别名，映射到 v4-flash 非思考模式
    "deepseek-reasoner": 1_000_000,   # 旧别名，映射到 v4-flash 思考模式
    "qwen3.6-max": 262_144,
    "qwen3.6-plus": 131_072,
    "qwen3.6-flash": 131_072,
}

# 供应商在报错里写上限时的常见说法。宁可多试几个，也不要漏。
_ERROR_PATTERNS = (
    re.compile(r"maximum context length is (\d+)", re.I),
    re.compile(r"max(?:imum)?[_ ]context[_ ]length[\"'\s:=]+(\d+)", re.I),
    re.compile(r"context[_ ]length[_ ]exceeded.*?(\d{4,})", re.I),
    re.compile(r"context window.*?(\d{4,})", re.I),
    re.compile(r"(?:limit|limits|maximum|max)[^\d]{0,20}(\d{4,})", re.I),
)

# 探测结果缓存：进程内，键为 "base_url|model_id"
_RESOLVED: dict[str, int] = {}
# 自适应学到的值，优先级高于内置表（因为它是供应商亲口说的）
_LEARNED: dict[str, int] = {}


@dataclass(frozen=True)
class WindowResolution:
    window: int
    source: str  # override | probe | learned | builtin | fallback


def _match_builtin(model_id: str) -> int | None:
    """前缀匹配内置表，取最长前缀。"""
    model = model_id.lower()
    hits = [w for prefix, w in KNOWN_WINDOWS.items() if model.startswith(prefix)]
    return max(hits) if hits else None


def parse_window_from_error(message: str) -> int | None:
    """从供应商的错误消息里抠出真实上限。抠不到返回 None。

    抠出来的一定要合理 —— 正则可能撞上无关的大数字，所以做一次下限校验。
    """
    for pattern in _ERROR_PATTERNS:
        for match in pattern.finditer(message):
            try:
                value = int(match.group(1))
            except (TypeError, ValueError):
                continue
            if 1_000 <= value <= 100_000_000:
                return value
    return None


def learn_from_error(model_id: str, message: str) -> int | None:
    """撞到上限错误时学习真实值。返回学到的窗口，抠不到返回 None。"""
    learned = parse_window_from_error(message)
    if learned is None:
        return None
    _LEARNED[model_id.lower()] = learned
    logger.warning(
        "模型窗口内置值不准，已从错误消息学习并覆盖：model=%s window=%d。"
        "请更新 KNOWN_WINDOWS 以免每次都要撞一次。",
        model_id,
        learned,
    )
    return learned


def _probe_models_endpoint(base_url: str, api_key: str, model_id: str) -> int | None:
    """探一次 /v1/models。拿不到就返回 None —— 这是常态，不是错误。"""
    if not base_url:
        return None
    try:
        import httpx
    except ImportError:
        return None
    url = base_url.rstrip("/") + "/models"
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    try:
        resp = httpx.get(url, headers=headers, timeout=5.0)
        resp.raise_for_status()
        payload = resp.json()
    except Exception:
        logger.debug("探测 /models 失败，降级到内置表", exc_info=True)
        return None

    for item in payload.get("data") or []:
        if not isinstance(item, dict):
            continue
        if item.get("id") != model_id:
            continue
        for key in ("context_length", "context_window", "max_model_len", "max_context_length"):
            value = item.get(key)
            if isinstance(value, int) and value > 0:
                return value
    return None


def resolve_window(
    model_id: str,
    base_url: str = "",
    api_key: str = "",
    override: int = 0,
) -> WindowResolution:
    """按优先级链解析窗口。结果缓存，同一进程不重复探测。"""
    key = f"{base_url}|{model_id}"

    if override > 0:
        return WindowResolution(override, "override")

    if key in _RESOLVED:
        cached = _RESOLVED[key]
        return WindowResolution(cached, "cache")

    learned = _LEARNED.get(model_id.lower())
    if learned:
        _RESOLVED[key] = learned
        return WindowResolution(learned, "learned")

    probed = _probe_models_endpoint(base_url, api_key, model_id)
    if probed:
        _RESOLVED[key] = probed
        logger.info("窗口来自 /models 探测：%s -> %d", model_id, probed)
        return WindowResolution(probed, "probe")

    builtin = _match_builtin(model_id)
    if builtin:
        _RESOLVED[key] = builtin
        return WindowResolution(builtin, "builtin")

    logger.warning(
        "模型 %s 不在内置表里且探测失败，回退保守值 %d。"
        "如果实际窗口更小，压缩会失效并撞供应商上限 —— 请补进 KNOWN_WINDOWS。",
        model_id,
        CONSERVATIVE_WINDOW,
    )
    _RESOLVED[key] = CONSERVATIVE_WINDOW
    return WindowResolution(CONSERVATIVE_WINDOW, "fallback")


def reset_cache() -> None:
    """测试用。"""
    _RESOLVED.clear()
    _LEARNED.clear()
