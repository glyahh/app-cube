"""端到端：真实数据库票据 + backend 门禁，验证未批准不落盘。

需要 AGENT_POSTGRES_DSN 与已应用的 V44–V46 迁移；缺失时跳过。

真实链路里票据由 Java 从 agent.permission.requested 事件创建并决定；
这里用独立连接复刻同一顺序：插票 → 决定 → 消费。
Python 侧只负责等待与消费证明校验，绝不自行批准。
"""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
import threading
import time
import uuid
from pathlib import Path

import pytest

psycopg = pytest.importorskip('psycopg')
pytestmark = pytest.mark.external


def _dsn():
    for name in ('AGENT_POSTGRES_DSN', 'POSTGRES_DSN'):
        value = os.environ.get(name, '').strip()
        if value:
            return value
    # 测试进程不继承 .env，从仓库根读取一次。
    # 必须按键优先级取，不能按行序：.env 里 POSTGRES_DSN 就在 AGENT_POSTGRES_DSN 前面，
    # 且两者用户不同（前者是受限角色，后者是平台角色）。
    env_file = Path(__file__).parents[2] / '.env'
    values = {}
    if env_file.exists():
        for line in env_file.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip().strip('"')
    for name in ('AGENT_POSTGRES_DSN', 'POSTGRES_DSN'):
        if values.get(name):
            return values[name]
    return ''


@pytest.fixture
def db():
    dsn = _dsn()
    if not dsn:
        pytest.skip('未配置数据库连接')
    connection = psycopg.connect(dsn, autocommit=True)
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT to_regclass('biz.agent_edit_session')")
            if cur.fetchone()[0] is None:
                pytest.skip('V44 迁移未应用')
        yield connection
    finally:
        connection.close()


def _owner_app(connection):
    with connection.cursor() as cur:
        cur.execute('SELECT id, user_id FROM biz.app WHERE is_delete=0 LIMIT 1')
        row = cur.fetchone()
    if row is None:
        pytest.skip('没有可用应用')
    return row[1], row[0]


def _open_session(connection, owner, app, *, approval='MANUAL', sandbox='WORKSPACE_WRITE'):
    session_id = uuid.uuid4()
    with connection.cursor() as cur:
        cur.execute(
            'INSERT INTO biz.agent_edit_session(session_id,owner_id,app_id,run_id,source_hash,'
            'sandbox_mode,approval_mode,policy_revision,session_epoch,server_generation,state,expires_at) '
            "VALUES(%s,%s,%s,1,%s,%s,%s,0,%s,%s,'EDITING',now()+interval '30 minutes')",
            (session_id, owner, app, '0' * 64, sandbox, approval, uuid.uuid4(), uuid.uuid4()))
    return session_id


def _close(connection, session_id):
    with connection.cursor() as cur:
        cur.execute("UPDATE biz.agent_edit_session SET state='CANCELLED',closed_at=now() "
                    'WHERE session_id=%s', (session_id,))
        cur.execute('DELETE FROM biz.agent_tool_permission WHERE session_id=%s', (session_id,))
        cur.execute('DELETE FROM biz.agent_edit_session WHERE session_id=%s', (session_id,))


class Emitter:
    def __init__(self):
        self.events = []

    def emit(self, event, **payload):
        self.events.append((event, payload))


def hash_scope(action):
    """与 Java 侧 scope_hash 同形态：对路径做规范化摘要。"""
    path = str(action.get('path') or '')
    canonical = json.dumps({'scope': path}, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def _permissions(connection, session_id, emitter, wait_seconds=1):
    from core.tool_permissions import ToolPermissions
    return ToolPermissions(connection, session_id, eligible=lambda: True, emitter=emitter,
                           clock=time.monotonic,
                           sleep=lambda seconds: time.sleep(min(seconds, 0.02)),
                           wait_seconds=wait_seconds)


def _java(dsn, session_id, emitter, *, approve=True, target_risk='ORDINARY'):
    """复刻 Java 侧顺序：按事件插票 → 决定 → 消费。"""
    stop = threading.Event()

    def run():
        client = psycopg.connect(dsn, autocommit=True)
        seen = set()
        try:
            while not stop.is_set():
                for name, payload in list(emitter.events):
                    if name != 'agent.permission.requested':
                        continue
                    permission_id = payload['permissionId']
                    if permission_id in seen:
                        continue
                    seen.add(permission_id)
                    action = payload['action']
                    with client.cursor() as cur:
                        try:
                            cur.execute(
                                'INSERT INTO biz.agent_tool_permission(permission_id,session_id,tool,'
                                'action_hash,action_json,risk,scope_hash,expires_at) '
                                "VALUES(%s,%s,%s,%s,CAST(%s AS jsonb),%s,%s,now()+interval '5 minutes')",
                                (permission_id, session_id, action['tool'],
                                 payload['actionHash'], json.dumps(action, ensure_ascii=False),
                                 target_risk, hash_scope(action)))
                        except Exception:
                            pass
                with client.cursor() as cur:
                    cur.execute("SELECT permission_id FROM biz.agent_tool_permission "
                                "WHERE session_id=%s AND status='REQUESTED' LIMIT 1", (session_id,))
                    row = cur.fetchone()
                    if row:
                        status = 'APPROVED' if approve else 'REJECTED'
                        # MANUAL 会话只接受 USER 来源；AUTO 会被数据库守卫拒绝。
                        cur.execute('UPDATE biz.agent_tool_permission SET status=%s,'
                                    'decision_source=%s,revision=revision+1 '
                                    "WHERE permission_id=%s AND status='REQUESTED'",
                                    (status, 'USER', row[0]))
                        continue
                    # 只在执行侧真正发出 consume 事件后才消费，避免抢先进入 CONSUMED
                    # 让 Python 看不到 APPROVED。
                    if not any(name == 'agent.permission.consume'
                               for name, _ in list(emitter.events)):
                        stop.wait(0.02)
                        continue
                    cur.execute("SELECT permission_id FROM biz.agent_tool_permission "
                                "WHERE session_id=%s AND status='APPROVED' LIMIT 1", (session_id,))
                    row = cur.fetchone()
                    if row:
                        cur.execute('UPDATE biz.agent_tool_permission SET status=\'CONSUMED\','
                                    'consumption_proof=%s,consume_request_id=%s,revision=revision+1 '
                                    "WHERE permission_id=%s AND status='APPROVED'",
                                    (uuid.uuid4(), uuid.uuid4(), row[0]))
                        continue
                stop.wait(0.02)
        finally:
            client.close()

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return stop, thread


def test_manual_without_approval_never_writes(db):
    """MANUAL 下没人批准，文件绝不能出现。"""
    from backends.local_shell import GuardedShellBackend
    from core.tool_permissions import ToolPermissionStopped

    owner, app = _owner_app(db)
    session = _open_session(db, owner, app, approval='MANUAL')
    try:
        with tempfile.TemporaryDirectory() as root:
            emitter = Emitter()
            backend = GuardedShellBackend(root, permissions=_permissions(db, session, emitter, 1))
            with pytest.raises(ToolPermissionStopped):
                backend.write('/gate.txt', 'blocked')
            assert not (Path(root) / 'gate.txt').exists(), '未批准却写入了文件'
            assert any(name == 'agent.permission.requested' for name, _ in emitter.events)
    finally:
        _close(db, session)


def test_approved_write_lands_exactly_once(db):
    """批准后写入成功，并留下消费证明。"""
    from backends.local_shell import GuardedShellBackend

    owner, app = _owner_app(db)
    session = _open_session(db, owner, app, approval='MANUAL')
    emitter = Emitter()
    stop, thread = _java(_dsn(), session, emitter, approve=True)
    try:
        with tempfile.TemporaryDirectory() as root:
            backend = GuardedShellBackend(root, permissions=_permissions(db, session, emitter, 5))
            backend.write('/ok.txt', 'approved')
            assert (Path(root) / 'ok.txt').read_text() == 'approved'
        with db.cursor() as cur:
            cur.execute('SELECT status FROM biz.agent_tool_permission WHERE session_id=%s', (session,))
            statuses = [row[0] for row in cur.fetchall()]
        assert statuses, '应当登记过票据'
        assert set(statuses) & {'CONSUMED', 'RESULT_CONFIRMED'}
    finally:
        stop.set()
        thread.join(timeout=2)
        _close(db, session)


def test_rejected_write_never_lands(db):
    """拒绝后文件绝不应出现。"""
    from backends.local_shell import GuardedShellBackend
    from core.tool_permissions import ToolPermissionStopped

    owner, app = _owner_app(db)
    session = _open_session(db, owner, app, approval='MANUAL')
    emitter = Emitter()
    stop, thread = _java(_dsn(), session, emitter, approve=False)
    try:
        with tempfile.TemporaryDirectory() as root:
            backend = GuardedShellBackend(root, permissions=_permissions(db, session, emitter, 5))
            with pytest.raises(ToolPermissionStopped):
                backend.write('/rejected.txt', 'nope')
            assert not (Path(root) / 'rejected.txt').exists()
    finally:
        stop.set()
        thread.join(timeout=2)
        _close(db, session)


def test_read_only_session_blocks_write(db):
    """只读档位由 ReadOnlyBackend 拦，不进入审批。"""
    from backends.local_shell import GuardedShellBackend
    from backends.read_only import ReadOnlyBackend, ReadOnlyViolation

    owner, app = _owner_app(db)
    session = _open_session(db, owner, app, approval='AUTO', sandbox='READ_ONLY')
    try:
        with tempfile.TemporaryDirectory() as root:
            emitter = Emitter()
            backend = ReadOnlyBackend(
                GuardedShellBackend(root, permissions=_permissions(db, session, emitter, 1)))
            # 只读层是硬拒绝：直接抛异常，不返回可继续的错误结果。
            with pytest.raises(ReadOnlyViolation):
                backend.write('/ro.txt', 'nope')
            assert not (Path(root) / 'ro.txt').exists()
    finally:
        _close(db, session)
