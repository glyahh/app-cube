"""仅在专用测试用户/任务中构造提交中断；不修改已有应用，不执行导入工程。"""
import argparse
import hashlib
import http.cookiejar
import json
import secrets
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from datetime import datetime
import psycopg


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--live-pid", required=True, type=int)
    parser.add_argument("--live-start", required=True)
    args = parser.parse_args()
    evidence = Path(args.output).resolve(); evidence.mkdir(parents=True, exist_ok=False)
    root = Path.cwd(); output = (root / "temp/code_output").resolve()
    dsn = next((line.split("=", 1)[1].strip().strip('"').strip("'")
                for line in (root / ".env").read_text(encoding="utf-8-sig").splitlines()
                if line.strip().startswith("AGENT_POSTGRES_DSN=")), None)
    if not dsn:
        raise RuntimeError("缺少已配置的测试数据库连接；未修改配置。")
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    checks, fixtures = [], []
    report = {"status": "RUNNING", "checks": checks, "fixtures": fixtures, "fullRecoveryAcceptance": "NOT_RUN"}
    csrf = None

    def call(path, data=None, method=None, expected=20000):
        headers = {"Origin": "http://localhost:5173"}
        if csrf: headers["X-Project-Import-CSRF"] = csrf
        if isinstance(data, bytes): body = data; headers["Content-Type"] = "application/octet-stream"
        elif data is None: body = None
        else: body = json.dumps(data).encode(); headers["Content-Type"] = "application/json"
        request = urllib.request.Request("http://127.0.0.1:8124/api" + path, data=body, headers=headers, method=method or ("POST" if data is not None else "GET"))
        try: response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as error: response = error
        with response: actual = json.loads(response.read())
        if actual.get("code") != expected: raise RuntimeError(f"{path}: code={actual.get('code')}, expected={expected}")
        checks.append({"path": path, "businessCode": actual["code"], "status": "PASS"})
        return actual.get("data")

    def static_closed(app_id):
        try:
            response = opener.open(f"http://127.0.0.1:8124/api/static/project_{app_id}/index.html", timeout=10)
        except urllib.error.HTTPError as error: response = error
        with response:
            if response.status != 404: raise RuntimeError("未提交内容可被读取。")
        checks.append({"name": "pending-static-closed", "appId": str(app_id), "status": "PASS"})

    account = "import_recovery_" + secrets.token_hex(8)
    password = secrets.token_urlsafe(24)
    owner = int(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
    call("/user/login", {"userAccount": account, "userPassword": password})
    csrf = call("/project/import/csrf")
    report["user"] = {"ownerId": str(owner), "account": account, "passwordRecorded": False}
    content = b"<meta charset=utf-8><h1>known recovery fixture</h1>"
    try:
        with psycopg.connect(dsn) as database:
            for scenario in ["dead-executor-files-moved", "damaged-files", "live-executor"]:
                created = call("/project/import/tasks", {"ownerId": str(owner), "appName": "恢复验证-" + scenario,
                    "files": [{"path": "index.html", "size": len(content)}], "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}})
                task_id = created["taskId"]
                ready = call(f"/project/import/tasks/{task_id}/files/0?revision=0", content, "PUT")
                app_id = 1_000_000_000_000_000_000 + secrets.randbelow(500_000_000_000_000_000)
                target = output / ("project_" + str(app_id))
                assert target.resolve().is_relative_to(output) and not target.exists()
                with database.transaction():
                    row = database.execute("SELECT status,revision,content_hash FROM biz.project_import_task WHERE task_id=%s AND owner_id=%s FOR UPDATE", (uuid.UUID(task_id), owner)).fetchone()
                    assert row and row[0] == "WAITING_CONFIRMATION" and row[2] == ready["contentHash"]
                    assert database.execute("SELECT count(*) FROM biz.app WHERE id=%s", (app_id,)).fetchone()[0] == 0
                    database.execute("UPDATE biz.project_import_task SET status='COMMITTING',revision=revision+1,executor_id='controlled-fault-fixture' WHERE task_id=%s AND owner_id=%s", (uuid.UUID(task_id), owner))
                    database.execute("INSERT INTO biz.project_import_publication(task_id,app_id,owner_id,state,content_hash,executor_pid,executor_start) VALUES (%s,%s,%s,%s,%s,%s,%s)",
                        (uuid.UUID(task_id), app_id, owner, "FILES_READY" if scenario == "damaged-files" else "PUBLISHING", ready["contentHash"], args.live_pid if scenario == "live-executor" else 999999999, args.live_start))
                database.commit()
                target.mkdir()
                (target / "index.html").write_bytes(b"damaged" if scenario == "damaged-files" else content)
                fixture = {"scenario": scenario, "taskId": task_id, "appId": str(app_id), "ownerId": str(owner), "journalInjection": True}
                fixtures.append(fixture)
                static_closed(app_id)
                current = call(f"/project/import/tasks/{task_id}")
                assert current["status"] == "RECOVERY_REQUIRED"
                confirmation = {"revision": current["revision"], "contentHash": current["contentHash"]}
                if scenario == "live-executor":
                    call(f"/project/import/tasks/{task_id}/recover", confirmation, expected=40900)
                    with database.transaction():
                        database.execute("UPDATE biz.project_import_publication SET executor_pid=999999999 WHERE task_id=%s AND owner_id=%s AND state='PUBLISHING'", (uuid.UUID(task_id), owner))
                    database.commit()
                elif scenario == "damaged-files":
                    call(f"/project/import/tasks/{task_id}/recover", confirmation, expected=50001)
                    static_closed(app_id)
                    assert database.execute("SELECT count(*) FROM biz.app WHERE id=%s", (app_id,)).fetchone()[0] == 0
                    database.commit()
                    (target / "index.html").write_bytes(content)
                    current = call(f"/project/import/tasks/{task_id}")
                    confirmation["revision"] = current["revision"]
                recovered = call(f"/project/import/tasks/{task_id}/recover", confirmation)
                assert str(recovered["appId"]) == str(app_id)
                assert hashlib.sha256((target / "index.html").read_bytes()).digest() == hashlib.sha256(content).digest()
                assert call(f"/project/import/tasks/{task_id}")["status"] == "SUCCEEDED"
                assert database.execute("SELECT user_id,app_name FROM biz.app WHERE id=%s", (app_id,)).fetchone() == (owner, "恢复验证-" + scenario)
                database.commit()
                call(f"/app/{app_id}/runtime/start", method="POST", expected=50001)
                call("/app/deploy", {"appId": str(app_id)}, expected=50001)
                if scenario == "dead-executor-files-moved":
                    before_edit = call(f"/app/{app_id}/files/content?path=index.html")
                    after_edit = call(f"/app/{app_id}/files/content", {"path": "index.html", "content": "legitimate new workspace version", "baseVersion": before_edit["version"]})
                    assert after_edit["version"] != before_edit["version"]
                    assert call(f"/project/import/tasks/{task_id}/result")["contentHash"] == ready["contentHash"]
                    assert (root / "temp/project_import_private" / task_id / "0.bin").read_bytes() == content
                    fixture["postImportEditPreservesSourceIdentity"] = True
                    # 只在本脚本专用应用上模拟删除，核对历史任务不能再返回可用结果。
                    try:
                        with database.transaction():
                            database.execute("UPDATE biz.app SET is_delete=1 WHERE id=%s AND user_id=%s", (app_id, owner))
                        database.commit()
                        call(f"/project/import/tasks/{task_id}/result", expected=40900)
                        static_closed(app_id)
                    finally:
                        with database.transaction():
                            database.execute("UPDATE biz.app SET is_delete=0 WHERE id=%s AND user_id=%s", (app_id, owner))
                        database.commit()
                    assert str(call(f"/project/import/tasks/{task_id}/result")["appId"]) == str(app_id)
                    fixture["deletedAppCannotReplaySuccess"] = True
                fixture["status"] = "PASS"; fixture["retainedSuccessfulApplication"] = True
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["errorType"] = type(error).__name__
        raise
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": len(checks), "evidence": str(evidence)}))


if __name__ == "__main__": main()
