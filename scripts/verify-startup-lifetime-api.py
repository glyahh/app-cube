"""受控夹具的安装与运行验收。只运行本脚本生成的小工程，不使用私有仓库，不调用模型。"""
import hashlib
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import time
import traceback
import urllib.error
import urllib.request
import uuid
import sys
import difflib
import textwrap
from pathlib import Path

import psycopg

WSL = ["wsl.exe", "-d", os.environ.get("AGENT_WSL_DISTRO", "Ubuntu"), "--exec"]
APP = """from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        if self.path == '/late-log':
            print('fixture-log-after-ready', flush=True)
        if self.path == '/overflow':
            print('X' * (5 * 1024 * 1024), flush=True)
    def log_message(self, fmt, *args):
        return
ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
"""
FAIL = "import sys\nsys.exit(3)\n"
QUIET = "print('bye')\n"


def tree(path):
    path = Path(path)
    if not path.exists():
        return {}
    return {str(p.relative_to(path)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(path.rglob("*")) if p.is_file()}


def docker(*args, timeout=60):
    return subprocess.run(WSL + ["docker", *args], capture_output=True, timeout=timeout)


def main():
    evidence = Path("temp") / ("import-startup-preparation-" + time.strftime("%Y%m%d")) / ("lifetime-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE", "fixture": "CONTROLLED_SCRIPTS_ONLY",
              "privateSuccess": "DEFERRED", "s05": "NOT_RUN", "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines()
               if line.startswith("AGENT_POSTGRES_DSN="))
    apps = []
    repair_mode = "--repair-only" in sys.argv
    report["scope"] = "MANUAL_REPAIR_REPLAY" if repair_mode else "LIFETIME_AND_HARD_DISK"

    def check(group, name, ok, detail=""):
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": detail[:500]})
        if not ok:
            raise AssertionError(group + " " + name + " " + detail[:500])

    def db_all(sql, params):
        with psycopg.connect(dsn) as db:
            return db.execute(sql, params).fetchall()

    def executions(app):
        return db_all("SELECT slot,state,reason FROM biz.project_import_startup_execution WHERE app_id=%s ORDER BY created_at", (int(app),))

    def containers(app):
        listed = docker("ps", "-a", "--filter", "label=glyahh.import.app=" + str(app), "--format", "{{.ID}} {{.Status}} {{.Ports}}")
        if listed.returncode != 0:
            raise RuntimeError("本应用容器状态无法确认")
        return listed.stdout.decode("utf-8", "replace").strip()

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}

    def call(path, body=None, method=None, expected=20000, timeout=60):
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
            response = opener.open(urllib.request.Request("http://127.0.0.1:8124/api" + path, data=data, headers=headers,
                                                           method=method or ("GET" if body is None else "POST")), timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.loads(response.read())
        if expected is not None:
            assert result["code"] == expected, f"{path} -> {result.get('code')} {result.get('message')}"
        return result.get("data")

    def client():
        account = "life_" + secrets.token_hex(8)
        password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        report.setdefault('fixtures', []).append({'ownerId': owner, 'account': account})
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")
        return owner

    def import_app(owner, name, files):
        task = call("/project/import/tasks", {"ownerId": owner, "appName": name,
                                              "files": [{"path": path, "size": len(content)} for path, content in files.items()],
                                              "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        apps.append(committed["appId"])
        report.setdefault("imports", []).append({"appId": committed["appId"], "taskId": task["taskId"], "contentHash": task["contentHash"]})
        return committed["appId"]

    def prepare(owner, app, services):
        config = call(f"/project/import/apps/{app}/startup/configuration")
        config = call(f"/project/import/apps/{app}/startup/configuration", {"ownerId": owner, "configRevision": int(config["configRevision"]),
                      "contextEpoch": int(config["contextEpoch"]), "scope": ".", "services": services}, method="PUT")
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        return config, inspection

    def confirm(owner, app, inspection, service_ids, acknowledge=False):
        plans = f"/project/import/apps/{app}/startup/plans"
        plan = call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": service_ids})
        return call(f"{plans}/{plan['planId']}/confirm", {"ownerId": owner, "planRevision": plan["planRevision"], "aggregateAcknowledged": acknowledge})

    def prep(app, plan, kind, approved=None):
        body = {"ownerId": owner, "planId": plan["planId"], "planRevision": plan["planRevision"]}
        if kind == "install":
            body["approved"] = approved
        return call(f"/project/import/apps/{app}/startup/preparations/{kind}", body)

    def verify_repair(owner):
        bad = APP.replace('b\'{"ok":true}\'', 'b\'{"reply":"pang"}\'')
        fixed = bad.replace('"reply":"pang"', '"reply":"pong"')
        declaration = {"processes": [{"name": "api", "command": "python3 app.py", "port": 8080}]}
        (evidence / "repair.patch").write_text("".join(difflib.unified_diff(bad.splitlines(True), fixed.splitlines(True), fromfile="a/app.py", tofile="b/app.py")), encoding="utf-8")
        (evidence / "baseline.py").write_text(bad, encoding="utf-8")
        for group in ("E04", "E05"):
            app = import_app(owner, "修复重放夹具", {"app.py": bad.encode(), ".glyahh/run.json": json.dumps(declaration).encode()})
            _, inspection = prepare(owner, app, [])
            original_plan = confirm(owner, app, inspection, ["declared:api"])
            original = prep(app, original_plan, "run")["services"][0]
            check(group, "干净缺陷版本确实启动并复现错误业务结果", original["state"] == "READY" and json.load(urllib.request.urlopen(original["address"], timeout=5))["reply"] == "pang", app)
            prep(app, original_plan, "stop")
            # 明确走所有者编辑阶段，测试执行不因此获得修改执行副本的权限。
            content = call(f"/app/{app}/files/content?path=app.py")
            saved = call(f"/app/{app}/files/content", {"path": "app.py", "content": fixed, "baseVersion": content["version"]})
            old_plan = call(f"/project/import/apps/{app}/startup/plans/{original_plan['planId']}")
            check(group, "版本化编辑使旧运行计划失效", saved["version"] != content["version"] and old_plan["state"] == "STALE", old_plan["state"])
            _, renewed = prepare(owner, app, [])
            new_plan = confirm(owner, app, renewed, ["declared:api"])
            repaired = prep(app, new_plan, "run")["services"][0]
            response = json.load(urllib.request.urlopen(repaired["address"], timeout=5)) if repaired["state"] == "READY" else {}
            check(group, "重新确认新版后原失败用例转绿且不是旧容器结果", repaired["state"] == "READY" and response.get("reply") == "pong" and repaired["executionId"] != original["executionId"] and new_plan["planRevision"] != original_plan["planRevision"], str(repaired))
            check(group, "修复仅改变当前导入应用且原缺陷夹具保持不变", (Path("temp/code_output") / ("project_" + app) / "app.py").read_text(encoding="utf-8") == fixed and (evidence / "baseline.py").read_text(encoding="utf-8") == bad, app)
            report.setdefault("repairIdentities", []).append({"group": group, "appId": app, "oldPlanId": original_plan["planId"], "newPlanId": new_plan["planId"], "oldRevision": original_plan["planRevision"], "newRevision": new_plan["planRevision"], "oldExecutionId": original["executionId"], "newExecutionId": repaired["executionId"], "baselineVersion": content["version"], "fixedVersion": saved["version"], "result": response})
            prep(app, new_plan, "stop")

    try:
        owner = client()
        if repair_mode:
            verify_repair(owner)
        else:
            serve_files = {"app.py": APP.encode(), "fail.py": FAIL.encode(), "quiet.py": QUIET.encode(), "README.md": "[受控夹具] 安装与运行核对\n".encode()}
            serve = import_app(owner, "运行生命周期夹具", serve_files)
            workspace = Path("temp/code_output") / ("project_" + serve)
            before = tree(workspace)
            _, inspection = prepare(owner, serve, [
                {"id": None, "name": "网页", "directory": ".", "command": "python3 app.py", "install": None, "build": "printf built > built.txt", "port": 8080},
                {"id": None, "name": "失败项", "directory": ".", "command": "python3 fail.py", "install": None, "build": "exit 4", "port": None},
                {"id": None, "name": "立刻退出", "directory": ".", "command": "python3 quiet.py", "install": None, "build": None, "port": None},
            ])
            ids = {row["name"]: row["id"] for row in inspection["result"]["candidates"]}
            check("D01", "导入和识别完成后没有执行记录，也没有本应用容器", executions(serve) == [] and containers(serve) == "", containers(serve))

            web_only = confirm(owner, serve, inspection, [ids["网页"]])
            rejected = prep(serve, web_only, "install", False)
            check("D02", "拒绝安装不产生执行，安装入口仍可再次调用", rejected["state"] == "REJECTED" and executions(serve) == [] and rejected["servicesStarted"] is False, rejected["state"])
            approved = prep(serve, web_only, "install", True)
            check("D03", "没有写明安装命令时确认安装不启动服务", approved["state"] == "NOT_REQUIRED" and approved["servicesStarted"] is False and executions(serve) == [], approved["state"])

            # 带未核对依赖的另一个应用，用来核对拒绝后不启动。
            blocked_app = import_app(owner, "依赖未核对夹具", {"app.py": APP.encode(), "requirements.txt": b"# fixture\n"})
            _, blocked_inspection = prepare(owner, blocked_app, [
                {"id": None, "name": "网页", "directory": ".", "command": "python3 app.py", "install": None, "build": None, "port": 8080},
            ])
            blocked_plan = confirm(owner, blocked_app, blocked_inspection, [next(row["id"] for row in blocked_inspection["result"]["candidates"] if row["name"] == "网页")])
            waiting = prep(blocked_app, blocked_plan, "run")
            check("D04", "依赖未确认时运行停在等待安装，不创建执行", waiting["state"] == "WAITING_INSTALL" and executions(blocked_app) == [], waiting["state"] + " " + str(waiting.get("reason")))
            unknown = prep(blocked_app, blocked_plan, "install", True)
            check("D07", "没有写明安装命令时依赖保持未核对，不记成已就绪", unknown["state"] == "BLOCKED" and unknown["reason"] == "DEPENDENCY_UNKNOWN"
                  and all(row["status"] == "UNKNOWN" for row in unknown["dependencies"]) and executions(blocked_app) == [], unknown["state"])

            install_app = import_app(owner, "安装成功夹具", {"app.py": b"print('installed')\n", "requirements.txt": b"# fixture\n"})
            install_workspace = Path("temp/code_output") / ("project_" + install_app)
            install_before = tree(install_workspace)
            _, install_inspection = prepare(owner, install_app, [
                {"id": None, "name": "网页", "directory": ".", "command": "python3 app.py", "install": "printf 'installed\n' >> installed.txt", "build": None, "port": None},
                {"id": None, "name": "共享安装项", "directory": ".", "command": "python3 app.py", "install": "printf 'installed\n' >> installed.txt", "build": None, "port": None},
            ])
            install_plan = confirm(owner, install_app, install_inspection, [row['id'] for row in install_inspection['result']['candidates'] if row['name'] in ('网页', '共享安装项')], acknowledge=True)
            declined = prep(install_app, install_plan, "install", False)
            declined_run = prep(install_app, install_plan, "run")
            check("D04", "用户拒绝安装后再次运行仍不安装也不启动", declined["state"] == "REJECTED" and declined_run["state"] == "WAITING_INSTALL" and executions(install_app) == [],
                  declined_run["state"])
            installed = prep(install_app, install_plan, "install", True)
            copy = "/var/lib/glyahh/import-disks/" + install_app + "/work/installed.txt"
            copied = subprocess.run([WSL[0], "-d", WSL[2], "-u", "root", "--exec", "python3", "-c", "from pathlib import Path;import sys;sys.stdout.buffer.write(Path(sys.argv[1]).read_bytes())", copy], capture_output=True, timeout=30)
            check("D03", "确认安装只执行写明的命令，不启动服务", installed["state"] == "SUCCEEDED" and installed["servicesStarted"] is False and copied.returncode == 0,
                  installed["state"] + " " + str(installed.get("reason")))
            check("D08", "安装产物留在执行副本，导入工程字节不变", tree(install_workspace) == install_before and copied.stdout.strip() == b"installed",
                  "workspaceChanged=" + str(tree(install_workspace) != install_before))
            check('D05', '两个已选服务的共享安装只执行一次', copied.stdout == b'installed\n' and sum(row[0] == 'install' for row in executions(install_app)) == 1)
            report['checks'][-1]['assertion'] = 'shared-step-once'
            check("D07", "安装成功后依赖状态仍是未核对，不改写成已就绪", all(row["status"] == "UNKNOWN" for row in installed["dependencies"]),
                  json.dumps(installed["dependencies"], ensure_ascii=False)[:300])
            started = prep(install_app, install_plan, "run")
            install_rows = executions(install_app)
            check("D05", "安装成功后才启动；再次运行不重复安装", started["services"][0]["state"] in {"READY", "FAILED"} and sum(1 for row in install_rows if row[0] == "install" and row[1] == "SUCCEEDED") == 1,
                  started["state"] + " " + str(started["services"]))
            if started["services"][0]["state"] == "READY":
                prep(install_app, install_plan, "stop")

            fail_app = import_app(owner, "安装失败夹具", {"app.py": b"print(1)\n", "requirements.txt": b"# fixture\n"})
            _, fail_inspection = prepare(owner, fail_app, [
                {"id": None, "name": "网页", "directory": ".", "command": "python3 app.py", "install": "exit 3", "build": None, "port": None},
            ])
            fail_plan = confirm(owner, fail_app, fail_inspection, [next(row["id"] for row in fail_inspection["result"]["candidates"] if row["name"] == "网页")])
            failed_install = prep(fail_app, fail_plan, "install", True)
            failed_run = prep(fail_app, fail_plan, "run")
            check("D06", "安装失败后不启动，也不把服务记成就绪", failed_install["state"] == "FAILED" and failed_run["state"] == "FAILED" and failed_run["servicesStarted"] is False
                  and all(row[0] == "install" for row in executions(fail_app)), failed_install["state"] + "/" + failed_run["state"] + " " + str(failed_install.get("reason")))

            # 夹具源码整体反缩进：三引号内容会原样写入 pressure.py，带缩进会直接 IndentationError。
            pressure_source = textwrap.dedent("""\
                import errno,json,sys
                from pathlib import Path
                p=Path('quota-filler')
                try:
                    with open(p,'wb',buffering=0) as output:
                        for _ in range(1200): output.write(b'Q'*(1024*1024))
                except OSError as error:
                    print(json.dumps({'errno':error.errno,'size':p.stat().st_size}),flush=True)
                    sys.exit(28 if error.errno==errno.ENOSPC else 29)
                sys.exit(30)
                """)
            pressure_app = import_app(owner, "磁盘边界夹具", {"app.py": b"print('not-started')\n", "pressure.py": pressure_source.encode(), "requirements.txt": b"# fixture\n"})
            pressure_workspace = Path("temp/code_output") / ("project_" + pressure_app)
            pressure_before = tree(pressure_workspace)
            _, pressure_inspection = prepare(owner, pressure_app, [{"id": None, "name": "边界", "directory": ".", "command": "python3 app.py", "install": "python3 pressure.py", "build": None, "port": None}])
            pressure_plan = confirm(owner, pressure_app, pressure_inspection, [next(row["id"] for row in pressure_inspection["result"]["candidates"] if row["name"] == "边界")])
            pressure_result = prep(pressure_app, pressure_plan, "install", True)
            pressure_rows = db_all("SELECT execution_id,exit_code,state FROM biz.project_import_startup_execution WHERE app_id=%s ORDER BY created_at", (int(pressure_app),))
            pressure_log = Path("temp/project_import_exec") / (str(pressure_rows[-1][0]) + ".output.log")
            data = next(json.loads(line) for line in pressure_log.read_text(encoding="utf-8").splitlines() if line.startswith('{"errno"'))
            check("R08", "正式安装副本写满后收到真实ENOSPC并落失败状态", pressure_result["state"] == "FAILED" and pressure_rows[-1][1] == 28 and data["errno"] == 28 and data["size"] < (1 << 30), json.dumps(data))
            check("R08", "写满未改变导入原工程且没有残留执行容器", tree(pressure_workspace) == pressure_before and not containers(pressure_app), pressure_app)
            pressure_run = prep(pressure_app, pressure_plan, "run")
            check("D06", "磁盘导致安装失败后不启动服务", pressure_run["servicesStarted"] is False and all(row[0] == "install" for row in executions(pressure_app)), pressure_run["state"])

            # 只启动选中的网页；失败项的构建失败不带动网页。
            both = confirm(owner, serve, inspection, [ids["网页"], ids["失败项"]], acknowledge=True)
            both_before = tree(workspace)
            running = prep(serve, both, "run")
            web = next(row for row in running["services"] if row["serviceId"] == ids["网页"])
            broken = next(row for row in running["services"] if row["serviceId"] == ids["失败项"])
            serve_rows = executions(serve)
            selected = {row[0] for row in serve_rows}
            check("R01", "只有选中的服务进入执行；未选的立刻退出服务没有执行记录", running["state"] == "PARTIAL" and web["state"] == "READY" and ids["立刻退出"] not in json.dumps(serve_rows),
                  running["state"] + " " + containers(serve))
            check("R03", "构建失败的服务不启动", broken["state"] == "FAILED" and broken["phase"] == "BUILD", broken["state"] + " " + str(broken.get("reason")))
            address = web.get("address") or ""
            body = ""
            if address.startswith("http://127.0.0.1:"):
                with urllib.request.urlopen(address, timeout=5) as response:
                    body = response.read().decode("utf-8")
            check("R02", "已声明端口的服务记下本机地址，并且真实请求返回业务内容", web["state"] == "READY" and address.startswith("http://127.0.0.1:") and '"ok":true' in body,
                  address + " " + body[:120])
            check("R04", "一项失败时成功项的容器和地址仍在", web["state"] == "READY" and "Up" in containers(serve) and '"ok":true' in body, containers(serve))
            web_execution = web["executionId"]
            retried = prep(serve, both, "retry")
            retried_web = next(row for row in retried["services"] if row["serviceId"] == ids["网页"])
            check("R05", "重试不重复启动已经就绪的服务", retried_web["executionId"] == web_execution and retried_web["state"] == "READY", retried["state"])
            again = prep(serve, both, "run")
            again_web = next(row for row in again["services"] if row["serviceId"] == ids["网页"])
            check("R06", "重复点击运行不新建成功项的执行", again_web["executionId"] == web_execution, again["state"])
            stopped = prep(serve, both, "stop")
            gone = containers(serve)
            check("R07", "停止后本应用容器实际消失", stopped["reason"] == "STOPPED" and gone == "", stopped["reason"] + " containers=" + gone)
            check("R07", "停止后逐服务不再就绪且不保留可用地址", stopped["servicesStarted"] is False
                  and all(row["state"] == "STOPPED" and row.get("address") is None for row in stopped["services"]),
                  json.dumps(stopped["services"], ensure_ascii=False))
            restarted = prep(serve, both, "run")
            restarted_web = next(row for row in restarted["services"] if row["serviceId"] == ids["网页"])
            check("R07", "停止后再运行真正建立新执行并可访问", restarted_web["state"] == "READY"
                  and restarted_web["executionId"] != web["executionId"] and containers(serve) != "", restarted["state"])
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(restarted_web["address"], timeout=10) as response:
                check("R07", "重跑服务返回夹具业务结果", response.status == 200 and json.loads(response.read()) == {"ok": True})
            live_id = db_all("SELECT container_id FROM biz.project_import_startup_execution WHERE execution_id=%s AND owner_id=%s AND app_id=%s",
                             (restarted_web["executionId"], int(owner), int(serve)))[0][0]
            assert re.fullmatch(r"[0-9a-f]{64}", live_id)
            killed = docker("kill", live_id)
            assert killed.returncode == 0, "受控服务故障注入失败"
            observed = call(f"/project/import/apps/{serve}/startup/preparations/current?planId={both['planId']}")
            observed_web = next(row for row in observed["services"] if row["serviceId"] == ids["网页"])
            check("R07", "实际退出后读取业务状态不再显示 READY", observed_web["state"] == "FAILED"
                  and observed_web.get("address") is None and observed_web["reason"] == "SERVICE_EXITED", observed["state"])
            recovered = prep(serve, both, "retry")
            recovered_web = next(row for row in recovered["services"] if row["serviceId"] == ids["网页"])
            check("R07", "异常退出后可以重新执行失败服务", recovered_web["state"] == "READY"
                  and recovered_web["executionId"] != restarted_web["executionId"], recovered["state"])
            late_log = Path("temp/project_import_exec") / (str(uuid.UUID(recovered_web["executionId"])) + ".output.log")
            local = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with local.open(recovered_web["address"] + "late-log", timeout=10) as response:
                assert response.status == 200
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline and b"fixture-log-after-ready" not in late_log.read_bytes():
                time.sleep(.1)
            check("R07", "服务就绪后日志仍被采集", b"fixture-log-after-ready" in late_log.read_bytes(), str(late_log))
            with local.open(recovered_web["address"] + "overflow", timeout=10) as response:
                assert response.status == 200
            deadline = time.monotonic() + 30
            while True:
                state, reason = db_all("SELECT state,reason FROM biz.project_import_startup_execution WHERE execution_id=%s AND owner_id=%s AND app_id=%s",
                                      (recovered_web["executionId"], int(owner), int(serve)))[0]
                if state not in {"RUNNING", "PREPARING", "STOPPING"} or time.monotonic() >= deadline:
                    break
                time.sleep(.2)
            check("R08", "运行中输出超限自动停止并落库，不依赖查询触发", state == "FAILED" and reason == "OUTPUT_LIMIT"
                  and containers(serve) == "", state + " " + str(reason))
            check("R08", "日志文件有硬字节边界", late_log.stat().st_size <= 4 * 1024 * 1024, str(late_log.stat().st_size))
            failed_output = call(f"/project/import/apps/{serve}/startup/preparations/current?planId={both['planId']}")
            output_web = next(row for row in failed_output["services"] if row["serviceId"] == ids["网页"])
            check("R08", "业务状态保留真实输出超限原因", output_web["state"] == "FAILED" and output_web["reason"] == "OUTPUT_LIMIT",
                  output_web.get("reason"))
            prep(serve, both, "stop")
            check("D08", "运行和构建没有把产物写回导入工程", tree(workspace) == both_before == before, "changed=" + str(tree(workspace) != before))

            quiet = confirm(owner, serve, inspection, [ids["立刻退出"]])
            exited = prep(serve, quiet, "run")
            quiet_row = exited["services"][0]
            check("R03", "进程退出码 0 不算服务已就绪", quiet_row["state"] == "FAILED" and quiet_row["reason"] == "NOT_READY", quiet_row["state"] + " " + str(quiet_row.get("reason")))
            # 声明路径纳入冻结计划，根路径 404 不导致健康接口成功的服务被误拒绝。
            health_source = APP.replace("        body = b", "        if self.path != '/health':\n            self.send_response(404)\n            self.end_headers()\n            return\n        body = b")
            declaration = {"processes": [{"name": "api", "command": "python3 app.py", "port": 8080, "healthPath": "/health"}]}
            health_app = import_app(owner, "健康路径夹具", {"app.py": health_source.encode(), ".glyahh/run.json": json.dumps(declaration).encode()})
            health_before = tree(Path("temp/code_output") / ("project_" + health_app))
            _, health_inspection = prepare(owner, health_app, [])
            health_plan = confirm(owner, health_app, health_inspection, ["declared:api"])
            check("R02", "健康路径写入已确认冻结计划", health_plan["plan"]["services"][0]["readiness"]["path"] == "/health", health_plan["planRevision"])
            health_result = prep(health_app, health_plan, "run")
            health_row = health_result["services"][0]
            check("R02", "根路径404但声明健康路径成功时服务真实就绪", health_row["state"] == "READY", str(health_row))
            base_address = health_row["address"]
            response = urllib.request.urlopen(base_address.rstrip('/') + '/health', timeout=5)
            check("R02", "对应服务健康路径返回真实业务内容", json.loads(response.read())["ok"] is True, base_address)
            try:
                urllib.request.urlopen(base_address, timeout=5)
                root_code = 200
            except urllib.error.HTTPError as error:
                root_code = error.code
            check("R02", "服务根路径实际保持404而非测试替身成功", root_code == 404, str(root_code))
            prep(health_app, health_plan, "stop")
            check("R07", "健康路径服务停止后容器消失且原工程不变", not containers(health_app) and health_before == tree(Path("temp/code_output") / ("project_" + health_app)), health_app)
            # 根路径成功不能替代失败的已声明健康接口。
            failed_declaration = {"processes": [{"name": "api", "command": "python3 app.py", "port": 8080, "healthPath": "/missing"}]}
            failed_source = APP.replace("        body = b", "        if self.path == '/missing':\n            self.send_response(404)\n            self.end_headers()\n            return\n        body = b")
            health_failed = import_app(owner, "健康路径失败夹具", {"app.py": failed_source.encode(), ".glyahh/run.json": json.dumps(failed_declaration).encode()})
            _, failed_inspection = prepare(owner, health_failed, [])
            failed_plan = confirm(owner, health_failed, failed_inspection, ["declared:api"])
            failed_result = prep(health_failed, failed_plan, "run")
            failed_row = failed_result["services"][0]
            check("R02", "声明路径失败不改用其他成功路径且不遗留容器", failed_row["state"] == "FAILED" and failed_row["reason"] == "NOT_READY" and not containers(health_failed), str(failed_row))
            report["healthIdentities"] = {"appId": health_app, "planId": health_plan["planId"], "planRevision": health_plan["planRevision"], "executionId": health_row["executionId"], "failedAppId": health_failed, "failedPlanId": failed_plan["planId"]}
        report["status"] = "PASS"
        report["groupsStillOpen"] = ["R08", "S05", "G02", "G04", "E04", "E05", "F01-F07", "G01", "G03", "G05-G11", "C01-C05", "U01-U06", "S01-S04", "S06", "S07", "E01-E03", "E06"]
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = str(error)[:800]
        report["trace"] = traceback.format_exc()[-1500:]
    finally:
        for app in apps:
            try:
                listed = docker("ps", "-aq", "--filter", "label=glyahh.import.app=" + str(app))
                if listed.returncode != 0:
                    raise RuntimeError("本应用容器清单无法读取")
                for container_id in listed.stdout.decode().split():
                    if not re.fullmatch(r"[0-9a-f]{12,64}", container_id):
                        raise RuntimeError("容器身份无效")
                    docker("rm", "-f", container_id)
                if containers(app):
                    raise RuntimeError("本应用容器清理未完成")
                deleted = call("/app/delete", {"id": str(app)})
                if deleted is not True:
                    raise RuntimeError("专用应用业务删除未成功")
                resource = Path("temp/project_import_exec/apps") / (str(app) + ".volume.json")
                if resource.exists():
                    raise RuntimeError("业务删除后资源登记仍存在")
                absent = subprocess.run([WSL[0], "-d", WSL[2], "-u", "root", "--exec", "python3", "-c", "from pathlib import Path;import sys;sys.exit(1 if Path(sys.argv[1]).exists() else 0)", "/var/lib/glyahh/import-disks/" + str(app)], capture_output=True, timeout=30)
                report.setdefault("businessCleanup", []).append({"appId": str(app), "deleted": deleted, "volumeAbsentExitCode": absent.returncode})
                if absent.returncode:
                    raise RuntimeError("业务删除后受限卷仍存在")
            except Exception as error:
                report["status"] = "FAIL"
                report.setdefault("cleanupErrors", []).append({"appId": str(app), "errorType": type(error).__name__})
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({key: report[key] for key in report if key != "trace"}, ensure_ascii=False))
    raise SystemExit(0 if report["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
