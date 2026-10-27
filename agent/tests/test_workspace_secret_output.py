"""配置秘密的裸值/编码形式不能经命令或普通文件回到模型。"""
import asyncio
import base64
import subprocess
from types import SimpleNamespace
from urllib.parse import quote

import pytest

from backends.docker_sandbox import DockerSandboxBackend, SandboxRecord
from backends.local_shell import GuardedShellBackend
from backends.workspace_secrets import WorkspaceSecrets


def test_empty_configuration_does_not_register_empty_secret(tmp_path):
    (tmp_path / '.env').write_bytes(b'')
    registry = WorkspaceSecrets(tmp_path, lambda name: name == '.env')
    registry.refresh()
    assert '' not in registry.values
    assert registry.mask("print('hello')") == "print('hello')"
    registry._remember('real-secret-7f816')
    registry._remember_bytes(b'')
    assert registry.mask('hello real-secret-7f816') == 'hello ***'


@pytest.fixture(params=['local', 'docker'])
def backend(request, tmp_path, monkeypatch):
    value = 'private value/with+punctuation-7f816'
    (tmp_path / '.env').write_bytes(('API_TOKEN="' + value + '"\nPORT=8080\n').encode())
    values = [value, base64.b64encode(value.encode()).decode(), value.encode().hex(), quote(value, safe='')]
    output = '\n'.join(values) + '\nordinary output\n'
    completed = subprocess.CompletedProcess('fixture', 0, output, output)
    if request.param == 'local':
        backend = GuardedShellBackend(tmp_path)
        local_completed = subprocess.CompletedProcess('fixture', 0, output.encode(), output.encode())
        monkeypatch.setattr('backends.local_shell.subprocess.run', lambda *a, **k: local_completed)
    else:
        manager = SimpleNamespace(acquire=lambda *a, **k: SandboxRecord(
            session_id='unit-only', container_id='unit-only', workspace=tmp_path,
            image='fixture', created_at=0, last_active_at=0),
                                  execute=lambda *a, **k: completed)
        backend = DockerSandboxBackend(tmp_path, session_id='unit-only', manager=manager)
    return backend, value, values


def test_indirect_shell_values_and_encodings_are_masked(backend):
    instance, value, forms = backend
    result = instance.execute('python3 show.py')
    assert result.exit_code == 0
    assert all(form not in result.output for form in forms)
    assert 'ordinary output' in result.output
    assert instance.workspace.joinpath('.env').read_text().startswith('API_TOKEN="' + value)


def test_double_encoded_configuration_does_not_reach_model(backend):
    instance, value, _ = backend
    import base64
    encoded = base64.b64encode(base64.b64encode(value.encode())).decode()
    registry = instance._secret_registry()
    registry.refresh()
    masked = registry.mask(encoded + '\n' + value)
    assert encoded not in masked
    assert value not in masked


def test_async_execute_cannot_bypass_policy(backend):
    instance, _, forms = backend
    result = asyncio.run(instance.aexecute('python3 show.py'))
    assert all(form not in result.output for form in forms)


def test_copied_secret_uses_same_file_boundary(backend):
    instance, _, forms = backend
    # 项目脚本把值复制成普通名称，路径过滤本身不够。
    instance.workspace.joinpath('public.txt').write_bytes('\n'.join(forms).encode())
    assert all(form not in str(instance.read('/public.txt')) for form in forms)
    assert all(form not in str(instance.grep('private|cHJp|7072', '/public.txt')) for form in forms)
    downloaded = instance.download_files(['/public.txt'])[0]
    assert all(form.encode() not in downloaded.content for form in forms)


def test_mask_precedes_output_truncation(backend, monkeypatch):
    instance, value, _ = backend
    module = 'backends.local_shell' if isinstance(instance, GuardedShellBackend) else 'backends.docker_sandbox'
    monkeypatch.setattr(module + '.MAX_OUTPUT_CHARS', len(value) - 2)
    result = instance.execute('python3 show.py')
    assert value[:len(value) - 2] not in result.output


def test_deleted_configuration_does_not_forget_known_secret(backend):
    instance, _, forms = backend
    instance.execute('python3 show.py')
    instance.workspace.joinpath('.env').unlink()
    assert all(form not in instance.execute('python3 show.py').output for form in forms)


def test_other_app_does_not_inherit_secret_registry(backend, tmp_path):
    _, value, _ = backend
    other = tmp_path / 'other-app'
    other.mkdir()
    other.joinpath('public.txt').write_bytes(value.encode())
    assert GuardedShellBackend(other).read('/public.txt').file_data['content'] == value


def test_changed_secret_is_added_without_forgetting_old(backend):
    instance, value, _ = backend
    instance.read('/missing.txt')
    changed = 'changed-secret-fa9b81'
    instance.workspace.joinpath('.env').write_bytes(('API_TOKEN=' + changed).encode())
    instance.workspace.joinpath('public.txt').write_bytes((value + '\n' + changed).encode())
    result = instance.read('/public.txt')
    assert value not in str(result)
    assert changed not in str(result)


def test_unverifiable_secret_stops_before_command(backend, monkeypatch):
    from backends.workspace_secrets import SecretBoundaryError
    instance, _, _ = backend
    registry = instance._secret_registry()
    monkeypatch.setattr(registry, 'MAX_BYTES', 8)
    with pytest.raises(SecretBoundaryError, match='超过内容限制'):
        instance.execute('python3 show.py')


def test_lf_example_structure_is_not_replaced_wholesale(backend):
    instance, _, _ = backend
    instance.workspace.joinpath('.env.example').write_bytes(b'API_TOKEN="sample-secret-9919"\nPORT=8080\n')
    result = instance.read('/.env.example').file_data['content']
    assert 'sample-secret-9919' not in result
    assert 'PORT=8080' in result
