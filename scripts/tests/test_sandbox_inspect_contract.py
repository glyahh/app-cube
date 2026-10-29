"""容器 inspect 断言函数的固定样本测试。"""
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'assert_sandbox_inspect', Path(__file__).parents[1] / 'checks' / 'assert-sandbox-inspect.py')
sandbox = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sandbox)


def inspect_record(network='bridge', readonly=False, user=''):
    return {
        'Config': {'User': user},
        'HostConfig': {
            'Privileged': False,
            'CapDrop': ['ALL'],
            'SecurityOpt': ['no-new-privileges'],
            'PidsLimit': 512,
            'Memory': 4294967296,
            'NetworkMode': network,
            'ReadonlyRootfs': readonly,
        },
        'Mounts': [{'Destination': '/workspace'}],
    }


def failed(checks):
    return {check['name'] for check in checks if not check['ok']}


def test_generate_container_passes_with_empty_user_field():
    checks = sandbox.evaluate(inspect_record(), 'generate')
    assert failed(checks) == set()


def test_missing_cap_drop_fails():
    data = inspect_record()
    data['HostConfig']['CapDrop'] = []
    assert 'cap-drop-all' in failed(sandbox.evaluate(data, 'generate'))


def test_wrong_network_fails():
    assert 'network' in failed(sandbox.evaluate(inspect_record(network='host'), 'generate'))


def test_import_requires_readonly_and_nobody():
    assert 'readonly-root' in failed(sandbox.evaluate(inspect_record(network='none'), 'import'))
    data = inspect_record(network='none', readonly=True, user='root')
    assert 'user' in failed(sandbox.evaluate(data, 'import'))


def test_extra_mount_fails():
    data = inspect_record()
    data['Mounts'].append({'Destination': '/host'})
    assert 'mounts' in failed(sandbox.evaluate(data, 'generate'))


def test_import_accepts_expected_mounts():
    data = inspect_record(network='none', readonly=True, user='65534:65534')
    data['Mounts'] = [{'Destination': '/work'}, {'Destination': '/input'},
                      {'Destination': '/glyahh/guard.sh'}]
    assert failed(sandbox.evaluate(data, 'import')) == set()


def test_inspect_with_utf8_bom_is_readable(tmp_path):
    path = tmp_path / 'inspect.json'
    path.write_bytes(b'\xef\xbb\xbf' + json.dumps([inspect_record()]).encode('utf-8'))

    data = sandbox.load_inspect(path)

    assert data['HostConfig']['NetworkMode'] == 'bridge'


if __name__ == '__main__':
    import pytest
    raise SystemExit(pytest.main([__file__]))
