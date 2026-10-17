"""单次导入工具许可：仅SELECT，决定和CAS消费由Java写入。"""
from __future__ import annotations

import json
import time
import uuid

from backends.import_action import action_hash, canonical_bytes


class ImportPermissionStopped(RuntimeError):
    """不可转成可恢复ToolMessage；整个导入执行必须停止。"""


class ImportPermissions:
    def __init__(self, connection, identity, *, eligible, emitter, clock=time.monotonic, sleep=time.sleep):
        self.connection, self.identity = connection, dict(identity)
        self.eligible, self.emitter = eligible, emitter
        self.clock, self.sleep = clock, sleep
        self._used_permissions, self._used_proofs = set(), set()
        self._stopped = False

    def _qualified(self):
        if self._stopped or self.eligible() is not True:
            raise ImportPermissionStopped('IMPORT_PERMISSION_REVOKED')

    def _row(self, permission_id, digest):
        i = self.identity
        # 许可只带edit_id，其余完整身份必须通过父编辑匹配；不能只按UUID读票。
        return self.connection.execute('''SELECT p.status,p.consumption_proof,p.action_json,
                    p.expires_at>now() AND e.expires_at>now()
                FROM biz.project_import_agent_permission p
                JOIN biz.project_import_agent_edit e ON e.edit_id=p.edit_id
                WHERE p.permission_id=%s AND p.edit_id=%s AND p.action_hash=%s
                  AND e.owner_id=%s AND e.app_id=%s AND e.run_id=%s AND e.import_task_id=%s
                  AND e.source_hash=%s AND e.config_revision=%s AND e.context_epoch=%s
                  AND e.state='EDITING' AND NOT e.cancel_requested AND e.closed_at IS NULL''',
            (permission_id, i['editId'], digest, i['ownerId'], i['appId'], i['runId'], i['importTaskId'],
             i['sourceHash'], i['configRevision'], i['contextEpoch'])).fetchone()

    def _wait(self, permission_id, digest, raw, expected, deadline):
        heartbeat = self.clock()
        while True:
            self._qualified()
            if self.clock() >= deadline:
                raise ImportPermissionStopped('IMPORT_PERMISSION_TIMEOUT')
            row = self._row(permission_id, digest)
            if row:
                state, proof, registered, live = row
                if live is not True or canonical_bytes(registered) != raw:
                    raise ImportPermissionStopped('IMPORT_PERMISSION_MISMATCH')
                if state == expected:
                    if expected == 'APPROVED' and proof is not None:
                        raise ImportPermissionStopped('IMPORT_PERMISSION_REPLAY')
                    return proof
                allowed = {'REQUESTED'} if expected == 'APPROVED' else {'APPROVED'}
                if state not in allowed:
                    raise ImportPermissionStopped('IMPORT_PERMISSION_' + str(state))
            now = self.clock()
            if now >= heartbeat:
                self.emitter.emit('import.permission.heartbeat', editId=self.identity['editId'], permissionId=permission_id)
                heartbeat = now + 5
            self.sleep(min(0.25, max(0, deadline - now)))

    def perform(self, factory, operation):
        permission_id, proof, digest = None, None, None
        try:
            # 1. 硬校验和冻结完成后才通知Java；绝不发送未验证内容。
            self._qualified()
            action = factory()
            raw = canonical_bytes(action)
            digest = action_hash(action)
            permission_id = str(uuid.uuid4())
            deadline = self.clock() + 300
            self.emitter.emit('import.permission.requested', editId=self.identity['editId'],
                              permissionId=permission_id, actionHash=digest, action=json.loads(raw))
            self._wait(permission_id, digest, raw, 'APPROVED', deadline)
            self._qualified()
            if canonical_bytes(factory()) != raw:
                raise ImportPermissionStopped('IMPORT_PERMISSION_ACTION_CHANGED')
            # 2. 每次请求都用新票据；消费证明不能复用，消费后失败也不重放。
            consume_id = str(uuid.uuid4())
            self.emitter.emit('import.permission.consume', editId=self.identity['editId'],
                              permissionId=permission_id, actionHash=digest, consumeRequestId=consume_id)
            proof = self._wait(permission_id, digest, raw, 'CONSUMED', deadline)
            if not isinstance(proof, (str, uuid.UUID)):
                raise ImportPermissionStopped('IMPORT_PERMISSION_PROOF_INVALID')
            proof = str(proof)
            if str(uuid.UUID(proof)) != proof or permission_id in self._used_permissions or proof in self._used_proofs:
                raise ImportPermissionStopped('IMPORT_PERMISSION_REPLAY')
            self._used_permissions.add(permission_id)
            self._used_proofs.add(proof)
            self._qualified()
            if canonical_bytes(factory()) != raw:
                raise ImportPermissionStopped('IMPORT_PERMISSION_ACTION_CHANGED')
            self._qualified()
            # 3. 此处才允许副作用；SDK错误结果也是明确失败，不让模型另开票重试。
            result = operation()
            values = result if isinstance(result, list) else [result]
            succeeded = all(not getattr(value, 'error', None) and getattr(value, 'exit_code', 0) == 0 for value in values)
            self.emitter.emit('import.permission.result', editId=self.identity['editId'], permissionId=permission_id,
                              actionHash=digest, consumeProof=proof, state='RESULT_CONFIRMED', succeeded=succeeded)
            if not succeeded:
                self._stopped = True
                raise ImportPermissionStopped('IMPORT_PERMISSION_EXECUTION_FAILED')
            return result
        except BaseException as error:
            self._stopped = True
            if proof is not None and permission_id in self._used_permissions:
                # 明确失败的返回值已上报；异常则不能断言实际副作用是否完成。
                if not isinstance(error, ImportPermissionStopped) or str(error) != 'IMPORT_PERMISSION_EXECUTION_FAILED':
                    self.emitter.emit('import.permission.result', editId=self.identity['editId'], permissionId=permission_id,
                                      actionHash=digest, consumeProof=proof, state='RESULT_UNCONFIRMED', succeeded=False)
            if isinstance(error, ImportPermissionStopped):
                raise
            if isinstance(error, Exception):
                raise ImportPermissionStopped('IMPORT_PERMISSION_UNCONFIRMED') from error
            raise
