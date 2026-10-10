"""正式 Worker 先拒绝未接通的导入编辑，不挂发布目录、不走占位成功。"""
import io
import json
from types import SimpleNamespace
from unittest.mock import Mock
import uuid

import pytest

from core.events import Emitter
from core.runtime import run_one


@pytest.mark.parametrize("key", ["", "fixture-key"])
@pytest.mark.parametrize("backend", ["docker", "local_shell"])
def test_import_identity_never_enters_published_workspace(tmp_path, monkeypatch, key, backend):
    import core.runtime as runtime
    resolver=Mock(side_effect=AssertionError("published path must not be resolved"))
    agent=Mock(side_effect=AssertionError("model must not be called"))
    monkeypatch.setattr(runtime,"resolve_workspace",resolver)
    monkeypatch.setattr(runtime,"_run_agent",agent)
    monkeypatch.setenv("AGENT_SANDBOX_BACKEND",backend)
    target=tmp_path/'published'/'project_101'
    request={"runId":"201","threadId":"app:101","appId":"101","userId":"7",
             "workspace":{"root":str(target.parent),"name":target.name,"importTaskId":str(uuid.uuid4())},
             "input":{"message":"edit app.py"}}
    stream=io.StringIO()
    run_one(request,SimpleNamespace(model=SimpleNamespace(api_key=key)),Emitter(stream,"201"))
    events=[json.loads(line) for line in stream.getvalue().splitlines()]
    assert len(events)==1 and events[0]['event']=='error'
    assert events[0]['code']=='IMPORT_EDIT_NOT_READY' and events[0]['retryable'] is False
    assert not target.exists()
    resolver.assert_not_called();agent.assert_not_called()


def test_non_imported_project_keeps_existing_flow(tmp_path, monkeypatch):
    import core.runtime as runtime
    agent=Mock()
    monkeypatch.setattr(runtime,"_run_agent",agent)
    request={"runId":"201","workspace":{"root":str(tmp_path),"name":"project_101"},
             "input":{"message":"edit app.py"}}
    run_one(request,SimpleNamespace(model=SimpleNamespace(api_key="fixture",model_id="fixture")),Emitter(io.StringIO(),"201"))
    agent.assert_called_once()
    assert (tmp_path/'project_101').is_dir()
