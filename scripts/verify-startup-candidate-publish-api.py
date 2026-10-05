"""第一项步骤2：真实后端的候选发布与合法上下文。模型回包用受控账本夹具（已标注），不运行导入工程。"""
import hashlib
import http.cookiejar
import json
import re
import secrets
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
import psycopg

TERMINAL = ("REJECTED", "CANCELLED", "SUCCEEDED", "FAILED", "STALE")


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main():
    evidence = Path("temp/import-startup-candidate-publish-20261005") / ("api-" + str(time.time_ns()))
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
        account = "publish_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); csrf = call("/project/import/csrf")
        return owner, call

    def wait(call, url, done, seconds=20):
        deadline = time.monotonic() + seconds
        while True:
            state = call(url)
            if done(state): return state
            assert time.monotonic() < deadline, f"timeout waiting for {url}"
            time.sleep(.2)

    stored_reject_hash = []

    def request_hash(owner, app, inspection, directories, approved):
        # 与 ImportStartupApprovalRepository.requestHash 一致；数字编码以真实拒绝行的已存哈希确定，不猜。
        def digest(number, values):
            payload = [number(owner), number(app), inspection["scanId"], inspection["importTaskId"], inspection["contentHash"],
                       number(inspection["configRevision"]), number(inspection["contextEpoch"]), rules, values[0], values[1]]
            return hashlib.sha256(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        number = next(n for n in (int, lambda v: str(int(v))) if digest(n, (["worker"], False)) == stored_reject_hash[0])
        return digest(number, (directories, approved))

    try:
        owner, call = client(); other_owner, other = client()
        server = "require('http').createServer().listen(3000)"
        pom = "<project><modelVersion>4.0.0</modelVersion></project>"
        files = {"index.html": b"<html><body>publish</body></html>",
                 "worker/package.json": json.dumps({"name": "worker", "scripts": {}}).encode(),
                 "worker/server.js": server.encode(),
                 "app/package.json": json.dumps({"name": "app", "packageManager": "npm@10.0.0", "scripts": {"dev": "vite"}}).encode(),
                 "app/pom.xml": pom.encode(),
                 "app/.env": b"TEST_ONLY_SECRET=must-not-reach-model"}
        task = call("/project/import/tasks", {"ownerId": owner, "appName": "候选发布验收", "files": [{"path": p, "size": len(c)} for p, c in files.items()],
                                              "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        app = committed["appId"]; report.update(appId=app, taskId=task["taskId"], ownerId=owner)
        workspace = Path("temp/code_output") / ("project_" + app)
        call(f"/project/import/apps/{app}/startup/configuration")
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        scopes = inspection["result"]["scopes"]; candidates = inspection["result"]["candidates"]
        base = f"/project/import/apps/{app}/startup/approvals"
        read = f"/project/import/apps/{app}/startup/inspections/{inspection['scanId']}"

        # 1. 同目录独立范围：app 的 Node 已识别，Maven 未识别仍可单独申请；真实批准行由真实执行器处理，结果如实记录。
        assert {"directory": "app", "phase": "FIXED", "status": "IDENTIFIED", "diagnostic": None} in scopes
        assert {"directory": "app", "phase": "ALGORITHM", "status": "UNIDENTIFIED", "diagnostic": "NO_SUPPORTED_JAVA_PLUGIN"} in scopes
        assert any(c["directory"] == "app" and c["command"] == "npm run dev" for c in candidates)
        reject = {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["worker"], "approved": False}
        call(base, reject)
        with psycopg.connect(dsn) as db:
            stored_reject_hash.append(db.execute("SELECT request_hash FROM biz.project_import_startup_approval WHERE owner_id=%s AND request_id=%s", (owner, reject["requestId"])).fetchone()[0])
        approved = call(base, {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["app"], "approved": True})
        final = wait(call, f"{base}/{approved['approvalId']}", lambda s: s["state"] in TERMINAL, 180)
        current = call(read)
        if final["state"] == "SUCCEEDED":
            # 真实模型回包经发布复核：候选可为 0（模型如实标未识别），以 AGENT 阶段诊断记录，原同目录两条诊断保留。
            published_scopes = current["inspection"]["result"]["scopes"]
            agent = [c for c in current["inspection"]["result"]["candidates"] if c["source"] == "AGENT"]
            assert current["status"] == "SUCCEEDED" and all(c["directory"] == "app" and c["command"] != "npm run dev" for c in agent)
            expected = {"directory": "app", "phase": "AGENT", "status": "IDENTIFIED" if agent else "UNIDENTIFIED", "diagnostic": None if agent else "AGENT_NO_ENTRY"}
            assert expected in published_scopes
            assert {"directory": "app", "phase": "ALGORITHM", "status": "UNIDENTIFIED", "diagnostic": "NO_SUPPORTED_JAVA_PLUGIN"} in published_scopes
            assert {"directory": "app", "phase": "FIXED", "status": "IDENTIFIED", "diagnostic": None} in published_scopes
            assert [c["command"] for c in current["inspection"]["result"]["candidates"] if c["source"] == "STATIC" and c["directory"] == "app"] == ["npm run dev"]
            call(base, {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["app"], "approved": True}, expected=40000)
            report["checks"].append({"name": "真实模型批准（同目录 Node 已识别、Maven 未识别）：回包经锁内全内容复核后发布，原两条同目录诊断与规则候选保留，不能重复申请",
                                     "status": "PASS", "approvalId": approved["approvalId"], "agentCandidates": len(agent), "agentScope": expected["status"]})
        else:
            # 真实模型未成功：只证明同目录未识别范围可单独批准且失败不进入候选，不冒称模型发布通过。
            assert current["status"] == "SUCCEEDED" and not any(c["source"] == "AGENT" for c in current["inspection"]["result"]["candidates"])
            report["checks"].append({"name": "同目录已识别 Node 不再阻断未识别 Maven 范围的真实批准；真实模型未成功，失败回包不进入候选", "status": "PASS",
                                     "approvalId": approved["approvalId"], "realModelState": final["state"], "realModelError": final["error"]})
            report["checks"].append({"name": "真实模型成功并发布", "status": "NOT_COVERED", "reason": final["error"]})
        assert not Path("temp/project_startup_model", approved["approvalId"]).exists()

        def fixture(state, directories, result, cancel=False, expires=None):
            # 受控账本夹具：本脚本新建应用中的回包记录，不经过模型或执行器，不是真实模型成功。
            approval = str(uuid.uuid4())
            body = None if result is None else json.dumps(result(approval), ensure_ascii=False)
            with psycopg.connect(dsn) as db:
                db.execute("""INSERT INTO biz.project_import_startup_approval(approval_id,request_id,owner_id,app_id,scan_id,import_task_id,content_hash,
                    config_revision,context_epoch,rules_version,directories_json,request_hash,decision,state,revision,cancel_requested,expires_at,result_json)
                    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'APPROVE',%s,2,%s,%s,CAST(%s AS jsonb))""",
                    (approval, str(uuid.uuid4()), owner, app, inspection["scanId"], inspection["importTaskId"], inspection["contentHash"],
                     int(inspection["configRevision"]), int(inspection["contextEpoch"]), rules, json.dumps(directories), request_hash(owner, app, inspection, directories, True),
                     state, cancel, expires or datetime.now(timezone.utc) + timedelta(minutes=2), body))
            return approval

        def reply(command, evidence_hash=None):
            def build(approval):
                return {"approvalId": approval, "scanId": inspection["scanId"], "appId": app, "importTaskId": inspection["importTaskId"], "contentHash": inspection["contentHash"],
                        "configRevision": inspection["configRevision"], "contextEpoch": inspection["contextEpoch"], "unidentified": [],
                        "services": [{"name": "worker 服务", "directory": "worker", "command": command, "build": None, "evidence": ["worker/server.js"],
                                      "source": "AGENT", "certainty": "INFERRED", "evidenceHashes": {"worker/server.js": evidence_hash or sha(server)}}]}
            return build

        def state_of(approval):
            with psycopg.connect(dsn) as db:
                return db.execute("SELECT state,error FROM biz.project_import_startup_approval WHERE approval_id=%s", (approval,)).fetchone()

        def agent_commands():
            record = call(read)
            if record["status"] != "SUCCEEDED": return record["status"], None
            return record["status"], [c["command"] for c in record["inspection"]["result"]["candidates"] if c["source"] == "AGENT" and c["directory"] == "worker"]

        # 2. 篡改依据的已发布记录：真实读取路径按当前文本复核，不显示并转 STALE。
        tampered = fixture("SUCCEEDED", ["worker"], reply("node tampered.js", "b" * 64))
        assert agent_commands() == ("SUCCEEDED", []) and state_of(tampered) == ("STALE", "MODEL_RESULT_STALE")
        report["checks"].append({"name": "[受控夹具] 依据摘要不符当前文本的 SUCCEEDED 记录不进入候选，转 STALE/MODEL_RESULT_STALE", "status": "PASS", "approvalId": tampered})

        # 3. 取消、过期作废与失败记录即使带回包也不进入候选；合法已发布记录逐项独立显示。
        cancelled = fixture("CANCELLED", ["worker"], reply("node cancelled.js"), cancel=True)
        expired = fixture("STALE", ["worker"], reply("node expired.js"), expires=datetime.now(timezone.utc) - timedelta(seconds=5))
        failed = fixture("FAILED", ["worker"], reply("node failed.js"))
        published = fixture("SUCCEEDED", ["worker"], reply("node server.js"))
        record = call(read)
        assert record["status"] == "SUCCEEDED"
        agent = [c for c in record["inspection"]["result"]["candidates"] if c["source"] == "AGENT" and c["directory"] == "worker"]
        assert [c["command"] for c in agent] == ["node server.js"] and agent[0]["id"].startswith("agent:" + published)
        assert {"directory": "worker", "phase": "FIXED", "status": "UNIDENTIFIED", "diagnostic": "PACKAGE_MANAGER_UNKNOWN"} in record["inspection"]["result"]["scopes"]
        assert {"directory": "worker", "phase": "AGENT", "status": "IDENTIFIED", "diagnostic": None} in record["inspection"]["result"]["scopes"]
        other(read, expected=40400)
        report["checks"].append({"name": "[受控夹具] CANCELLED/过期 STALE/FAILED 带回包均不显示；合法 SUCCEEDED 经全内容复核后作为独立 AGENT 候选显示，原诊断保留，跨用户不可见", "status": "PASS",
                                 "approvalIds": {"cancelled": cancelled, "expired": expired, "failed": failed, "published": published}})

        # 4. 已发布目录不能再次申请；重复候选与原规则候选并存而不覆盖。
        call(base, {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["worker"], "approved": True}, expected=40000)
        assert any(c["directory"] == "app" and c["command"] == "npm run dev" for c in record["inspection"]["result"]["candidates"])
        report["checks"].append({"name": "已发布补充目录不能再次申请批准；原规则候选未被替换", "status": "PASS"})

        if "--restart" in sys.argv:
            # 5. 真实标准重启后：已发布候选从账本恢复并重新复核，不重跑模型。
            with (evidence / "restart.log").open("w", encoding="utf-8") as log:
                completed = subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", "scripts/restart-backend.ps1", "-TimeoutSeconds", "180", "-LogDirectory", str(evidence / "runtime")],
                                           stdout=log, stderr=subprocess.STDOUT, timeout=240, check=False)
            assert completed.returncode == 0
            assert agent_commands() == ("SUCCEEDED", ["node server.js"]) and state_of(published) == ("SUCCEEDED", None)
            with psycopg.connect(dsn) as db:
                leases = db.execute("SELECT count(*) FROM biz.project_import_startup_model_execution WHERE approval_id = ANY(%s::uuid[])", ([tampered, cancelled, expired, failed, published],)).fetchone()[0]
            assert leases == 0
            report["checks"].append({"name": "真实标准重启后已发布候选重新复核仍可见，夹具从未产生执行租约", "status": "PASS"})

        # 6. 绕过平台的磁盘改动：数据库资格不变，但全内容复核使识别与已发布候选一起作废；还原字节也不复活。
        target = workspace / "worker" / "server.js"; original = target.read_bytes()
        target.write_bytes(b"require('http').createServer().listen(4000)")
        try:
            assert agent_commands() == ("STALE", None) and state_of(published) == ("STALE", "MODEL_RESULT_STALE")
        finally:
            target.write_bytes(original)
        assert agent_commands() == ("STALE", None)
        call(base, {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["worker"], "approved": True}, expected=40900)
        report["checks"].append({"name": "外部磁盘改动后识别 STALE、已发布候选转 STALE/MODEL_RESULT_STALE；还原原字节不复活，旧识别不能再批准", "status": "PASS"})

        # 7. 新识别仍按当前内容给出未识别范围，旧回包不迁移到新识别。
        fresh = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        assert not any(c["source"] == "AGENT" for c in fresh["result"]["candidates"])
        assert (workspace / "index.html").read_bytes() == files["index.html"] and target.read_bytes() == server.encode()
        assert (Path("temp/project_import_private") / task["taskId"] / "0.bin").read_bytes() == files["index.html"]
        report["checks"].append({"name": "重新识别不携带旧回包；导入工程（还原后）与保护字节一致", "status": "PASS", "freshScanId": fresh["scanId"]})
        report["fixtureScope"] = "CONTROLLED_DB_FIXTURES_FOR_MODEL_REPLIES_ON_worker; REAL_APPROVAL_ON_app"
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["failure"] = (type(error).__name__ + ":" + str(error))[:500]
        report["failureLine"] = traceback.extract_tb(error.__traceback__)[-1].lineno
        raise RuntimeError("候选发布 API 验证失败，未输出凭据") from None
    finally: (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
