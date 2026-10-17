"""导入轮次的严格模型目录；CLI 只输出可公开的配置元数据。"""
from __future__ import annotations

import copy
import json
import math
import re
import sys
from dataclasses import dataclass, replace
from urllib.parse import urlparse

from core.settings import ModelSettings, Settings, load_settings, validate_proxy_config
from env_utils import get_env

EFFORTS = ('low', 'medium', 'high')
MAX_CONFIG_BYTES = 65_536
MAX_PROFILES = 32


def _fail(code='MODEL_PROFILE_CONFIG_INVALID'):
    raise ValueError(code)


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                _fail('JSON_DUPLICATE_KEY')
            result[key] = value
        return result

    def constant(_):
        _fail('JSON_NONFINITE')

    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)


def _number(value, minimum, maximum):
    return type(value) in (int, float) and math.isfinite(value) and minimum <= value <= maximum


def validate_kwargs(value, *, effort=False):
    """只允许明确支持的请求参数，禁止透传任意 SDK 或工具配置。"""
    allowed = {'temperature', 'max_tokens', 'top_p', 'frequency_penalty',
               'presence_penalty', 'seed', 'reasoning_effort', 'extra_body'}
    if type(value) is not dict or value.keys() - allowed:
        _fail('MODEL_PROFILE_KWARGS_INVALID')
    ranges = {'temperature': (0, 2), 'top_p': (0, 1),
              'frequency_penalty': (-2, 2), 'presence_penalty': (-2, 2)}
    for key, bounds in ranges.items():
        if key in value and not _number(value[key], *bounds):
            _fail('MODEL_PROFILE_KWARGS_INVALID')
    if 'max_tokens' in value and (type(value['max_tokens']) is not int or not 1 <= value['max_tokens'] <= 1_000_000):
        _fail('MODEL_PROFILE_KWARGS_INVALID')
    if 'seed' in value and (type(value['seed']) is not int or not 0 <= value['seed'] < 2**31):
        _fail('MODEL_PROFILE_KWARGS_INVALID')
    if 'reasoning_effort' in value and value['reasoning_effort'] not in EFFORTS:
        _fail('MODEL_PROFILE_KWARGS_INVALID')
    body = value.get('extra_body', {})
    if type(body) is not dict or body.keys() - {'thinking', 'reasoning_effort'}:
        _fail('MODEL_PROFILE_KWARGS_INVALID')
    if 'reasoning_effort' in body and body['reasoning_effort'] not in EFFORTS:
        _fail('MODEL_PROFILE_KWARGS_INVALID')
    if 'thinking' in body:
        thinking = body['thinking']
        if type(thinking) is not dict or thinking.keys() - {'type', 'budget_tokens'} or thinking.get('type') not in ('enabled', 'disabled'):
            _fail('MODEL_PROFILE_KWARGS_INVALID')
        if 'budget_tokens' in thinking and (thinking['type'] != 'enabled' or type(thinking['budget_tokens']) is not int or not 1 <= thinking['budget_tokens'] <= 1_000_000):
            _fail('MODEL_PROFILE_KWARGS_INVALID')
    if 'reasoning_effort' in value and 'reasoning_effort' in body:
        _fail('MODEL_PROFILE_KWARGS_CONFLICT')
    if ('reasoning_effort' in value or 'reasoning_effort' in body) and body.get('thinking', {}).get('type') == 'disabled':
        _fail('MODEL_PROFILE_KWARGS_CONFLICT')
    if effort:
        if value.keys() - {'reasoning_effort', 'extra_body'}:
            _fail('MODEL_PROFILE_EFFORT_INVALID')
        if not ('reasoning_effort' in value or 'reasoning_effort' in body or body.get('thinking', {}).get('type') == 'enabled'):
            _fail('MODEL_PROFILE_EFFORT_INVALID')
    return copy.deepcopy(value)


def _text(value, maximum=128):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum or any(ord(char) < 32 for char in value):
        _fail()
    return value


def _env_ref(value):
    if not isinstance(value, str) or not re.fullmatch('[A-Z][A-Z0-9_]{0,127}', value):
        _fail()
    return get_env(value).strip()


def _url(value):
    try:
        parsed = urlparse(value)
        parsed.port
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            _fail('MODEL_PROFILE_CONNECTION_INVALID')
    except ValueError:
        _fail('MODEL_PROFILE_CONNECTION_INVALID')


@dataclass(frozen=True)
class ModelProfile:
    id: str
    name: str
    provider: str
    settings: ModelSettings
    effort_mapping_json: str = '{}'

    @property
    def configured(self):
        return bool(self.settings.api_key.strip() and self.settings.base_url.strip() and self.settings.model_id.strip()
                    and (not self.settings.proxy_enabled or self.settings.proxy_url.strip()))

    def safe(self):
        mapping = strict_json(self.effort_mapping_json)
        return {'id': self.id, 'name': self.name, 'provider': self.provider,
                'efforts': [item for item in EFFORTS if item in mapping], 'configured': self.configured}

    def select(self, effort=None):
        if not self.configured:
            _fail('MODEL_PROFILE_NOT_CONFIGURED')
        _url(self.settings.base_url)
        validate_proxy_config(self.settings.proxy_enabled, self.settings.proxy_url)
        kwargs = strict_json(self.settings.request_kwargs_json) if self.settings.request_kwargs_json is not None else {'extra_body': {'thinking': {'type': 'disabled'}}}
        if effort is not None:
            mapping = strict_json(self.effort_mapping_json)
            if not isinstance(effort, str) or effort not in mapping:
                _fail('MODEL_PROFILE_EFFORT_UNSUPPORTED')
            addition = mapping[effort]
            kwargs = {**kwargs, **addition, 'extra_body': {**kwargs.get('extra_body', {}), **addition.get('extra_body', {})}}
            # reasoning_effort 不能同时被旧的 thinking:disabled 抵消。
            if self.settings.request_kwargs_json is None and ('reasoning_effort' in addition or 'reasoning_effort' in addition.get('extra_body', {})) and 'thinking' not in addition.get('extra_body', {}):
                kwargs['extra_body'].pop('thinking', None)
        kwargs = validate_kwargs(kwargs)
        return replace(self.settings, request_kwargs_json=json.dumps(kwargs))


def load_profiles(settings: Settings):
    raw = get_env('AGENT_MODEL_PROFILES_JSON', '').strip() or '[]'
    if len(raw.encode('utf-8')) > MAX_CONFIG_BYTES:
        _fail('MODEL_PROFILE_CONFIG_TOO_LARGE')
    entries = strict_json(raw)
    if type(entries) is not list or len(entries) > MAX_PROFILES - 1:
        _fail()
    profiles = {'default': ModelProfile('default', '默认模型', 'openai-compatible', settings.model)}
    seen = set()
    for entry in entries:
        if type(entry) is not dict:
            _fail()
        profile_id = entry.get('id')
        if not isinstance(profile_id, str) or not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}', profile_id) or profile_id in seen:
            _fail()
        seen.add(profile_id)
        common = {'id', 'name', 'provider', 'efforts'}
        connection = {'model', 'apiKeyEnv', 'baseUrlEnv', 'contextWindow', 'kwargs', 'proxyUrlEnv'}
        allowed = common if profile_id == 'default' else common | connection
        required = {'id'} if profile_id == 'default' else {'id', 'name', 'provider', 'model', 'apiKeyEnv', 'baseUrlEnv', 'contextWindow'}
        if entry.keys() - allowed or required - entry.keys():
            _fail()
        name = _text(entry.get('name', profiles['default'].name))
        provider = _text(entry.get('provider', 'openai-compatible'), 64)
        mappings = entry.get('efforts', {})
        if type(mappings) is not dict or mappings.keys() - set(EFFORTS):
            _fail('MODEL_PROFILE_EFFORT_INVALID')
        mappings = {key: validate_kwargs(value, effort=True) for key, value in mappings.items()}
        if profile_id == 'default':
            model = settings.model
        else:
            window = entry['contextWindow']
            if type(window) is not int or not 1024 <= window <= 100_000_000:
                _fail('MODEL_PROFILE_WINDOW_INVALID')
            kwargs = validate_kwargs(entry.get('kwargs', {}))
            base_url = _env_ref(entry['baseUrlEnv'])
            proxy_url = _env_ref(entry['proxyUrlEnv']) if 'proxyUrlEnv' in entry else ''
            if base_url:
                _url(base_url)
            if proxy_url:
                validate_proxy_config(True, proxy_url)
            model = replace(settings.model, model_id=_text(entry['model'], 256),
                            api_key=_env_ref(entry['apiKeyEnv']), base_url=base_url,
                            context_window_override=window, proxy_enabled='proxyUrlEnv' in entry,
                            proxy_url=proxy_url, request_kwargs_json=json.dumps(kwargs))
        profiles[profile_id] = ModelProfile(profile_id, name, provider, model, json.dumps(mappings))
    return profiles


def select_profile(settings, profile_id, effort=None):
    if not isinstance(profile_id, str):
        _fail('MODEL_PROFILE_UNKNOWN')
    profile = load_profiles(settings).get(profile_id)
    if profile is None:
        _fail('MODEL_PROFILE_UNKNOWN')
    return profile.select(effort)


def resolve_profile_window(model: ModelSettings):
    """导入与独立审查必须具有可核对的窗口，不能使用未知模型的保守值。"""
    from core.model import model_connection_options
    from core.model_window import _match_builtin, resolve_window

    if type(model.context_window_override) is not int or (model.context_window_override != 0 and not 1024 <= model.context_window_override <= 100_000_000):
        _fail('MODEL_PROFILE_WINDOW_INVALID')

    result = resolve_window(model.model_id, model.base_url, model.api_key,
                            override=model.context_window_override,
                            proxy_url=model_connection_options(model))
    uncertain_cache = (result.source == 'cache' and result.window == 32_768
                       and not model.context_window_override and _match_builtin(model.model_id) is None)
    if result.source == 'fallback' or uncertain_cache or not 1024 <= result.window <= 100_000_000:
        _fail('MODEL_PROFILE_WINDOW_UNCONFIRMED')
    return result.window


def validate_import_windows(main: ModelSettings, summary: ModelSettings):
    """摘要窗口不得小于本轮主模型，输出预算必须小于各自窗口。"""
    def resolve(model):
        window = resolve_profile_window(model)
        output = strict_json(model.request_kwargs_json).get('max_tokens', model.max_output_tokens) if model.request_kwargs_json is not None else model.max_output_tokens
        if type(output) is not int or not 0 < output < window:
            _fail('MODEL_PROFILE_OUTPUT_WINDOW_INVALID')
        return window

    main_window = resolve(main)
    if resolve(summary) < main_window:
        _fail('MODEL_PROFILE_SUMMARIZER_WINDOW_TOO_SMALL')
    return main_window


def freeze_import_settings(request, settings):
    """局部副本构造主模型，摘要保留原配置；不修改 Worker 全局 Settings。"""
    profile_id = request.get('importModelProfileId')
    if not isinstance(profile_id, str) or not profile_id:
        _fail('MODEL_PROFILE_REQUIRED')
    main = select_profile(settings, profile_id, request.get('importThinkingEffort'))
    original = settings.model
    summary = replace(original, model_id=original.summarizer_model_id or original.model_id,
                      base_url=original.summarizer_base_url or original.base_url,
                      api_key=original.summarizer_api_key or original.api_key,
                      context_window_override=(original.summarizer_context_window_override or
                          (0 if original.summarizer_model_id or original.summarizer_base_url
                           else original.context_window_override)))
    if not summary.api_key:
        _fail('MODEL_PROFILE_SUMMARIZER_NOT_CONFIGURED')
    window = validate_import_windows(main, summary)
    return replace(settings, model=main), summary, window


def main():
    try:
        settings = load_settings()
        output = {'profiles': [profile.safe() for profile in load_profiles(settings).values()]}
        sys.stdout.write(json.dumps(output, ensure_ascii=True, allow_nan=False) + '\n')
        return 0
    except Exception:
        sys.stderr.write('MODEL_PROFILE_DIRECTORY_FAILED\n')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
