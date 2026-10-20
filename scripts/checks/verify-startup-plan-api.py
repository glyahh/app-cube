"""第一项步骤3：真实后端的冻结运行计划与确认失效。只生成/确认计划，不安装、不构建、不启动导入工程；补充候选用受控账本夹具（已标注）。"""
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


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main():
    evidence = Path("temp/import-startup-freeze-plan-20261005") / ("api-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE", "executionEntry": "CLOSED", "evidence": str(evidence)}
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
            assert result["code"] == expected, f"Unexpected business code {result['code']} for {path}: {result.get('message')}"
            return result.get("data")
        account = "plan_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); csrf = call("/project/import/csrf")
        return owner, call

    def db_one(sql, params):
        with psycopg.connect(dsn) as db:
            return db.execute(sql, params).fetchone()

    try:
        owner, call = client(); other_owner, other = client()
        server = "require('http').createServer().listen(3000)"
        web_scripts = {"dev": "concurrently \"npm:ui\" \"npm:api\"", "ui": "vite", "api": "node ../api/server.js", "start": "vite preview",
                       "build": "vite build", "predev": "node check.js", "postinstall": "node patch.js"}
        files = {"web/package.json": json.dumps({"name": "web", "packageManager": "npm@10.0.0", "scripts": web_scripts}).encode(),
                 "web/package-lock.json": b"{}",
                 "api/package.json": json.dumps({"name": "api", "packageManager": "npm@10.0.0", "scripts": {"start": "node server.js"}}).encode(),
                 "api/package-lock.json": b"{}",
                 "api/server.js": server.encode(),
                 "site/index.html": b"<html><body>plan</body></html>",
                 "worker/package.json": json.dumps({"name": "worker", "scripts": {}}).encode(),
                 "worker/server.js": server.encode()}
        task = call("/project/import/tasks", {"ownerId": owner, "appName": "冻结计划验收", "files": [{"path": p, "size": len(c)} for p, c in files.items()],
                                              "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        app = committed["appId"]; report.update(appId=app, taskId=task["taskId"], ownerId=owner)
        workspace = Path("temp/code_output") / ("project_" + app)
        plans = f"/project/import/apps/{app}/startup/plans"

        def plan_count():
            return db_one("SELECT count(*) FROM biz.project_import_startup_plan WHERE app_id=%s", (app,))[0]

        # 1. 手动配置与识别批准都不产生计划；生成计划只是草稿，不可执行。
        config = call(f"/project/import/apps/{app}/startup/configuration")
        config = call(f"/project/import/apps/{app}/startup/configuration", {"ownerId": owner, "configRevision": int(config["configRevision"]), "contextEpoch": int(config["contextEpoch"]),
                      "scope": ".", "services": [{"id": None, "name": "手动 api", "directory": "api", "command": "node server.js --manual", "install": "npm ci", "build": None}]}, method="PUT")
        manual = "manual:" + config["services"][0]["id"]
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        ids = {c["id"] for c in inspection["result"]["candidates"]}
        assert {"web/package.json:dev", "web/package.json:start", "api/package.json:start", "site/index.html", manual} <= ids
        reject = {"ownerId": owner, "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["worker"], "approved": False}
        call(f"/project/import/apps/{app}/startup/approvals", reject)
        assert plan_count() == 0 and call(plans + "/current") is None
        first = call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": ["api/package.json:start", "site/index.html", manual]})
        assert first["state"] == "DRAFT" and first["executable"] is False and first["confirmedAt"] is None and first["diff"] == []
        content = first["plan"]; services = {s["id"]: s for s in content["services"]}
        assert services["api/package.json:start"]["expansion"] == ["scripts.start: node server.js"] and services["api/package.json:start"]["readiness"]["method"] == "PROCESS_ALIVE"
        assert services["site/index.html"]["readiness"]["method"] == "STATIC_ENTRY" and "MANUAL_COMMAND_UNVERIFIED" in services[manual]["unknowns"]
        dependency = next(d for d in content["dependencies"] if d["id"] == "dependency:node:api")
        assert dependency["status"] == "UNKNOWN" and dependency["installs"] == ["npm ci"] and dependency["declarations"] == ["api/package-lock.json", "api/package.json"]
        assert sorted(dependency["services"]) == sorted(["api/package.json:start", manual]) and "dependency:node:api" in content["shared"]
        assert call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": [manual, "site/index.html", "api/package.json:start"]})["planId"] == first["planId"]
        other(f"{plans}/{first['planId']}", expected=40400)
        report["checks"].append({"name": "手动配置与识别拒绝不产生计划；生成的计划为草稿、不可执行，列明命令/依赖范围（UNKNOWN）/就绪方式/共享依赖，同内容幂等，跨用户不可见", "status": "PASS",
                                 "planId": first["planId"], "planRevision": first["planRevision"]})

        # 2. 聚合脚本展开：选 web dev 会经 concurrently 带上 api，必须显式确认展开范围。
        aggregate = call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": ["web/package.json:dev", "web/package.json:start"]})
        dev = next(s for s in aggregate["plan"]["services"] if s["id"] == "web/package.json:dev")
        assert dev["expansion"] == ["scripts.dev: concurrently \"npm:ui\" \"npm:api\"", "scripts.predev: node check.js", "scripts.ui: vite", "scripts.api: node ../api/server.js"]
        entry = next(a for a in aggregate["plan"]["aggregates"] if a["id"] == "service:web/package.json:dev")
        assert {"MULTI_SCRIPT_RUNNER", "REFERENCES_UNSELECTED"} <= set(entry["reasons"]) and "api/package.json:start" in entry["unselected"] and manual in entry["unselected"]
        build = aggregate["plan"]["builds"][0]
        assert len(aggregate["plan"]["builds"]) == 1 and build["command"] == "npm run build" and len(build["services"]) == 2 and build["id"] in aggregate["plan"]["shared"]
        web_dependency = next(d for d in aggregate["plan"]["dependencies"] if d["id"] == "dependency:node:web")
        assert web_dependency["lifecycle"] == ["scripts.postinstall"]
        call(f"{plans}/{aggregate['planId']}/confirm", {"ownerId": owner, "planRevision": aggregate["planRevision"], "aggregateAcknowledged": False}, expected=40000)
        report["checks"].append({"name": "聚合启动脚本展开列出（含 pre 生命周期与被带上的未选服务）；共享构建只列一次；未确认展开范围不能确认", "status": "PASS", "planId": aggregate["planId"]})

        # 3. 确认绑定 planRevision；新确认替代旧确认，旧计划不能再确认。
        call(f"{plans}/{first['planId']}/confirm", {"ownerId": owner, "planRevision": "0" * 64, "aggregateAcknowledged": False}, expected=40900)
        confirmed = call(f"{plans}/{first['planId']}/confirm", {"ownerId": owner, "planRevision": first["planRevision"], "aggregateAcknowledged": False})
        assert confirmed["state"] == "CONFIRMED" and confirmed["executable"] is False and confirmed["planRevision"] == first["planRevision"]
        assert call(f"{plans}/{first['planId']}/confirm", {"ownerId": owner, "planRevision": first["planRevision"], "aggregateAcknowledged": False})["state"] == "CONFIRMED"
        other(f"{plans}/{first['planId']}/confirm", {"ownerId": other_owner, "planRevision": first["planRevision"], "aggregateAcknowledged": False}, expected=40400)
        acknowledged = call(f"{plans}/{aggregate['planId']}/confirm", {"ownerId": owner, "planRevision": aggregate["planRevision"], "aggregateAcknowledged": True})
        assert acknowledged["state"] == "CONFIRMED" and acknowledged["aggregateAcknowledged"] is True
        assert call(f"{plans}/{first['planId']}")["state"] == "SUPERSEDED"
        call(f"{plans}/{first['planId']}/confirm", {"ownerId": owner, "planRevision": first["planRevision"], "aggregateAcknowledged": False}, expected=40900)
        assert call(plans + "/current")["planId"] == aggregate["planId"]
        report["checks"].append({"name": "错误 planRevision 拒绝；确认幂等、跨用户 404；确认聚合计划后旧确认 SUPERSEDED 且不能再确认", "status": "PASS"})

        # 4. [受控夹具] 发布新的补充候选：可选服务集合变化使已确认计划失效并展示差异。
        stored = db_one("SELECT request_hash FROM biz.project_import_startup_approval WHERE owner_id=%s AND request_id=%s", (owner, reject["requestId"]))[0]
        def request_hash(directories, approved):
            def digest(number, values):
                payload = [number(owner), number(app), inspection["scanId"], inspection["importTaskId"], inspection["contentHash"],
                           number(inspection["configRevision"]), number(inspection["contextEpoch"]), rules, values[0], values[1]]
                return hashlib.sha256(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
            number = next(n for n in (int, lambda v: str(int(v))) if digest(n, (["worker"], False)) == stored)
            return digest(number, (directories, approved))
        published = str(uuid.uuid4())
        result = {"approvalId": published, "scanId": inspection["scanId"], "appId": app, "importTaskId": inspection["importTaskId"], "contentHash": inspection["contentHash"],
                  "configRevision": inspection["configRevision"], "contextEpoch": inspection["contextEpoch"], "unidentified": [],
                  "services": [{"name": "worker 服务", "directory": "worker", "command": "node server.js", "build": None, "evidence": ["worker/server.js"],
                                "source": "AGENT", "certainty": "INFERRED", "evidenceHashes": {"worker/server.js": sha(server)}}]}
        with psycopg.connect(dsn) as db:
            db.execute("""INSERT INTO biz.project_import_startup_approval(approval_id,request_id,owner_id,app_id,scan_id,import_task_id,content_hash,
                config_revision,context_epoch,rules_version,directories_json,request_hash,decision,state,revision,cancel_requested,expires_at,result_json)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'APPROVE','SUCCEEDED',2,false,%s,CAST(%s AS jsonb))""",
                (published, str(uuid.uuid4()), owner, app, inspection["scanId"], inspection["importTaskId"], inspection["contentHash"], int(inspection["configRevision"]),
                 int(inspection["contextEpoch"]), rules, json.dumps(["worker"]), request_hash(["worker"], True), datetime.now(timezone.utc) + timedelta(minutes=2), json.dumps(result, ensure_ascii=False)))
        stale = call(f"{plans}/{aggregate['planId']}")
        assert stale["state"] == "STALE" and stale["staleReason"] == "PLAN_CHANGED" and stale["executable"] is False
        assert [c["path"] for c in stale["diff"]] and all(c["path"].startswith("available[agent:" + published) for c in stale["diff"]) and stale["diff"][0]["before"] is None
        call(f"{plans}/{aggregate['planId']}/confirm", {"ownerId": owner, "planRevision": aggregate["planRevision"], "aggregateAcknowledged": True}, expected=40900)
        report["checks"].append({"name": "[受控夹具] 新发布补充候选使已确认计划 STALE/PLAN_CHANGED，差异列出新增可选服务，旧确认不能再用", "status": "PASS", "approvalId": published})

        # 5. 重新生成计划，确认后经真实标准重启仍为同一冻结版本；数据库拒绝改写冻结内容。
        current = call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": ["api/package.json:start", "site/index.html"]})
        assert current["state"] == "DRAFT" and current["diffBase"] == aggregate["planId"] and current["diff"]
        current = call(f"{plans}/{current['planId']}/confirm", {"ownerId": owner, "planRevision": current["planRevision"], "aggregateAcknowledged": False})
        frozen = db_one("SELECT plan_json::text,plan_revision FROM biz.project_import_startup_plan WHERE plan_id=%s", (current["planId"],))
        rejected = False
        try:
            with psycopg.connect(dsn) as db: db.execute("UPDATE biz.project_import_startup_plan SET plan_json=plan_json||'{\"schema\":2}'::jsonb WHERE plan_id=%s", (current["planId"],))
        except psycopg.errors.CheckViolation: rejected = True
        assert rejected
        if "--restart" in sys.argv:
            with (evidence / "restart.log").open("w", encoding="utf-8") as log:
                completed = subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", "scripts/restart-backend.ps1", "-TimeoutSeconds", "180", "-LogDirectory", str(evidence / "runtime")],
                                           stdout=log, stderr=subprocess.STDOUT, timeout=240, check=False)
            assert completed.returncode == 0
            after = call(f"{plans}/{current['planId']}")
            assert after["state"] == "CONFIRMED" and after["planRevision"] == current["planRevision"] and after["executable"] is False
            assert db_one("SELECT plan_json::text,plan_revision FROM biz.project_import_startup_plan WHERE plan_id=%s", (current["planId"],)) == frozen
            report["checks"].append({"name": "真实标准重启后已确认计划重新复核仍为同一 planRevision，冻结内容未变；直接改写 plan_json 被数据库拒绝", "status": "PASS"})
        else:
            report["checks"].append({"name": "直接改写 plan_json 被数据库拒绝（未跑重启）", "status": "PASS"})

        # 6. 绕过平台的磁盘改动：识别与已确认计划一起失效；还原字节也不恢复，旧计划不能再确认。
        target = workspace / "api" / "server.js"; original = target.read_bytes()
        target.write_bytes(b"require('http').createServer().listen(4000)")
        try:
            gone = call(f"{plans}/{current['planId']}")
            assert gone["state"] == "STALE" and gone["staleReason"] == "INSPECTION_STALE"
        finally:
            target.write_bytes(original)
        assert call(f"{plans}/{current['planId']}")["state"] == "STALE"
        call(f"{plans}/{current['planId']}/confirm", {"ownerId": owner, "planRevision": current["planRevision"], "aggregateAcknowledged": False}, expected=40900)
        call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": ["api/package.json:start"]}, expected=40900)
        report["checks"].append({"name": "外部磁盘改动后已确认计划 STALE/INSPECTION_STALE；还原原字节不恢复，旧识别不能再生成计划", "status": "PASS"})

        # 7. 启动脚本真实变化：重新识别后的新计划相对上次确认展示命令展开差异。
        package = workspace / "api" / "package.json"
        package.write_text(json.dumps({"name": "api", "packageManager": "npm@10.0.0", "scripts": {"start": "node server.js --port 4000"}}), encoding="utf-8")
        fresh = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        changed = call(plans, {"ownerId": owner, "scanId": fresh["scanId"], "serviceIds": ["api/package.json:start", "site/index.html"]})
        paths = {c["path"] for c in changed["diff"]}
        assert changed["state"] == "DRAFT" and changed["diffBase"] == current["planId"] and changed["planRevision"] != current["planRevision"]
        assert {"contentHash", "scanId", "services[api/package.json:start].expansion"} <= paths
        expansion = next(c for c in changed["diff"] if c["path"] == "services[api/package.json:start].expansion")
        assert "node server.js --port 4000" in expansion["after"] and "node server.js --port 4000" not in expansion["before"]
        report["checks"].append({"name": "启动脚本改变后重新识别，新计划以上次确认为基准展示 contentHash 与命令展开差异", "status": "PASS", "planId": changed["planId"]})

        # 8. 配置变化（权限/上下文）使草稿失效；全程未安装、构建或启动。
        config = call(f"/project/import/apps/{app}/startup/configuration")
        call(f"/project/import/apps/{app}/startup/configuration", {"ownerId": owner, "configRevision": int(config["configRevision"]), "contextEpoch": int(config["contextEpoch"]),
             "scope": ".", "services": []}, method="PUT")
        revoked = call(f"{plans}/{changed['planId']}")
        assert revoked["state"] == "STALE" and revoked["staleReason"] == "INSPECTION_STALE"
        assert not any((workspace / d / "node_modules").exists() for d in ("web", "api", "worker")) and not (workspace / "web" / "dist").exists()
        assert (Path("temp/project_import_private") / task["taskId"] / "4.bin").read_bytes() == server.encode()
        with psycopg.connect(dsn) as db:
            states = dict(db.execute("SELECT state,count(*) FROM biz.project_import_startup_plan WHERE app_id=%s GROUP BY state", (app,)).fetchall())
            leases = db.execute("SELECT count(*) FROM biz.project_import_startup_model_execution e JOIN biz.project_import_startup_approval a ON a.approval_id=e.approval_id WHERE a.app_id=%s", (app,)).fetchone()[0]
        assert states.get("CONFIRMED", 0) == 0 and leases == 0
        report.update(planStates=states)
        report["checks"].append({"name": "手动配置变化使草稿计划 STALE；无 node_modules/dist 生成，保护快照未变，无模型执行租约，最终无已确认计划", "status": "PASS"})
        report["fixtureScope"] = "CONTROLLED_DB_FIXTURE_FOR_PUBLISHED_SUPPLEMENT_ON_worker_ONLY"
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["failure"] = (type(error).__name__ + ":" + str(error))[:500]
        report["failureLine"] = traceback.extract_tb(error.__traceback__)[-1].lineno
        raise RuntimeError("冻结计划 API 验证失败，未输出凭据") from None
    finally: (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
