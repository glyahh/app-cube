from types import SimpleNamespace
from unittest.mock import Mock
from core.runtime import run_one, TaskKind, is_read_only
from core.settings import ModelSettings


def test_title_is_one_model_call_without_workspace_or_tools(monkeypatch):
    model = Mock()
    model.invoke.return_value = SimpleNamespace(content="轨道空间站")
    monkeypatch.setattr("core.model.make_summarizer_model", lambda *a: model)
    monkeypatch.setattr("core.runtime.resolve_workspace", lambda *a: (_ for _ in ()).throw(AssertionError("不应读工作区")))
    emitter = Mock()
    settings = SimpleNamespace(model=ModelSettings(api_key="fixture"))
    run_one({"runId":"title-test", "input":{"taskKind":"title", "message":"做个空间站"}}, settings, emitter)
    model.invoke.assert_called_once()
    assert is_read_only(TaskKind.TITLE)
    assert emitter.emit.call_args_list[0].kwargs["content"] == "轨道空间站"
    emitter.error.assert_not_called()


def test_missing_key_does_not_emit_placeholder_title():
    emitter = Mock()
    settings = SimpleNamespace(model=ModelSettings(api_key=""))
    run_one({"runId":"title-test", "input":{"taskKind":"title", "message":"做个空间站"}}, settings, emitter)
    emitter.error.assert_called_once()
    emitter.emit.assert_not_called()
