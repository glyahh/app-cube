"""最终HTTP请求审查必须覆盖同步和异步客户端，且不串任务。"""
import asyncio
import httpx
import pytest
from core.model import _audit_request, _audit_async_request, model_request_audit


@pytest.mark.parametrize('asynchronous', [False, True])
def test_final_request_hook_rejects_secret_before_transport(asynchronous):
    sent = []
    observed = []
    def transport(request):
        sent.append(request)
        return httpx.Response(200, json={})
    def audit(request):
        observed.append(request.content)
        if b'test-secret' in request.content:
            raise ValueError('SECRET_REJECTED')
    token = model_request_audit.set(audit)
    try:
        if asynchronous:
            async def run():
                async with httpx.AsyncClient(transport=httpx.MockTransport(transport),
                        event_hooks={'request': [_audit_async_request]}) as client:
                    await client.post('https://fixture/model', json={'message': 'safe'})
                    with pytest.raises(ValueError, match='SECRET_REJECTED'):
                        await client.post('https://fixture/model', json={'message': 'test-secret'})
            asyncio.run(run())
        else:
            with httpx.Client(transport=httpx.MockTransport(transport),
                    event_hooks={'request': [_audit_request]}) as client:
                client.post('https://fixture/model', json={'message': 'safe'})
                with pytest.raises(ValueError, match='SECRET_REJECTED'):
                    client.post('https://fixture/model', json={'message': 'test-secret'})
        assert len(sent) == 1 and len(observed) == 2
    finally:
        model_request_audit.reset(token)
    _audit_request(httpx.Request('POST', 'https://fixture/model', content=b'test-secret'))
    assert len(observed) == 2
