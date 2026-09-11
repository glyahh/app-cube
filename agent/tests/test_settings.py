"""阈值折算。

这是整个压缩机制里最容易静默失效的一处：阈值写成纯绝对值时，
在小窗口上会算出大于窗口的值，那一层**永远不触发**，最后以供应商侧
的上限错误暴露，而现象和压缩机制看不出关联。

所以这里专门盯住 `clamp_to_window` 的方向性。
"""

from __future__ import annotations

import pytest

from core.settings import clamp_to_window, load_settings


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
