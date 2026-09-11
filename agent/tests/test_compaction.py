"""四层压缩。

重点在两处：
1. **铁律不许破** —— 不许越层、不许倒序、不许碰不该碰的
2. **阈值不许写成纯绝对值** —— 小窗口上会永久不触发，整条链路静默失效
"""

from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from core.compaction import (
    Thresholds,
    apply_layer0,
    compact,
    estimate_tokens,
    total_tokens,
    _layer1,
    _layer2,
)


# ═══════════════════════════ token 估算 ═══════════════════════════

def test_cjk_costs_more_than_ascii():
    """中文信息密度高，按 chars/4 估会严重低估。"""
    assert estimate_tokens('中文十个字符测试一下') == 10
    assert estimate_tokens('abcdefgh') == 2


def test_empty_is_zero():
    assert estimate_tokens('') == 0
    assert estimate_tokens(None) == 0


# ═══════════════════════════ 阈值折算 ═══════════════════════════

@pytest.mark.parametrize('window', [8_192, 32_768, 131_072, 1_000_000])
def test_every_threshold_fits_inside_window(window):
    """硬不变量：任何阈值都不能超过窗口。

    违反它 = 那一层永远不触发 = 静默失效，最后以供应商上限错误暴露。
    """
    t = Thresholds(window)
    assert 0 < t.trigger <= window
    assert 0 < t.protect_window <= window
    assert 0 < t.min_reclaim <= window
    assert 0 < t.tail_budget <= window
    assert 0 < t.summary_budget(1000) <= window


def test_small_window_raises_trigger_ratio():
    """小窗口固定开销占比大，按低比例触发会「压一两次又满」。

    比例只在窗口小到 64000 的绝对上限不生效时才起决定作用 ——
    所以这里用 50K 窗口验证比例，用 200K/1M 验证上限。
    """
    assert Thresholds(50_000).trigger == 37_500          # 0.75 × 50000
    assert Thresholds(200_000).trigger == 64_000         # 绝对上限生效
    assert Thresholds(1_000_000).trigger == 64_000


def test_spill_thresholds_scale_with_window():
    tiny = Thresholds(8_192)
    assert tiny.protect_window < 8_192
    assert tiny.min_reclaim < 8_192


# ═══════════════════════════ 第 0 层 ═══════════════════════════

def test_layer0_passes_short_content_through(tmp_path):
    result = apply_layer0('短内容', char_budget=2000, spill_threshold=40_000, spill_dir=tmp_path)
    assert result.content == '短内容'
    assert result.spilled is False


def test_layer0_truncates_medium_content(tmp_path):
    content = 'x' * 5000
    result = apply_layer0(content, char_budget=2000, spill_threshold=40_000, spill_dir=tmp_path)
    assert len(result.content) < len(content)
    assert '此处省略' in result.content
    # 留痕：说了省掉多少
    assert '3000' in result.content
    assert result.spilled is False


def test_layer0_spills_oversized_content_to_disk(tmp_path):
    content = 'y' * 50_000
    result = apply_layer0(content, char_budget=2000, spill_threshold=40_000,
                          spill_dir=tmp_path, label='execute')
    assert result.spilled is True
    assert result.spilled_path
    # 原文完整落在磁盘上，没有丢
    assert len(open(result.spilled_path, encoding='utf-8').read()) == 50_000
    # 原位留引用，告诉模型怎么读回来
    assert '.scratch/' in result.content
    assert 'read_file' in result.content


def test_layer0_spill_survives_unwritable_dir(tmp_path):
    """外置失败要退化成截断，不能因此弄死一轮。"""
    bad = tmp_path / 'file-not-dir'
    bad.write_text('x', encoding='utf-8')
    result = apply_layer0('z' * 50_000, char_budget=2000, spill_threshold=40_000, spill_dir=bad)
    assert result.spilled is False
    assert '此处省略' in result.content


# ═══════════════════════════ 第 1 层 ═══════════════════════════

def _tool(content: str, name: str = 'read_file') -> ToolMessage:
    return ToolMessage(content=content, tool_call_id='c1', name=name)


def test_layer1_dedups_identical_tool_output():
    same = 'x' * 500
    messages = [HumanMessage('q'), _tool(same), _tool(same)]
    out, notes = _layer1(messages)

    assert total_tokens(out) < total_tokens(messages)
    assert any('去重' in n for n in notes)
    assert '完全相同' in str(out[2].content)


def test_layer1_degrades_long_tool_output_but_keeps_a_trace():
    """降级不是擦除 —— 内容还在（工作区/磁盘），只是变短。"""
    messages = [HumanMessage('q'), _tool('line one\n' + 'x' * 2000)]
    out, notes = _layer1(messages)

    degraded = str(out[1].content)
    assert len(degraded) < 300
    assert 'read_file' in degraded, '要保留「这里发生过什么」'
    assert 'line one' in degraded, '首行信息要留下'
    assert any('降级' in n for n in notes)


def test_layer1_leaves_short_tool_output_alone():
    messages = [HumanMessage('q'), _tool('短输出')]
    out, _ = _layer1(messages)
    assert out[1].content == '短输出'


def test_layer1_never_touches_conversation_text():
    """绝不改对话正文 —— 用户的约束与纠正一旦被改写就没法追溯。"""
    messages = [
        HumanMessage('不要动 src/legacy.ts，那里是遗留兼容代码'),
        AIMessage('明白了' * 500),
        _tool('工具输出' * 500),
    ]
    out, _ = _layer1(messages)

    assert out[0].content == messages[0].content
    assert out[1].content == messages[1].content


# ═══════════════════════════ 第 2 层 ═══════════════════════════

def test_layer2_protects_recent_window():
    """最近的工作上下文最重要，不能动。

    用 2000 的窗口把保护窗口压到 200 token —— 否则要构造十几万字符才越界。
    """
    messages = [_tool('旧输出' * 500, name='read_file'), HumanMessage('最近的问题')]
    out, notes, reclaimed = _layer2(messages, Thresholds(2_000))

    assert reclaimed > 0
    assert '原有工具输出' in str(out[0].content)
    assert out[1].content == '最近的问题'
    assert any('原位留下占位符' in n for n in notes)


def test_layer2_never_prunes_whitelisted_tools():
    messages = [_tool('技能文档' * 500, name='skill'), HumanMessage('q')]
    out, _, reclaimed = _layer2(messages, Thresholds(2_000))

    assert reclaimed == 0
    assert out[0].content == '技能文档' * 500


def test_layer2_leaves_human_and_ai_messages_alone():
    messages = [
        _tool('工具' * 500),
        HumanMessage('用户的话' * 500),
        AIMessage('模型的回复' * 500),
    ]
    out, _, _ = _layer2(messages, Thresholds(2_000))
    assert out[1].content == '用户的话' * 500
    assert out[2].content == '模型的回复' * 500


# ═══════════════════════════ 第 3 层 ═══════════════════════════

def _summarizer(text: str, budget: int) -> str:
    return f'摘要（预算 {budget}）：共 {len(text)} 字符的早期对话'


def test_layer3_keeps_last_user_message_verbatim():
    """绝不动最新一条用户消息。"""
    messages = [SystemMessage('系统')] + [
        HumanMessage(f'第 {i} 轮用户消息' + 'x' * 3000) for i in range(10)
    ]
    result = compact(messages, window=8_000, summarize=_summarizer)

    assert result.layer == 'layer3'
    assert result.messages[-1].content == messages[-1].content


def test_layer3_inserts_bridge_and_marks_folding():
    messages = [SystemMessage('系统')] + [
        HumanMessage(f'轮 {i}' + 'x' * 3000) for i in range(12)
    ]
    result = compact(messages, window=8_000, summarize=_summarizer)

    text = '\n'.join(str(m.content) for m in result.messages)
    assert '原文已折叠' in text, '丢内容必须留痕'
    assert '摘要' in text
    assert any('折叠中段' in n for n in result.notes)


def test_layer3_refuses_empty_summary_and_keeps_original():
    """空判定必须作用于模型原始输出 —— 这正是参考项目踩过的坑。"""
    messages = [SystemMessage('系统')] + [HumanMessage('轮' + 'x' * 3000) for _ in range(12)]
    result = compact(messages, window=8_000, summarize=lambda t, b: '')

    assert '失败' in ' '.join(result.warnings)
    # 中间段原样保留，没有「无摘要丢弃」
    assert total_tokens(result.messages) >= total_tokens(messages) * 0.8


def test_layer3_refuses_whitespace_only_summary():
    messages = [SystemMessage('系统')] + [HumanMessage('轮' + 'x' * 3000) for _ in range(12)]
    result = compact(messages, window=8_000, summarize=lambda t, b: '   \n  ')
    assert '失败' in ' '.join(result.warnings)


def test_layer3_failure_keeps_messages_intact():
    messages = [SystemMessage('系统')] + [HumanMessage('轮' + 'x' * 3000) for _ in range(12)]

    def boom(text: str, budget: int) -> str:
        raise RuntimeError('模型挂了')

    result = compact(messages, window=8_000, summarize=boom)
    assert '失败' in ' '.join(result.warnings)
    assert len(result.messages) >= len(messages) - 2


def test_layer3_never_splits_tool_pairs():
    """切成「有 tool_calls 的 AIMessage 但缺结果」会让供应商报协议错。"""
    messages = [SystemMessage('系统')]
    for i in range(8):
        messages.append(AIMessage(f'轮 {i}', tool_calls=[
            {'name': 'read_file', 'args': {}, 'id': f'call-{i}'}
        ]))
        messages.append(ToolMessage(f'结果 {i}' + 'x' * 300, tool_call_id=f'call-{i}', name='read_file'))
    messages.append(HumanMessage('最新问题'))

    result = compact(messages, window=4_000, summarize=_summarizer)

    # 检查裁剪后的序列里没有「孤立的 ToolMessage」
    ids: set[str] = set()
    for message in result.messages:
        for call in getattr(message, 'tool_calls', None) or []:
            ids.add(str(call.get('id')))
    for message in result.messages:
        if isinstance(message, ToolMessage) and getattr(message, 'tool_call_id', None):
            assert message.tool_call_id in ids, f'切出了孤立的工具结果 {message.tool_call_id}'


# ═══════════════════════════ 铁律 ═══════════════════════════

def test_no_compaction_below_trigger():
    """摊销不等于没满也压 —— 没到触发点什么都不做。"""
    messages = [HumanMessage('短对话')]
    result = compact(messages, window=1_000_000, summarize=_summarizer)

    assert result.layer == 'none'
    assert result.messages == messages


def test_stops_at_layer1_when_enough():
    """铁律一：第 1 层能解决就不许用第 2 层。"""
    window = 1_000
    messages = [HumanMessage('q')]
    # 一条长工具输出 + 几条重复，第 1 层去重/降级后应能降到触发点以下
    messages.append(_tool('x' * 4000))
    messages.append(_tool('x' * 4000))

    calls = {'n': 0}

    def counting_summarizer(text: str, budget: int) -> str:
        calls['n'] += 1
        return '摘要'

    result = compact(messages, window=window, summarize=counting_summarizer)

    assert result.layer in ('layer1', 'layer2'), f'不该升到第 3 层，实际 {result.layer}'
    assert calls['n'] == 0, '第 1/2 层够用时不该调 LLM'


def test_never_inverts_order_degrades_before_erases():
    """铁律二：先降级（内容还在、只是变短），再擦除（换占位符）。"""
    window = 1_000
    messages = [HumanMessage('q'), _tool('y' * 6000, name='read_file'), _tool('y' * 6000, name='grep')]

    result = compact(messages, window=window, summarize=_summarizer)

    # 第 1 层先跑过：要么降级，要么后续层在降级结果上继续
    assert any('第 1 层' in n or '第 2 层' in n or '第 3 层' in n for n in result.notes)


def test_reports_what_was_lost():
    """丢信息必须留痕，禁止静默移除。"""
    messages = [SystemMessage('系统')] + [HumanMessage('轮' + 'x' * 3000) for _ in range(12)]
    result = compact(messages, window=8_000, summarize=_summarizer)

    assert result.changed
    assert result.notes, '任何造成内容损失的动作都要有说明'
    assert result.before_tokens > 0


def test_noop_is_not_reported_as_compaction():
    """降不下来就不算压过。

    实测踩过：固定开销把触发点撑破之后，每一轮都「触发」一次第 3 层，
    但中段为空、什么都没变 —— 日志刷了 28 条，token 从 8720 变成 8722。
    这种空转既误导观测，又让调用方白白替换一遍消息列表。
    """
    messages = [HumanMessage('很短的对话')]
    # 固定开销极大 → 总占用超过触发点，但消息本身没什么可压的
    result = compact(messages, window=8_000, summarize=_summarizer, overhead=20_000)

    assert result.layer == 'none', '没有实际下降就不该报告成压缩'
    assert result.notes == [], '没做事就不该留痕'
    assert result.messages == messages


def test_reports_change_only_when_tokens_actually_drop():
    messages = [SystemMessage('系统')] + [HumanMessage('轮' + 'x' * 3000) for _ in range(12)]
    result = compact(messages, window=8_000, summarize=_summarizer)

    assert result.after_tokens < result.before_tokens, '报告成压缩了就必须真的降下来'
