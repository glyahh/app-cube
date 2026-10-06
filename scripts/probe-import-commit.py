"""量化文件夹导入提交的失败率与阶段。

连续创建 N 次小夹具导入（真实 API、真实文件、独立应用），记录每次提交结果；
失败时从后端日志抓 stage/type。不执行导入工程，不调用模型，不访问私有仓库。
"""

import http.cookiejar
import json
import pathlib
import secrets
import sys
import time
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1:8124/api"
BACKEND_LOG = REPO / "temp/logs/backend/backend.stdout.log"


def main() -> int:
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    evidence = REPO / "temp" / ("import-commit-probe-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "rounds": rounds, "results": [], "evidence": str(evidence)}

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}

    def call(path, body=None, method=None, expected=20000, timeout=120):
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
        if expected is not None and result["code"] != expected:
            raise AssertionError(f"{path} -> {result.get('code')} {result.get('message')}")
        return result

    account = "probe_" + secrets.token_hex(8)
    password = secrets.token_urlsafe(24)
    owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password})["data"])
    call("/user/login", {"userAccount": account, "userPassword": password})
    csrf["value"] = call("/project/import/csrf")["data"]

    log_offset = BACKEND_LOG.stat().st_size if BACKEND_LOG.exists() else 0
    apps = []
    for index in range(rounds):
        files = {"index.html": f"<html><body>probe {index}</body></html>\n".encode(),
                 "tools/ok.sh": b"echo probe ok\n"}
        try:
            task = call("/project/import/tasks", {"ownerId": owner, "appName": f"提交探针 {index}",
                                                 "files": [{"path": p, "size": len(c)} for p, c in files.items()],
                                                 "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})["data"]
            for position, content in enumerate(files.values()):
                task = call(f"/project/import/tasks/{task['taskId']}/files/{position}?revision={task['revision']}", content, method="PUT")["data"]
            outcome = call(f"/project/import/tasks/{task['taskId']}/commit",
                           {"revision": task["revision"], "contentHash": task["contentHash"]}, expected=None)
            ok = outcome["code"] == 20000
            entry = {"round": index, "ok": ok, "code": outcome["code"], "taskId": task["taskId"]}
            if ok:
                apps.append(outcome["data"]["appId"])
                entry["appId"] = outcome["data"]["appId"]
        except Exception as error:
            entry = {"round": index, "ok": False, "error": f"{type(error).__name__}:{error}"[:200]}
        report["results"].append(entry)
        print(entry)

    # 抓取本轮后端日志里的发布失败阶段
    stages = []
    if BACKEND_LOG.exists():
        raw = BACKEND_LOG.read_bytes()[log_offset:]
        for line in raw.split(b"\n"):
            if b"\xe5\xaf\xbc\xe5\x85\xa5\xe5\x8f\x91\xe5\xb8\x83" in line:
                stages.append(line.decode("utf-8", "replace").strip()[-160:])
    report["backendStages"] = stages
    report["failures"] = sum(1 for row in report["results"] if not row["ok"])
    report["status"] = "DONE"
    (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"失败 {report['failures']}/{rounds}；阶段：{stages}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
