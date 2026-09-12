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

from core.settings import ModelSettings

logger = logging.getLogger(__name__)

# 关掉思考模式。顶层字段，不是嵌套的 extra_body。
THINKING_DISABLED = {"type": "disabled"}

# 按出口策略缓存。worker 是常驻进程，模型对象每次 run 都重建 ——
# client 要是跟着重建，连接会一直堆在池里没人关，而且每轮都要重做 TLS 握手。
_CLIENTS: dict[bool, tuple] = {}


def _clients(use_system_proxy: bool):
    """进程内复用的 (同步, 异步) httpx 客户端。

    `trust_env` 决定要不要读系统代理。**必须显式给值**：httpx 默认 True，
    在开了代理的机器上会把到国内模型端点的请求也截走，报出来只有一句
    "Connection error."（LangChain 把 SSL 层的异常压平了），排查时完全看不出方向。
    """
    cached = _CLIENTS.get(use_system_proxy)
    if cached is None:
        import httpx

        kwargs = dict(
            trust_env=use_system_proxy,
            # 连接要快速失败；读取要够久 —— 生成整个文件时单次响应可能跑几分钟。
            timeout=httpx.Timeout(connect=15.0, read=600.0, write=60.0, pool=15.0),
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        )
        cached = (httpx.Client(**kwargs), httpx.AsyncClient(**kwargs))
        _CLIENTS[use_system_proxy] = cached
        logger.info("模型 httpx 客户端：trust_env=%s（%s）", use_system_proxy,
                    "走系统代理" if use_system_proxy else "直连")
    return cached


def _build(model_id: str, base_url: str, api_key: str, settings: ModelSettings, *, streaming: bool):
    from langchain_openai import ChatOpenAI

    http_client, http_async_client = _clients(settings.use_system_proxy)

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
