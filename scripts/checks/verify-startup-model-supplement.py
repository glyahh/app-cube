"""第一项 I1-D：三层识别的真实模型补充验收（只跑 S05，不涉及私有仓库）。

受控夹具是"固定扫描与算法识别都识别不出启动方式"的目录（有 package.json 但没有 scripts），
只有取得用户批准后才调用一次真实模型补充候选。验证：
- 前两层留下未识别范围；
- 批准后真实模型返回有依据的候选，来源标 AGENT、目录与命令正确；
- 补充过程不安装、不启动任何服务；
- 不输出模型密钥，不打印原始模型文本。
"""

import http.cookiejar
import json
import pathlib
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

REPO = pathlib.Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8124/api"

MYSTERY = {
    "api/package.json": b'{"name":"api","packageManager":"npm@10.0.0"}\n',
    "api/server.js": b'const http=require("http"); http.createServer(function(req,res){res.end("ok");}).listen(8080);\n',
    "api/README.md": "启动命令：node server.js\n".encode(),
}


def main() -> int:
    evidence = REPO / "temp" / ("import-startup-model-supplement-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "evidence": str(evidence), "fixture": "CONTROLLED_NO_SCRIPTS"}
    key = ""
    for line in (REPO / ".env").read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("AGENT_MODEL_API_KEY="):
            key = line.split("=", 1)[1].strip().strip('"').strip("'")

    def redact(text):
        return text.replace(key, "***") if key else text

    def check(group, name, ok, detail=""):
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": redact(str(detail))[:400]})
        if not ok:
            raise AssertionError(group + " " + name + " " + redact(str(detail))[:400])

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}

    def call(path, body=None, method=None, expected=20000, timeout=180):
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
            raise AssertionError(redact(f"{path} -> {result.get('code')} {result.get('message')}"))
        return result.get("data")

    app = None
    try:
        account = "model_" + secrets.token_hex(8)
        password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")

        task = call("/project/import/tasks", {"ownerId": owner, "appName": "模型补充夹具",
                                             "files": [{"path": p, "size": len(c)} for p, c in MYSTERY.items()],
                                             "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(MYSTERY.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        app = committed["appId"]

        config = call(f"/project/import/apps/{app}/startup/configuration")
        call(f"/project/import/apps/{app}/startup/configuration",
             {"ownerId": owner, "configRevision": int(config["configRevision"]), "contextEpoch": int(config["contextEpoch"]),
              "scope": ".", "services": []}, method="PUT")
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        scopes = inspection["result"].get("scopes") or []
        unidentified = [row for row in scopes if row.get("directory") == "api" and row.get("status") == "UNIDENTIFIED"]
        check("S05", "固定扫描与算法识别后仍留下未识别目录，且未安装/未启动",
              bool(unidentified) and not inspection.get("servicesStarted", False), json.dumps(scopes, ensure_ascii=False)[:300])

        approval = call(f"/project/import/apps/{app}/startup/approvals",
                        {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"],
                         "directories": ["api"], "approved": True})
        deadline = time.monotonic() + 170
        while approval["state"] in ("APPROVED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(2)
            approval = call(f"/project/import/apps/{app}/startup/approvals/{approval['approvalId']}")

        current = call(f"/project/import/apps/{app}/startup/inspections/{inspection['scanId']}")
        published = (current.get("inspection") or current).get("result") or {}
        rows = [row for row in published.get("candidates") or [] if row.get("source") == "AGENT" and row.get("directory") == "api"]
        report["approval"] = {"state": approval["state"], "error": redact(str(approval.get("error")))[:200]}
        report["agentCandidates"] = rows
        check("S05", "真实模型补充给出有依据的候选（source=AGENT，含目录与命令）",
              approval["state"] == "SUCCEEDED" and bool(rows) and all(row.get("command") for row in rows),
              approval["state"] + " " + json.dumps(rows, ensure_ascii=False)[:300])
        check("S05", "补充过程只读：不安装、不启动服务", not current.get("servicesStarted", False), str(current.get("servicesStarted")))

        call("/app/delete", {"id": str(app)})
        app = None
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = redact(str(error))[:400]
    finally:
        if app:
            try:
                call("/app/delete", {"id": str(app)})
            except Exception:
                report["cleanupFailed"] = True
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        for row in report["checks"]:
            print(row["status"], row["group"], row["name"], "|", row["detail"][:160])
        print("status:", report["status"], report.get("error", ""))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
