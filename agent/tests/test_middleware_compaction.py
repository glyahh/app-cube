"""压缩中间件的接入。

`core.compaction` 测的是算法本身；这里测的是「挂进 agent 循环后还对不对」：
钩子会不会被调用、固定开销有没有算进去、留痕有没有打出来。
"""

from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from core.compaction import OverheadEstimator, total_tokens
from core.middleware.compaction import (
    CompactionMiddleware,
    ToolOutputBudgetMiddleware,
    make_summarizer,
)


def _summarizer(text: str, budget: int) -> str:
    return f'摘要：{len(text)} 字符'


def _long_conversation(turns: int = 12, size: int = 3000):
    messages = [SystemMessage('你是编码助手')]
    for i in range(turns):
        messages.append(HumanMessage(f'第 {i} 轮' + 'x' * size))
    return messages


# ═══════════════════════════ 触发 ═══════════════════════════

def test_before_model_is_noop_below_trigger():
    middleware = CompactionMiddleware(window=1_000_000, summarize=_summarizer)
    state = {'messages': [HumanMessage('短对话')]}

    assert middleware.before_model(state, None) is None
    assert middleware.stats['compactions'] == 0


def test_before_model_compacts_above_trigger():
    middleware = CompactionMiddleware(window=8_000, summarize=_summarizer)
    state = {'messages': _long_conversation()}

    update = middleware.before_model(state, None)

    assert update is not None, '超过触发点必须返回新的消息列表'
    assert 'messages' in update
    assert middleware.stats['compactions'] == 1
    assert middleware.stats['lastLayer'] in ('layer1', 'layer2', 'layer3')


def test_before_model_reduces_tokens():
    window = 8_000
    messages = _long_conversation()
    middleware = CompactionMiddleware(window=window, summarize=_summarizer)

    update = middleware.before_model({'messages': messages}, None)

    assert update is not None
    after = total_tokens(update['messages'])
    assert after < total_tokens(messages), '压缩后占用必须真的下降'


def test_before_model_handles_empty_state():
    middleware = CompactionMiddleware(window=1_000, summarize=_summarizer)
    assert middleware.before_model({'messages': []}, None) is None
    assert middleware.before_model({}, None) is None


# ═══════════════════════════ 固定开销 ═══════════════════════════

def test_fixed_overhead_counts_toward_trigger():
    """system prompt 与工具 schema 不在 messages 里，但确实占窗口。

    不算进去的话触发点永远到不了 —— 表现就是「压缩配了但没生效」。

    注意这里的比例要合理：如果固定开销本身就逼近够触发点，那总占用永远降不下来，
    压缩只能空转（这正是设计文档里「小窗口固定开销占比过半，按比例算的阈值全失效」）。
    """
    # 20 条长工具输出，消息本身约 1 万 token
    messages = [SystemMessage('你是编码助手')]
    for i in range(20):
        messages.append(ToolMessage('x' * 2000, tool_call_id=f'c{i}', name='read_file'))

    window = 20_000                      # 触发点 = min(64000, 15000) = 15000
    bare = CompactionMiddleware(window=window, summarize=_summarizer)
    loaded = CompactionMiddleware(window=window, summarize=_summarizer,
                                  overhead=OverheadEstimator(estimate=6_000))

    assert total_tokens(messages) < 15_000, '前提：消息本身不到触发点'
    assert bare.before_model({'messages': messages}, None) is None, '不计开销时不该触发'

    update = loaded.before_model({'messages': messages}, None)
    assert update is not None, '把固定开销算进去后才应该触发'
    assert total_tokens(update['messages']) < total_tokens(messages), '压缩必须真的降下来'


def test_overhead_estimator_learns_from_real_usage():
    estimator = OverheadEstimator(estimate=1_000)
    estimator.observe(actual_input_tokens=8_000, message_tokens=600)

    assert estimator.current() == 7_400


def test_overhead_estimator_keeps_the_smallest_seen():
    """取最小值，不是最大值。

    推导值是系统性偏高的：我们数消息时只数 content，漏掉工具参数与消息结构本身
    的开销，会话越长偏得越多。所以最早、消息最少的那次观测最接近真值。
    取 max 会让估计随会话一路上爬，触发点被人为拉低，压缩开始空转 ——
    实测过 28 次压缩、token 从 8720 变成 8722 的情形。
    """
    estimator = OverheadEstimator()
    estimator.observe(8_000, 600)     # 推导 7400
    estimator.observe(5_000, 600)     # 推导 4400 —— 更接近真值

    assert estimator.current() == 4_400


def test_overhead_estimator_ignores_nonsense():
    estimator = OverheadEstimator(estimate=500)
    estimator.observe(0, 100)         # 没有用量
    estimator.observe(100, 500)       # 差额为负
    estimator.observe(1_000, 990)     # 差额只占 1%，多半是消息估算偏得太狠

    assert estimator.current() == 500


# ═══════════════════════════ 摘要接入 ═══════════════════════════

def test_summarizer_wraps_model_and_respects_budget():
    class FakeResponse:
        content = '这是摘要正文'

    class FakeModel:
        def __init__(self):
            self.calls = []

        def invoke(self, messages, **kwargs):
            self.calls.append(kwargs)
            return FakeResponse()

    model = FakeModel()
    summarize = make_summarizer(model)
    out = summarize('很长的对话内容', budget=1234)

    assert out == '这是摘要正文'
    assert model.calls[0]['max_tokens'] == 1234


def test_summarizer_clamps_outrageous_budget():
    """摘要预算不能超过摘要模型的输出上限，否则会被截断还当完整摘要存下来。"""
    class FakeResponse:
        content = 'ok'

    class FakeModel:
        def __init__(self):
            self.calls = []

        def invoke(self, messages, **kwargs):
            self.calls.append(kwargs)
            return FakeResponse()

    model = FakeModel()
    make_summarizer(model)('内容', budget=10_000_000)
    assert model.calls[0]['max_tokens'] == 8_000


# ═══════════════════════════ 第 0 层 ═══════════════════════════

def test_tool_output_budget_passes_short_output(tmp_path):
    middleware = ToolOutputBudgetMiddleware(
        char_budget=2000, spill_threshold=40_000, spill_dir=tmp_path)

    message = ToolMessage(content='短输出', tool_call_id='c1', name='read_file')
    assert middleware._budget(message) is message


def test_tool_output_budget_truncates_long_output(tmp_path):
    middleware = ToolOutputBudgetMiddleware(
        char_budget=2000, spill_threshold=40_000, spill_dir=tmp_path)

    message = ToolMessage(content='x' * 10_000, tool_call_id='c1', name='read_file')
    out = middleware._budget(message)

    assert out is not message
    assert len(str(out.content)) < 10_000
    assert '此处省略' in str(out.content)
    assert middleware.stats['truncated'] == 1


def test_tool_output_budget_spills_oversized_output(tmp_path):
    middleware = ToolOutputBudgetMiddleware(
        char_budget=2000, spill_threshold=40_000, spill_dir=tmp_path)

    message = ToolMessage(content='y' * 60_000, tool_call_id='c1', name='execute')
    out = middleware._budget(message)

    assert middleware.stats['spilled'] == 1
    assert '.scratch/' in str(out.content)
    assert list(tmp_path.iterdir()), '超阈值内容应落盘'


def test_tool_output_budget_ignores_non_tool_messages(tmp_path):
    middleware = ToolOutputBudgetMiddleware(
        char_budget=10, spill_threshold=20, spill_dir=tmp_path)

    message = AIMessage('这是一条很长的模型回复' * 100)
    assert middleware._budget(message) is message
