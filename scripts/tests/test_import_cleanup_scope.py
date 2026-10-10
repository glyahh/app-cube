"""清理只相信本轮明确身份，目录越界和 Docker 查询失败都停止。"""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest


spec = importlib.util.spec_from_file_location('import_cleanup', Path(__file__).parents[1] / 'cleanup-import-test-residue.py')
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


def write_report(tmp_path, fixtures):
    report = tmp_path / 'result.json'
    report.write_text(json.dumps({'fixtures': fixtures}), encoding='utf-8')
    return report


def test_exact_identity_can_be_loaded_alongside_nonidentity_evidence(tmp_path):
    report = write_report(tmp_path, [{'ownerId': '123', 'account': 'import_source_this_run'}, {'completedAppId': '456'}])
    assert cleanup.fixture_owners([report]) == {123: 'import_source_this_run'}


@pytest.mark.parametrize('fixtures', [[], [{'completedAppId': '456'}], [{'ownerId': '123'}],
                                    [{'ownerId': '123', 'account': 'ordinary_user'}],
                                    [{'ownerId': '../123', 'account': 'import_source_this_run'}]])
def test_no_deletion_authority_without_valid_explicit_identity(tmp_path, fixtures):
    with pytest.raises(ValueError):
        cleanup.fixture_owners([write_report(tmp_path, fixtures)])


def test_cleanup_never_deletes_a_root_or_an_outside_directory(tmp_path):
    for path in (tmp_path, cleanup.CODE_OUTPUT, cleanup.CODE_OUTPUT / '..' / 'unrelated'):
        with pytest.raises(OSError, match='OUTSIDE_FIXTURE_ROOT'):
            cleanup.delete_tree(path)


def test_docker_failure_is_not_an_empty_container_inventory(monkeypatch):
    monkeypatch.setattr(cleanup.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=1, stdout=b''))
    with pytest.raises(RuntimeError, match='UNCONFIRMED'):
        cleanup.docker_containers(123)


def test_cleanup_rejects_a_reparse_point_in_an_ancestor(monkeypatch):
    original = Path.is_junction
    monkeypatch.setattr(Path, 'is_junction', lambda self: self == cleanup.CODE_OUTPUT or original(self))
    with pytest.raises(OSError, match='LINK_ANCESTOR'):
        cleanup.delete_tree(cleanup.CODE_OUTPUT / 'project_123')


def test_source_cleanup_requires_confirmed_exact_executor_exit(monkeypatch):
    assert cleanup.source_resources_stopped([])
    assert not cleanup.source_resources_stopped([{'pid': '123'}])
    monkeypatch.setattr(cleanup.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=1))
    assert not cleanup.source_resources_stopped([{'pid': '123', 'started': '2026-10-08T00:00:00Z'}])
    monkeypatch.setattr(cleanup.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=0))
    assert cleanup.source_resources_stopped([{'pid': '123', 'started': '2026-10-08T00:00:00Z'}])


def test_exact_fixture_readonly_git_objects_can_be_removed(monkeypatch,tmp_path):
    monkeypatch.setattr(cleanup,'IMPORT_PRIVATE',tmp_path)
    folder=tmp_path/'fixture.git';folder.mkdir();obj=folder/'object';obj.write_bytes(b'git');obj.chmod(0o444)
    assert cleanup.delete_tree(folder)==3
    assert not folder.exists()
