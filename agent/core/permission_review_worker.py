"""独立审查常驻入口：只复用依赖，不复用消息、工具或审查结果。"""
from __future__ import annotations

import json
import logging
import os
import sys
import threading
from contextlib import redirect_stderr, redirect_stdout
from uuid import UUID

from core.permission_review import PROCESS_TIMEOUT_SECONDS, MAX_OUTPUT_BYTES, _expire, review
from core.model_profiles import strict_json

MAX_FRAME_BYTES = 256 * 1024 + 256


def preload():
    from core.model import make_review_model
    from core.permission_review import bounded_review_client
    from core.settings import load_settings
    from core.model_profiles import select_profile
    from env_utils import get_env

    settings = load_settings()
    selected = select_profile(settings, get_env('AGENT_REVIEW_MODEL_PROFILE_ID', '').strip() or 'default')
    with bounded_review_client(None) as client:
        # 不发送请求；启动时完成 SDK 及模型类的加载。
        make_review_model(selected, timeout=60, max_output_tokens=2048, http_client=client)


def _write(sink, value, limit):
    data = json.dumps(value, ensure_ascii=True, separators=(',', ':'), allow_nan=False).encode('ascii') + b'\n'
    if len(data) > limit:
        raise ValueError('REVIEW_OUTPUT_LIMIT')
    sink.write(data)
    sink.flush()


def serve(source, sink, *, reviewer=review, preload=preload):
    with open(os.devnull, 'w') as quiet, redirect_stdout(quiet), redirect_stderr(quiet):
        preload()
    _write(sink, {'type': 'ready', 'protocol': 1}, MAX_OUTPUT_BYTES)
    while True:
        raw = source.readline(MAX_FRAME_BYTES + 1)
        if not raw:
            return 0
        request_id = None
        timer = None
        try:
            if len(raw) > MAX_FRAME_BYTES or not raw.endswith(b'\n'):
                raise ValueError('REVIEW_FRAME_LIMIT')
            frame = strict_json(raw.decode('utf-8'))
            if type(frame) is not dict or set(frame) != {'requestId', 'input'}:
                raise ValueError('REVIEW_FRAME_INVALID')
            request_id = str(UUID(frame['requestId']))
            if request_id != frame['requestId']:
                raise ValueError('REVIEW_ID_INVALID')
            timer = threading.Timer(PROCESS_TIMEOUT_SECONDS, _expire)
            timer.daemon = True
            timer.start()
            timing = {}
            with open(os.devnull, 'w') as quiet, redirect_stdout(quiet), redirect_stderr(quiet):
                result = reviewer(json.dumps(frame['input'], ensure_ascii=False, allow_nan=False).encode('utf-8'), metrics=timing)
            # 结果本身仍受原来的序列化上限约束，信封仅增加固定身份字段。
            if len(json.dumps(result, ensure_ascii=True).encode('ascii')) + 1 > MAX_OUTPUT_BYTES:
                raise ValueError('REVIEW_OUTPUT_LIMIT')
            _write(sink, {'requestId': request_id, 'result': result, 'timing': timing}, MAX_OUTPUT_BYTES + 256)
            del frame, result, timing, raw
        except Exception:
            if request_id is not None:
                _write(sink, {'requestId': request_id, 'error': 'PERMISSION_REVIEW_FAILED'}, MAX_OUTPUT_BYTES)
            return 2
        finally:
            if timer is not None:
                timer.cancel()


if __name__ == '__main__':
    logging.disable(logging.CRITICAL)
    raise SystemExit(serve(sys.stdin.buffer, sys.stdout.buffer))
