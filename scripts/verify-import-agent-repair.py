"""第一项 I1-D：导入后的真实 Agent 修复闭环（E04/E05）。

受控夹具是一个返回错误字段值的小服务。流程：
1. 导入 → 确认计划 → 运行 → 用真实 HTTP 复现缺陷（返回 pang）；
2. 停止服务，用正式 SSE 通道把缺陷交给主 Agent，由真实模型决定并执行最小修改；
3. 读回文件核对实际改动，重新确认计划并运行，用真实 HTTP 验证修复后的业务结果；
4. 干净重放：从同一来源重新导入，只重放该补丁，再运行验证同样结果。

不使用私有仓库；只调用一次真实模型；不改平台源码。
"""

import http.cookiejar
import json
import pathlib
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1:8124/api"
WSL = ["wsl.exe", "-d", "Ubuntu", "--exec"]

BUGGY = ("from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n"
         "class Handler(BaseHTTPRequestHandler):\n"
         "    def do_GET(self):\n"
         "        body = b'{\"reply\":\"pang\"}'\n"
         "        self.send_response(200)\n"
         "        self.send_header('Content-Type', 'application/json')\n"
         "        self.send_header('Content-Length', str(len(body)))\n"
         "        self.end_headers()\n"
         "        self.wfile.write(body)\n"
         "    def log_message(self, fmt, *args):\n"
         "        return\n"
         "ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()\n")
DECLARATION = {"processes": [{"name": "api", "command": "python3 app.py", "port": 8080}]}
FIXTURE = {
    "app.py": BUGGY.encode(),
    ".glyahh/run.json": json.dumps(DECLARATION).encode(),
    "README.md": "[受控夹具] 缺陷修复闭环用的小服务，不是用户工程。\n".encode(),
}
PROMPT = "app.py 里接口返回的 reply 字段应该是 pong，现在返回的是 pang。请只做这一处最小修改，不要改其他行为。"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.URLError("夹具请求不允许跳转")


LOOPBACK = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def fetch(address):
    parsed = urllib.parse.urlsplit(address)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost") or not parsed.port:
        raise AssertionError("只允许访问本机回环地址")
    if parsed.query or parsed.fragment or not parsed.path.startswith("/") or ".." in parsed.path:
        raise AssertionError("夹具请求无效")
    with LOOPBACK.open(address, timeout=10) as response:
        return response.status, response.read().decode("utf-8", "replace")


def main() -> int:
    evidence = REPO / "temp" / ("import-agent-repair-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "evidence": str(evidence), "fixture": "CONTROLLED_DEFECT"}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}
    apps: list[str] = []
    owner = ""

    def check(group, name, ok, detail=""):
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": str(detail)[:400]})
        if not ok:
            raise AssertionError(group + " " + name + " " + str(detail)[:400])

    def call(path, body=None, method=None, expected=20000, timeout=300):
        headers = {"Origin": "http://localhost:5173"}
        if csrf["value"]:
            headers["X-Project-Import-CSRF"] = csrf["value"]
        if isinstance(body, bytes):
            data = body
            headers["Content-Type"] = "application/octet-stream"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        else:
            data = None
        try:
            response = opener.open(urllib.request.Request(BASE + path, data=data, headers=headers,
                                                          method=method or ("GET" if body is None else "POST")), timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.loads(response.read())
        if expected is not None and result.get("code") != expected:
            raise AssertionError(f"{path} -> {result.get('code')} {result.get('message')}")
        return result.get("data")

    def import_app(name):
        task = call("/project/import/tasks", {"ownerId": owner, "appName": name,
                                             "files": [{"path": p, "size": len(c)} for p, c in FIXTURE.items()],
                                             "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(FIXTURE.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        apps.append(str(committed["appId"]))
        return str(committed["appId"]), task["taskId"]

    def plan_for(app):
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        declared = [row for row in inspection["result"]["candidates"] if row.get("source") == "DECLARED"]
        if not declared:
            raise AssertionError("声明式候选缺失：" + json.dumps(inspection["result"], ensure_ascii=False)[:200])
        plan = call(f"/project/import/apps/{app}/startup/plans",
                    {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": [declared[0]["id"]]})
        return call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}/confirm",
                    {"ownerId": owner, "planRevision": plan["planRevision"], "aggregateAcknowledged": False})

    def prep(app, plan, kind, approved=None):
        body = {"ownerId": owner, "planId": plan["planId"], "planRevision": plan["planRevision"]}
        if kind == "install":
            body["approved"] = approved
        return call(f"/project/import/apps/{app}/startup/preparations/{kind}", body)

    def read_file(app, path):
        return call(f"/app/{app}/files/content?path={urllib.parse.quote(path)}")["content"]

    def containers(app):
        listed = subprocess.run(WSL + ["docker", "ps", "-a", "--filter", "label=glyahh.import.app=" + app,
                                       "--format", '{{.ID}} {{.Label "glyahh.import.execution"}}'],
                                capture_output=True, timeout=60)
        rows = [row.split() for row in listed.stdout.decode().splitlines() if row.strip()]
        return "\n".join(row[0] for row in rows if len(row) > 1 and row[1] != "probe")

    def ask_agent(app, message, deadline_seconds=900):
        """走正式 SSE 通道把消息交给主 Agent，收集事件直到 done。"""
        url = BASE + "/chat/generate/stream?" + urllib.parse.urlencode({"appId": app, "message": message})
        request = urllib.request.Request(url, headers={"Origin": "http://localhost:5173", "Accept": "text/event-stream"})
        events = []
        with opener.open(request, timeout=deadline_seconds) as response:
            current = {}
            for raw in response:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line.startswith("event:"):
                    current["event"] = line.split(":", 1)[1].strip()
                elif line.startswith("data:"):
                    current["data"] = line.split(":", 1)[1].strip()
                elif line == "" and current:
                    events.append(current)
                    if current.get("event") == "done":
                        break
                    current = {}
        return events

    try:
        account = "repair_" + secrets.token_hex(8)
        password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")

        # 1. 复现缺陷
        app, app_task = import_app("缺陷修复夹具")
        plan = plan_for(app)
        started = prep(app, plan, "run")
        service = started["services"][0]
        check("E04", "导入副本上真实复现缺陷业务结果",
              service["state"] == "READY" and '"reply":"pang"' in fetch(service["address"])[1],
              json.dumps(service, ensure_ascii=False)[:200])
        prep(app, plan, "stop")
        check("E04", "复现后服务已停止、容器消失", containers(app) == "", containers(app))
        before = read_file(app, "app.py")

        # 2. 真实 Agent 最小修复
        events = ask_agent(app, PROMPT)
        report["agentEvents"] = [{"event": row.get("event"), "data": row.get("data", "")[:160]} for row in events]
        completed = [row for row in events if row.get("event") == "run.completed"]
        failed = [row for row in events if row.get("event") == "error"]
        check("E04", "真实 Agent 运行完成且没有错误事件",
              bool(completed) and not failed and any(row.get("event") == "done" for row in events),
              json.dumps(report["agentEvents"][-3:], ensure_ascii=False)[:300])
        after = read_file(app, "app.py")
        check("E04", "Agent 真实修改了导入副本，只改缺陷字段值",
              "pong" in after and "pang" not in after and after.replace("pong", "pang") == before.replace("pong", "pang"),
              f"before={before[:60]!r} after={after[:60]!r}")

        # 3. 版本化回归：内容变化使旧计划失效，重新确认后运行验证修复结果
        stale = call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}")
        check("E04", "源码变化使原运行计划失效", stale["state"] == "STALE", stale["state"])
        renewed = plan_for(app)
        started = prep(app, renewed, "run")
        service = started["services"][0]
        status, body = fetch(service["address"])
        check("E04", "修复后真实业务结果正确", service["state"] == "READY" and status == 200 and '"reply":"pong"' in body,
              f"{status} {body[:80]}")
        prep(app, renewed, "stop")

        # 4. 干净重放：重新导入同一来源，只重放该补丁
        patch = "".join(__import__("difflib").unified_diff(before.splitlines(True), after.splitlines(True), fromfile="a/app.py", tofile="b/app.py"))
        (evidence / "repair.patch").write_text(patch, encoding="utf-8")
        replay, replay_task = import_app("干净重放夹具")
        content = call(f"/app/{replay}/files/content?path=app.py")
        check("E05", "重新导入的应用初始仍是缺陷原样（上一个应用的编辑未污染来源）",
              '"reply":"pang"' in content["content"] and "pong" not in content["content"], content["content"][:80])
        call(f"/app/{replay}/files/content", {"path": "app.py", "content": after, "baseVersion": content["version"]})
        replay_plan = plan_for(replay)
        started = prep(replay, replay_plan, "run")
        service = started["services"][0]
        status, body = fetch(service["address"])
        check("E05", "干净来源重建并重放补丁后得到同样业务结果",
              service["state"] == "READY" and status == 200 and '"reply":"pong"' in body, f"{status} {body[:80]}")
        prep(replay, replay_plan, "stop")
        check("E05", "重放验证后容器全部消失", containers(replay) == "", containers(replay))
        staged = REPO / "temp" / "project_import_private" / replay_task / "0.bin"
        check("E05", "来源暂存上传件逐字节未变（Agent 编辑只写副本，不回写来源）",
              staged.is_file() and staged.read_bytes() == BUGGY.encode(), str(staged))

        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = str(error)[:500]
    finally:
        for app in apps:
            try:
                call("/app/delete", {"id": app})
            except Exception:
                report.setdefault("cleanupErrors", []).append(app)
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        for row in report["checks"]:
            print(row["status"], row["group"], row["name"], "|", row["detail"][:150])
        print("status:", report["status"], report.get("error", ""))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
