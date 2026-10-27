from pathlib import Path

import pytest


def pytest_addoption(parser):
    parser.addoption('--external', action='store_true', help='run explicitly enabled real infrastructure tests')


def pytest_collection_modifyitems(config, items):
    if config.getoption('--external'):
        return
    for item in items:
        if 'external' in item.keywords:
            item.add_marker(pytest.mark.skip(reason='external environment tests require --external'))


@pytest.fixture(autouse=True)
def offline_test_boundary(monkeypatch):
    monkeypatch.setenv('AGENT_ENV_FILE', str(Path(__file__).with_name('missing.env')))
    for name in ('AGENT_MODEL_API_KEY', 'DEEPSEEK_API_KEY', 'DASHSCOPE_API_KEY',
                 'AGENT_SUMMARIZER_API_KEY', 'AGENT_POSTGRES_DSN', 'POSTGRES_DSN',
                 'AGENT_MODEL_BASE_URL', 'AGENT_SUMMARIZER_BASE_URL'):
        monkeypatch.delenv(name, raising=False)
