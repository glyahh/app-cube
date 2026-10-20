"""普通应用的会话工具许可。

与导入的区别只在存储表与动作源：
- 导入读 V40 表、动作从去秘密视图重算
- 普通读 V44 表、动作从真实工作区重算

决策、消费、结果回写语义完全一致，不接受执行 Agent 自报的风险结论。
"""
from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
import uuid

MAX_ACTION_BYTES = 1 << 20
logger = logging.getLogger(__name__)


class ToolPermissionStopped(RuntimeError):
    """不可转成可恢复 ToolMessage；整个执行必须停止。"""


def canonical_bytes(value):
    """递归字典序、UTF-8、无空白；与 Java ImportAgentCanonicalJson 保持一致。"""
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True,
                     separators=(',', ':'), allow_nan=False).encode('utf-8')
    if len(raw) > MAX_ACTION_BYTES:
        raise ToolPermissionStopped('TOOL_PERMISSION_ACTION_LIMIT')
    return raw


def action_hash(action):
    return hashlib.sha256(canonical_bytes(action)).hexdigest()


class ToolPermissions:
    """单次许可的等待、消费与结果回写。

    调用方必须在硬边界校验（路径、命令白名单）**之后**才构造动作，
    否则会先弹批准再拒绝，用户点了也没有意义。
    """

    def __init__(self, connection, session_id, *, eligible, emitter, workspace=None,
                 clock=time.monotonic, sleep=time.sleep, wait_seconds=120):
        self.connection = connection
        self.session_id = str(session_id)
        # 变更账本需要读写入前后的内容；为 None 时只做许可，不记账。
        self.workspace = workspace
        self.eligible = eligible
        self.emitter = emitter
        self.clock, self.sleep = clock, sleep
        self.wait_seconds = wait_seconds
        self._used_permissions, self._used_proofs = set(), set()
        self._stopped = False
        # 同一会话同时只能有一张待决票据（库里 agent_tool_permission_wait_idx 保证）。
        # 模型一次发出多个工具调用时会并发请求，不串行化就会撞唯一索引，
        # 整轮被判失败 —— 这是「小游戏」这类复杂任务必挂的原因。
        self._gate = threading.Lock()

    def _qualified(self):
        if self._stopped or self.eligible() is not True:
            raise ToolPermissionStopped('TOOL_PERMISSION_REVOKED')

    def _row(self, permission_id, digest):
        # 许可只带 session；其余资格必须通过父会话匹配，不能只按 UUID 读票。
        return self.connection.execute('''SELECT p.status,p.consumption_proof,p.action_json,
                    p.expires_at>now() AND s.expires_at>now()
                FROM biz.agent_tool_permission p
                JOIN biz.agent_edit_session s ON s.session_id=p.session_id
                WHERE p.permission_id=%s AND p.session_id=%s AND p.action_hash=%s
                  AND s.state='EDITING' AND s.closed_at IS NULL''',
            (permission_id, self.session_id, digest)).fetchone()

    def _wait(self, permission_id, digest, raw, expected, deadline):
        heartbeat = self.clock()
        while True:
            self._qualified()
            if self.clock() >= deadline:
                raise ToolPermissionStopped('TOOL_PERMISSION_TIMEOUT')
            row = self._row(permission_id, digest)
            if row:
                state, proof, registered, live = row
                if live is not True or canonical_bytes(registered) != raw:
                    raise ToolPermissionStopped('TOOL_PERMISSION_MISMATCH')
                if state == expected:
                    if expected == 'APPROVED' and proof is not None:
                        raise ToolPermissionStopped('TOOL_PERMISSION_REPLAY')
                    return proof
                allowed = {'REQUESTED'} if expected == 'APPROVED' else {'APPROVED'}
                if state not in allowed:
                    raise ToolPermissionStopped('TOOL_PERMISSION_' + str(state))
            now = self.clock()
            if now >= heartbeat:
                self.emitter.emit('agent.permission.heartbeat', sessionId=self.session_id,
                                  permissionId=permission_id)
                heartbeat = now + 5
            self.sleep(min(0.25, max(0, deadline - now)))

    def _snapshot(self, action):
        """读操作前的文件内容；读不到就当新增。"""
        if self.workspace is None:
            return None
        path = action.get('path')
        if not isinstance(path, str) or not path:
            return None
        try:
            target = (self.workspace / path).resolve()
            target.relative_to(self.workspace)
            return target.read_text(encoding='utf-8') if target.is_file() else None
        except (OSError, ValueError):
            return None

    def _record(self, action, before):
        """成功后上报变更，由 Java 落账本；不在这里直写数据库。"""
        path = action.get('path')
        if self.workspace is None or not isinstance(path, str) or not path:
            return
        after = self._snapshot(action)
        if before == after:
            return
        if before is None:
            change = 'ADDED'
        elif after is None:
            change = 'DELETED'
        else:
            change = 'MODIFIED'
        self.emitter.emit('agent.change.recorded', sessionId=self.session_id, path=path,
                          change=change, beforeText=before, afterText=after)

    def perform(self, factory, operation):
        # 工具在 asyncio.to_thread 里跑，这里是真线程，用阻塞锁安全。
        # 拿不到锁说明同会话已有票据在等，排队而不是并发插库。
        with self._gate:
            return self._perform_locked(factory, operation)

    def _perform_locked(self, factory, operation):
        permission_id, proof, digest = None, None, None
        action = None
        try:
            # 1. 硬校验与冻结完成后才通知 Java；绝不发送未经校验的内容。
            self._qualified()
            action = factory()
            raw = canonical_bytes(action)
            digest = action_hash(action)
            permission_id = str(uuid.uuid4())
            deadline = self.clock() + self.wait_seconds
            self.emitter.emit('agent.permission.requested', sessionId=self.session_id,
                              permissionId=permission_id, actionHash=digest,
                              action=json.loads(raw))
            logger.info("许可请求 tool=%s permission=%s hash=%s",
                        action.get('tool'), permission_id, digest[:12])
            self._wait(permission_id, digest, raw, 'APPROVED', deadline)
            self._qualified()
            if canonical_bytes(factory()) != raw:
                raise ToolPermissionStopped('TOOL_PERMISSION_ACTION_CHANGED')
            # 2. 每次请求都用新票据；消费证明不能复用，消费后失败也不重放。
            consume_id = str(uuid.uuid4())
            self.emitter.emit('agent.permission.consume', sessionId=self.session_id,
                              permissionId=permission_id, actionHash=digest,
                              consumeRequestId=consume_id)
            proof = self._wait(permission_id, digest, raw, 'CONSUMED', deadline)
            if not isinstance(proof, (str, uuid.UUID)):
                raise ToolPermissionStopped('TOOL_PERMISSION_PROOF_INVALID')
            proof = str(proof)
            if str(uuid.UUID(proof)) != proof or permission_id in self._used_permissions \
                    or proof in self._used_proofs:
                raise ToolPermissionStopped('TOOL_PERMISSION_REPLAY')
            self._used_permissions.add(permission_id)
            self._used_proofs.add(proof)
            self._qualified()
            if canonical_bytes(factory()) != raw:
                raise ToolPermissionStopped('TOOL_PERMISSION_ACTION_CHANGED')
            self._qualified()
            # 3. 此处才允许副作用；已确认的命令失败返回模型，修正后仍需新许可。
            before = self._snapshot(action)
            result = operation()
            if action.get('tool') == 'execute' and type(getattr(result, 'exit_code', None)) is not int:
                raise ToolPermissionStopped('TOOL_PERMISSION_UNCONFIRMED')
            values = result if isinstance(result, list) else [result]
            succeeded = all(not getattr(value, 'error', None)
                            and getattr(value, 'exit_code', 0) == 0 for value in values)
            if succeeded:
                self._record(action, before)
            self.emitter.emit('agent.permission.result', sessionId=self.session_id,
                              permissionId=permission_id, actionHash=digest,
                              consumeProof=proof, state='RESULT_CONFIRMED', succeeded=succeeded)
            if not succeeded and action.get('tool') != 'execute':
                self._stopped = True
                raise ToolPermissionStopped('TOOL_PERMISSION_EXECUTION_FAILED')
            return result
        except BaseException as error:
            self._stopped = True
            # 原始异常必须留痕：否则现场只剩一个 TOOL_PERMISSION_UNCONFIRMED，
            # 无法判断是容器、秘密扫描还是命令本身出的问题。
            logger.warning("工具许可执行失败 tool=%s error=%s: %s",
                           action.get('tool') if isinstance(action, dict) else None,
                           type(error).__name__, error, exc_info=True)
            if proof is not None and permission_id in self._used_permissions:
                if not isinstance(error, ToolPermissionStopped) \
                        or str(error) != 'TOOL_PERMISSION_EXECUTION_FAILED':
                    self.emitter.emit('agent.permission.result', sessionId=self.session_id,
                                      permissionId=permission_id, actionHash=digest,
                                      consumeProof=proof, state='RESULT_UNCONFIRMED', succeeded=False)
            if isinstance(error, ToolPermissionStopped):
                raise
            if isinstance(error, Exception):
                raise ToolPermissionStopped('TOOL_PERMISSION_UNCONFIRMED') from error
            raise
