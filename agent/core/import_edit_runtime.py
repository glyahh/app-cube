"""正式原生编辑执行；结果仅供Java验证，不发送业务成功。"""
from __future__ import annotations

import os
from pathlib import Path
import uuid

from core.events import ErrorCode, Event


def _disk_bytes(value):
    # Java统一将Long序列化为字符串；容量仍须按同一硬限额严格核验。
    import re
    if isinstance(value, str) and re.fullmatch('[1-9][0-9]{0,10}', value):
        value = int(value)
    if type(value) is not int or not 16 << 20 <= value <= 8 << 30 or value % 4096:
        raise ValueError('IMPORT_EDIT_DISK_LIMIT_INVALID')
    return value


def run_import_edit(request, settings, emitter):
    workspace = request.get('workspace') or {}
    required = ('importEditId', 'sourceHash', 'importTaskId', 'configRevision', 'contextEpoch',
                'importViewPath', 'viewStateHash', 'containerId', 'workerLeaseId')
    if any(not isinstance(workspace.get(key), str) or not workspace[key] for key in required):
        emitter.error(ErrorCode.IMPORT_EDIT_NOT_READY, '隔离编辑身份不完整，本次未执行。')
        return
    backend = None
    connection = None
    audit_token = None
    stage = 'IMPORT_EDIT_IMPORTS'
    emitter.emit('import.edit.stage', stage=stage)
    try:
        from core.runtime import import_model_settings
        stage = 'IMPORT_EDIT_MODEL_CONFIGURATION'
        emitter.emit('import.edit.stage', stage=stage)
        settings, summarizer_settings, model_window = import_model_settings(request, settings)
        import psycopg
        from backends.import_platform_docker import ImportPlatformDockerManager
        from backends.secret_free_docker import SecretFreeDockerBackend
        from backends.secret_free_view import SecretFreeView
        from core.compaction import OverheadEstimator
        from core.model import ModelRouteState, model_request_audit, set_model_request_audit_fallback
        from core.persistence import get_checkpointer, get_store
        from core.streaming_runtime import _event_loop, run_agent_with_events
        from server import build_agent

        stage = 'IMPORT_EDIT_IDENTITY'
        emitter.emit('import.edit.stage', stage=stage)
        edit_id = str(uuid.UUID(workspace['importEditId']))
        lease_id = str(uuid.UUID(workspace['workerLeaseId']))
        if edit_id != workspace['importEditId'] or lease_id != workspace['workerLeaseId']:
            raise ValueError('IMPORT_EDIT_IDENTITY_INVALID')
        root = Path('/var/lib/glyahh/import-disks') / ('edit-' + edit_id) / 'work'
        if workspace['importViewPath'] != str(root / 'view') or not settings.model.api_key:
            raise ValueError('IMPORT_EDIT_CONFIGURATION_INVALID')
        native_ticks = Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()[19]
        connection = psycopg.connect(settings.postgres_dsn, autocommit=True)

        def eligible():
            row = connection.execute('''SELECT EXISTS(
                SELECT 1 FROM biz.project_import_agent_edit e
                JOIN biz.project_import_agent_edit_worker w ON w.edit_id=e.edit_id
                JOIN biz.project_import_publication p ON p.app_id=e.app_id AND p.owner_id=e.owner_id AND p.task_id=e.import_task_id
                JOIN biz.app a ON a.id=e.app_id AND a.user_id=e.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=e.import_task_id AND t.owner_id=e.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration c ON c.app_id=e.app_id AND c.owner_id=e.owner_id AND c.import_task_id=e.import_task_id
                WHERE e.edit_id=%s AND e.owner_id=%s AND e.app_id=%s AND e.run_id=%s AND e.import_task_id=%s
                  AND e.source_hash=%s AND e.config_revision=%s AND e.context_epoch=%s AND e.state='EDITING'
                  AND NOT e.cancel_requested AND e.closed_at IS NULL AND e.expires_at>now() AND p.state='READY'
                  AND c.config_revision=e.config_revision AND c.context_epoch=e.context_epoch
                  AND w.lease_id=%s AND w.phase='RUNNING' AND w.native_pid=%s AND w.native_start_ticks=%s)''',
                (edit_id, request['userId'], request['appId'], request['runId'], workspace['importTaskId'],
                 workspace['sourceHash'], workspace['configRevision'], workspace['contextEpoch'], lease_id, os.getpid(), native_ticks)).fetchone()
            return row == (True,)

        identity = {'appId': request['appId'], 'ownerId': request['userId'], 'runId': request['runId'],
                    'editId': edit_id, 'contentHash': workspace['sourceHash'], 'formatVersion': 3,
                    'diskBytes': _disk_bytes((request.get('limits') or {}).get('importDiskBytes'))}
        stage = 'IMPORT_EDIT_PLATFORM'
        emitter.emit('import.edit.stage', stage=stage)
        manager = ImportPlatformDockerManager(identity, workspace['containerId'], eligible=eligible)
        stage = 'IMPORT_EDIT_VIEW'
        emitter.emit('import.edit.stage', stage=stage)
        view = SecretFreeView.load_private_state(root / 'private-state.json', state_hash=workspace['viewStateHash'],
                    root=root / 'view', app_id=request['appId'], owner_id=request['userId'], run_id=request['runId'], eligible=eligible)
        stage = 'IMPORT_EDIT_BACKEND'
        emitter.emit('import.edit.stage', stage=stage)
        from core.import_permissions import ImportPermissions
        permission_identity = {'editId': edit_id, 'ownerId': request['userId'], 'appId': request['appId'],
                    'runId': request['runId'], 'importTaskId': workspace['importTaskId'],
                    'sourceHash': workspace['sourceHash'], 'configRevision': workspace['configRevision'],
                    'contextEpoch': workspace['contextEpoch']}
        permissions = ImportPermissions(connection, permission_identity, eligible=eligible, emitter=emitter)
        backend = SecretFreeDockerBackend(view, eligible=eligible, session_id='app:' + request['appId'],
                    run_id=request['runId'], manager=manager, on_edit_started=manager.edit_started,
                    on_edit_finished=manager.edit_finished, permissions=permissions, state_hash=manager.authorization_hash)
        # 1. Prompt、事件和持久记忆只使用隔离内容；不导入旧发布目录的历史或平台工具。
        stage = 'IMPORT_EDIT_MASK'
        emitter.emit('import.edit.stage', stage=stage)
        safe = backend._secret_registry().mask
        request_audit = {'count': 0, 'blocked': 0, 'digests': []}
        def audit_request(request):
            import hashlib
            import json
            raw = request.content
            normalized = json.dumps(json.loads(raw), ensure_ascii=False)
            if safe(normalized) != normalized or str(root) in normalized:
                request_audit['blocked'] += 1
                raise ValueError('IMPORT_EDIT_MODEL_SECRET_REJECTED')
            request_audit['count'] += 1
            request_audit['digests'].append(hashlib.sha256(raw).hexdigest())
        audit_token = model_request_audit.set(audit_request)
        previous_audit_fallback = set_model_request_audit_fallback(audit_request)
        class SafeEmitter:
            _run_id = emitter._run_id
            def error(self, code, message, **payload):
                emitter.error(code, safe(message), **payload)
            def emit(self, event, **payload):
                def clean(value):
                    if isinstance(value, str): return safe(value)
                    if isinstance(value, dict): return {key: clean(item) for key, item in value.items()}
                    if isinstance(value, list): return [clean(item) for item in value]
                    return value
                emitter.emit(event, **clean(payload))
        output = SafeEmitter()
        output.emit(Event.RUN_STARTED, appId=request['appId'], threadId=request.get('threadId', ''), taskKind='coding',
                    modelId=settings.model.model_id, importModelProfileId=request['importModelProfileId'],
                    importThinkingEffort=request.get('importThinkingEffort'))
        loop = _event_loop()
        checkpoint = loop.run_until_complete(get_checkpointer(settings.postgres_dsn, require_persistent=True))
        store = loop.run_until_complete(get_store(settings.postgres_dsn, require_persistent=True))
        overhead = OverheadEstimator()
        route = ModelRouteState(route=str(request.get('modelRoute') or 'configured'), allow_fallback=False)
        stage = 'IMPORT_EDIT_MODEL_BUILD'
        emitter.emit('import.edit.stage', stage=stage)
        agent = build_agent(settings=settings, backend=backend, checkpointer=checkpoint, store=store,
                            task_kind='coding', tools=[], workspace=view.root, overhead=overhead, route_state=route,
                            import_edit=True, window=model_window, summarizer_settings=summarizer_settings)
        message = safe(str((request.get('input') or {}).get('message') or '')).strip()
        if not message:
            raise ValueError('IMPORT_EDIT_EMPTY_MESSAGE')
        prompt = message + '\n使用文件工具进行最小修改。Shell仅用于检查和测试；安装和启动服务必须另行批准。不要修改秘密占位符所在行。'
        stage = 'IMPORT_EDIT_MODEL_RUN'
        emitter.emit('import.edit.stage', stage=stage)
        summary = run_agent_with_events(agent=agent, messages=[{'role': 'user', 'content': prompt}],
                    thread_id='import-edit:' + edit_id, emitter=output, task_kind='coding', overhead=overhead, import_edit=True)
        if summary.get('status') == 'failed' and summary.get('failureKey'):
            return
        if summary.get('status') != 'succeeded' or summary.get('usage', {}).get('modelCalls', 0) < 1:
            raise ValueError('IMPORT_EDIT_MODEL_NOT_COMPLETED')
        # 2. 摘要只表示待提交；Java确认Worker停止后才能封存、校验和发布。
        stage = 'IMPORT_EDIT_AUTHORIZATION'
        state_hash = manager.authorization_hash()
        if request_audit['count'] < 1 or request_audit['blocked']:
            raise ValueError('IMPORT_EDIT_MODEL_AUDIT_MISSING')
        output.emit('import.edit.model.audit', editId=edit_id, **request_audit)
        output.emit('import.edit.result', editId=edit_id, authorizedStateHash=state_hash,
                    summary=summary.get('text', ''), usage=summary.get('usage', {}))
    except Exception as error:
        import re
        failure_key = str(error) if re.fullmatch('[A-Z][A-Z0-9_]{1,80}', str(error)) else 'UNCONFIRMED'
        emitter.emit(Event.ERROR, code=ErrorCode.TOOL_EXECUTION_FAILED.value,
                     message='隔离编辑未完成，请检查任务或恢复状态。', retryable=False,
                     stage=stage, errorType=type(error).__name__, failureKey=failure_key)
    finally:
        if audit_token is not None:
            model_request_audit.reset(audit_token)
        if 'previous_audit_fallback' in locals():
            set_model_request_audit_fallback(previous_audit_fallback)
        if backend is not None:
            try:
                backend.close()
            except Exception:
                emitter.error(ErrorCode.SANDBOX_TERMINATED, '编辑容器停止未确认，不能发布。', retryable=False)
        if connection is not None:
            connection.close()
