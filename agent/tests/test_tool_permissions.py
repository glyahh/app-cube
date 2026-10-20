"""普通应用门禁：未批准不得产生副作用。"""
from __future__ import annotations

import json

import pytest

from core.tool_permissions import ToolPermissions, ToolPermissionStopped, action_hash, canonical_bytes


class FakeConnection:
    def __init__(self, row):
        self.row = row

    def fetchone(self):
        return self.row


class FakeCursor:
    """按脚本返回票据状态；脚本耗尽后一直返回最后一行的副本。"""

    def __init__(self, rows):
        self.rows = list(rows)
        self.calls = 0

    def execute(self, sql, params):
        self.calls += 1
        if self.rows:
            self.row = self.rows.pop(0)
        return FakeConnection(self.row)


class FakeEmitter:
    def __init__(self):
        self.events = []

    def emit(self, event, **payload):
        self.events.append((event, payload))


def make(rows, *, eligible=lambda: True):
    tick = iter(range(100000))
    emitter = FakeEmitter()
    permissions = ToolPermissions(
        FakeCursor(rows), 'session-1', eligible=eligible, emitter=emitter,
        clock=lambda: next(tick), sleep=lambda _: None, wait_seconds=5)
    return permissions, emitter


def row(state, proof=None, action=None, live=True):
    return (state, proof, json.loads(canonical_bytes(action)), live)


def test_approved_then_consumed_allows_side_effect_once():
    action = {'tool': 'write', 'path': 'a.txt', 'content': 'x'}
    permissions, _ = make([
        row('APPROVED', None, action),
        row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000001', action),
    ])
    ran = []
    assert permissions.perform(lambda: action, lambda: ran.append(1) or 'ok') == 'ok'
    assert ran == [1]


def test_pending_permission_times_out_without_side_effect():
    action = {'tool': 'write', 'path': 'a.txt', 'content': 'x'}
    permissions, _ = make([row('REQUESTED', None, action)])
    ran = []
    with pytest.raises(ToolPermissionStopped, match='TIMEOUT'):
        permissions.perform(lambda: action, lambda: ran.append(1))
    assert ran == []


def test_rejected_permission_never_runs():
    action = {'tool': 'delete', 'path': 'a.txt'}
    permissions, _ = make([row('REJECTED', None, action)])
    ran = []
    with pytest.raises(ToolPermissionStopped, match='REJECTED'):
        permissions.perform(lambda: action, lambda: ran.append(1))
    assert ran == []


def test_action_change_after_approval_blocks_execution():
    """票面动作与当前动作不一致时必须停：先于执行拦截，报 MISMATCH。"""
    approved = {'tool': 'write', 'path': 'a.txt', 'content': 'one'}
    permissions, _ = make([row('APPROVED', None, approved)])
    ran = []
    with pytest.raises(ToolPermissionStopped, match='MISMATCH'):
        permissions.perform(lambda: {'tool': 'write', 'path': 'a.txt', 'content': 'two'},
                            lambda: ran.append(1))
    assert ran == []


def test_revoked_eligibility_stops_before_request():
    permissions, emitter = make([], eligible=lambda: False)
    ran = []
    with pytest.raises(ToolPermissionStopped, match='REVOKED'):
        permissions.perform(lambda: {'tool': 'write', 'path': 'a.txt'}, lambda: ran.append(1))
    assert ran == []
    assert emitter.events == []


def test_expired_ticket_is_not_treated_as_approved():
    """live=False 表示会话或票据已过期，绝不能执行。"""
    action = {'tool': 'write', 'path': 'a.txt', 'content': 'x'}
    permissions, _ = make([row('APPROVED', None, action, live=False)])
    ran = []
    with pytest.raises(ToolPermissionStopped, match='MISMATCH'):
        permissions.perform(lambda: action, lambda: ran.append(1))
    assert ran == []