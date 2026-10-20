"""真实文件夹提交、只读检查、写入锁与内容版本；不运行导入工程。"""
import http.cookiejar
import json
import secrets
import subprocess
import time
import sys
import uuid
from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor
import urllib.error
import urllib.request
from pathlib import Path
import psycopg


def main():
    evidence = Path("temp/import-startup-business-20261004") / ("api-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE", "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))

    def client():
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = None
        def call(path, body=None, method=None, expected=20000):
            headers = {"Origin": "http://localhost:5173"}
            if csrf: headers["X-Project-Import-CSRF"] = csrf
            if isinstance(body, bytes): data = body; headers["Content-Type"] = "application/octet-stream"
            elif body is not None: data = json.dumps(body).encode(); headers["Content-Type"] = "application/json"
            else: data = None
            try: response = opener.open(urllib.request.Request("http://127.0.0.1:8124/api" + path, data=data, headers=headers, method=method or ("GET" if body is None else "POST")), timeout=45)
            except urllib.error.HTTPError as error: response = error
            with response: result = json.loads(response.read())
            if expected is None: return result
            assert result["code"] == expected, f"Unexpected business code {result['code']}"
            return result.get("data")
        account = "startup_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); csrf = call("/project/import/csrf")
        return owner, call

    try:
        owner, call = client(); other_owner, other = client()
        original = b"<html><body>first</body></html>"
        files = {"index.html": original}
        if "--python" in sys.argv:
            files.update({"backend/requirements.txt": b"fastapi\nuvicorn", "backend/app.py": b"raise RuntimeError('must not execute project')\nfrom fastapi import FastAPI\napp = FastAPI()\n"})
        if "--cancel" in sys.argv:
            files.update({f"bulk/part-{index}.bin": b"x" * (512 * 1024) for index in range(32)})
        task = call("/project/import/tasks", {"ownerId": owner, "appName": "启动只读验收", "files": [{"path": path, "size": len(content)} for path, content in files.items()], "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        assert task["status"] == "WAITING_CONFIRMATION"
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        app = committed["appId"]; path = f"/project/import/apps/{app}/startup/inspection"; body = {"ownerId": owner, "scope": "."}
        report.update(appId=app, taskId=task["taskId"], ownerId=owner)
        first = call(path, body)
        assert first["appId"] == app and first["importTaskId"] == task["taskId"] and len(first["contentHash"]) == 64
        assert any(candidate["source"] == "STATIC" for candidate in first["result"]["candidates"])
        if "--python" in sys.argv:
            candidates = [candidate for candidate in first["result"]["candidates"] if candidate["directory"] == "backend"]
            assert len(candidates) == 1 and candidates[0]["command"] == "python -m uvicorn app:app --host 0.0.0.0"
            assert candidates[0]["source"] == "HEURISTIC"
            report["checks"].append({"name": "真实 Python AST 识别，顶层异常工程未 import/执行", "status": "PASS"})
        workspace = Path("temp/code_output") / ("project_" + app)
        assert (workspace / "index.html").read_bytes() == original
        report["checks"].append({"name": "最终导入应用与只读检查绑定，原内容不改", "status": "PASS"})
        other(path, {"ownerId": other_owner, "scope": "."}, expected=40400)
        call(path, {**body, "ownerId": other_owner}, expected=40900)
        call(path, {**body, "scope": "../other"}, expected=40000)
        report["checks"].append({"name": "跨用户、会话变化、越界目录拒绝", "status": "PASS"})
        record_path = f"/project/import/apps/{app}/startup/inspections/{first['scanId']}"
        restored = call(record_path)
        assert restored["status"] == "SUCCEEDED" and restored["inspection"] == first
        other(record_path, expected=40400)
        call(f"/project/import/apps/{app}/startup/inspections/{uuid.uuid4()}", expected=40400)
        report["checks"].append({"name": "完成证据真实落库与只读找回，跨用户和未知ID拒绝", "status": "PASS"})
        if "--async" in sys.argv:
            config = call(f"/project/import/apps/{app}/startup/configuration")
            scan_path = f"/project/import/apps/{app}/startup/scans"
            payload = {"ownerId": owner, "requestId": str(uuid.uuid4()), "configRevision": config["configRevision"], "contextEpoch": config["contextEpoch"], "scope": "."}
            background = call(scan_path, payload)
            repeated = call(scan_path, payload)
            assert repeated["scanId"] == background["scanId"]
            lookup = call(f"/project/import/apps/{app}/startup/scan-requests/{payload['requestId']}")
            assert lookup["scanId"] == background["scanId"]
            other(scan_path + "/" + background["scanId"], expected=40400)
            call(scan_path, {**payload, "scope": "backend"}, expected=40900)
            deadline = time.monotonic() + 15
            while True:
                current_scan = call(scan_path + "/" + background["scanId"])
                if current_scan["status"] not in ("QUEUED", "SCANNING"): break
                assert time.monotonic() < deadline
                time.sleep(.1)
            assert current_scan["status"] == "SUCCEEDED" and current_scan["cleanup"] == "DONE"
            result = call(f"/project/import/apps/{app}/startup/inspections/{background['scanId']}")
            assert result["status"] == "SUCCEEDED" and result["inspection"]["contentHash"] == first["contentHash"]
            with psycopg.connect(dsn) as db:
                count = db.execute("SELECT count(*) FROM biz.project_import_startup_scan WHERE owner_id=%s AND request_id=%s", (owner, payload["requestId"])).fetchone()[0]
                assert count == 1
            report["checks"].append({"name": "真实后台扫描与请求找回，重复请求单任务、异输入与跨用户拒绝", "status": "PASS"})
            if "--cancel" in sys.argv:
                pending = call(scan_path, {**payload, "requestId": str(uuid.uuid4())})
                cancel_path = scan_path + "/" + pending["scanId"] + "/cancel"
                outcome = call(cancel_path, {"ownerId": owner, "revision": pending["revision"]}, expected=None)
                if outcome["code"] == 40900:
                    pending = call(scan_path + "/" + pending["scanId"])
                    outcome = call(cancel_path, {"ownerId": owner, "revision": pending["revision"]}, expected=None)
                assert outcome["code"] == 20000
                deadline = time.monotonic() + 15
                while True:
                    cancelled = call(scan_path + "/" + pending["scanId"])
                    if cancelled["status"] not in ("QUEUED", "SCANNING", "CANCELLING"): break
                    assert time.monotonic() < deadline
                    time.sleep(.1)
                assert cancelled["status"] == "CANCELLED" and cancelled["cleanup"] == "DONE"
                call(f"/project/import/apps/{app}/startup/inspections/{pending['scanId']}", expected=40400)
                assert (workspace / "index.html").read_bytes() == original
                report["checks"].append({"name": "真实后台扫描取消完成且不发布候选、不改最终应用字节", "status": "PASS"})
        if "--restart" in sys.argv:
            with (evidence / "restart.log").open("w", encoding="utf-8") as log:
                completed = subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", "scripts/restart-backend.ps1", "-TimeoutSeconds", "180", "-LogDirectory", str(evidence / "runtime")], stdout=log, stderr=subprocess.STDOUT, timeout=240, check=False)
            assert completed.returncode == 0
            restored = call(record_path)
            assert restored["status"] == "SUCCEEDED" and restored["inspection"] == first
            report["checks"].append({"name": "标准重启后真实会话找回同一完成证据，不重新识别或执行", "status": "PASS"})
        configuration_path = f"/project/import/apps/{app}/startup/configuration"
        saved_configuration = None
        if "--config" in sys.argv:
            configuration = call(configuration_path)
            assert configuration["configRevision"] == "0" and configuration["contextEpoch"] == "0"
            payload = {"ownerId": owner, "configRevision": "0", "contextEpoch": "0", "scope": ".", "services": [{"id": None, "name": "Manual", "directory": ".", "command": "unknown --fixture", "install": None, "build": None}]}
            saved_configuration = call(configuration_path, payload, method="PUT")
            assert saved_configuration["configRevision"] == "1" and saved_configuration["contextEpoch"] == "1"
            assert saved_configuration["services"][0]["id"]
            call(configuration_path, payload, method="PUT", expected=40900)
            call(configuration_path, {key: value for key, value in payload.items() if key != "configRevision"}, method="PUT", expected=40000)
            other(configuration_path, expected=40400)
            with_manual = call(path, body)
            assert with_manual["configRevision"] == "1" and with_manual["contextEpoch"] == "1"
            assert with_manual["result"]["candidates"][0]["source"] == "MANUAL"
            assert (workspace / "index.html").read_bytes() == original
            old_record = call(record_path)
            assert old_record["status"] == "STALE" and old_record["inspection"] is None
            report["checks"].append({"name": "真实配置持久化、服务端身份、CAS与版本必填、手动优先但不执行", "status": "PASS"})
        file_path = f"/app/{app}/files/content"
        current = call(file_path + "?path=index.html")
        edited = "<html><body>other</body></html>"
        save = {"path": "index.html", "content": edited, "baseVersion": current["version"]}
        key = int(app) - (1 << 63)
        with psycopg.connect(dsn, autocommit=True) as db:
            assert db.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
            try:
                call(path, body, expected=40900); call(file_path, save, expected=40900)
                call("/app/delete", {"id": app}, expected=40900)
                assert (workspace / "index.html").read_bytes() == original
            finally: assert db.execute("SELECT pg_advisory_unlock(%s)", (key,)).fetchone()[0]
        report["checks"].append({"name": "真实另一数据库会话占锁，检查和保存都拒绝且内容不变", "status": "PASS"})
        saved = call(file_path, save); assert saved["content"] == edited
        second = call(path, body); assert second["contentHash"] != first["contentHash"]
        if saved_configuration is not None:
            current_configuration = call(configuration_path)
            assert current_configuration["configRevision"] == saved_configuration["configRevision"]
            assert int(current_configuration["contextEpoch"]) > int(saved_configuration["contextEpoch"])
            assert current_configuration["services"] == saved_configuration["services"]
            assert second["contextEpoch"] == current_configuration["contextEpoch"]
            old_record = call(f"/project/import/apps/{app}/startup/inspections/{with_manual['scanId']}")
            assert old_record["status"] == "STALE" and old_record["inspection"] is None
            stale_payload = {"ownerId": owner, **{key: saved_configuration[key] for key in ("configRevision", "contextEpoch", "scope", "services")}}
            call(configuration_path, stale_payload, method="PUT", expected=40900)
            report["checks"].append({"name": "改代码撤销上下文、不丢手动配置，旧上下文不能保存", "status": "PASS"})
            current_payload = {"ownerId": owner, **{key: current_configuration[key] for key in ("configRevision", "contextEpoch", "scope", "services")}}
            with ThreadPoolExecutor(max_workers=2) as workers:
                futures = [workers.submit(call, configuration_path, current_payload, method="PUT", expected=None) for _ in range(2)]
                outcomes = [future.result() for future in futures]
            assert sorted(outcome["code"] for outcome in outcomes) == [20000, 40900]
            latest = call(configuration_path)
            assert int(latest["configRevision"]) == int(current_configuration["configRevision"]) + 1
            assert int(latest["contextEpoch"]) == int(current_configuration["contextEpoch"]) + 1
            report["checks"].append({"name": "真实同版本双保存只有一次成功，不重复递增配置", "status": "PASS"})
        disk_inspection = call(path, body)
        disk_record_path = f"/project/import/apps/{app}/startup/inspections/{disk_inspection['scanId']}"
        (workspace / "index.html").write_text("<html><body>third</body></html>", encoding="utf-8")
        try:
            disk_record = call(disk_record_path)
            assert disk_record["status"] == "STALE" and disk_record["inspection"] is None
        finally:
            (workspace / "index.html").write_text(edited, encoding="utf-8")
        disk_record = call(disk_record_path)
        assert disk_record["status"] == "STALE" and disk_record["inspection"] is None
        report["checks"].append({"name": "同大小外部磁盘改动撤销完成证据，恢复原字节不自动恢复资格", "status": "PASS"})
        call(file_path, save, expected=40900)
        assert (workspace / "index.html").read_text(encoding="utf-8") == edited
        report["checks"].append({"name": "释放后保存与识别成功，内容版本改变，旧保存版本拒绝", "status": "PASS"})
        assert (Path("temp/project_import_private") / task["taskId"] / "0.bin").read_bytes() == original
        report["checks"].append({"name": "编辑工作区不覆盖原导入保护字节", "status": "PASS"})
        assert not list(Path("temp/project_startup_private").iterdir())
        report["checks"].append({"name": "所有只读检查临时副本已清理", "status": "PASS"})
        if "--recovery" in sys.argv:
            # 仅在本脚本新建的测试应用中注入故障账本；不是实际服务崩溃验收。
            config = call(f"/project/import/apps/{app}/startup/configuration")
            now = datetime.now(timezone.utc)
            recovery_checks = []
            for cancelled, unjournaled in ((False, False), (True, False), (False, True)):
                scan_id = str(uuid.uuid4())
                snapshot = Path("temp/project_startup_private") / scan_id
                helper = Path("temp/project_startup_python") / scan_id
                snapshot.mkdir(); (snapshot / "0.bin").write_bytes(b"controlled recovery input")
                if unjournaled:
                    helper.mkdir(); (helper / "input.json").write_text("{}", encoding="utf-8")
                with psycopg.connect(dsn) as db:
                    db.execute("""INSERT INTO biz.project_import_startup_scan
                        (scan_id,request_id,app_id,owner_id,import_task_id,config_revision,context_epoch,
                         scope,request_hash,status,executor_id,executor_pid,executor_start,expires_at,
                         cleanup,cancel_requested)
                        VALUES (%s,%s,%s,%s,%s,%s,%s,'.',%s,%s,%s,2147483647,%s,%s,'UNVERIFIED',%s)""",
                        (scan_id, str(uuid.uuid4()), app, owner, task["taskId"], config["configRevision"],
                         config["contextEpoch"], "c" * 64, "STOP_FAILED" if cancelled else "SCANNING",
                         str(uuid.uuid4()), now-timedelta(minutes=2), now+timedelta(minutes=1), cancelled))
                scan_url = f"/project/import/apps/{app}/startup/scans/{scan_id}"
                other(scan_url + "/reconcile", {"ownerId": other_owner, "revision": 0}, expected=40400)
                call(scan_url + "/reconcile", {"ownerId": other_owner, "revision": 0}, expected=40900)
                deadline = time.monotonic()+15
                while True:
                    state = call(scan_url)
                    if unjournaled and state["status"] == "RECOVERY_REQUIRED": break
                    if not unjournaled and state["status"] in ("FAILED", "CANCELLED"): break
                    assert time.monotonic()<deadline
                    time.sleep(.1)
                if unjournaled:
                    assert snapshot.exists() and helper.exists() and state["cleanup"] != "DONE"
                    call(scan_url + "/reconcile", {"ownerId": owner, "revision": str(int(state["revision"])+1)}, expected=40900)
                    repeated = call(scan_url + "/reconcile", {"ownerId": owner, "revision": state["revision"]})
                    assert repeated["status"] == "RECOVERY_REQUIRED" and helper.exists()
                    recovery_checks.append({"name": "无 PID 日志但有辅助输入时保留资源与配额，不假报停止", "status": "PASS"})
                    # 已知这是本脚本创建、从未启动进程的测试输入；显式移除后再次核对。
                    (helper / "input.json").unlink(); helper.rmdir()
                    deadline = time.monotonic()+15
                    while True:
                        state = call(scan_url)
                        if state["status"] == "FAILED": break
                        assert time.monotonic()<deadline
                        time.sleep(.1)
                assert state["status"] == ("CANCELLED" if cancelled else "FAILED")
                assert state["cancelRequested"] is cancelled and state["cleanup"] == "DONE"
                assert not snapshot.exists() and not helper.exists()
                call(f"/project/import/apps/{app}/startup/inspections/{scan_id}", expected=40400)
                with psycopg.connect(dsn) as db:
                    stored = db.execute("SELECT status,cleanup,cancel_requested FROM biz.project_import_startup_scan WHERE scan_id=%s", (scan_id,)).fetchone()
                    assert stored == (state["status"], "DONE", cancelled)
                recovery_checks.append({"name": "持久取消意图恢复" if cancelled else "孤儿扫描终止、仅清任务私有输入且无成功证据", "status": "PASS", "scanId": scan_id})
            assert (workspace / "index.html").read_text(encoding="utf-8") == edited
            assert (Path("temp/project_import_private") / task["taskId"] / "0.bin").read_bytes() == original
            report["checks"].extend(recovery_checks)
            report["recoveryScope"] = "CONTROLLED_DB_FIXTURES_NOT_REAL_BACKEND_CRASH"
        if "--rules" in sys.argv:
            current_rule_inspection = call(path, body)
            with psycopg.connect(dsn) as db:
                assert db.execute("UPDATE biz.project_import_startup_inspection SET rules_version='import-startup-2026-10-04-v1' WHERE scan_id=%s AND app_id=%s AND owner_id=%s", (current_rule_inspection["scanId"], app, owner)).rowcount == 1
            old_rule_record = call(f"/project/import/apps/{app}/startup/inspections/{current_rule_inspection['scanId']}")
            assert old_rule_record["status"] == "STALE" and old_rule_record["inspection"] is None
            report["checks"].append({"name": "受控规则版本变化使完成证据失效，不返回旧候选", "status": "PASS"})
        if "--crash" in sys.argv:
            assert "--cancel" in sys.argv, "Crash verification needs the bounded binary fixture"
            config = call(f"/project/import/apps/{app}/startup/configuration")
            payload = {"ownerId": owner, "requestId": str(uuid.uuid4()), "configRevision": config["configRevision"], "contextEpoch": config["contextEpoch"], "scope": "."}
            pending = call(f"/project/import/apps/{app}/startup/scans", payload)
            scan_id = pending["scanId"]
            snapshot = Path("temp/project_startup_private") / scan_id
            deadline = time.monotonic()+10
            with psycopg.connect(dsn, autocommit=True) as db:
                while True:
                    before = db.execute("SELECT status,executor_pid,executor_start,resources_json FROM biz.project_import_startup_scan WHERE scan_id=%s AND owner_id=%s", (scan_id, owner)).fetchone()
                    if before[0] == "SCANNING" and snapshot.exists(): break
                    assert before[0] in ("QUEUED", "SCANNING") and time.monotonic()<deadline
                    time.sleep(.005)
            # 只停止账本中本扫描的 Java 执行者；操作前核对真实启动时间和进程名。
            started = before[2].astimezone(timezone.utc).isoformat()
            command = f"$p=Get-Process -Id {int(before[1])} -ErrorAction Stop; $expected=[DateTimeOffset]::Parse('{started}').UtcDateTime; if($p.ProcessName -ne 'java' -or [Math]::Abs(($p.StartTime.ToUniversalTime()-$expected).TotalMilliseconds) -gt 1){{throw 'executor identity mismatch'}}; Stop-Process -InputObject $p -Force -ErrorAction Stop"
            killed = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, text=True, timeout=20)
            (evidence / "crash-stop.log").write_text(killed.stdout+killed.stderr, encoding="utf-8")
            assert killed.returncode == 0
            with psycopg.connect(dsn) as db:
                interrupted = db.execute("SELECT status,resources_json FROM biz.project_import_startup_scan WHERE scan_id=%s", (scan_id,)).fetchone()
            # 若停止前已完成，保留失败证据，不能把完成后的重启算成扫描崩溃。
            assert interrupted[0] == "SCANNING"
            report["crashBefore"] = {"scanId": scan_id, "executorPid": before[1], "executorStart": started, "resources": interrupted[1], "status": interrupted[0]}
            # 后台启动器可能继承管道句柄；日志落文件，不让 communicate 等待服务退出。
            with (evidence / "crash-restart.log").open("w", encoding="utf-8") as restart_log:
                restarted = subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", "scripts/restart-backend.ps1", "-TimeoutSeconds", "180", "-LogDirectory", str(evidence / "runtime-after-crash")], stdout=restart_log, stderr=subprocess.STDOUT, timeout=240)
            assert restarted.returncode == 0
            deadline = time.monotonic()+20
            while True:
                state = call(f"/project/import/apps/{app}/startup/scans/{scan_id}")
                if state["status"] == "FAILED": break
                assert time.monotonic()<deadline
                time.sleep(.1)
            assert state["cleanup"] == "DONE" and state["error"] == "SCAN_INTERRUPTED"
            assert not snapshot.exists() and not (Path("temp/project_startup_python") / scan_id).exists()
            call(f"/project/import/apps/{app}/startup/inspections/{scan_id}", expected=40400)
            assert (workspace / "index.html").read_text(encoding="utf-8") == edited
            assert (Path("temp/project_import_private") / task["taskId"] / "0.bin").read_bytes() == original
            report["checks"].append({"name": "真实扫描中终止 Java 后标准重启，失败清理不自动重跑且原保护字节保留", "status": "PASS", "scanId": scan_id})
        report["status"] = "PASS"
    except Exception:
        report["status"] = "FAIL"
        raise RuntimeError("启动检查 API 验证失败，未输出凭据") from None
    finally: (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
