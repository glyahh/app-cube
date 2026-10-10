"""每轮独立止损；确定性拒绝不能成为成功候选。"""
import io
import json
from types import SimpleNamespace

import pytest
from langchain_core.messages import ToolMessage
from langchain.agents.middleware.model_call_limit import ModelCallLimitExceededError
from core.events import Emitter
from core.streaming_runtime import run_agent_with_events


class EventsAgent:
    def __init__(self, outputs):
        self.outputs = outputs

    async def astream_events(self, *args, **kwargs):
        for index, (name, output) in enumerate(self.outputs):
            yield {'event': 'on_tool_start', 'name': name, 'run_id': str(index)}
            yield {'event': 'on_tool_end', 'name': name, 'run_id': str(index), 'data': {'output': output}}


def rejected(reason='OPERATORS'):
    return ToolMessage(content='Error: Invalid parameter. IMPORT_COMMAND_REJECTED:' + reason + '。请修正命令。',
                       tool_call_id='test', name='execute', status='error')


def run(agent, import_edit=True):
    stream = io.StringIO()
    summary = run_agent_with_events(agent=agent, messages=[{'content': '检查并修改'}], thread_id='unit',
                                    emitter=Emitter(stream, '201'), task_kind='coding', import_edit=import_edit)
    return summary, [json.loads(line) for line in stream.getvalue().splitlines()]


def test_second_same_category_rejection_stops_even_with_other_tools():
    summary, events = run(EventsAgent([('execute', rejected()), ('read_file', 'ok'),
                                      ('execute', rejected()), ('edit_file', 'must not happen')]))
    assert summary['status'] == 'failed'
    assert summary['failureKey'] == 'IMPORT_EDIT_REPEATED_COMMAND_REJECTION'
    assert not any(e.get('toolName') == 'edit_file' for e in events)
    assert events[-1]['event'] == 'error' and events[-1]['failureKey'] == summary['failureKey']
    assert [e['rejectionReason'] for e in events if e.get('failureKey') == 'IMPORT_COMMAND_REJECTED'] == ['OPERATORS'] * 2


def test_first_rejection_can_be_corrected_and_new_round_resets():
    for _ in range(2):
        summary, _ = run(EventsAgent([('execute', rejected()), ('execute', 'ok'), ('edit_file', 'ok')]))
        assert summary['status'] == 'succeeded'


def test_different_categories_and_transient_errors_are_not_same_rejection():
    transient = ToolMessage(content='timeout', tool_call_id='test', status='error')
    summary, _ = run(EventsAgent([('execute', rejected('QUOTING')), ('execute', transient),
                                 ('execute', transient), ('execute', rejected('OPERATORS'))]))
    assert summary['status'] == 'succeeded'


def test_non_import_execution_keeps_legacy_behavior():
    summary, _ = run(EventsAgent([('execute', rejected()), ('execute', rejected())]), import_edit=False)
    assert summary['status'] == 'succeeded'


class BudgetAgent:
    async def astream_events(self, *args, **kwargs):
        yield {'event': 'on_chat_model_start'}
        raise ModelCallLimitExceededError(thread_count=60, run_count=60, thread_limit=None, run_limit=60)


def test_import_model_budget_is_explicit_failure():
    summary, events = run(BudgetAgent())
    assert summary['status'] == 'failed' and summary['failureKey'] == 'IMPORT_EDIT_MODEL_BUDGET_EXHAUSTED'
    assert events[-1]['event'] == 'error'
    assert events[-1]['code'] == 'MODEL_QUOTA_EXCEEDED'


def test_non_import_budget_exception_behavior_is_unchanged():
    with pytest.raises(ModelCallLimitExceededError):
        run(BudgetAgent(), import_edit=False)


def test_typed_tool_error_has_same_stop_rule():
    from backends.permissions import ImportCommandRejected
    class TypedAgent:
        async def astream_events(self, *args, **kwargs):
            for index in range(2):
                yield {'event': 'on_tool_error', 'name': 'execute', 'run_id': str(index),
                       'data': {'error': ImportCommandRejected('PATH')}}
    summary, events = run(TypedAgent())
    assert summary['failureKey'] == 'IMPORT_EDIT_REPEATED_COMMAND_REJECTION'
    assert [e['rejectionReason'] for e in events if e.get('rejectionReason')] == ['PATH', 'PATH']
