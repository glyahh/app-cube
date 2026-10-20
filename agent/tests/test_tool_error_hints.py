"""只读拒绝必须给模型可执行提示。

缺这条时模型会反复重试写入，用户只看到「写入文件（失败）」不知道原因。
"""
from core.middleware.tool_error import _HINTS


def test_read_only_violation_has_hint():
    hint = _HINTS.get("ReadOnlyViolation")
    assert hint, "只读拒绝没有提示，模型会盲目重试"
    assert "只读" in hint
    assert "重复" in hint, "要明确告诉模型不要重复尝试"
