import logging
from types import SimpleNamespace

from core.model_timing import bind_run, model_call, request_started, response_headers


def test_attempts_and_chunks_are_correlated_without_content(caplog):
    caplog.set_level(logging.INFO)
    with bind_run('run-1'), model_call() as call:
        first = SimpleNamespace(headers={}, extensions={})
        attempt = request_started(first)
        attempt.network('connection.connect_tcp.failed', {'exception': TimeoutError('SECRET-payload')})
        second = SimpleNamespace(headers={'x-stainless-retry-count': '1'}, extensions={})
        request_started(second)
        response_headers(SimpleNamespace(request=second, status_code=200))
        call.chunk()
        call.chunk()
    rows = [r.message for r in caplog.records]
    assert any('attempt=2' in row and 'retryReason=TimeoutError' in row for row in rows)
    assert any('phase=response_headers' in row and 'status=200' in row for row in rows)
    assert any('firstChunkMs=' in row and 'maxChunkGapMs=' in row for row in rows)
    assert all('SECRET' not in row for row in rows)
    assert all('runId=run-1' in row and f'callId={call.call_id}' in row for row in rows)


def test_no_observation_outside_run_or_change_to_timeout():
    request = SimpleNamespace(headers={}, extensions={'timeout': {'read': None}})
    assert request_started(request) is None
    assert request.extensions == {'timeout': {'read': None}}


def test_instrumentation_preserves_model_payload_and_network_policy():
    from core.model import _chat_model
    from core.settings import ModelSettings
    model = _chat_model('test', 'http://127.0.0.1:9/v1', 'PRIVATE', ModelSettings(), streaming=True, proxy_url=None)
    unwrapped = model.model_copy()
    unwrapped.__class__ = type(model).__bases__[0]
    messages = [{'role': 'user', 'content': '同一输入'}]
    assert model._get_request_payload(messages) == unwrapped._get_request_payload(messages)
    assert model.root_async_client.max_retries == unwrapped.root_async_client.max_retries == 2
    assert model.root_async_client.timeout is unwrapped.root_async_client.timeout is None


def test_cancelled_stream_releases_timing_context(caplog):
    import asyncio
    import pytest
    from core.model_timing import instrument

    class Model:
        async def _astream(self):
            yield SimpleNamespace(content='PRIVATE', additional_kwargs={})
            await asyncio.Event().wait()

    async def run():
        ready = asyncio.Event()

        async def consume():
            with bind_run('cancelled-run'):
                async for chunk in instrument(Model())._astream():
                    ready.set()

        task = asyncio.create_task(consume())
        await ready.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with bind_run('next-run'), model_call() as call:
            assert call.run_id == 'next-run'

    caplog.set_level(logging.INFO, logger='core.model_timing')
    asyncio.run(run())
    assert any('runId=cancelled-run' in row.message and 'phase=finished' in row.message for row in caplog.records)


def test_real_async_http_delay_retry_and_stream_are_measured(caplog):
    import asyncio
    import json
    import re
    import threading
    import time
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from core.model import _chat_model
    from core.settings import ModelSettings

    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            time.sleep(.08)
            if len(requests) == 1:
                self.send_response(503)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            frames = []
            for delta, finish in [({}, None), ({'content': 'PRIVATE-ANSWER'}, None), ({}, 'stop')]:
                frames.append(('data: ' + json.dumps({'id': 'local', 'object': 'chat.completion.chunk',
                    'model': 'test', 'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}) + '\n\n').encode())
            frames.append(b'data: [DONE]\n\n')
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(sum(map(len, frames))))
            self.end_headers()
            for frame in frames:
                time.sleep(.03)
                self.wfile.write(frame)
                self.wfile.flush()

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    caplog.set_level(logging.INFO, logger='core.model_timing')
    try:
        settings = ModelSettings(api_key='PRIVATE-KEY')
        model = _chat_model('test', f'http://127.0.0.1:{server.server_port}/v1', 'PRIVATE-KEY', settings,
                            streaming=True, proxy_url=None, max_retries=1)

        async def run():
            with bind_run('real-delay'):
                return [chunk async for chunk in model.astream('PRIVATE-PROMPT')]

        chunks = asyncio.run(run())
        assert ''.join(chunk.content for chunk in chunks) == 'PRIVATE-ANSWER'
        rows = [r.message for r in caplog.records if r.name == 'core.model_timing']
        assert len(requests) == 2
        assert any('attempt=2' in row and 'retryReason=http_503' in row for row in rows)
        assert any('phase=connection.connect_tcp.complete' in row for row in rows)
        waits = [int(re.search(r'headerWaitMs=(\d+)', row)[1]) for row in rows if 'headerWaitMs=' in row]
        assert len(waits) == 2 and min(waits) >= 60
        assert any('phase=first_chunk' in row for row in rows)
        assert len({re.search(r'callId=([\w-]+)', row)[1] for row in rows}) == 1
        assert not any('PRIVATE' in row for row in rows)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
