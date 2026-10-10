"""执行视图必须先去秘密；候选合入保留原配置，不默认写回工作区。"""
import pytest

from backends.secret_free_view import SecretFreeView, ViewError


@pytest.fixture
def source(tmp_path):
    root = tmp_path / 'source'
    root.mkdir()
    root.joinpath('.env').write_bytes(b'API_TOKEN=private-key-e27da591\nPORT=8080\n')
    root.joinpath('.env.example').write_bytes(b'API_TOKEN="private-key-e27da591"\nPORT=8080\n')
    root.joinpath('config.py').write_bytes(b'API_TOKEN="private-key-e27da591"\n')
    root.joinpath('app.py').write_bytes(b'print("ordinary code")\n')
    root.joinpath('asset.bin').write_bytes(b'\x00\xff\x01\x02')
    root.joinpath('empty').mkdir()
    return root


def prepare(source, tmp_path, **kwargs):
    return SecretFreeView.create(source, tmp_path / 'view', app_id='101', owner_id='7', run_id='201',
                                 eligible=lambda: True, **kwargs)


def test_empty_secret_file_keeps_source_and_candidate_valid(tmp_path):
    source = tmp_path / 'source'
    source.mkdir()
    source.joinpath('.env').write_bytes(b'')
    original = b"print('hello')\n"
    source.joinpath('app.py').write_bytes(original)
    view = prepare(source, tmp_path)
    assert view.root.joinpath('app.py').read_bytes() == original
    compile(view.root.joinpath('app.py').read_text(), 'app.py', 'exec')
    assert not view.root.joinpath('.env').exists()
    changed = b"print('fixed')\n"
    view.root.joinpath('app.py').write_bytes(changed)
    view.record_edit('app.py', eligible=lambda: True)
    candidate = view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('app.py').read_bytes() == changed
    assert candidate.joinpath('.env').read_bytes() == b''
    assert source.joinpath('app.py').read_bytes() == original


def test_view_keeps_complete_nonsecret_project_and_source_unchanged(source, tmp_path):
    before = {p.name: p.read_bytes() for p in source.iterdir() if p.is_file()}
    view = prepare(source, tmp_path)
    assert not view.root.joinpath('.env').exists()
    assert 'private-key-e27da591' not in view.root.joinpath('.env.example').read_text()
    assert 'PORT=8080' in view.root.joinpath('.env.example').read_text()
    assert 'private-key-e27da591' not in view.root.joinpath('config.py').read_text()
    compile(view.root.joinpath('config.py').read_text(), 'config.py', 'exec')
    assert view.root.joinpath('asset.bin').read_bytes() == before['asset.bin']
    assert view.root.joinpath('empty').is_dir()
    assert before == {p.name: p.read_bytes() for p in source.iterdir() if p.is_file()}
    assert len(view.source_hash) == len(view.view_hash) == 64


def test_candidate_restores_original_configuration_but_not_unrequested_edits(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('config.py').write_bytes(view.root.joinpath('config.py').read_bytes() + b'ENABLED=True\n')
    view.root.joinpath('app.py').write_bytes(b'print("fixed code")\n')
    view.record_edit('config.py', eligible=lambda: True)
    view.record_edit('app.py', eligible=lambda: True)
    candidate = view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('.env').read_bytes() == source.joinpath('.env').read_bytes()
    assert candidate.joinpath('.env.example').read_bytes() == source.joinpath('.env.example').read_bytes()
    assert candidate.joinpath('config.py').read_bytes() == source.joinpath('config.py').read_bytes() + b'ENABLED=True\n'
    assert candidate.joinpath('app.py').read_bytes() == b'print("fixed code")\n'
    assert source.joinpath('app.py').read_bytes() == b'print("ordinary code")\n'


@pytest.mark.parametrize('field', ['OTHER_TOKEN', 'print', '# API_TOKEN'])
def test_secret_marker_count_cannot_authorize_changed_field(source, tmp_path, field):
    view = prepare(source, tmp_path)
    target = view.root / 'config.py'
    safe = target.read_bytes()
    target.write_bytes(safe.replace(b'API_TOKEN', field.encode()))
    view.record_edit('config.py', eligible=lambda: True)
    with pytest.raises(ViewError, match='VIEW_SECRET_BINDING'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert not (tmp_path / 'candidate').exists()


def test_existing_destination_is_not_overwritten(source, tmp_path):
    target = tmp_path / 'view'
    target.mkdir()
    target.joinpath('mine').write_bytes(b'keep')
    with pytest.raises(ViewError):
        prepare(source, tmp_path)
    assert target.joinpath('mine').read_bytes() == b'keep'


def test_source_change_refuses_candidate(source, tmp_path):
    view = prepare(source, tmp_path)
    source.joinpath('app.py').write_bytes(b'print("user edit")\n')
    with pytest.raises(ViewError, match='SOURCE_CHANGED'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert not tmp_path.joinpath('candidate').exists()


def test_expired_permission_prevents_create_and_merge(source, tmp_path):
    with pytest.raises(ViewError, match='REVOKED'):
        SecretFreeView.create(source, tmp_path / 'denied', app_id='101', owner_id='7', run_id='201', eligible=lambda: False)
    assert not tmp_path.joinpath('denied').exists()
    view = prepare(source, tmp_path)
    with pytest.raises(ViewError, match='REVOKED'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: False)
    assert not tmp_path.joinpath('candidate').exists()


def test_secret_paths_created_by_worker_are_not_published(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('.env').write_bytes(b'API_TOKEN=worker-created')
    with pytest.raises(ViewError, match='PROTECTED_PATH'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)


def test_secret_placeholders_cannot_be_copied_to_another_file(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('leak.py').write_bytes(view.root.joinpath('config.py').read_bytes())
    view.record_edit('leak.py', eligible=lambda: True)
    with pytest.raises(ViewError, match='SECRET_BINDING'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)


def test_worker_cannot_drop_secret_binding_silently(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('config.py').write_bytes(b'API_TOKEN="replacement"\n')
    view.record_edit('config.py', eligible=lambda: True)
    with pytest.raises(ViewError, match='SECRET_BINDING'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)


def test_whole_project_limits_do_not_skip_large_or_binary_files(source, tmp_path):
    with pytest.raises(ViewError, match='LIMIT'):
        prepare(source, tmp_path, max_bytes=2)
    assert not tmp_path.joinpath('view').exists()


def test_nested_private_directories_and_hard_links_are_not_exposed(source, tmp_path):
    source.joinpath('keys').mkdir()
    source.joinpath('keys', '.env.example').write_bytes(b'opaque private key')
    view = prepare(source, tmp_path)
    assert not view.root.joinpath('keys').exists()
    import os
    os.link(source / 'app.py', source / 'alias.py')
    with pytest.raises(ViewError, match='LINK'):
        SecretFreeView.create(source, tmp_path / 'hardlinked', app_id='101', owner_id='7', run_id='202', eligible=lambda: True)


def test_identity_and_path_boundaries_fail_closed(source, tmp_path):
    with pytest.raises(ViewError, match='IDENTITY'):
        SecretFreeView.create(source, tmp_path / 'view', app_id='0', owner_id='7', run_id='201', eligible=lambda: True)
    with pytest.raises(ViewError, match='OVERLAP'):
        SecretFreeView.create(source, source / 'view', app_id='101', owner_id='7', run_id='201', eligible=lambda: True)


def test_private_replacements_are_not_written_into_the_view(source, tmp_path):
    view = prepare(source, tmp_path)
    assert 'private-key-e27da591' not in repr(view)
    assert not any(p.name.endswith('.json') for p in view.root.iterdir())
    assert all(b'private-key-e27da591' not in p.read_bytes() for p in view.root.rglob('*') if p.is_file())


def test_git_metadata_is_preserved_not_published_from_execution_view(source, tmp_path):
    source.joinpath('.git').mkdir()
    source.joinpath('.git', 'HEAD').write_bytes(b'original history')
    view = prepare(source, tmp_path)
    assert not view.root.joinpath('.git').exists()
    view.root.joinpath('.git').mkdir()
    view.root.joinpath('.git', 'HEAD').write_bytes(b'private execution snapshot')
    candidate = view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('.git', 'HEAD').read_bytes() == b'original history'


def test_removed_empty_directory_is_a_real_change(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('empty').rmdir()
    view.record_edit('empty', eligible=lambda: True)
    candidate = view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert not candidate.joinpath('empty').exists()


def test_short_secret_cannot_damage_the_replacement_markers(source, tmp_path):
    source.joinpath('.env').write_bytes(b'PASSWORD=a\n')
    source.joinpath('config.py').write_bytes(b'PASSWORD="a"\nENABLED=True\n')
    view = prepare(source, tmp_path)
    compile(view.root.joinpath('config.py').read_text(), 'config.py', 'exec')
    view.root.joinpath('config.py').write_bytes(view.root.joinpath('config.py').read_bytes() + b'FLAG=True\n')
    view.record_edit('config.py', eligible=lambda: True)
    candidate = view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert candidate.joinpath('config.py').read_bytes() == source.joinpath('config.py').read_bytes() + b'FLAG=True\n'
    assert view.root.joinpath('app.py').read_bytes() == b'print("ordinary code")\n'


def test_missing_execution_view_is_not_treated_as_deleted_project(source, tmp_path):
    view = prepare(source, tmp_path)
    import shutil
    shutil.rmtree(view.root)
    with pytest.raises(ViewError, match='MISSING'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert not tmp_path.joinpath('candidate').exists()


def test_execution_does_not_grant_itself_business_code_write_permission(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('app.py').write_bytes(b'print("changed by a test script")\n')
    with pytest.raises(ViewError, match='UNAUTHORIZED_CHANGE'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)


def test_execution_cannot_change_previously_authorized_bytes_afterward(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('app.py').write_bytes(b'print("authorized edit")\n')
    view.record_edit('app.py', eligible=lambda: True)
    view.root.joinpath('app.py').write_bytes(b'print("changed afterward")\n')
    with pytest.raises(ViewError, match='EDIT_CHANGED_AFTER_AUTHORIZATION'):
        view.candidate(tmp_path / 'candidate', eligible=lambda: True)


def test_execution_artifacts_stay_in_the_execution_view(source, tmp_path):
    view = prepare(source, tmp_path)
    view.root.joinpath('__pycache__').mkdir()
    view.root.joinpath('__pycache__', 'config.pyc').write_bytes(view.root.joinpath('config.py').read_bytes())
    view.root.joinpath('test-result.json').write_bytes(b'{"passed":true}')
    candidate = view.candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert not candidate.joinpath('__pycache__').exists()
    assert not candidate.joinpath('test-result.json').exists()
    assert '__pycache__/config.pyc' in view.retained_outputs
    assert 'test-result.json' in view.retained_outputs


def test_private_recovery_mapping_stays_outside_model_view(source, tmp_path):
    import hashlib
    import json
    view = prepare(source, tmp_path)
    path = tmp_path / 'private-state.json'
    digest = view.save_private_state(path, eligible=lambda: True)
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == digest
    state = json.loads(data)
    assert (state['ownerId'], state['appId'], state['runId']) == ('7', '101', '201')
    assert state['sourceHash'] == view.source_hash and state['viewHash'] == view.view_hash
    assert b'private-key-e27da591' in data
    assert not view.root.joinpath('private-state.json').exists()
    if __import__('os').name != 'nt':
        assert path.stat().st_mode & 0o777 == 0o600
    with pytest.raises(ViewError, match='PRIVATE_STATE_IO'):
        view.save_private_state(path, eligible=lambda: True)


def test_private_mapping_cannot_be_saved_inside_view_or_after_revocation(source, tmp_path):
    view = prepare(source, tmp_path)
    with pytest.raises(ViewError, match='PRIVATE_STATE_OVERLAP'):
        view.save_private_state(view.root / 'state.json', eligible=lambda: True)
    with pytest.raises(ViewError, match='VIEW_REVOKED'):
        view.save_private_state(tmp_path / 'state.json', eligible=lambda: False)
    assert not tmp_path.joinpath('state.json').exists()


def test_private_mapping_reports_actual_disk_full(source, tmp_path, monkeypatch):
    import errno
    from pathlib import Path
    view = prepare(source, tmp_path)
    def full(*args, **kwargs):
        raise OSError(errno.ENOSPC, 'fixture disk full')
    monkeypatch.setattr(Path, 'open', full)
    with pytest.raises(ViewError, match='VIEW_DISK_QUOTA'):
        view.save_private_state(tmp_path / 'state.json', eligible=lambda: True)


def test_private_state_roundtrip_keeps_authorization_and_secret_restoration(source, tmp_path):
    view = prepare(source, tmp_path)
    path = tmp_path / 'state.json'
    digest = view.save_private_state(path, eligible=lambda: True)
    def load(digest, **changes):
        args = dict(state_hash=digest, root=view.root, app_id='101', owner_id='7', run_id='201', eligible=lambda: True)
        args.update(changes)
        return SecretFreeView.load_private_state(path, **args)
    restored = load(digest)
    restored.require_editable('config.py', eligible=lambda: True)
    target = restored.root / 'config.py'
    target.write_bytes(target.read_bytes() + b'PORT=9090\n')
    restored.record_edit('config.py', eligible=lambda: True)
    next_digest = restored.save_private_state(path, eligible=lambda: True, replace=True)
    assert next_digest != digest
    with pytest.raises(ViewError, match='PRIVATE_STATE_HASH'):
        load(digest)
    with pytest.raises(ViewError, match='VIEW_IDENTITY'):
        load(next_digest, owner_id='8')
    candidate = load(next_digest).candidate(tmp_path / 'candidate', eligible=lambda: True)
    assert b'private-key-e27da591' in candidate.joinpath('config.py').read_bytes()
    assert b'PORT=9090' in candidate.joinpath('config.py').read_bytes()
    assert candidate.joinpath('.env').read_bytes() == source.joinpath('.env').read_bytes()


def test_private_state_restore_rejects_changed_source_and_links(source, tmp_path):
    view = prepare(source, tmp_path)
    path = tmp_path / 'state.json'
    digest = view.save_private_state(path, eligible=lambda: True)
    args = dict(state_hash=digest, root=view.root, app_id='101', owner_id='7', run_id='201', eligible=lambda: True)
    with pytest.raises(ViewError, match='VIEW_REVOKED'):
        SecretFreeView.load_private_state(path, **{**args, 'eligible': lambda: False})
    source.joinpath('app.py').write_bytes(b'changed')
    with pytest.raises(ViewError, match='VIEW_SOURCE_CHANGED'):
        SecretFreeView.load_private_state(path, **args)
