"""阈值折算。

这是整个压缩机制里最容易静默失效的一处：阈值写成纯绝对值时，
在小窗口上会算出大于窗口的值，那一层**永远不触发**，最后以供应商侧
的上限错误暴露，而现象和压缩机制看不出关联。

所以这里专门盯住 `clamp_to_window` 的方向性。
"""

from __future__ import annotations

import pytest

from core.settings import clamp_to_window, load_settings


def test_generation_budget_defaults_to_thirty_minutes(monkeypatch, tmp_path):
    from core.settings import Limits

    monkeypatch.setenv("AGENT_ENV_FILE", str(tmp_path / "missing.env"))
    monkeypatch.delenv("AGENT_DEADLINE_MS", raising=False)
    assert Limits().deadline_ms == 1_800_000
    assert load_settings().limits.deadline_ms == 1_800_000
    monkeypatch.setenv("AGENT_DEADLINE_MS", "12000")
    assert load_settings().limits.deadline_ms == 12_000


def test_大窗口取绝对值():
    """窗口足够大时，绝对值就是有效阈值（摊销：不等到窗口快满才压）。"""
    assert clamp_to_window(40_000, 0.1, window=1_000_000) == 40_000


def test_小窗口被比例压下来():
    """32K 窗口下，40_000 的绝对值必须被压到窗口以内。"""
    got = clamp_to_window(40_000, 0.1, window=32_768)
    assert got == 3_276
    assert got < 32_768


@pytest.mark.parametrize("window", [8_192, 16_384, 32_768, 131_072, 1_000_000])
def test_任何窗口下阈值都不会超过窗口(window):
    """这条是硬不变量。违反它 = 该层永久不触发 = 静默失效。"""
    for absolute, ratio in [(64_000, 0.5), (40_000, 0.1), (20_000, 0.05), (25_000, 0.025)]:
        assert clamp_to_window(absolute, ratio, window) <= window


def test_方向是_min_不是_max():
    """写成 max 就会在小窗口上得到大于窗口的阈值 —— 这正是要防的 bug。"""
    window = 32_768
    assert clamp_to_window(64_000, 0.5, window) == 16_384
    assert clamp_to_window(64_000, 0.5, window) != 64_000


def test_返回整数():
    assert isinstance(clamp_to_window(40_000, 0.1, 131_072), int)


def test_默认配置能在无任何环境变量时加载():
    """骨架要能在没有 .env 的机器上起来。"""
    settings = load_settings()
    assert settings.model.model_id
    assert settings.limits.deadline_ms > 0
    assert settings.workspace.root


def test_单条工具结果预算是字符数不是_token():
    """第 0 层按字符算（工具输出是文本），与窗口无关，不参与 clamp。"""
    settings = load_settings()
    assert settings.limits.tool_output_max_chars == 2_000
    assert settings.limits.tool_output_spill_chars == 40_000
    assert settings.limits.tool_output_spill_chars > settings.limits.tool_output_max_chars


# ═══════════════════════ 模型出口策略 ═══════════════════════
# httpx 默认 trust_env=True 会读系统代理。本机开着 127.0.0.1:7897，
# DeepSeek 是国内端点，被代理截走后只报一句 "Connection error."。
# 所以「走不走代理」必须是显式配置，且默认直连。

@pytest.mark.parametrize("raw,expected", [
    ("true", True), ("1", True), ("yes", True), ("on", True), ("TRUE", True),
    ("false", False), ("0", False), ("no", False), ("off", False), ("False", False),
])
def test_布尔环境变量识别真假(monkeypatch, raw, expected):
    """`bool("false")` 是 True —— 非空字符串一律为真，这类配置项踩过一次就够。"""
    from env_utils import get_env_bool

    monkeypatch.setenv("AGENT_TEST_BOOL", raw)
    assert get_env_bool("AGENT_TEST_BOOL", not expected) is expected


def test_布尔环境变量为空时回落到默认值(monkeypatch):
    from env_utils import get_env_bool

    monkeypatch.setenv("AGENT_TEST_BOOL", "")
    assert get_env_bool("AGENT_TEST_BOOL", True) is True
    assert get_env_bool("AGENT_TEST_BOOL", False) is False


def test_模型客户端默认直连():
    """两个客户端都要设 —— 主模型走 astream_events，摘要模型走 invoke。"""
    from core.model import _clients

    sync_client, async_client = _clients()
    assert sync_client._trust_env is False
    assert async_client._trust_env is False


def test_模型客户端可显式走指定代理():
    from core.model import _clients

    sync_client, async_client = _clients("http://127.0.0.1:17897")
    assert sync_client._trust_env is False
    assert async_client._trust_env is False


def test_客户端在进程内复用():
    """worker 是常驻进程、模型每次 run 重建 —— client 跟着重建会一直漏连接。"""
    from core.model import _clients

    assert _clients()[0] is _clients()[0]
    assert _clients()[0] is not _clients("http://127.0.0.1:17897")[0]


def test_模型出口由总开关和单轮直连覆盖决定():
    from core.model import model_connection_options
    from core.settings import ModelSettings

    settings = ModelSettings(proxy_enabled=True, proxy_url="http://127.0.0.1:17897")
    assert model_connection_options(settings) == "http://127.0.0.1:17897"
    assert model_connection_options(settings, "direct") is None
    assert model_connection_options(ModelSettings(proxy_enabled=False)) is None


def test_指定代理客户端与直连隔离():
    from core.model import _clients

    domestic_sync, domestic_async = _clients("http://127.0.0.1:17897")
    again_sync, again_async = _clients("http://127.0.0.1:17897")
    plain_sync, plain_async = _clients()

    assert domestic_sync is again_sync
    assert domestic_async is again_async
    assert domestic_sync is not plain_sync
    assert domestic_async is not plain_async
    assert domestic_sync._trust_env is False
    assert domestic_async._trust_env is False
    assert len(domestic_sync._mounts) == 1
    assert len(domestic_async._mounts) == 1


def test_指定代理配置非法时明确失败():
    import pytest

    from core.settings import validate_proxy_config

    with pytest.raises(ValueError, match="PROXY_URL"):
        validate_proxy_config(True, "127.0.0.1:17897")
    validate_proxy_config(False, "")


def test_指定代理配置从环境变量加载(monkeypatch):
    from core.settings import load_settings

    monkeypatch.setenv("AGENT_MODEL_PROXY_ENABLED", "true")
    monkeypatch.setenv("AGENT_MODEL_PROXY_URL", "http://127.0.0.1:17897")

    settings = load_settings()

    assert settings.model.proxy_enabled is True
    assert settings.model.proxy_url == "http://127.0.0.1:17897"
