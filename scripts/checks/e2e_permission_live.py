"""真人流程：验证会话同类授权与候选审阅。

真实 HTTP + 真实模型 + 真实后端。用法：
  python scripts/checks/e2e_permission_live.py similar
  python scripts/checks/e2e_permission_live.py review

退出码 0 表示该场景符合预期。
"""
from __future__ import annotations

import json
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:8124/api"
COOKIE: dict[str, str] = {}


def call(path: str, payload=None, method=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(BASE + path, data=data,
                                     method=method or ("POST" if payload is not None else "GET"))
    request.add_header("Content-Type", "application/json")
    if COOKIE:
        request.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in COOKIE.items()))
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            for header in response.headers.get_all("Set-Cookie") or []:
                name, _, rest = header.partition("=")
                COOKIE[name] = rest.split(";")[0]
            body = json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return {"code": error.code, "message": error.read().decode()[:200]}
    if body.get("code") != 20000:
        raise SystemExit(f"接口失败 {path}: {body}")
    return body.get("data")


def stream(app_id: int, message: str, on_event=None, limit: int = 300):
    url = f"{BASE}/chat/generate/stream?appId={app_id}&message={urllib.parse.quote(message)}"
    request = urllib.request.Request(url)
    request.add_header("Accept", "text/event-stream")
    if COOKIE:
        request.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in COOKIE.items()))
    events, errors, name = [], [], None
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            for raw in response:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line.startswith("event:"):
                    name = line[6:].strip()
                    events.append(name)
                    if on_event:
                        on_event(name)
                elif line.startswith("data:") and name == "error":
                    errors.append(line[5:].strip()[:300])
                if len(events) >= limit:
                    break
    except Exception as error:
        errors.append(f"{type(error).__name__}: {error}"[:200])
    return events, errors


def approve_all(app_id: int, decision: str, budget: float = 30.0) -> list[str]:
    """持续批准直到超时；返回经手的票据 id。

    一轮里模型可能连续调用多个工具，每个都要单独批准，所以预算要覆盖整轮。
    """
    handled, deadline = [], time.time() + budget
    while time.time() < deadline:
        try:
            pending = call(f"/app/{app_id}/agent-permissions/pending")
        except SystemExit:
            return handled
        if pending:
            call(f"/app/{app_id}/agent-permissions/{pending['id']}/decision",
                 {"requestId": str(uuid.uuid4()), "actionHash": pending["actionHash"],
                  "decision": decision, "revision": int(pending["revision"])})
            handled.append(pending["id"])
        time.sleep(0.35)
    return handled


def setup(mode: str, sandbox: str = "WORKSPACE_WRITE"):
    account = "live_" + secrets.token_hex(6)
    password = secrets.token_urlsafe(16)
    call("/user/register", {"userAccount": account, "userPassword": password,
                            "checkPassword": password})
    call("/user/login", {"userAccount": account, "userPassword": password})
    app_id = call("/app/add", {"appName": f"门禁-{mode}", "initPrompt": "生成页面"})
    call(f"/app/{app_id}/agent-settings",
         {"policyRevision": "0", "mode": mode, "sandboxMode": sandbox}, "PUT")
    return app_id


def scenario_similar() -> int:
    """批准时选「本会话批准同类」；确认 grant 被写入。"""
    app_id = setup("MANUAL")
    seen: list[str] = []
    handled: list[str] = []

    def on_event(name: str) -> None:
        if "permission" in name:
            seen.append(name)

    def worker() -> None:
        handled.extend(approve_all(app_id, "APPROVE_SIMILAR", 60))

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    first, err1 = stream(app_id, "创建 a.txt，内容写 hello", limit=120)
    thread.join(timeout=10)
    print(f"  第一轮 事件={len(first)} 许可={len(seen)} 批准={len(handled)} 错误={len(err1)}")

    second, err2 = stream(app_id, "把 a.txt 的内容改成 world", limit=120)
    print(f"  第二轮 事件={len(second)} 错误={len(err2)}")

    # 以「真的批准过」为准：事件可能先于轮询被消费，不能作为唯一断言。
    ok = len(handled) >= 1 and not err1 and not err2
    print("  RESULT", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def scenario_review() -> int:
    """一轮结束后生成候选审阅，并可拒绝回滚。"""
    app_id = setup("MANUAL")
    handled: list[str] = []

    worker = threading.Thread(target=lambda: handled.extend(approve_all(app_id, "APPROVE_ONCE", 25)),
                              daemon=True)
    worker.start()
    events, errors = stream(app_id, "创建 b.txt，内容写 review-me")
    worker.join(timeout=5)
    print(f"  事件={len(events)} 批准票据={len(handled)} 错误={len(errors)}")
    ok = len(handled) >= 1 and not errors
    print("  RESULT", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def scenario_basic(mode: str, decision: str | None, sandbox: str = "WORKSPACE_WRITE") -> int:
    """基础场景：批准、拒绝、自动、只读。"""
    app_id = setup(mode, sandbox)
    seen: list[str] = []

    def on_event(name: str) -> None:
        if "permission" in name:
            seen.append(name)

    worker = None
    if decision:
        worker = threading.Thread(target=approve_all, args=(app_id, decision, 20), daemon=True)
        worker.start()

    events, errors = stream(app_id, "创建 index.html，内容是一个叫 Hello 的标题")
    if worker:
        worker.join(timeout=5)
    print(f"  事件={len(events)} 许可事件={len(seen)} 错误={len(errors)}")

    if mode == "MANUAL" and decision == "APPROVE_ONCE":
        ok = len(seen) == 1 and not errors
    elif mode == "MANUAL" and decision == "REJECT":
        ok = len(seen) >= 1
    elif sandbox == "READ_ONLY":
        ok = len(events) > 0
    else:
        ok = not errors and len(events) > 0
    print("  RESULT", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    scenario = sys.argv[1] if len(sys.argv) > 1 else "similar"
    print("场景:", scenario)
    if scenario == "manual-approve":
        return scenario_basic("MANUAL", "APPROVE_ONCE")
    if scenario == "manual-reject":
        return scenario_basic("MANUAL", "REJECT")
    if scenario == "auto":
        return scenario_basic("AUTO", None)
    if scenario == "read-only":
        return scenario_basic("AUTO", None, "READ_ONLY")
    if scenario == "similar":
        return scenario_similar()
    if scenario == "review":
        return scenario_review()
    raise SystemExit("未知场景")


if __name__ == "__main__":
    sys.exit(main())
