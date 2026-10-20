"""模型构造与单次调用级代理降级。"""

from __future__ import annotations

import json
import logging
import threading
import uuid
from contextvars import ContextVar
from collections.abc import Callable
from urllib.parse import urlparse

from core.settings import ModelSettings

logger = logging.getLogger(__name__)
THINKING_DISABLED = {"type": "disabled"}
_CLIENTS: dict[str | None, tuple] = {}
model_request_audit: ContextVar[Callable | None] = ContextVar('model_request_audit', default=None)
_AUDIT_FALLBACK: Callable | None = None
_AUDIT_LOCK = threading.Lock()


def set_model_request_audit_fallback(callback: Callable | None):
    global _AUDIT_FALLBACK
    with _AUDIT_LOCK:
        previous = _AUDIT_FALLBACK
        _AUDIT_FALLBACK = callback
    return previous


def _audit_request(request):
    callback = model_request_audit.get()
    if callback is None:
        with _AUDIT_LOCK:
            callback = _AUDIT_FALLBACK
    if callback is not None:
        callback(request)
    from core.model_timing import request_started
    attempt = request_started(request)
    if attempt is not None:
        request.extensions['trace'] = attempt.network


async def _audit_async_request(request):
    _audit_request(request)
    attempt = request.extensions.get('glyahh_timing')
    if attempt is not None:
        async def trace(name, info):
            attempt.network(name, info)
        request.extensions['trace'] = trace


def _audit_response(response):
    from core.model_timing import response_headers
    response_headers(response)


async def _audit_async_response(response):
    _audit_response(response)


class ModelRouteState:
    """一轮 run 共享的模型出口状态。主模型与摘要模型共用。"""

    def __init__(self, *, route: str = "configured", allow_fallback: bool = False,
                 on_fallback: Callable[[], None] | None = None) -> None:
        self._direct = route == "direct"
        self.allow_fallback = allow_fallback
        self._on_fallback = on_fallback
        self._notified = False
        self._lock = threading.Lock()

    @property
    def direct(self) -> bool:
        with self._lock:
            return self._direct

    def begin_fallback(self) -> bool:
        with self._lock:
            if self._direct or not self.allow_fallback:
                return False
            self._direct = True
            return True

    def rollback_fallback(self) -> None:
        with self._lock:
            if not self._notified:
                self._direct = False

    def confirm_fallback(self) -> None:
        callback = None
        with self._lock:
            if not self._notified:
                self._notified = True
                callback = self._on_fallback
        if callback:
            callback()


def model_host(base_url: str) -> str | None:
    """取模型 base URL 主机名，仅用于脱敏路由日志。"""
    try:
        host = urlparse(base_url).hostname
    except ValueError:
        return None
    return host.lower().rstrip(".") if host else None


def model_connection_options(settings: ModelSettings, route: str = "configured") -> str | None:
    """返回显式代理 URL；None 表示明确直连。"""
    if route == "direct" or not settings.proxy_enabled:
        return None
    return settings.proxy_url


def _proxy_label(proxy_url: str | None) -> str:
    if not proxy_url:
        return "none"
    try:
        parsed = urlparse(proxy_url)
        port = f":{parsed.port}" if parsed.port else ""
        return f"{parsed.scheme}://{parsed.hostname}{port}"
    except ValueError:
        return "invalid"


def _clients(proxy_url: str | None = None):
    """复用同步/异步 httpx 客户端；直连与代理都不读取系统代理。"""
    cached = _CLIENTS.get(proxy_url)
    if cached is None:
        import httpx

        kwargs = dict(
            trust_env=False,
            timeout=httpx.Timeout(connect=15.0, read=600.0, write=60.0, pool=15.0),
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        )
        if proxy_url:
            kwargs["proxy"] = proxy_url
        cached = (httpx.Client(**kwargs, event_hooks={'request': [_audit_request], 'response': [_audit_response]}),
                  httpx.AsyncClient(**kwargs, event_hooks={'request': [_audit_async_request], 'response': [_audit_async_response]}))
        _CLIENTS[proxy_url] = cached
        logger.info("模型 httpx 客户端：trust_env=false proxy=%s", _proxy_label(proxy_url))
    return cached


def _is_proxy_connect_error(exc: BaseException) -> bool:
    """只认底层 ConnectError；超时与 HTTP 状态错误都不降级。"""
    try:
        from openai import APIConnectionError, APITimeoutError
    except ImportError:
        return False
    if not isinstance(exc, APIConnectionError) or isinstance(exc, APITimeoutError):
        return False

    current = exc.__cause__ or exc.__context__
    visited: set[int] = set()
    while current is not None and id(current) not in visited:
        visited.add(id(current))
        module = type(current).__module__.split(".", 1)[0]
        if type(current).__name__ == "ConnectError" and module in {
            "httpx", "httpcore", "httpx2", "httpcore2"
        }:
            return True
        current = current.__cause__ or current.__context__
    return False


def _chat_model(model_id: str, base_url: str, api_key: str, settings: ModelSettings,
                *, streaming: bool, proxy_url: str | None, max_retries: int | None = None,
                timeout: float | None = None, http_client=None):
    from langchain_openai import ChatOpenAI

    if http_client is None:
        http_client, http_async_client = _clients(proxy_url)
    else:
        http_async_client = None
    kwargs = dict(
        model=model_id,
        base_url=base_url,
        api_key=api_key,
        temperature=settings.temperature,
        max_tokens=settings.max_output_tokens,
        streaming=streaming,
        extra_body={"thinking": THINKING_DISABLED} if settings.request_kwargs_json is None else {},
        http_client=http_client,
        http_async_client=http_async_client,
    )
    if settings.request_kwargs_json is not None:
        from core.model_profiles import strict_json, validate_kwargs
        kwargs.update(validate_kwargs(strict_json(settings.request_kwargs_json)))
    # OpenCode Go 用会话头做请求路由；普通 OpenAI-compatible 供应商不需要。
    if "opencode.ai/inference/go" in base_url:
        kwargs["default_headers"] = {"x-opencode-session": str(uuid.uuid4())}
    if max_retries is not None:
        kwargs["max_retries"] = max_retries
    if timeout is not None:
        kwargs['timeout'] = timeout
    from core.model_timing import instrument
    return instrument(ChatOpenAI(**kwargs))


def _route_aware_model(proxy_model, direct_model, state: ModelRouteState):
    """把失败重试限制在当前模型调用，不重跑 Agent。"""
    from pydantic import PrivateAttr

    class RouteAwareChatOpenAI(type(proxy_model)):
        _direct_delegate = PrivateAttr()
        _route_state = PrivateAttr()

        def bind_route(self, direct_delegate, route_state):
            self._direct_delegate = direct_delegate
            self._route_state = route_state
            return self

        def _sync_call(self, method: str, *args, **kwargs):
            if self._route_state.direct:
                return getattr(self._direct_delegate, method)(*args, **kwargs)
            try:
                return getattr(super(), method)(*args, **kwargs)
            except Exception as exc:
                if not _is_proxy_connect_error(exc) or not self._route_state.begin_fallback():
                    raise
                try:
                    result = getattr(self._direct_delegate, method)(*args, **kwargs)
                except Exception:
                    self._route_state.rollback_fallback()
                    raise
                self._route_state.confirm_fallback()
                return result

        def _generate(self, *args, **kwargs):
            return self._sync_call("_generate", *args, **kwargs)

        async def _agenerate(self, *args, **kwargs):
            if self._route_state.direct:
                return await self._direct_delegate._agenerate(*args, **kwargs)
            try:
                return await super()._agenerate(*args, **kwargs)
            except Exception as exc:
                if not _is_proxy_connect_error(exc) or not self._route_state.begin_fallback():
                    raise
                try:
                    result = await self._direct_delegate._agenerate(*args, **kwargs)
                except Exception:
                    self._route_state.rollback_fallback()
                    raise
                self._route_state.confirm_fallback()
                return result

        def _stream(self, *args, **kwargs):
            if self._route_state.direct:
                yield from self._direct_delegate._stream(*args, **kwargs)
                return
            try:
                yield from super()._stream(*args, **kwargs)
                return
            except Exception as exc:
                if not _is_proxy_connect_error(exc) or not self._route_state.begin_fallback():
                    raise
            try:
                iterator = self._direct_delegate._stream(*args, **kwargs)
                first = next(iterator, None)
                self._route_state.confirm_fallback()
                if first is not None:
                    yield first
                yield from iterator
            except Exception:
                self._route_state.rollback_fallback()
                raise

        async def _astream(self, *args, **kwargs):
            if self._route_state.direct:
                async for item in self._direct_delegate._astream(*args, **kwargs):
                    yield item
                return
            try:
                async for item in super()._astream(*args, **kwargs):
                    yield item
                return
            except Exception as exc:
                if not _is_proxy_connect_error(exc) or not self._route_state.begin_fallback():
                    raise
            try:
                iterator = self._direct_delegate._astream(*args, **kwargs)
                try:
                    first = await anext(iterator)
                except StopAsyncIteration:
                    first = None
                self._route_state.confirm_fallback()
                if first is not None:
                    yield first
                async for item in iterator:
                    yield item
            except Exception:
                self._route_state.rollback_fallback()
                raise

    proxy_model.__class__ = RouteAwareChatOpenAI
    from core.model_timing import instrument
    return instrument(proxy_model.bind_route(direct_model, state))


def _build(model_id: str, base_url: str, api_key: str, settings: ModelSettings, *,
           streaming: bool, route_state: ModelRouteState | None = None):
    state = route_state or ModelRouteState()
    proxy_url = model_connection_options(settings, "direct" if state.direct else "configured")
    logger.debug("模型请求出口：host=%s route=%s", model_host(base_url) or "<unknown>",
                 _proxy_label(proxy_url) if proxy_url else "direct")

    if not proxy_url:
        return _chat_model(model_id, base_url, api_key, settings,
                           streaming=streaming, proxy_url=None)

    proxy_model = _chat_model(model_id, base_url, api_key, settings,
                              streaming=streaming, proxy_url=proxy_url,
                              max_retries=0 if state.allow_fallback else None)
    if not state.allow_fallback:
        return proxy_model
    direct_model = _chat_model(model_id, base_url, api_key, settings,
                               streaming=streaming, proxy_url=None)
    return _route_aware_model(proxy_model, direct_model, state)


def make_main_model(settings: ModelSettings, route_state: ModelRouteState | None = None):
    return _build(settings.model_id, settings.base_url, settings.api_key, settings,
                  streaming=True, route_state=route_state)


def make_summarizer_model(settings: ModelSettings, route_state: ModelRouteState | None = None):
    model_id = settings.summarizer_model_id or settings.model_id
    base_url = settings.summarizer_base_url or settings.base_url
    api_key = settings.summarizer_api_key or settings.api_key
    if model_id == settings.model_id:
        logger.debug("摘要模型复用主模型 %s（未单独配置）", model_id)
    else:
        logger.info("摘要模型 %s，主模型 %s —— 其上下文容量必须不小于主模型",
                    model_id, settings.model_id)
    return _build(model_id, base_url, api_key, settings,
                  streaming=False, route_state=route_state)


def make_review_model(settings: ModelSettings, *, timeout: float, max_output_tokens: int, http_client=None):
    """独立非流式审查；无工具、无代理回退、无自动重试。"""
    from dataclasses import replace
    from core.model_profiles import strict_json, validate_kwargs

    kwargs = validate_kwargs(strict_json(settings.request_kwargs_json))
    configured_output = kwargs.get('max_tokens', settings.max_output_tokens)
    if configured_output > max_output_tokens:
        kwargs['max_tokens'] = max_output_tokens
    bounded = replace(settings, max_output_tokens=min(configured_output, max_output_tokens),
                      request_kwargs_json=json.dumps(kwargs))
    return _chat_model(bounded.model_id, bounded.base_url, bounded.api_key, bounded,
                       streaming=False, proxy_url=model_connection_options(bounded),
                       max_retries=0, timeout=timeout, http_client=http_client)
