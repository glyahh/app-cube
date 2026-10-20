import io
import json
from uuid import uuid4

from core import permission_review_worker as worker


def test_requests_are_independent_and_bound_to_request_id():
    seen = []

    def review(raw, **kwargs):
        body = json.loads(raw)
        seen.append(body)
        return {'objectHash': body['objectHash'], 'risk': 'HIGH', 'reason': '需要人工核对。'}

    ids = [str(uuid4()), str(uuid4())]
    rows = [{'requestId': rid, 'input': {'kind': 'ACTION', 'objectHash': str(i) * 64,
                                      'payload': {'content': f'untrusted-{i}'}}}
            for i, rid in enumerate(ids)]
    source = io.BytesIO(b''.join(json.dumps(row).encode() + b'\n' for row in rows))
    sink = io.BytesIO()
    assert worker.serve(source, sink, reviewer=review, preload=lambda: None) == 0
    frames = [json.loads(line) for line in sink.getvalue().splitlines()]
    assert frames[0] == {'type': 'ready', 'protocol': 1}
    assert [f['requestId'] for f in frames[1:]] == ids
    assert seen == [row['input'] for row in rows]
    assert frames[1]['result']['objectHash'] != frames[2]['result']['objectHash']


def test_failed_request_does_not_approve_or_expose_exception():
    rid = str(uuid4())
    source = io.BytesIO(json.dumps({'requestId': rid, 'input': {}}).encode() + b'\n')
    sink = io.BytesIO()

    def fail(raw, **kwargs):
        raise ValueError('credential-never-output')

    assert worker.serve(source, sink, reviewer=fail, preload=lambda: None) == 2
    assert json.loads(sink.getvalue().splitlines()[-1]) == {'requestId': rid, 'error': 'PERMISSION_REVIEW_FAILED'}
    assert b'credential' not in sink.getvalue()


def test_oversize_and_invalid_frame_stop_worker():
    for frame in [b'x' * (worker.MAX_FRAME_BYTES + 1), b'{}\n', b'{"requestId":"fake","input":{}}\n']:
        sink = io.BytesIO()
        assert worker.serve(io.BytesIO(frame), sink, preload=lambda: None) == 2
        assert sink.getvalue().splitlines() == [b'{"type":"ready","protocol":1}']


def test_worker_watchdog_terminates_blocked_request():
    import subprocess
    import sys
    from pathlib import Path
    code = '''import sys,time
import core.permission_review_worker as w
w.PROCESS_TIMEOUT_SECONDS=.2
def blocked(raw,**kwargs): time.sleep(10)
raise SystemExit(w.serve(sys.stdin.buffer,sys.stdout.buffer,reviewer=blocked,preload=lambda:None))
'''
    frame = {'requestId': str(uuid4()), 'input': {}}
    process = subprocess.run([sys.executable, '-c', code],
        input=json.dumps(frame).encode() + b'\n', cwd=Path(__file__).parents[1], capture_output=True, timeout=5)
    assert process.returncode == 2
    assert process.stdout.splitlines() == [b'{"type":"ready","protocol":1}']
    assert process.stderr.replace(b'\r\n', b'\n') == b'PERMISSION_REVIEW_TIMEOUT\n'
