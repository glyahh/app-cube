"""仅供受管 Git；秘密协议 stdout 不接通用日志，不使用宿主凭据。"""
import os
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit


def main():
    endpoint = os.environ.get("IMPORT_ASKPASS_ENDPOINT", "")
    capability = os.environ.get("IMPORT_ASKPASS_CAPABILITY", "")
    uri = urlsplit(endpoint)
    if (len(sys.argv) != 2 or uri.scheme != "http" or uri.hostname != "127.0.0.1"
            or uri.path != "/credential" or uri.query or uri.fragment or uri.username or not capability):
        return 1
    prompt = sys.argv[1].encode("utf-8")
    if len(prompt) > 4096:
        return 1
    # 专用回环通道不继承系统 HTTP 代理，不跳转其他地址。
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, message, headers, new_url):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(endpoint, data=prompt, method="POST", headers={"X-Import-Capability": capability})
    try:
        with opener.open(request, timeout=10) as response:
            value = response.read(4097)
            if response.status != 200 or not value or len(value) > 4096 or any(c < 33 or c > 126 for c in value):
                return 1
            # Git 的专用 askpass 协议，禁止在调用侧合并到通用事件或日志。
            sys.stdout.buffer.write(value + b"\n")
            sys.stdout.buffer.flush()
        return 0
    except Exception:
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
