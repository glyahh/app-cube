"""补齐能用受控夹具证实的安装与运行缺口。不调用模型，不访问私有仓库，不制造停不掉的容器。"""
import json
import os
import secrets
import subprocess
import time
import traceback
import http.cookiejar
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import psycopg

WSL = ["wsl.exe", "-d", os.environ.get("AGENT_WSL_DISTRO", "Ubuntu"), "--exec"]
APP = """from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"ok":true,"side":"%s"}'
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *args):
        return
ThreadingHTTPServer(("0.0.0.0", %s), Handler).serve_forever()
"""
ALLOC = """chunks = []
for i in range(160):
    block = bytearray(8 * 1024 * 1024)
    block[0] = 1
    chunks.append(block)
print(len(chunks), flush=True)
"""


def docker(*args, timeout=60):
    return subprocess.run(WSL + ["docker", *args], capture_output=True, timeout=timeout)


def main():
    evidence = Path("temp/import-startup-preparation-20261005") / ("gaps-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE", "fixture": "CONTROLLED_SCRIPTS_ONLY",
              "skipped": {
                  "R02": "两个服务都只发布到本机回环，容器内访问不到对方的 127.0.0.1，无法做前后端互调",
                  "R03-port": "宿主端口由引擎分配，两个服务不会抢同一个宿主端口",
                  "R06-parallel": "并行导入的冲突策略还没有单独定死",
                  "R07": "停止失败要制造杀不掉的容器；重启对账会打断当前后端",
                  "R08-cpu-disk": "CPU 节流和写满磁盘没有稳定的短时判定",
                  "S05": "需要真实模型补充成功",
                  "E04-E05": "需要真实业务缺陷，不用脚本故障冒充",
                  "G02-G04": "有效私有仓库按原决定暂缓",
              }, "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines()
               if line.startswith("AGENT_POSTGRES_DSN="))
    apps = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}
    owner = {"id": None}

    def check(group, name, ok, detail=""):
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": str(detail)[:500]})
        if not ok:
            raise AssertionError(group + " " + name + " " + str(detail)[:500])

    def db_all(sql, params):
        with psycopg.connect(dsn) as db:
            return db.execute(sql, params).fetchall()

    def executions(app):
        return db_all("SELECT slot,directory,state,reason FROM biz.project_import_startup_execution WHERE app_id=%s ORDER BY created_at", (int(app),))

    def containers(app):
        listed = docker("ps", "-a", "--filter", "label=glyahh.import.app=" + str(app), "--format", "{{.ID}} {{.Status}}")
        return [line for line in listed.stdout.decode("utf-8", "replace").splitlines() if line.strip()]

    def call(path, body=None, method=None, expected=20000, timeout=180):
        headers = {"Origin": "http://localhost:5173"}
        if csrf["value"]:
            headers["X-Project-Import-CSRF"] = csrf["value"]
        data = None
        if isinstance(body, bytes):
            data = body
            headers["Content-Type"] = "application/octet-stream"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
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

    def import_app(name, files):
        task = call("/project/import/tasks", {"ownerId": owner["id"], "appName": name,
                                              "files": [{"path": path, "size": len(content)} for path, content in files.items()],
                                              "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        apps.append(committed["appId"])
        return committed["appId"]

    def configure(app, services):
        config = call(f"/project/import/apps/{app}/startup/configuration")
        call(f"/project/import/apps/{app}/startup/configuration", {"ownerId": owner["id"], "configRevision": int(config["configRevision"]),
             "contextEpoch": int(config["contextEpoch"]), "scope": ".", "services": services}, method="PUT")
        return call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner["id"], "scope": "."})

    def confirm(app, inspection, service_ids, acknowledge=False, expected=20000):
        plan = call(f"/project/import/apps/{app}/startup/plans", {"ownerId": owner["id"], "scanId": inspection["scanId"], "serviceIds": service_ids})
        return call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}/confirm",
                    {"ownerId": owner["id"], "planRevision": plan["planRevision"], "aggregateAcknowledged": acknowledge}, expected=expected)

    def prep(app, plan, kind, approved=None, expected=20000, timeout=180):
        body = {"ownerId": owner["id"], "planId": plan["planId"], "planRevision": plan["planRevision"]}
        if approved is not None:
            body["approved"] = approved
        return call(f"/project/import/apps/{app}/startup/preparations/{kind}", body, expected=expected, timeout=timeout)

    try:
        account = "gap_" + secrets.token_hex(6)
        password = secrets.token_urlsafe(18)
        owner["id"] = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")

        # 1. 聚合脚本未确认不能执行；只运行选中的服务，不额外执行未选脚本。
        web_pkg = json.dumps({"name": "web", "packageManager": "npm@10.0.0", "scripts": {
            "dev": "concurrently \"npm:ui\" \"npm:api\"", "ui": "echo UI", "api": "echo API", "start": "echo START"}}).encode()
        agg = import_app("聚合选择夹具", {"web/package.json": web_pkg, "web/package-lock.json": b"{}", "solo/app.py": (APP % ("solo", 8080)).encode()})
        inspection = configure(agg, [{"id": None, "name": "solo", "directory": "solo", "command": "python3 app.py", "install": None, "build": None, "port": 8080}])
        by_id = {row["id"]: row for row in inspection["result"]["candidates"]}
        dev = next(row for row in by_id.values() if row["id"].endswith(":dev"))
        solo = next(row for row in by_id.values() if row["name"] == "solo")
        confirm(agg, inspection, [dev["id"]], acknowledge=False, expected=40000)
        check("R01", "聚合启动脚本未确认展开范围时不能确认计划", True, dev["id"])
        chosen = confirm(agg, inspection, [solo["id"]])
        started = prep(agg, chosen, "run")
        dirs = {row[1] for row in executions(agg)}
        check("R01", "只启动选中的服务，未选的聚合脚本没有执行", started["services"][0]["state"] == "READY" and dirs == {"solo"} and len(containers(agg)) == 1,
              str(dirs) + " " + str(containers(agg)))
        prep(agg, chosen, "stop")

        # 2. 同一目录的同一构建只跑一次，失败只挡住依赖它的服务。
        shared = import_app("共享构建夹具", {
            "shared/a.py": b"print('a')\n", "shared/b.py": b"print('b')\n", "other/app.py": (APP % ("other", 8080)).encode()})
        inspection = configure(shared, [
            {"id": None, "name": "shared-a", "directory": "shared", "command": "python3 a.py", "install": None, "build": "exit 4", "port": None},
            {"id": None, "name": "shared-b", "directory": "shared", "command": "python3 b.py", "install": None, "build": "exit 4", "port": None},
            {"id": None, "name": "other", "directory": "other", "command": "python3 app.py", "install": None, "build": None, "port": 8080},
        ])
        ids = {row["name"]: row["id"] for row in inspection["result"]["candidates"]}
        plan = confirm(shared, inspection, [ids["shared-a"], ids["shared-b"], ids["other"]], acknowledge=True)
        running = prep(shared, plan, "run")
        rows = {row["serviceId"]: row for row in running["services"]}
        build_rows = [row for row in executions(shared) if str(row[0]).startswith("build:")]
        check("D05", "共享构建只执行一次，失败只挡住依赖该构建的服务", len(build_rows) == 1 and build_rows[0][2] == "FAILED"
              and rows[ids["shared-a"]]["phase"] == "BUILD" and rows[ids["shared-b"]]["phase"] == "BUILD"
              and rows[ids["other"]]["state"] == "READY" and rows[ids["other"]].get("address"),
              json.dumps({"build": build_rows, "services": running["services"]}, ensure_ascii=False, default=str)[:500])
        other_execution = rows[ids["other"]]["executionId"]

        # 3. 工作区变化后旧确认失效，不能把旧成功项并进新结果。
        target = Path("temp/code_output") / ("project_" + shared) / "other" / "app.py"
        target.write_bytes(target.read_bytes() + b"\n# changed\n")
        stale = call(f"/project/import/apps/{shared}/startup/plans/{plan['planId']}")
        check("R05", "工作区内容变化后原确认失效", stale["state"] == "STALE", stale["state"] + " " + str(stale.get("staleReason")))
        prep(shared, plan, "retry", expected=40900)
        check("R05", "失效计划的重试被拒绝", True, "40900")
        still = [row for row in executions(shared) if row[1] == "other" and row[2] == "RUNNING"]
        check("R05", "失效后原来的成功执行还在，没有被改写成新版本的成功", len(still) == 1 and str(still[0]) and other_execution, str(still))
        fresh = configure(shared, [
            {"id": ids["shared-a"].removeprefix("manual:"), "name": "shared-a", "directory": "shared", "command": "python3 a.py", "install": None, "build": "exit 4", "port": None},
            {"id": ids["shared-b"].removeprefix("manual:"), "name": "shared-b", "directory": "shared", "command": "python3 b.py", "install": None, "build": "exit 4", "port": None},
            {"id": ids["other"].removeprefix("manual:"), "name": "other", "directory": "other", "command": "python3 app.py", "install": None, "build": None, "port": 8080},
        ])
        fresh_ids = {row["name"]: row["id"] for row in fresh["result"]["candidates"]}
        renewed = confirm(shared, fresh, [fresh_ids["other"]])
        renewed_run = prep(shared, renewed, "run")
        renewed_exec = renewed_run["services"][0]["executionId"]
        old_container = docker("ps", "-aq", "--filter", "label=glyahh.import.execution=" + other_execution)
        check("R05", "新计划的执行编号与旧成功项不同，旧容器已停", renewed_exec != other_execution and old_container.stdout.decode().strip() == "",
              renewed_exec + " old=" + other_execution + " containers=" + old_container.stdout.decode().strip())
        if renewed_run["services"][0]["state"] == "READY":
            prep(shared, renewed, "stop")

        # 4. 声明了端口但进程迟迟不监听，不能记成就绪。
        slow = import_app("启动超时夹具", {"app.py": b"import time\ntime.sleep(60)\n"})
        inspection = configure(slow, [{"id": None, "name": "slow", "directory": ".", "command": "python3 app.py", "install": None, "build": None, "port": 8080}])
        slow_id = next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "slow")
        slow_plan = confirm(slow, inspection, [slow_id])
        slow_run = prep(slow, slow_plan, "run")
        check("R03", "声明端口后在就绪时限内没有可访问地址，记为尚未就绪并删除容器", slow_run["services"][0]["state"] == "FAILED"
              and slow_run["services"][0]["reason"] == "NOT_READY" and containers(slow) == [],
              slow_run["services"][0]["state"] + " " + str(slow_run["services"][0].get("reason")) + " " + str(containers(slow)))

        # 5. 输出和内存达到执行器上限时失败，不把截断或超额记成成功。
        flood = import_app("输出上限夹具", {"app.py": b"print(1)\n", "requirements.txt": b"# fixture\n"})
        inspection = configure(flood, [{"id": None, "name": "flood", "directory": ".", "command": "python3 app.py", "install": "yes glyahh-output-flood", "build": None, "port": None}])
        flood_id = next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "flood")
        flood_plan = confirm(flood, inspection, [flood_id])
        flooded = prep(flood, flood_plan, "install", True)
        check("R08", "输出达到上限时安装失败，不继续启动", flooded["state"] == "FAILED" and flooded["reason"] == "OUTPUT_LIMIT" and flooded["servicesStarted"] is False,
              flooded["state"] + " " + str(flooded.get("reason")))

        memory = import_app("内存上限夹具", {"alloc.py": ALLOC.encode(), "requirements.txt": b"# fixture\n"})
        inspection = configure(memory, [{"id": None, "name": "memory", "directory": ".", "command": "python3 alloc.py", "install": "python3 alloc.py", "build": None, "port": None}])
        memory_id = next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "memory")
        memory_plan = confirm(memory, inspection, [memory_id])
        exhausted = prep(memory, memory_plan, "install", True, timeout=180)
        # 安装记录把含 MEMORY 的原因记成 REFUSED，避免当成已安装。执行行仍是 FAILED / MEMORY_LIMIT，说明命令跑过并被硬上限杀掉，不是启动前拒绝。
        memory_rows = executions(memory)
        blocked = prep(memory, memory_plan, "run")
        check("R08", "内存超过容器硬上限时安装失败，导入工程仍在，且不再启动",
              exhausted["state"] == "REFUSED" and exhausted["reason"] == "MEMORY_LIMIT" and exhausted["servicesStarted"] is False
              and memory_rows == [("install", ".", "FAILED", "MEMORY_LIMIT")]
              and blocked["state"] == "REFUSED" and blocked["services"] == [] and containers(memory) == []
              and (Path("temp/code_output") / ("project_" + memory) / "alloc.py").is_file(),
              exhausted["state"] + " " + str(exhausted.get("reason")) + " rows=" + str(memory_rows) + " run=" + blocked["state"])
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = str(error)[:800]
        report["trace"] = traceback.format_exc()[-1500:]
    finally:
        for app in apps:
            listed = docker("ps", "-aq", "--filter", "label=glyahh.import.app=" + str(app))
            for container_id in listed.stdout.decode().split():
                docker("rm", "-f", container_id)
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({key: report[key] for key in report if key != "trace"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
