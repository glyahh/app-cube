"""同一完整输入、同一真实模型：交替比较一次性审查与预热 Worker。"""
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
CODE = '''import json,sys
from core.permission_review import review
metrics={}
result=review(sys.stdin.buffer.read(),metrics=metrics)
print(json.dumps({'result':result,'timing':metrics}))
'''


def main():
    directory = ROOT / 'temp/logs/chat-performance-20261020'
    directory.mkdir(parents=True, exist_ok=True)
    body = {'kind': 'ACTION', 'objectHash': hashlib.sha256(b'performance-fixture').hexdigest(),
            'payload': {'tool': 'edit', 'path': 'src/note.txt', 'before': 'hello', 'after': 'hello world'}}
    started = time.perf_counter()
    worker = subprocess.Popen([sys.executable, '-u', '-m', 'core.permission_review_worker'], cwd=ROOT / 'agent',
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    import concurrent.futures
    results = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as reader:
            ready = json.loads(reader.submit(worker.stdout.readline).result(timeout=60))
            assert ready == {'type': 'ready', 'protocol': 1}
            startup = (time.perf_counter() - started) * 1000
            for i in range(3):
                for mode in ('cold', 'warm') if i % 2 == 0 else ('warm', 'cold'):
                    start = time.perf_counter()
                    if mode == 'cold':
                        process = subprocess.run([sys.executable, '-c', CODE], cwd=ROOT / 'agent',
                            input=json.dumps(body).encode(), capture_output=True, timeout=90)
                        assert process.returncode == 0, 'COLD_REVIEW_FAILED'
                        response = json.loads(process.stdout)
                    else:
                        rid = str(uuid4())
                        worker.stdin.write(json.dumps({'requestId': rid, 'input': body}).encode() + b'\n')
                        worker.stdin.flush()
                        response = json.loads(reader.submit(worker.stdout.readline).result(timeout=90))
                        assert response['requestId'] == rid
                    assert response['result']['objectHash'] == body['objectHash']
                    results.append({'mode': mode, 'iteration': i, 'totalMs': (time.perf_counter() - start) * 1000,
                                    'timing': response['timing'], 'risk': response['result']['risk']})
                    print(json.dumps(results[-1]), flush=True)
                    (directory / 'review-performance.json').write_text(json.dumps({'startupMs': startup, 'results': results}, indent=2))
    finally:
        worker.kill()
        worker.wait(timeout=5)
        worker.stdin.close(); worker.stdout.close(); worker.stderr.close()


if __name__ == '__main__':
    main()
