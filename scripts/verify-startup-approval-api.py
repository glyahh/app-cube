"""第一项步骤1：真实后端的识别批准/拒绝、找回、取消与过期/重启对账。不发布候选，不运行导入工程。"""
import http.cookiejar
import json
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
import psycopg

MODEL_ROOT = Path("temp/project_startup_model")
ACTIVE = ("APPROVED", "RUNNING", "CANCELLING")


def main():
    evidence = Path("temp/import-startup-approval-flow-20261005") / ("api-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE", "s05": "NOT_CLAIMED", "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    rules = re.search(r'RULES_VERSION="([^"]+)"', Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupInspectionRepository.java").read_text(encoding="utf-8")).group(1)

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
            assert result["code"] == expected, f"Unexpected business code {result['code']} for {path}"
            return result.get("data")
        account = "approval_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); csrf = call("/project/import/csrf")
        return owner, call

    def wait(call, url, done, seconds=20):
        deadline = time.monotonic() + seconds
        while True:
            state = call(url)
            if done(state): return state
            assert time.monotonic() < deadline, f"timeout waiting for {url}: {state['state']}"
            time.sleep(.1)

    def request_hash(owner, app, inspection, directories, approved):
        # 与 ImportStartupApprovalRepository.requestHash 的冻结字段和 Jackson 紧凑序列化一致。
        # 后端 ObjectMapper 可能把 long 写成字符串；以真实拒绝行的已存哈希确定编码，不猜。
        import hashlib
        def digest(number, values):
            payload = [number(owner), number(app), inspection["scanId"], inspection["importTaskId"], inspection["contentHash"],
                       number(inspection["configRevision"]), number(inspection["contextEpoch"]), rules, values[0], values[1]]
            return hashlib.sha256(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        number = next(n for n in (int, lambda v: str(int(v))) if digest(n, (["worker"], False)) == stored_reject_hash[0])
        return digest(number, (directories, approved))

    stored_reject_hash = []
    try:
        owner, call = client(); other_owner, other = client()
        files = {"index.html": b"<html><body>approval</body></html>",
                 "worker/package.json": json.dumps({"name": "worker", "packageManager": "npm@10.0.0", "scripts": {}}).encode()}
        task = call("/project/import/tasks", {"ownerId": owner, "appName": "识别批准验收", "files": [{"path": p, "size": len(c)} for p, c in files.items()], "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        app = committed["appId"]; report.update(appId=app, taskId=task["taskId"], ownerId=owner)
        workspace = Path("temp/code_output") / ("project_" + app)
        call(f"/project/import/apps/{app}/startup/configuration")
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        assert any(s["directory"] == "worker" and s["status"] == "UNIDENTIFIED" for s in inspection["result"]["scopes"])
        base = f"/project/import/apps/{app}/startup/approvals"

        # 1. 拒绝：真实落库、幂等找回、跨用户不可见、不产生执行租约。
        reject = {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["worker"], "approved": False}
        rejected = call(base, reject); repeated = call(base, reject)
        assert rejected["state"] == "REJECTED" and repeated["approvalId"] == rejected["approvalId"]
        assert call(f"/project/import/apps/{app}/startup/approval-requests/{reject['requestId']}")["approvalId"] == rejected["approvalId"]
        other(f"{base}/{rejected['approvalId']}", expected=40400)
        call(base, {**reject, "approved": True}, expected=40900)
        call(base, {**reject, "requestId": str(uuid.uuid4()), "directories": ["."]}, expected=40000)
        call(base, {**reject, "ownerId": other_owner}, expected=40900)
        with psycopg.connect(dsn) as db:
            row = db.execute("SELECT count(*),min(decision),min(state) FROM biz.project_import_startup_approval WHERE owner_id=%s AND request_id=%s", (owner, reject["requestId"])).fetchone()
            leases = db.execute("SELECT count(*) FROM biz.project_import_startup_model_execution WHERE approval_id=%s", (rejected["approvalId"],)).fetchone()[0]
            stored_reject_hash.append(db.execute("SELECT request_hash FROM biz.project_import_startup_approval WHERE approval_id=%s", (rejected["approvalId"],)).fetchone()[0])
        assert row == (1, "REJECT", "REJECTED") and leases == 0
        report["checks"].append({"name": "真实拒绝行落库、重复请求同一行、异输入/越界目录/会话变化拒绝、无执行租约", "status": "PASS", "approvalId": rejected["approvalId"]})

        # 2. 批准后立即取消：先撤销版本，再确认独占进程和私有输入退出。
        approve = {**reject, "requestId": str(uuid.uuid4()), "approved": True}
        approved = call(base, approve)
        assert approved["approved"] and approved["state"] in ("APPROVED", "RUNNING", "SUCCEEDED", "FAILED")
        url = f"{base}/{approved['approvalId']}"
        outcome = None
        for _ in range(5):
            current = call(url)
            if current["state"] not in ("APPROVED", "RUNNING"): break
            outcome = call(url + "/cancel", {"ownerId": owner, "revision": int(current["revision"])}, expected=None)
            if outcome["code"] == 20000: break
            assert outcome["code"] == 40900
        final = wait(call, url, lambda s: s["state"] not in ACTIVE, 120)
        with psycopg.connect(dsn) as db:
            lease = db.execute("SELECT cleanup,jsonb_array_length(resources_json) FROM biz.project_import_startup_model_execution WHERE approval_id=%s", (approved["approvalId"],)).fetchone()
        assert not (MODEL_ROOT / approved["approvalId"]).exists()
        if outcome is not None and outcome["code"] == 20000:
            assert final["state"] == "CANCELLED" and final["cancelRequested"] and not final["resultRecorded"]
            assert lease is None or lease[0] == "DONE"
            report["checks"].append({"name": "真实批准行认领后取消：撤销旧版本、资源与私有输入确认退出、无结果", "status": "PASS",
                                     "approvalId": approved["approvalId"], "claimed": lease is not None, "resources": None if lease is None else lease[1]})
        else:
            # 取消前已结束：只记录真实终态，不冒称取消验证通过。
            report["checks"].append({"name": "批准在取消前已结束，取消路径未覆盖", "status": "NOT_COVERED", "state": final["state"], "error": final["error"]})
        assert (workspace / "index.html").read_bytes() == files["index.html"]

        def fixture(state, revision, expires, cancel=False, lease=None):
            approval = str(uuid.uuid4())
            with psycopg.connect(dsn) as db:
                db.execute("""INSERT INTO biz.project_import_startup_approval(approval_id,request_id,owner_id,app_id,scan_id,import_task_id,content_hash,
                    config_revision,context_epoch,rules_version,directories_json,request_hash,decision,state,revision,cancel_requested,expires_at)
                    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'["worker"]',%s,'APPROVE',%s,%s,%s,%s)""",
                    (approval, str(uuid.uuid4()), owner, app, inspection["scanId"], inspection["importTaskId"], inspection["contentHash"],
                     int(inspection["configRevision"]), int(inspection["contextEpoch"]), rules, request_hash(owner, app, inspection, ["worker"], True),
                     state, revision, cancel, expires))
                if lease:
                    db.execute("""INSERT INTO biz.project_import_startup_model_execution(approval_id,owner_id,app_id,approval_revision,executor_id,executor_pid,executor_start)
                        VALUES (%s,%s,%s,%s,%s,%s,%s)""", (approval, owner, app, lease[0], str(uuid.uuid4()), lease[1], lease[2]))
            return approval
        now = datetime.now(timezone.utc)
        dead = (1, 2147483647, now - timedelta(minutes=2))
        # 以下为本脚本新建应用中的受控账本夹具，不是真实模型或真实崩溃；执行者 PID 不存在。
        expired = fixture("APPROVED", 0, now - timedelta(seconds=1))
        state = wait(call, f"{base}/{expired}", lambda s: s["state"] != "APPROVED")
        assert state["state"] == "STALE" and state["error"] == "APPROVAL_EXPIRED" and state["cleanup"] is None
        report["checks"].append({"name": "过期未认领批准释放为 STALE，从未认领或执行", "status": "PASS", "approvalId": expired})

        lost = fixture("RUNNING", 1, now + timedelta(minutes=2), lease=dead)
        state = wait(call, f"{base}/{lost}", lambda s: s["state"] not in ACTIVE + ("RECOVERY_REQUIRED",))
        assert state["state"] == "FAILED" and state["error"] == "MODEL_EXECUTOR_LOST" and state["cleanup"] == "DONE" and not state["resultRecorded"]
        report["checks"].append({"name": "执行者已不存在的运行批准：撤销、核对资源与输入后失败，不重跑", "status": "PASS", "approvalId": lost})

        cancelled = fixture("CANCELLING", 2, now + timedelta(minutes=2), cancel=True, lease=dead)
        state = wait(call, f"{base}/{cancelled}", lambda s: s["state"] not in ACTIVE + ("RECOVERY_REQUIRED",))
        assert state["state"] == "CANCELLED" and state["error"] is None and state["cleanup"] == "DONE"
        report["checks"].append({"name": "持久取消意图在执行者消失后收尾为 CANCELLED", "status": "PASS", "approvalId": cancelled})

        window = fixture("RUNNING", 1, now + timedelta(minutes=2), lease=dead)
        (MODEL_ROOT / window).mkdir(parents=True); (MODEL_ROOT / window / "input.json").write_text("{}", encoding="utf-8")
        state = wait(call, f"{base}/{window}", lambda s: s["state"] == "RECOVERY_REQUIRED" and s["cleanup"] == "UNVERIFIED")
        other(f"{base}/{window}/reconcile", {"ownerId": other_owner, "revision": int(state["revision"])}, expected=40400)
        call(f"{base}/{window}/reconcile", {"ownerId": owner, "revision": int(state["revision"]) + 1}, expected=40900)
        again = call(f"{base}/{window}/reconcile", {"ownerId": owner, "revision": int(state["revision"])})
        assert again["state"] == "RECOVERY_REQUIRED" and (MODEL_ROOT / window / "input.json").exists()
        with psycopg.connect(dsn) as db:
            held = db.execute("SELECT count(*) FROM biz.project_import_startup_approval WHERE owner_id=%s AND state IN ('APPROVED','RUNNING','CANCELLING','RECOVERY_REQUIRED')", (owner,)).fetchone()[0]
        assert held == 1
        report["checks"].append({"name": "PID 登记前已有私有输入：保留 RECOVERY_REQUIRED、配额和输入，不猜停止", "status": "PASS", "approvalId": window})
        # 已知这是本脚本创建、从未启动进程的输入；显式移除后再核对。
        (MODEL_ROOT / window / "input.json").unlink(); (MODEL_ROOT / window).rmdir()
        state = wait(call, f"{base}/{window}", lambda s: s["state"] == "FAILED")
        assert state["cleanup"] == "DONE"
        report["checks"].append({"name": "输入确认消失后同一记录收尾 FAILED 并释放配额", "status": "PASS"})

        if "--restart" in sys.argv:
            # 2. 真实重启：租约执行者是当前后端 JVM（存活、非本实例 executorId），重启前不得被清理；重启后由新实例对账。
            pid = int(subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-NetTCPConnection -LocalPort 8124 -State Listen | Select-Object -First 1).OwningProcess"], capture_output=True, text=True, timeout=30).stdout.strip())
            ticks = int(subprocess.run(["powershell", "-NoProfile", "-Command", f"(Get-Process -Id {pid}).StartTime.ToUniversalTime().Ticks"], capture_output=True, text=True, timeout=30).stdout.strip())
            started = datetime(1, 1, 1, tzinfo=timezone.utc) + timedelta(microseconds=(ticks // 10000) * 1000)
            restart_case = fixture("RUNNING", 1, datetime.now(timezone.utc) + timedelta(minutes=3), lease=(1, pid, started))
            time.sleep(5)
            before = call(f"{base}/{restart_case}")
            assert before["state"] == "RUNNING" and before["cleanup"] == "PENDING"
            with (evidence / "restart.log").open("w", encoding="utf-8") as log:
                completed = subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", "scripts/restart-backend.ps1", "-TimeoutSeconds", "180", "-LogDirectory", str(evidence / "runtime")], stdout=log, stderr=subprocess.STDOUT, timeout=240, check=False)
            assert completed.returncode == 0
            state = wait(call, f"{base}/{restart_case}", lambda s: s["state"] not in ACTIVE + ("RECOVERY_REQUIRED",), 30)
            assert state["state"] == "FAILED" and state["error"] == "MODEL_EXECUTOR_LOST" and state["cleanup"] == "DONE"
            restored = call(f"{base}/{rejected['approvalId']}")
            assert restored["state"] == "REJECTED"
            report["checks"].append({"name": "真实标准重启：旧 JVM 持有的运行批准由新实例撤销并收尾，不重跑；拒绝记录可找回", "status": "PASS",
                                     "approvalId": restart_case, "oldExecutorPid": pid})
        with psycopg.connect(dsn) as db:
            executed = db.execute("SELECT count(*) FROM biz.project_import_startup_model_execution e JOIN biz.project_import_startup_approval a USING(approval_id) WHERE a.owner_id=%s AND a.decision='REJECT'", (owner,)).fetchone()[0]
            succeeded = db.execute("SELECT count(*) FROM biz.project_import_startup_approval WHERE owner_id=%s AND state='SUCCEEDED'", (owner,)).fetchone()[0]
        assert executed == 0
        report["succeededRecords"] = succeeded
        assert (workspace / "index.html").read_bytes() == files["index.html"]
        assert (Path("temp/project_import_private") / task["taskId"] / "0.bin").read_bytes() == files["index.html"]
        report["checks"].append({"name": "导入工程与保护字节未改，拒绝批准从未产生执行", "status": "PASS"})
        report["fixtureScope"] = "CONTROLLED_DB_FIXTURES_FOR_EXPIRY_LOST_EXECUTOR_PID_WINDOW"
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["failure"] = str(error)[:500]
        raise RuntimeError("识别批准 API 验证失败，未输出凭据") from None
    finally: (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
