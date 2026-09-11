"""动态窗口解析。

这块的重点不是「能不能拿到数」，而是**拿不到的时候会不会静默失效**。
所以测试集中在：优先级顺序、保守兜底、以及从错误消息里学。
"""

from __future__ import annotations

import pytest

from core import model_window
from core.settings import CONSERVATIVE_WINDOW


@pytest.fixture(autouse=True)
def _clear_cache():
    model_window.reset_cache()
    yield
    model_window.reset_cache()


def test_显式覆盖优先级最高(monkeypatch):
    # 即使内置表里有，也要被 override 压过
    monkeypatch.setattr(model_window, "_probe_models_endpoint", lambda *a, **k: 999_999)
    got = model_window.resolve_window("deepseek-v4-flash", override=4_096)
    assert got.window == 4_096
    assert got.source == "override"


def test_探测结果优先于内置表(monkeypatch):
    monkeypatch.setattr(model_window, "_probe_models_endpoint", lambda *a, **k: 777_777)
    got = model_window.resolve_window("deepseek-v4-flash")
    assert got.window == 777_777
    assert got.source == "probe"


def test_探测失败时用内置表(monkeypatch):
    monkeypatch.setattr(model_window, "_probe_models_endpoint", lambda *a, **k: None)
    got = model_window.resolve_window("deepseek-v4-flash")
    assert got.window == 1_000_000
    assert got.source == "builtin"


def test_未知模型回退保守值而不是猜大的(monkeypatch):
    """宁可低估 —— 低估只是提前压缩，高估会撞供应商上限。"""
    monkeypatch.setattr(model_window, "_probe_models_endpoint", lambda *a, **k: None)
    got = model_window.resolve_window("some-unknown-model-xyz")
    assert got.window == CONSERVATIVE_WINDOW
    assert got.source == "fallback"


def test_内置表取最长前缀(monkeypatch):
    """'deepseek-v4-pro' 不能被 'deepseek-v4-flash' 之类的短前缀抢走。"""
    monkeypatch.setattr(model_window, "_probe_models_endpoint", lambda *a, **k: None)
    assert model_window._match_builtin("deepseek-v4-pro") == 1_000_000
    assert model_window._match_builtin("deepseek-v4-flash-20260901") == 1_000_000


def test_学到的值压过内置表(monkeypatch):
    monkeypatch.setattr(model_window, "_probe_models_endpoint", lambda *a, **k: None)
    model_window.learn_from_error("deepseek-v4-flash", "maximum context length is 65536 tokens")
    got = model_window.resolve_window("deepseek-v4-flash")
    assert got.window == 65_536
    assert got.source == "learned"


def test_第二次解析走缓存不再探测(monkeypatch):
    calls = []

    def counting_probe(*a, **k):
        calls.append(1)
        return 500_000

    monkeypatch.setattr(model_window, "_probe_models_endpoint", counting_probe)
    model_window.resolve_window("m-x")
    model_window.resolve_window("m-x")
    assert len(calls) == 1


@pytest.mark.parametrize(
    "message,expected",
    [
        ("This model's maximum context length is 1048576 tokens", 1_048_576),
        ('{"error":{"code":"context_length_exceeded","message":"limit 65536"}}', 65_536),
        ("max_context_length=131072 exceeded", 131_072),
        ("The context window is 200000 tokens", 200_000),
    ],
)
def test_从错误消息里抠出上限(message, expected):
    assert model_window.parse_window_from_error(message) == expected


def test_抠不到就返回_None_不乱猜():
    assert model_window.parse_window_from_error("connection reset by peer") is None


def test_忽略不合理的小数字():
    """正则可能撞上无关数字（端口、行号）。低于 1000 的一律不认。"""
    assert model_window.parse_window_from_error("failed at line 42 of file 7") is None


def test_忽略离谱的大数字():
    assert model_window.parse_window_from_error("limit 999999999999999") is None
