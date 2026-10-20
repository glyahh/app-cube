"""终端失败必须反馈模型，不能绕过审批或误报执行状态。"""
import re
import subprocess
from unittest.mock import AsyncMock, Mock

import pytest
from deepagents.backends.protocol import ExecuteResponse
from langchain_core.messages import ToolMessage

from backends.docker_sandbox import DockerSandboxManager, SandboxError, SandboxRecord
from core.streaming_runtime import _RunState, _on_tool_end
from prompt import get_system_prompt
from test_tool_permissions import make, row


def test_default_image_contains_runtime(monkeypatch):
    monkeypatch.delenv('AGENT_SANDBOX_IMAGE', raising=False)
    manager = DockerSandboxManager()
    try:
        assert manager.image == 'glyahh-sandbox-runtime:24.04'
    finally:
        manager.shutdown()


@pytest.mark.parametrize('import_edit', [False, True])
def test_docker_prompt_matches_execution_environment(import_edit):
    prompt = get_system_prompt('coding', import_edit=import_edit, execution_backend='docker')
    assert '运行环境是Linux容器' in prompt
    assert 'python3' in prompt
    assert 'shell 是 cmd.exe' not in prompt


def test_local_prompt_preserves_windows_contract():
    assert 'shell 是 cmd.exe' in get_system_prompt(execution_backend='local_shell')


@pytest.mark.parametrize('code', [0, 1, 127])
def test_command_exit_is_returned_without_weakening_docker_management(monkeypatch, tmp_path, code):
    manager = DockerSandboxManager()
    record = SandboxRecord('session', 'cid', tmp_path, manager.image, 0, 0, active=True)
    manager._records['session'] = record
    monkeypatch.setattr(manager, '_ensure_hard_swap', lambda _: None)

    def docker(*args, check=True, **kwargs):
        assert args[0] == 'exec'
        if check and code:
            raise SandboxError('python: command not found')
        marker = re.search(r'GLYAHH_EXEC_DONE_[0-9a-f]+', args[-1])
        receipt = '\n' + marker.group() + ':' + str(code) + '\n' if marker else ''
        return subprocess.CompletedProcess(args, code, 'command output', 'command error' + receipt)

    monkeypatch.setattr(manager, '_docker', docker)
    try:
        result = manager.execute(record, 'python3 check.py', 5)
        assert result.returncode == code
        assert result.stdout == 'command output'
        assert result.stderr == 'command error'
    finally:
        manager._records.clear()
        manager.shutdown()


def test_missing_execution_receipt_stops_instead_of_guessing(monkeypatch, tmp_path):
    manager = DockerSandboxManager()
    record = SandboxRecord('session', 'cid', tmp_path, manager.image, 0, 0, active=True)
    manager._records['session'] = record
    monkeypatch.setattr(manager, '_ensure_hard_swap', lambda _: None)
    monkeypatch.setattr(manager, '_docker', lambda *a, **k: subprocess.CompletedProcess(a, 1, '', 'daemon unavailable'))
    try:
        with pytest.raises(SandboxError):
            manager.execute(record, 'python3 check.py', 5)
    finally:
        manager._records.clear()
        manager.shutdown()


def test_failed_command_then_correction_uses_two_distinct_permissions():
    first = {'tool': 'execute', 'argv': ['node', '--check', 'bad.js'], 'cwd': '.'}
    second = {'tool': 'execute', 'argv': ['node', '--check', 'fixed.js'], 'cwd': '.'}
    permissions, emitter = make([
        row('APPROVED', None, first),
        row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000001', first),
        row('APPROVED', None, second),
        row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000002', second),
    ])
    failed = permissions.perform(lambda: first, lambda: ExecuteResponse(output='SyntaxError', exit_code=1))
    assert failed.exit_code == 1 and 'SyntaxError' in failed.output
    assert not permissions._stopped
    corrected = permissions.perform(lambda: second, lambda: ExecuteResponse(output='checked', exit_code=0))
    assert corrected.exit_code == 0
    requests = [p['permissionId'] for e, p in emitter.events if e == 'agent.permission.requested']
    assert len(requests) == len(set(requests)) == 2
    results = [p for e, p in emitter.events if e == 'agent.permission.result']
    assert [p['state'] for p in results] == ['RESULT_CONFIRMED', 'RESULT_CONFIRMED']
    assert [p['succeeded'] for p in results] == [False, True]


@pytest.mark.parametrize('code', [0, 1, 127])
def test_tool_event_uses_execution_artifact_instead_of_text(code):
    emitter = Mock()
    output = ToolMessage(content='output says success', tool_call_id='call', artifact={'exit_code': code})
    _on_tool_end({'run_id': 'call', 'name': 'execute', 'data': {'output': output}}, emitter, _RunState())
    payload = emitter.emit.call_args.kwargs
    assert payload['exitCode'] == code
    assert payload['status'] == ('succeeded' if code == 0 else 'failed')


def test_failed_permission_proof_cannot_be_replayed():
    action = {'tool': 'execute', 'argv': ['node', '--check', 'bad.js'], 'cwd': '.'}
    proof = '9f1c1f1e-0000-4000-8000-000000000001'
    permissions, _ = make([row('APPROVED', None, action), row('CONSUMED', proof, action),
                           row('APPROVED', None, action), row('CONSUMED', proof, action)])
    permissions.perform(lambda: action, lambda: ExecuteResponse(output='SyntaxError', exit_code=1))
    operation = Mock()
    with pytest.raises(RuntimeError, match='REPLAY'):
        permissions.perform(lambda: action, operation)
    operation.assert_not_called()


@pytest.mark.parametrize('error', [SandboxError('daemon unavailable'), TimeoutError('deadline')])
def test_unconfirmed_execution_stops_with_audit(error):
    action = {'tool': 'execute', 'argv': ['python3', 'check.py'], 'cwd': '.'}
    permissions, emitter = make([row('APPROVED', None, action),
                                row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000001', action)])
    with pytest.raises(RuntimeError, match='UNCONFIRMED'):
        permissions.perform(lambda: action, Mock(side_effect=error))
    assert permissions._stopped
    assert emitter.events[-1][1]['state'] == 'RESULT_UNCONFIRMED'


def test_unknown_command_exit_does_not_allow_continuation():
    action = {'tool': 'execute', 'argv': ['python3', 'check.py'], 'cwd': '.'}
    permissions, emitter = make([row('APPROVED', None, action),
                                row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000001', action)])
    with pytest.raises(RuntimeError, match='UNCONFIRMED'):
        permissions.perform(lambda: action, lambda: ExecuteResponse(output='unknown', exit_code=None))
    assert permissions._stopped
    assert emitter.events[-1][1]['state'] == 'RESULT_UNCONFIRMED'


def test_import_command_failure_is_feedback_but_file_failure_still_stops():
    from core.import_permissions import ImportPermissions, ImportPermissionStopped
    from test_tool_permissions import FakeCursor, FakeEmitter
    identity = {key: 'identity' for key in ('editId', 'ownerId', 'appId', 'runId', 'importTaskId',
                                          'sourceHash', 'configRevision', 'contextEpoch')}
    command = {'tool': 'execute', 'argv': ['node', '--check', 'bad.js'], 'cwd': '.'}
    write = {'tool': 'write', 'path': 'index.html', 'content': 'hello'}
    connection = FakeCursor([row('APPROVED', None, command),
                             row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000001', command),
                             row('APPROVED', None, write),
                             row('CONSUMED', '9f1c1f1e-0000-4000-8000-000000000002', write)])
    gate = ImportPermissions(connection, identity, eligible=lambda: True, emitter=FakeEmitter())
    assert gate.perform(lambda: command, lambda: ExecuteResponse(output='SyntaxError', exit_code=1)).exit_code == 1
    assert not gate._stopped
    with pytest.raises(ImportPermissionStopped, match='EXECUTION_FAILED'):
        gate.perform(lambda: write, lambda: type('FailedWrite', (), {'error': 'disk full'})())


def test_runtime_probe_failure_blocks_acquire(monkeypatch, tmp_path):
    manager = DockerSandboxManager()
    monkeypatch.setattr(manager, '_healthy', lambda: None)
    monkeypatch.setattr(manager, '_inspect_container', lambda _: None)
    monkeypatch.setattr(manager, '_create_and_start_container', lambda *a: ('cid', False))
    monkeypatch.setattr(manager, '_ensure_hard_swap', lambda _: None)
    monkeypatch.setattr(manager, '_verify_runtime', Mock(side_effect=SandboxError('python3 missing')))
    stop = Mock()
    monkeypatch.setattr(manager, '_stop_after_guard_failure', stop)
    try:
        with pytest.raises(SandboxError, match='python3 missing'):
            manager.acquire('session', tmp_path)
        stop.assert_called_once()
    finally:
        manager.shutdown()


def test_image_probe_missing_image_never_runs_or_pulls(monkeypatch):
    manager = DockerSandboxManager()
    docker = Mock(side_effect=SandboxError('image missing'))
    monkeypatch.setattr(manager, '_docker', docker)
    try:
        with pytest.raises(SandboxError, match='image missing'):
            manager.verify_image_runtime()
        docker.assert_called_once_with('image', 'inspect', manager.image)
    finally:
        manager.shutdown()


def test_failed_command_fallback_does_not_claim_checks_passed():
    from core.streaming_runtime import _fallback_summary
    state = _RunState()
    state.command_failures = 1
    assert '命令失败' in _fallback_summary(state)


@pytest.mark.parametrize('import_edit', [False, True])
@pytest.mark.parametrize('asynchronous', [False, True])
def test_permission_stop_is_not_converted_to_recoverable_tool_message(import_edit, asynchronous):
    import asyncio
    from core.middleware.tool_error import ToolErrorMiddleware
    from core.import_permissions import ImportPermissionStopped
    from core.tool_permissions import ToolPermissionStopped
    error = ImportPermissionStopped('IMPORT_PERMISSION_REJECTED') if import_edit else ToolPermissionStopped('TOOL_PERMISSION_REJECTED')
    with pytest.raises(type(error)):
        if asynchronous:
            asyncio.run(ToolErrorMiddleware().awrap_tool_call(Mock(), AsyncMock(side_effect=error)))
        else:
            ToolErrorMiddleware().wrap_tool_call(Mock(), Mock(side_effect=error))


def test_import_platform_uses_confirmed_results_and_preserves_deadline(monkeypatch):
    import threading
    from backends.import_platform_docker import ImportPlatformDockerManager
    manager = object.__new__(ImportPlatformDockerManager)
    manager.container_id = 'cid'
    manager.identity = {'appId': 'test'}
    manager.eligible = lambda: True
    manager.record = type('Record', (), {'active': True})()
    manager.platform = Mock()
    manager.platform.locked.return_value = threading.RLock()
    monkeypatch.setattr(manager, '_load_record', lambda: {'editState': 'READY'})
    commands = []

    def docker(*args, check=True, **kwargs):
        commands.append(args[-1])
        marker = re.search(r'GLYAHH_EXEC_DONE_[0-9a-f]+', args[-1])
        return subprocess.CompletedProcess(args, 1, '', 'SyntaxError' + '\n' + marker.group() + ':1\n')

    monkeypatch.setattr(manager, '_docker', docker)
    result = manager.execute(manager.record, 'python3 check.py', 10)
    assert result.returncode == 1 and result.stderr == 'SyntaxError'
    assert 'timeout -k 2 10' in commands[0]


@pytest.mark.parametrize('stop_confirmed', [False, True])
def test_unknown_execution_cannot_release_a_potentially_running_container(monkeypatch, tmp_path, stop_confirmed):
    manager = DockerSandboxManager()
    record = SandboxRecord('session', 'cid', tmp_path, manager.image, 0, 0, active=True)
    manager._records['session'] = record
    monkeypatch.setattr(manager, '_ensure_hard_swap', lambda _: None)
    calls = []

    def docker(*args, **kwargs):
        calls.append(args)
        if args[0] == 'inspect':
            return subprocess.CompletedProcess(args, 0, 'false' if stop_confirmed else 'true', '')
        return subprocess.CompletedProcess(args, 1 if args[0] == 'exec' else 0, '', 'connection lost')

    monkeypatch.setattr(manager, '_docker', docker)
    try:
        with pytest.raises(SandboxError):
            manager.execute(record, 'python3 check.py', 5)
        manager.release('session')
        assert any(args[0] == 'stop' for args in calls)
        assert record.stop_failed is (not stop_confirmed)
        assert record.active is (not stop_confirmed)
    finally:
        manager._records.clear()
        manager.shutdown()
