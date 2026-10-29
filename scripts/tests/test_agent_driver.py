"""基准驱动：用假 agent 子进程验证协议解析、超时和协议错误。"""
import importlib.util
import sys
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'agent_driver', Path(__file__).parents[1] / 'checks' / 'agent_driver.py')
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)

COMPLETE = '''
import json, sys
sys.stdin.readline()
for event in ("run.started", "text.message", "__end__"):
    print(json.dumps({"v": 1, "seq": 0, "event": event}), flush=True)
'''

NO_END = '''
import json, sys
sys.stdin.readline()
print(json.dumps({"v": 1, "seq": 0, "event": "run.started"}), flush=True)
'''

BAD_FRAME = '''
import json, sys
sys.stdin.readline()
print("not json", flush=True)
print(json.dumps({"v": 1, "seq": 0, "event": "__end__"}), flush=True)
'''

SLOW = '''
import time
time.sleep(30)
'''


def script(tmp_path, source, name='fake.py'):
    path = tmp_path / name
    path.write_text(source, encoding='utf-8')
    return [sys.executable, str(path)]


def test_end_frame_completes(tmp_path):
    result = driver.run(script(tmp_path, COMPLETE), tmp_path, 'do it', 10)
    assert result['ok'] is True
    assert result['exitCode'] == 0
    assert result['events'][-1]['event'] == '__end__'


def test_missing_end_is_not_ok(tmp_path):
    result = driver.run(script(tmp_path, NO_END), tmp_path, 'do it', 5)
    assert result['ok'] is False
    assert result['timedOut'] is False


def test_non_json_frame_is_reported(tmp_path):
    result = driver.run(script(tmp_path, BAD_FRAME), tmp_path, 'do it', 5)
    assert result['ok'] is False
    assert result['protocolError'] == 'non-json frame'


def test_timeout_kills_process(tmp_path):
    result = driver.run(script(tmp_path, SLOW), tmp_path, 'do it', 1)
    assert result['ok'] is False
    assert result['timedOut'] is True


def test_request_contains_contract_fields(tmp_path):
    request = driver.build_request(tmp_path, 'fix it')
    # runtime.resolve_workspace 使用 root / name，root 必须是父目录。
    assert request['workspace']['root'] == str(tmp_path.parent)
    assert request['workspace']['name'] == tmp_path.name
    assert (Path(request['workspace']['root']) / request['workspace']['name']).resolve() == tmp_path.resolve()
    assert request['input']['message'] == 'fix it'
    for key in ('v', 'runId', 'threadId', 'appId', 'userId', 'roundId', 'traceId'):
        assert key in request


def test_thread_id_is_unique_per_run(tmp_path):
    first = driver.build_request(tmp_path, 'a')['threadId']
    second = driver.build_request(tmp_path, 'b')['threadId']
    assert first != second
