"""主 Agent 启动门禁：正式环境缺持久化时不得进入 stdin 循环。"""
from types import SimpleNamespace

import pytest

import app


class ExplodingStdin:
    def __iter__(self):
        raise AssertionError('持久化失败后不应读取 stdin')


def settings(**overrides):
    values = {'require_persistent': True, 'postgres_dsn': '', 'log_level': 'INFO'}
    values.update(overrides)
    return SimpleNamespace(**values)


def test_main_uses_settings_when_argument_absent(monkeypatch):
    seen = {}
    monkeypatch.setattr(app, 'load_settings', lambda: settings())
    monkeypatch.setattr(app, '_configure_logging', lambda level: None)
    monkeypatch.setattr(app._sys, 'stdin', ExplodingStdin())

    def precheck(_settings, *, require_persistent=False):
        seen['require_persistent'] = require_persistent
        raise RuntimeError('POSTGRES_PERSISTENCE_UNAVAILABLE')

    monkeypatch.setattr(app, '_precheck_persistence', precheck)

    with pytest.raises(RuntimeError, match='POSTGRES_PERSISTENCE_UNAVAILABLE'):
        app.main()
    assert seen['require_persistent'] is True


def test_explicit_argument_overrides_settings(monkeypatch):
    seen = {}
    monkeypatch.setattr(app, 'load_settings', lambda: settings(require_persistent=True))
    monkeypatch.setattr(app, '_configure_logging', lambda level: None)

    def precheck(_settings, *, require_persistent=False):
        seen['require_persistent'] = require_persistent
        raise RuntimeError('stop')

    monkeypatch.setattr(app, '_precheck_persistence', precheck)

    with pytest.raises(RuntimeError):
        app.main(require_persistent=False)
    assert seen['require_persistent'] is False


def test_precheck_reports_marker_for_missing_dsn():
    with pytest.raises(RuntimeError, match='POSTGRES_PERSISTENCE_UNAVAILABLE'):
        app._precheck_persistence(settings(), require_persistent=True)
