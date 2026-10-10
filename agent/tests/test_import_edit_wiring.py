"""导入编辑装配回归，不调用真实模型或安装依赖。"""
from unittest.mock import Mock

import pytest


@pytest.mark.parametrize('import_edit,expected', [(True, 'error'), (False, 'end')])
def test_model_budget_behavior_is_scoped(monkeypatch, tmp_path, import_edit, expected):
    import deepagents
    import core.model as model
    from core.settings import Settings, ModelSettings, Limits, WorkspaceSettings
    from langchain.agents.middleware import ModelCallLimitMiddleware
    from server import build_agent
    monkeypatch.setattr(model, 'make_main_model', lambda *args: Mock())
    monkeypatch.setattr(model, 'make_summarizer_model', lambda *args: Mock())
    monkeypatch.setattr(model, 'model_connection_options', lambda *args: None)
    captured = {}
    monkeypatch.setattr(deepagents, 'create_deep_agent', lambda **kwargs: captured.update(kwargs))
    settings = Settings(ModelSettings(), Limits(), WorkspaceSettings(tmp_path), 'INFO', '')
    build_agent(settings=settings, backend=Mock(), checkpointer=None, store=None,
                task_kind='coding', workspace=tmp_path, window=32768, import_edit=import_edit)
    limit = next(item for item in captured['middleware'] if isinstance(item, ModelCallLimitMiddleware))
    assert limit.exit_behavior == expected
    assert limit.run_limit == settings.limits.max_model_calls
