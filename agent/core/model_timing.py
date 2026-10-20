"""仅记录阶段、计数与耗时，不记录地址、正文、密钥或模型文本。"""
from __future__ import annotations

import logging
import time
from contextlib import contextmanager
from contextvars import ContextVar
from uuid import uuid4

logger = logging.getLogger(__name__)
_run = ContextVar('timing_run', default=None)
_call = ContextVar('timing_call', default=None)


@contextmanager
def bind_run(run_id):
    token = _run.set({'id': str(run_id), 'input': 0, 'output': 0})
    try:
        yield
    finally:
        _run.reset(token)


class Call:
    def __init__(self, run):
        self.run = run
        self.run_id = run['id']
        self.call_id = str(uuid4())
        self.started = time.monotonic()
        self.attempts = 0
        self.reason = 'none'
        self.first_chunk = None
        self.last_chunk = None
        self.max_gap = 0.0
        self.usage = None

    def log(self, phase, **fields):
        values = ' '.join(f'{key}={value}' for key, value in fields.items())
        logger.info('model timing runId=%s callId=%s attempt=%d phase=%s elapsedMs=%d %s',
                    self.run_id, self.call_id, self.attempts, phase,
                    (time.monotonic() - self.started) * 1000, values)

    def chunk(self):
        now = time.monotonic()
        if self.first_chunk is None:
            self.first_chunk = now
            self.log('first_chunk')
        if self.last_chunk is not None:
            self.max_gap = max(self.max_gap, now - self.last_chunk)
        self.last_chunk = now

    def observe(self, chunk):
        message = getattr(chunk, 'message', chunk)
        usage = getattr(message, 'usage_metadata', None)
        if isinstance(usage, dict):
            self.usage = usage


@contextmanager
def model_call():
    existing = _call.get()
    if existing is not None or _run.get() is None:
        yield existing
        return
    call = Call(_run.get())
    token = _call.set(call)
    call.log('prepared')
    try:
        yield call
    except BaseException as error:
        call.log('failed', reason=type(error).__name__)
        raise
    finally:
        if call.last_chunk is not None:
            call.max_gap = max(call.max_gap, time.monotonic() - call.last_chunk)
        single_input = int((call.usage or {}).get('input_tokens') or 0)
        single_output = int((call.usage or {}).get('output_tokens') or 0)
        call.run['input'] += single_input
        call.run['output'] += single_output
        call.log('finished', firstChunkMs=-1 if call.first_chunk is None else int((call.first_chunk - call.started) * 1000),
                 maxChunkGapMs=int(call.max_gap * 1000), usagePresent=call.usage is not None,
                 inputTokens=single_input, outputTokens=single_output,
                 cumulativeInputTokens=call.run['input'], cumulativeOutputTokens=call.run['output'])
        _call.reset(token)


class Attempt:
    def __init__(self, call):
        self.call = call
        self.number = call.attempts
        self.started = time.monotonic()

    def network(self, name, info):
        # httpcore 的 info 含请求及异常对象，只取白名单阶段与异常类型。
        phase = name.rsplit('.', 1)[0].split('.')[-1]
        if phase not in {'connect_tcp', 'start_tls', 'send_request_headers', 'receive_response_headers'}:
            return
        if name.endswith('.failed'):
            self.call.reason = type(info.get('exception')).__name__
        self.call.log(name, networkAttempt=self.number)


def request_started(request):
    call = _call.get()
    if call is None:
        return None
    call.attempts += 1
    attempt = Attempt(call)
    request.extensions['glyahh_timing'] = attempt
    call.log('request_sent', retryReason=call.reason if call.attempts > 1 else 'none')
    return attempt


def response_headers(response):
    attempt = response.request.extensions.get('glyahh_timing')
    if attempt is not None:
        attempt.call.reason = f'http_{response.status_code}'
        attempt.call.log('response_headers', status=response.status_code,
                         headerWaitMs=int((time.monotonic() - attempt.started) * 1000))


def has_payload(chunk):
    message = getattr(chunk, 'message', chunk)
    return bool(getattr(message, 'content', None) or getattr(message, 'tool_call_chunks', None)
                or getattr(message, 'additional_kwargs', {}).get('reasoning_content'))


def instrument(model):
    """在 SDK 调用所在任务绑定身份，HTTP hooks 与流片段共享该身份。"""
    class TimedModel(type(model)):
        def _generate(self, *args, **kwargs):
            with model_call():
                return super()._generate(*args, **kwargs)

        async def _agenerate(self, *args, **kwargs):
            with model_call():
                return await super()._agenerate(*args, **kwargs)

        def _stream(self, *args, **kwargs):
            with model_call() as call:
                for chunk in super()._stream(*args, **kwargs):
                    if call:
                        call.observe(chunk)
                    if call and has_payload(chunk):
                        call.chunk()
                    yield chunk

        async def _astream(self, *args, **kwargs):
            with model_call() as call:
                async for chunk in super()._astream(*args, **kwargs):
                    if call:
                        call.observe(chunk)
                    if call and has_payload(chunk):
                        call.chunk()
                    yield chunk

    model.__class__ = TimedModel
    return model
