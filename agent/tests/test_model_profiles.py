from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import replace
from pathlib import Path

import pytest

from core.model_profiles import (freeze_import_settings, load_profiles, resolve_profile_window,
                                 select_profile, strict_json, validate_kwargs)
from core.settings import Limits, ModelSettings, Settings, WorkspaceSettings


@pytest.fixture
def settings(monkeypatch):
    monkeypatch.setenv('AGENT_ENV_FILE', str(Path(__file__).with_name('absent.env')))
    monkeypatch.setenv('AGENT_MODEL_PROFILES_JSON', '[]')
    monkeypatch.setenv('PROFILE_KEY', 'private-key-not-for-display')
    monkeypatch.setenv('PROFILE_URL', 'https://private-model.invalid/v1')
    return Settings(ModelSettings(api_key='default-private-key', context_window_override=262144,
                                  max_output_tokens=8192), Limits(), WorkspaceSettings(), 'INFO', '')


def entry(**kwargs):
    return {'id': 'extra', 'name': '自有模型', 'provider': 'openai-compatible', 'model': 'my-model',
            'apiKeyEnv': 'PROFILE_KEY', 'baseUrlEnv': 'PROFILE_URL', 'contextWindow': 131072,
            'kwargs': {'max_tokens': 4096}, **kwargs}


def configure(monkeypatch, *entries):
    monkeypatch.setenv('AGENT_MODEL_PROFILES_JSON', json.dumps(entries))


def test_default_uses_existing_configuration_and_safe_projection(settings):
    profile = load_profiles(settings)['default']
    assert profile.settings is settings.model
    assert profile.safe() == {'id': 'default', 'name': '默认模型', 'model': settings.model.model_id,
                              'provider': 'openai-compatible', 'efforts': [], 'configured': True}
    selected = profile.select()
    assert selected.model_id == settings.model.model_id
    assert strict_json(selected.request_kwargs_json) == {'extra_body': {'thinking': {'type': 'disabled'}}}
    assert settings.model.request_kwargs_json is None


@pytest.mark.parametrize('effort', ['low', 'medium', 'high'])
@pytest.mark.parametrize('mapping', ['native', 'body', 'thinking'])
def test_explicit_effort_mapping_reaches_real_client(settings, monkeypatch, effort, mapping):
    if mapping == 'native':
        kwargs = {'reasoning_effort': effort}
    elif mapping == 'body':
        kwargs = {'extra_body': {'reasoning_effort': effort}}
    else:
        kwargs = {'extra_body': {'thinking': {'type': 'enabled', 'budget_tokens': 2048}}}
    configure(monkeypatch, entry(efforts={effort: kwargs}))
    selected = select_profile(settings, 'extra', effort)
    from core.model import make_main_model
    model = make_main_model(selected)
    payload = model._get_request_payload([{'role': 'user', 'content': 'test'}])
    assert payload['model'] == 'my-model'
    assert payload['max_completion_tokens'] == 4096
    for key, value in kwargs.items():
        assert payload[key] == value
    assert model.openai_api_key.get_secret_value() == 'private-key-not-for-display'
    assert model.openai_api_base == 'https://private-model.invalid/v1'
    assert load_profiles(settings)['extra'].safe()['efforts'] == [effort]


def test_default_effort_mapping_does_not_change_connection(settings, monkeypatch):
    configure(monkeypatch, {'id': 'default', 'efforts': {'low': {'reasoning_effort': 'low'}}})
    model = select_profile(settings, 'default', 'low')
    assert strict_json(model.request_kwargs_json) == {'reasoning_effort': 'low', 'extra_body': {}}
    assert model.api_key == settings.model.api_key


@pytest.mark.parametrize('profile,effort', [('missing', None), ('default', 'low'), ('default', ''), ('default', 'extreme')])
def test_unknown_or_unsupported_refused(settings, profile, effort):
    with pytest.raises(ValueError):
        select_profile(settings, profile, effort)


def test_missing_credentials_are_visible_but_not_executable(settings, monkeypatch):
    monkeypatch.delenv('PROFILE_KEY')
    configure(monkeypatch, entry())
    assert load_profiles(settings)['extra'].safe()['configured'] is False
    with pytest.raises(ValueError, match='NOT_CONFIGURED'):
        select_profile(settings, 'extra')


@pytest.mark.parametrize('kwargs', [
    {'model': 'evil'}, {'api_key': 'evil'}, {'base_url': 'https://evil'}, {'tools': []},
    {'tool_choice': 'auto'}, {'model_kwargs': {'tools': []}}, {'extra_body': {'model': 'evil'}},
    {'extra_body': {'tools': []}}, {'temperature': True}, {'max_tokens': 0}, {'seed': 2**32},
    {'reasoning_effort': 'extreme'}, {'extra_body': {'thinking': {'type': 'auto'}}},
    {'reasoning_effort': 'low', 'extra_body': {'thinking': {'type': 'disabled'}}},
    {'reasoning_effort': 'low', 'extra_body': {'reasoning_effort': 'low'}},
])
def test_strict_allowlist(kwargs):
    with pytest.raises(ValueError):
        validate_kwargs(kwargs)


@pytest.mark.parametrize('kwargs', [{}, {'temperature': 1}, {'extra_body': {'thinking': {'type': 'disabled'}}}])
def test_effort_mapping_must_really_enable_effort(kwargs):
    with pytest.raises(ValueError):
        validate_kwargs(kwargs, effort=True)


@pytest.mark.parametrize('raw', [
    '{}', '[{"id":"default","id":"duplicate"}]', '[{"id":"default","apiKeyEnv":"PROFILE_KEY"}]',
    '[{"id":"default","efforts":{"extreme":{"reasoning_effort":"high"}}}]',
    '[{"id":"default"},{"id":"default"}]', '[{"id":"default","name":NaN}]',
])
def test_malformed_directory_refused(settings, monkeypatch, raw):
    monkeypatch.setenv('AGENT_MODEL_PROFILES_JSON', raw)
    with pytest.raises(ValueError):
        load_profiles(settings)


def test_import_freezes_local_model_and_preserves_original_summary(settings, monkeypatch):
    configure(monkeypatch, entry(efforts={'medium': {'reasoning_effort': 'medium'}}))
    frozen, summary, window = freeze_import_settings({'importModelProfileId': 'extra',
                                                      'importThinkingEffort': 'medium'}, settings)
    assert frozen is not settings and frozen.model.model_id == 'my-model'
    assert window == 131072
    assert summary.model_id == settings.model.model_id
    assert summary.api_key == settings.model.api_key
    assert summary.request_kwargs_json is None
    assert settings.model.context_window_override == 262144
    with pytest.raises(ValueError, match='REQUIRED'):
        freeze_import_settings({}, settings)


@pytest.mark.parametrize('window,output,summary_window,error', [
    (4096, 4096, 262144, 'OUTPUT_WINDOW_INVALID'),
    (524288, 4096, 262144, 'SUMMARIZER_WINDOW_TOO_SMALL'),
    (131072, 4096, 4096, 'OUTPUT_WINDOW_INVALID'),
])
def test_import_window_constraints(settings, monkeypatch, window, output, summary_window, error):
    configure(monkeypatch, entry(contextWindow=window, kwargs={'max_tokens': output}))
    settings = replace(settings, model=replace(settings.model, context_window_override=summary_window))
    with pytest.raises(ValueError, match=error):
        freeze_import_settings({'importModelProfileId': 'extra'}, settings)


def test_separate_summary_keeps_credentials_and_validates_window(settings, monkeypatch):
    configure(monkeypatch, entry())
    settings = replace(settings, model=replace(settings.model, summarizer_model_id='summary',
                       summarizer_base_url='https://summary.invalid', summarizer_api_key='summary-key',
                       summarizer_context_window_override=200000))
    _, summary, _ = freeze_import_settings({'importModelProfileId': 'extra'}, settings)
    assert (summary.model_id, summary.base_url, summary.api_key) == ('summary', 'https://summary.invalid', 'summary-key')


def test_unknown_window_cannot_reuse_cached_fallback(settings, monkeypatch):
    from core import model_window
    model_window.reset_cache()
    monkeypatch.setattr(model_window, '_probe_models_endpoint', lambda *a, **k: None)
    model = replace(settings.model, model_id='unknown-model', context_window_override=0)
    for _ in range(2):
        with pytest.raises(ValueError, match='WINDOW_UNCONFIRMED'):
            resolve_profile_window(model)
    model_window.reset_cache()


def test_directory_cli_only_public_metadata(settings, monkeypatch):
    configure(monkeypatch, entry())
    env = {**os.environ, 'AGENT_MODEL_API_KEY': settings.model.api_key, 'AGENT_MODEL_PROXY_ENABLED': 'false'}
    process = subprocess.run([sys.executable, '-m', 'core.model_profiles'], cwd=Path(__file__).parents[1],
                             env=env, capture_output=True, timeout=10)
    assert process.returncode == 0, process.stderr
    result = json.loads(process.stdout)
    assert set(result) == {'profiles'}
    assert all(set(profile) == {'id', 'name', 'model', 'provider', 'efforts', 'configured'}
               for profile in result['profiles'])
    assert b'private-key' not in process.stdout and b'private-model' not in process.stdout
    monkeypatch.setenv('AGENT_MODEL_PROFILES_JSON', '{"secret":"do-not-echo"}')
    failed = subprocess.run([sys.executable, '-m', 'core.model_profiles'], cwd=Path(__file__).parents[1],
                            env=os.environ, capture_output=True, timeout=10)
    assert failed.returncode == 2 and failed.stdout == b''
    assert failed.stderr.replace(b'\r\n', b'\n') == b'MODEL_PROFILE_DIRECTORY_FAILED\n'


@pytest.mark.parametrize('window', [-1, 512, True, 100000001])
def test_invalid_default_windows_are_not_silently_ignored(settings, window):
    with pytest.raises(ValueError, match='WINDOW_INVALID'):
        resolve_profile_window(replace(settings.model, context_window_override=window))


def test_original_generation_does_not_read_import_directory(settings, monkeypatch):
    monkeypatch.setenv('AGENT_MODEL_PROFILES_JSON', 'invalid')
    from core.model import make_main_model
    model = make_main_model(settings.model)
    payload = model._get_request_payload([{'role': 'user', 'content': 'test'}])
    assert payload['extra_body'] == {'thinking': {'type': 'disabled'}}
    assert 'reasoning_effort' not in payload


def test_import_configuration_failure_is_before_backend_access(settings):
    from core.import_edit_runtime import run_import_edit
    from unittest.mock import Mock

    workspace = {key: 'present' for key in ('importEditId', 'sourceHash', 'importTaskId', 'configRevision',
                  'contextEpoch', 'importViewPath', 'viewStateHash', 'containerId', 'workerLeaseId')}
    emitter = Mock()
    run_import_edit({'workspace': workspace}, settings, emitter)
    failure = emitter.emit.call_args.kwargs
    assert failure['stage'] == 'IMPORT_EDIT_MODEL_CONFIGURATION'
    assert failure['failureKey'] == 'MODEL_PROFILE_REQUIRED'
