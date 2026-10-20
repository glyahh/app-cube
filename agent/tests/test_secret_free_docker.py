"""真实 SDK 文件方法经过授权登记；容器生命周期在单元层用受控替身。"""
import asyncio
import subprocess
from types import SimpleNamespace

import pytest

from backends.secret_free_view import SecretFreeView, ViewError
from backends.secret_free_docker import SecretFreeDockerBackend
from backends.docker_sandbox import SandboxError


@pytest.fixture
def setup(tmp_path):
    source = tmp_path / 'source'
    source.mkdir()
    source.joinpath('.env').write_bytes(b'API_TOKEN=original-secret-f783811\n')
    source.joinpath('app.py').write_bytes(b'print("before")\n')
    view = SecretFreeView.create(source, tmp_path / 'view', app_id='101', owner_id='7', run_id='201', eligible=lambda: True)
    manager = SimpleNamespace(acquire=lambda session, workspace, **kw: SimpleNamespace(container_id='unit-only', workspace=workspace, reused=False),
                              close=lambda *args: None,
                              _docker=lambda *a, **kw: subprocess.CompletedProcess('ps', 0, '', ''))
    backend = SecretFreeDockerBackend(view, eligible=lambda: True, session_id='unit', run_id='201', manager=manager)
    return backend, view, source, tmp_path


def test_only_view_is_given_to_container_and_real_edit_is_recorded(setup):
    backend, view, source, tmp = setup
    assert backend.record.workspace == view.root
    assert backend.record.workspace != source
    assert not backend.edit('/app.py', 'before', 'after').error
    candidate = view.candidate(tmp / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('app.py').read_bytes() == b'print("after")\n'
    assert source.joinpath('app.py').read_bytes() == b'print("before")\n'


def test_async_write_delete_and_upload_have_same_authorization(setup):
    backend, view, _, tmp = setup
    assert not asyncio.run(backend.awrite('/new.py', 'print("new")\n')).error
    assert not asyncio.run(backend.adelete('/app.py')).error
    assert not asyncio.run(backend.aupload_files([('/data.txt', b'allowed')]))[0].error
    candidate = view.candidate(tmp / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('new.py').is_file()
    assert not candidate.joinpath('app.py').exists()
    assert candidate.joinpath('data.txt').read_bytes() == b'allowed'


def test_protected_config_and_git_mutations_are_refused(setup):
    backend, _, _, _ = setup
    assert backend.write('/.env', 'API_TOKEN=replacement').error
    assert backend.delete('/.env').error
    assert backend.write('/.git/HEAD', 'rewrite').error
    assert backend.upload_files([('/keys/key.pem', b'replacement')])[0].error


def test_shell_mutation_does_not_create_a_business_edit_grant(setup):
    backend, view, _, tmp = setup
    def execute(*args):
        view.root.joinpath('app.py').write_bytes(b'print("changed by execution")\n')
        return subprocess.CompletedProcess('fixture', 0, 'test finished', '')
    backend.manager.execute = execute
    backend.execute('python3 test.py')
    with pytest.raises(ViewError, match='UNAUTHORIZED_CHANGE'):
        view.candidate(tmp / 'candidate', eligible=lambda: True)


@pytest.mark.parametrize('asynchronous', [False, True])
def test_import_execute_preserves_python_arguments_and_rejects_before_manager(setup, asynchronous):
    import shlex
    from backends.permissions import ImportCommandRejected
    backend, _, _, _ = setup
    calls = []
    def execute(record, command, timeout):
        calls.append(shlex.split(command))
        return subprocess.CompletedProcess('fixture', 0, '', '')
    backend.manager.execute = execute
    command = 'python3 -c "print(\'中文 type 空格\'); print(1)"'
    def invoke(command):
        return asyncio.run(backend.aexecute(command)) if asynchronous else backend.execute(command)
    invoke(command)
    assert calls == [['python3', '-c', "print('中文 type 空格'); print(1)"]]
    with pytest.raises(ImportCommandRejected):
        invoke('echo ok; echo bad')
    assert len(calls) == 1


@pytest.mark.parametrize('asynchronous', [False, True])
def test_sdk_rejection_wrapper_matches_event_stop_protocol(setup, asynchronous):
    from deepagents.middleware.filesystem import FilesystemMiddleware
    from core.streaming_runtime import _on_tool_end, _RunState, ImportEditStopped
    from unittest.mock import Mock
    backend, _, _, _ = setup
    middleware = FilesystemMiddleware(backend=backend)
    tool = middleware._create_execute_tool()
    runtime = SimpleNamespace(tool_call_id='test')
    if asynchronous:
        result = asyncio.run(tool.coroutine('echo ok; echo bad', runtime))
    else:
        result = tool.func('echo ok; echo bad', runtime)
    assert result.status == 'error'
    assert result.content.startswith('Error: Invalid parameter. IMPORT_COMMAND_REJECTED:OPERATORS。')
    state = _RunState(import_edit=True)
    event = {'name': 'execute', 'run_id': 'test', 'data': {'output': result}}
    _on_tool_end(event, Mock(), state)
    with pytest.raises(ImportEditStopped):
        _on_tool_end(event, Mock(), state)


def test_revoked_grant_stops_before_any_mutation(setup):
    backend, view, _, _ = setup
    backend.eligible = lambda: False
    assert backend.edit('/app.py', 'before', 'after').error
    assert view.root.joinpath('app.py').read_bytes() == b'print("before")\n'


@pytest.mark.parametrize('operation', ['edit', 'write', 'delete', 'upload'])
def test_file_tool_cannot_authorize_prior_shell_changes(setup, operation):
    backend, view, _, tmp = setup
    shell_content = b'print("shell")\n# unauthorized extra code\n'
    view.root.joinpath('app.py').write_bytes(shell_content)
    if operation == 'edit':
        result = backend.edit('/app.py', 'shell', 'tool')
    elif operation == 'write':
        result = backend.write('/app.py', 'print("tool")\n')
    elif operation == 'delete':
        result = backend.delete('/app.py')
    else:
        result = backend.upload_files([('/app.py', b'print("tool")\n')])[0]
    assert result.error
    assert view.root.joinpath('app.py').read_bytes() == shell_content
    with pytest.raises(ViewError, match='UNAUTHORIZED_CHANGE'):
        view.candidate(tmp / 'candidate', eligible=lambda: True)


def test_shell_cannot_change_an_already_authorized_file_then_get_reauthorized(setup):
    backend, view, _, _ = setup
    assert not backend.edit('/app.py', 'before', 'first').error
    view.root.joinpath('app.py').write_bytes(b'print("shell")\n')
    assert backend.edit('/app.py', 'shell', 'second').error


def test_generated_outputs_are_not_file_tool_source_edits(setup):
    backend, view, _, tmp = setup
    assert backend.write('/dist/index.html', 'output').error
    view.root.joinpath('dist').mkdir()
    view.root.joinpath('dist/index.html').write_bytes(b'execution output')
    candidate = view.candidate(tmp / 'candidate', eligible=lambda: True)
    assert not candidate.joinpath('dist').exists()
    assert 'dist/index.html' in view.retained_outputs


def test_multiple_authorized_file_edits_still_work(setup):
    backend, view, _, tmp = setup
    assert not backend.edit('/app.py', 'before', 'first').error
    assert not backend.edit('/app.py', 'first', 'second').error
    candidate = view.candidate(tmp / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('app.py').read_bytes() == b'print("second")\n'


def test_cleanup_engine_failure_is_not_reported_as_absent_container(setup):
    backend, _, _, _ = setup
    backend.manager._docker = lambda *a, **k: subprocess.CompletedProcess('ps', 1, '', 'unavailable')
    with pytest.raises(SandboxError, match='清理未确认'):
        backend.close()
