from __future__ import annotations

import httpx
import httpx2
from openai import APIConnectionError, APITimeoutError

from core.model import ModelRouteState, _is_proxy_connect_error, make_main_model
from core.settings import ModelSettings


def _wrapped_connection_error(cause: Exception) -> APIConnectionError:
    try:
        raise cause
    except Exception as inner:
        try:
            raise APIConnectionError(request=httpx2.Request("POST", "https://model.test")) from inner
        except APIConnectionError as outer:
            return outer


def test_only_connect_error_is_eligible_for_fallback():
    request = httpx.Request("POST", "https://model.test")
    assert _is_proxy_connect_error(
        _wrapped_connection_error(httpx.ConnectError("refused", request=request))
    )
    assert not _is_proxy_connect_error(
        _wrapped_connection_error(httpx.ConnectTimeout("timeout", request=request))
    )
    assert not _is_proxy_connect_error(
        _wrapped_connection_error(httpx.ReadTimeout("timeout", request=request))
    )


def test_api_timeout_subclass_is_never_fallback():
    timeout = APITimeoutError(request=httpx2.Request("POST", "https://model.test"))
    assert not _is_proxy_connect_error(timeout)


def test_route_state_notifies_once_and_rolls_back_failed_direct_attempt():
    notices: list[str] = []
    state = ModelRouteState(allow_fallback=True, on_fallback=lambda: notices.append("fallback"))
    assert state.begin_fallback()
    state.rollback_fallback()
    assert not state.direct

    assert state.begin_fallback()
    state.confirm_fallback()
    state.confirm_fallback()
    assert state.direct
    assert notices == ["fallback"]


def test_direct_or_disabled_state_cannot_start_fallback():
    assert not ModelRouteState(route="direct", allow_fallback=True).begin_fallback()
    assert not ModelRouteState(route="configured", allow_fallback=False).begin_fallback()


def test_only_current_model_call_retries_and_run_stays_direct(monkeypatch):
    from langchain_openai import ChatOpenAI

    calls = {"proxy": 0, "direct": 0}
    notices: list[str] = []

    def fake_generate(self, *args, **kwargs):
        route = "proxy" if self.max_retries == 0 else "direct"
        calls[route] += 1
        if route == "proxy":
            request = httpx.Request("POST", "https://model.test")
            raise _wrapped_connection_error(httpx.ConnectError("refused", request=request))
        return "direct-result"

    monkeypatch.setattr(ChatOpenAI, "_generate", fake_generate)
    state = ModelRouteState(allow_fallback=True, on_fallback=lambda: notices.append("fallback"))
    model = make_main_model(ModelSettings(
        api_key="x", proxy_enabled=True, proxy_url="http://127.0.0.1:17897"
    ), state)

    assert model._generate([]) == "direct-result"
    assert model._generate([]) == "direct-result"
    assert calls == {"proxy": 1, "direct": 2}
    assert notices == ["fallback"]


def test_timeout_does_not_call_direct_delegate(monkeypatch):
    from langchain_openai import ChatOpenAI

    calls = {"proxy": 0, "direct": 0}

    def fake_generate(self, *args, **kwargs):
        route = "proxy" if self.max_retries == 0 else "direct"
        calls[route] += 1
        if route == "proxy":
            raise APITimeoutError(request=httpx2.Request("POST", "https://model.test"))
        return "unexpected"

    monkeypatch.setattr(ChatOpenAI, "_generate", fake_generate)
    model = make_main_model(ModelSettings(
        api_key="x", proxy_enabled=True, proxy_url="http://127.0.0.1:17897"
    ), ModelRouteState(allow_fallback=True))

    import pytest
    with pytest.raises(APITimeoutError):
        model._generate([])
    assert calls == {"proxy": 1, "direct": 0}
