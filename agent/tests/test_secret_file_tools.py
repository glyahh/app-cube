"""文件工具的真实读取实现；Docker 生命周期在单元层使用替身，另做真实容器验收。"""
import asyncio
from types import SimpleNamespace

import pytest

from backends.local_shell import GuardedShellBackend
from backends.docker_sandbox import DockerSandboxBackend, SandboxRecord


@pytest.fixture(params=['local', 'docker'])
def backend(request, tmp_path):
    (tmp_path / '.env').write_text('API_TOKEN=PRIVATE_FILE_MARKER\n', encoding='utf-8')
    (tmp_path / '.env.example').write_text('API_TOKEN="EXAMPLE_FILE_MARKER"\nPORT=8080\n', encoding='utf-8')
    (tmp_path / 'app.py').write_bytes(b'print("ordinary code")\n')
    (tmp_path / 'config.py').write_bytes(b'API_TOKEN="VISIBLE_FILE_MARKER"\n')
    if request.param == 'local':
        return GuardedShellBackend(tmp_path)
    manager = SimpleNamespace(acquire=lambda *a, **kw: SandboxRecord(
        session_id='unit-only', container_id='unit-only', workspace=tmp_path,
        image='fixture', created_at=0, last_active_at=0))
    return DockerSandboxBackend(tmp_path, session_id='unit-only', manager=manager)


def test_sensitive_configuration_is_not_readable(backend):
    result = backend.read('/.env')
    assert result.error
    assert 'PRIVATE_FILE_MARKER' not in str(result)


def test_example_keeps_structure_not_secret(backend):
    result = backend.read('/.env.example')
    assert result.error is None
    assert 'EXAMPLE_FILE_MARKER' not in str(result)
    assert 'API_TOKEN=***' in result.file_data['content']
    assert 'PORT=8080' in result.file_data['content']
    assert result.start_line == 1
    assert result.end_line == 2


def test_root_search_does_not_return_configuration_secret(backend):
    result = backend.grep('API_TOKEN', '/', context_lines=1)
    assert 'PRIVATE_FILE_MARKER' not in str(result)
    assert 'EXAMPLE_FILE_MARKER' not in str(result)
    assert 'VISIBLE_FILE_MARKER' not in str(result)
    assert 'API_TOKEN=***' in str(result)
    assert backend.grep('API_TOKEN', '/.env').error


def test_async_read_and_search_use_same_boundary(backend):
    assert asyncio.run(backend.aread('/.env')).error
    assert 'EXAMPLE_FILE_MARKER' not in str(asyncio.run(backend.agrep('API_TOKEN', '/')))
    assert 'VISIBLE_FILE_MARKER' not in str(asyncio.run(backend.agrep('API_TOKEN', '/')))


def test_model_download_cannot_bypass_read_boundary(backend):
    results = backend.download_files(['/.env', '/.env.example', '/app.py'])
    assert results[0].error == 'permission_denied'
    assert results[0].content is None
    assert b'EXAMPLE_FILE_MARKER' not in results[1].content
    assert results[2].content == b'print("ordinary code")\n'


def test_code_editing_and_protocol_still_work(backend):
    assert hasattr(type(backend), 'delete')
    assert backend.read('/app.py').file_data['content'] == 'print("ordinary code")\n'
    backend.edit('/app.py', 'ordinary code', 'changed code')
    assert 'changed code' in backend.read('/app.py').file_data['content']


def test_example_under_secret_directory_is_denied(backend):
    folder = backend.workspace / 'keys'
    folder.mkdir()
    (folder / '.env.example').write_bytes(b'API_TOKEN=KEY_DIRECTORY_MARKER\n')
    assert backend.read('/keys/.env.example').error


def test_mask_handles_quotes_json_and_long_names():
    import time
    from core.masking import mask
    for text in ('API_TOKEN="quoted value"', "API_TOKEN='quoted value'", '{"api_token":"quoted value"}', 'API_TOKEN="unclosed value'):
        assert 'quoted value' not in mask(text)
        assert 'unclosed value' not in mask(text)
    before = time.monotonic()
    assert mask('TOKEN' * 20000) == 'TOKEN' * 20000
    assert time.monotonic() - before < 2
    assert mask('PORT=8080') == 'PORT=8080'


def test_normal_token_expressions_are_not_hidden(backend):
    source = b'tokenizer = build_tokenizer()\npassword = request.password\nAPI_TOKEN="CODE_SECRET_MARKER"\n'
    (backend.workspace / 'business.py').write_bytes(source)
    read = backend.read('/business.py').file_data['content']
    assert 'tokenizer = build_tokenizer()' in read
    assert 'password = request.password' in read
    assert 'CODE_SECRET_MARKER' not in read
    assert 'build_tokenizer()' in str(backend.grep('tokenizer', '/business.py'))
