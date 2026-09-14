"""模型构造。

**必须显式关掉 thinking。** 实测（2026-09-12，deepseek-flash）：

    默认                        推理 14 tokens，总输出 16
    thinking={type:disabled}    推理 0，       总输出 1

一个「只回复两个字」的请求，默认模式下 20 个 max_tokens 里 14 个被推理吃掉。
不关的话流式输出里 `delta.content` 会大段为空，前端看起来就是卡住 ——
现有 Java 侧的 `thinkingDisabled(true)` 加 `reasoningEffort(null)` 就是为这个。

关法是往请求体顶层塞 `thinking` 字段。用 LangChain 时写在 `extra_body` 里，
`init_chat_model` 会把它摊平到顶层。
"""

from __future__ import annotations

import logging
from urllib.parse import urlparse

from core.settings import ModelSettings

logger = logging.getLogger(__name__)

# 关掉思考模式。顶层字段，不是嵌套的 extra_body。
THINKING_DISABLED = {"type": "disabled"}

# 按出口策略缓存。worker 是常驻进程，模型对象每次 run 都重建 ——
# client 要是跟着重建，连接会一直堆在池里没人关，而且每轮都要重做 TLS 握手。
_CLIENTS: dict[tuple[bool, str | None], tuple] = {}


def model_host(base_url: str) -> str | None:
    """取模型 base URL 的主机名，供专用出口做精确匹配。"""
    try:
        host = urlparse(base_url).hostname
    except ValueError:
        return None
    return host.lower().rstrip(".") if host else None


def domestic_direct_proxy_for_url(base_url: str, settings: ModelSettings) -> str | None:
    """返回命中主机的国内专用代理，否则返回 None。"""
    if not settings.domestic_direct_enabled:
        return None
    host = model_host(base_url)
    if host in settings.domestic_direct_hosts:
        return settings.domestic_direct_proxy
    return None


def model_connection_options(
    base_url: str, settings: ModelSettings,
) -> tuple[bool, str | None]:
    """返回 (trust_env, explicit_proxy)，不改变操作系统路由/TUN。"""
    proxy_url = domestic_direct_proxy_for_url(base_url, settings)
    if proxy_url:
        return False, proxy_url
    return settings.use_system_proxy, None


def _proxy_label(proxy_url: str | None) -> str:
    if not proxy_url:
        return "none"
    try:
        parsed = urlparse(proxy_url)
        port = f":{parsed.port}" if parsed.port else ""
        return f"{parsed.scheme}://{parsed.hostname}{port}"
    except ValueError:
        return "invalid"


def _clients(use_system_proxy: bool, proxy_url: str | None = None):
    """进程内复用的 (同步, 异步) httpx 客户端。

    `trust_env` 只决定要不要读 HTTP_PROXY 等显式代理环境变量；它不会绕过
    Windows 路由或 TUN。传入 `proxy_url` 时走指定的本机入口，并强制关闭环境代理。
    """
    trust_env = bool(use_system_proxy) if proxy_url is None else False
    cache_key = (trust_env, proxy_url)
    cached = _CLIENTS.get(cache_key)
    if cached is None:
        import httpx

        kwargs = dict(
            trust_env=trust_env,
            # 连接要快速失败；读取要够久 —— 生成整个文件时单次响应可能跑几分钟。
            timeout=httpx.Timeout(connect=15.0, read=600.0, write=60.0, pool=15.0),
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        )
        if proxy_url:
            kwargs["proxy"] = proxy_url
        cached = (httpx.Client(**kwargs), httpx.AsyncClient(**kwargs))
        _CLIENTS[cache_key] = cached
        logger.info("模型 httpx 客户端：trust_env=%s proxy=%s", trust_env,
                    _proxy_label(proxy_url))
    return cached


def _build(model_id: str, base_url: str, api_key: str, settings: ModelSettings, *, streaming: bool):
    from langchain_openai import ChatOpenAI

    use_system_proxy, proxy_url = model_connection_options(base_url, settings)
    http_client, http_async_client = _clients(use_system_proxy, proxy_url)
    logger.debug(
        "模型请求出口：host=%s route=%s",
        model_host(base_url) or "<unknown>",
        _proxy_label(proxy_url) if proxy_url else ("system" if use_system_proxy else "tun/direct"),
    )

    return ChatOpenAI(
        model=model_id,
        base_url=base_url,
        api_key=api_key,
        temperature=settings.temperature,
        max_tokens=settings.max_output_tokens,
        streaming=streaming,
        # 摊到请求体顶层，见模块 docstring
        extra_body={"thinking": THINKING_DISABLED},
        # 主模型走 astream_events（异步），摘要模型走 invoke（同步）——
        # 两条独立的连接路径，少给一边就会只剩压缩在某类机器上单独失效。
        http_client=http_client,
        http_async_client=http_async_client,
    )


def make_main_model(settings: ModelSettings):
    """主模型。流式。"""
    return _build(
        settings.model_id,
        settings.base_url,
        settings.api_key,
        settings,
        streaming=True,
    )


def make_summarizer_model(settings: ModelSettings):
    """压缩用的摘要模型。

    设计要求：**上下文容量必须 >= 主模型**。小于主模型时摘要调用会吃
    context-length 错误并返回空，被压缩的那段就「无摘要丢弃」了 ——
    Hermes 官方称这是压缩质量下降最常见的原因。所以没单独配就复用主模型。

    非流式，温度更低 —— 摘要是提取任务，不需要发挥。
    """
    model_id = settings.summarizer_model_id or settings.model_id
    base_url = settings.summarizer_base_url or settings.base_url
    api_key = settings.summarizer_api_key or settings.api_key

    if model_id == settings.model_id:
        logger.debug("摘要模型复用主模型 %s（未单独配置）", model_id)
    else:
        logger.info("摘要模型 %s，主模型 %s —— 其上下文容量必须不小于主模型", model_id, settings.model_id)

    return _build(model_id, base_url, api_key, settings, streaming=False)
