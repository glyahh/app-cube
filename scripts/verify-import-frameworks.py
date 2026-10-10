"""第一项 I1-E：真实框架与多服务组合验收。

覆盖矩阵 R/S/D 的真实框架要求，全部在受控夹具上执行（不使用用户真实工程）：
- 静态页面：无安装，真实 HTTP 内容与相对资源；
- Python Web（真实框架 Flask，容器内 pip 安装到副本 .venv）；
- Node/Web（真实框架 Express，npm install 到副本 node_modules）；
- 双服务组合：两个服务同时就绪、各自地址返回各自业务内容，停止后容器消失。

只使用受控夹具与公开包源；不访问私有仓库，不调用模型。
"""

import http.cookiejar
import json
import pathlib
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import hashlib
import importlib.util
import argparse
import uuid
from import_framework_fixtures import VUE, FLASK_BUSINESS, SPRING_BOOT, VUE_SERVICE, FLASK_SERVICE, SPRING_SERVICE

REPO = pathlib.Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1:8124/api"
WSL = ["wsl.exe", "-d", "Ubuntu", "--exec"]
_strict_spec = importlib.util.spec_from_file_location("strict_item1", REPO / "scripts" / "verify-item1-complete.py")
_strict = importlib.util.module_from_spec(_strict_spec)
_strict_spec.loader.exec_module(_strict)

STATIC = {
    "index.html": b"<html><body><h1>static fixture</h1><script src=\"app.js\"></script></body></html>\n",
    "app.js": b"document.title='static-fixture';\n",
    "data.json": b'{"kind":"static","ok":true}\n',
}
FLASK = {
    "requirements.txt": b"Flask==3.1.2\n",
    "app.py": ("from flask import Flask, jsonify\n"
               "app = Flask(__name__)\n"
               "@app.get('/')\n"
               "def home():\n"
               "    return jsonify(kind='flask', ok=True)\n"
               "app.run(host='0.0.0.0', port=8081)\n").encode(),
}
NODE = {
    "package.json": json.dumps({"name": "node-fixture", "private": True, "dependencies": {"express": "4.19.2"},
                                "scripts": {"start": "node server.js"}}).encode(),
    "server.js": ("const express=require('express');const app=express();"
                  "app.get('/',(req,res)=>res.json({kind:'express',ok:true}));"
                  "app.listen(8082,'0.0.0.0');\n").encode(),
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """拒绝跳转：地址只允许是平台自己返回的本机回环地址，不能被重定向带走。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.URLError("夹具请求不允许跳转")


LOOPBACK_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

INSTALL_TERMINAL = {"SUCCEEDED", "FAILED", "REJECTED", "CANCELLED"}


def observe_install(call, app, body, record, observation_seconds=1800, sleep=time.sleep, clock=time.monotonic):
    """按任务身份观察；响应丢失只查询，不重复提交或删除现场。"""
    request_id = str(uuid.uuid4())
    prefix = f"/project/import/apps/{app}/startup"
    try:
        job = call(prefix + "/preparations/install", {**body, "requestId": request_id})
    except (TimeoutError, OSError):
        job = call(prefix + "/install-jobs/by-request/" + request_id)
    record(job)
    deadline = clock() + observation_seconds
    while job["state"] not in INSTALL_TERMINAL:
        if clock() >= deadline:
            raise TimeoutError("INSTALL_OBSERVATION_TIMED_OUT")
        sleep(2)
        job = call(prefix + "/install-jobs/" + job["jobId"])
        record(job)
    if not job.get("preparation"):
        raise AssertionError("INSTALL_PREPARATION_MISSING")
    return job["preparation"]


def selected_views(services, ids, rows):
    """运行返回顺序不属于契约，按已确认计划中的服务身份匹配。"""
    by_id = {row['serviceId']: row for row in rows}
    expected = {ids[service['name']] for service in services}
    if len(by_id) != len(rows) or set(by_id) != expected:
        raise AssertionError('运行服务身份与已确认选择不一致')
    return {service['name']: by_id[ids[service['name']]] for service in services}


def fetch(address):
    """只访问平台返回的本机回环地址；校验协议、主机与端口，阻断其它目标。"""
    parsed = urllib.parse.urlsplit(address)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost") or not parsed.port:
        raise AssertionError("只允许访问本机回环地址")
    if parsed.query or parsed.fragment:
        raise AssertionError("夹具请求不允许查询串或片段")
    if not parsed.path.startswith("/") or ".." in parsed.path or "\\" in parsed.path:
        raise AssertionError("夹具请求路径无效")
    with LOOPBACK_OPENER.open(address, timeout=10) as response:
        return response.status, response.read().decode("utf-8", "replace")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("case", nargs="?", default="all")
    parser.add_argument("--outputs", type=pathlib.Path)
    args = parser.parse_args()
    case = args.case
    if case not in {"all", "legacy", "extended", "static", "vue", "spring", "vue-python", "vue-java"}:
        raise SystemExit("用例应为 all/legacy/extended/vue/spring/vue-python/vue-java")
    evidence = args.outputs or REPO / "temp" / ("import-frameworks-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "evidence": str(evidence), "fixture": "CONTROLLED_REAL_FRAMEWORKS", "requestedCase": case,
              "runId": "framework-" + str(time.time_ns()), "sourceHash": _strict.source_hash(),
              "command": subprocess.list2cmdline([sys.executable, *sys.argv]), "exitCode": None}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}
    apps: list[str] = []
    tasks: list[str] = []
    plans = {}
    install_jobs = {}
    owner = ""

    def check(group, name, ok, detail=""):
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": str(detail)[:400]})
        if not ok:
            raise AssertionError(group + " " + name + " " + str(detail)[:400])

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
            response = opener.open(urllib.request.Request(BASE + path, data=data, headers=headers,
                                                          method=method or ("GET" if body is None else "POST")), timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.loads(response.read())
        if expected is not None and result.get("code") != expected:
            raise AssertionError(f"{path} -> {result.get('code')} {result.get('message')}")
        return result.get("data")

    def import_app(name, files):
        task = call("/project/import/tasks", {"ownerId": owner, "appName": name,
                                             "files": [{"path": p, "size": len(c)} for p, c in files.items()],
                                             "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        tasks.append(task["taskId"])
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        apps.append(str(committed["appId"]))
        report.setdefault("inputs", []).append({"appId": str(committed["appId"]), "taskId": task["taskId"],
            "contentHash": task["contentHash"], "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}})
        return str(committed["appId"])

    def plan_for(app, services):
        config = call(f"/project/import/apps/{app}/startup/configuration")
        call(f"/project/import/apps/{app}/startup/configuration",
             {"ownerId": owner, "configRevision": int(config["configRevision"]), "contextEpoch": int(config["contextEpoch"]),
              "scope": ".", "services": services}, method="PUT")
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        candidates = inspection["result"]["candidates"]
        report.setdefault("inspections", {})[app] = candidates
        ids = {row["name"]: row["id"] for row in candidates}
        wanted = [row["name"] for row in services]
        missing = [name for name in wanted if name not in ids]
        if missing:
            raise AssertionError(f"手动配置的服务未出现在候选里：{missing}；候选={list(ids)}")
        selected = [ids[name] for name in wanted]
        plan = call(f"/project/import/apps/{app}/startup/plans",
                    {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": selected})
        report.setdefault("reviewedPlanContents", {})[app] = plan["plan"]
        plan = call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}/confirm",
                    {"ownerId": owner, "planRevision": plan["planRevision"], "aggregateAcknowledged": bool(plan["plan"].get("aggregates"))})
        report.setdefault("plans", []).append({"appId": app, "planId": plan["planId"], "planRevision": plan["planRevision"], "serviceIds": ids})
        plans[app] = plan
        return plan, ids

    def prep(app, plan, kind, approved=None):
        print(f"preparation app={app} stage={kind} observationTimeout=1800s", flush=True)
        body = {"ownerId": owner, "planId": plan["planId"], "planRevision": plan["planRevision"]}
        if kind == "install":
            body["approved"] = approved
            def record(job):
                install_jobs[app] = job
                (evidence / "install-jobs.json").write_text(json.dumps(install_jobs, ensure_ascii=False, indent=2), encoding="utf-8")
            observe_install(call, app, body, record)
            return call(f"/project/import/apps/{app}/startup/preparations/current?planId={plan['planId']}")
        return call(f"/project/import/apps/{app}/startup/preparations/{kind}", body, timeout=1800)

    def containers(app):
        """本应用的执行容器；排除 preflight 的瞬时探针容器。"""
        listed = subprocess.run(WSL + ["docker", "ps", "-a", "--filter", "label=glyahh.import.app=" + app,
                                       "--format", '{{.ID}} {{.Label "glyahh.import.execution"}}'],
                                capture_output=True, timeout=60)
        if listed.returncode != 0:
            raise AssertionError("无法核对容器清理")
        rows = [row.split() for row in listed.stdout.decode().splitlines() if row.strip()]
        return "\n".join(row[0] for row in rows if len(row) > 1 and row[1] != "probe")

    def browser(web, api=None, label="vue"):
        job = evidence / (label + "-job.json")
        result = evidence / (label + "-browser.json")
        prior = json.loads(fetch(api.rstrip('/') + '/records')[1])['values'] if api else []
        job.write_text(json.dumps({"web": web, "api": api, "value": "business-" + secrets.token_hex(8), "priorValues": prior}), encoding="utf-8")
        tested = subprocess.run(["node", str(REPO / "scripts/verify-import-framework-browser.mjs"), str(job), str(result)],
                                capture_output=True, timeout=90)
        (evidence / (label + "-browser.log")).write_bytes(tested.stdout + tested.stderr)
        check("R02", label + "真实浏览器业务验收", tested.returncode == 0 and result.is_file()
              and json.loads(result.read_text())["status"] == "PASS", "exit=" + str(tested.returncode))

    def real_app(name, files, services):
        app = import_app(name, files)
        source = REPO / "temp/code_output" / ("project_" + app)
        before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in files}
        plan, ids = plan_for(app, services)
        installed = prep(app, plan, "install", True)
        report.setdefault("preparations", {})[app] = installed
        check("D03", name + "安装完成但未启动", installed["state"] == "SUCCEEDED" and installed["servicesStarted"] is False,
              installed["state"] + " " + str(installed.get("reason")))
        running = prep(app, plan, "run")
        report.setdefault("runs", {})[app] = running
        check("R02", name + "全部选中服务真实就绪", running["state"] == "READY"
              and len(running["services"]) == len(services) and all(row["state"] == "READY" for row in running["services"]),
              json.dumps(running, ensure_ascii=False)[:300])
        check("F07", name + "安装构建运行未修改导入源码", before == {
            name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in files})
        return app, plan, selected_views(services, ids, running['services'])

    try:
        account = "frames_" + secrets.token_hex(8)
        password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        report['fixtures'] = [{'ownerId': owner, 'account': account}]
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")

        if case in ("all", "legacy", "static"):
            # 1. 静态页面：不安装，真实内容与相对资源可访问
            static_app = import_app("静态页面夹具", STATIC)
            static_plan, _ = plan_for(static_app, [
                {"id": None, "name": "站点", "directory": ".", "command": "python3 -m http.server 8083", "install": None, "build": None, "port": 8083},
            ])
            static_install = prep(static_app, static_plan, "install", True)
            check("D03", "静态页面计划不需要安装时安装步骤明确", static_install["state"] in ("NOT_REQUIRED", "SUCCEEDED"), static_install["state"])
            static_run = prep(static_app, static_plan, "run")
            report["staticRun"] = static_run["services"]
            site = static_run["services"][0]
            check("R02", "静态页面服务真实就绪", site["state"] == "READY", json.dumps(site, ensure_ascii=False)[:200])
            status, body = fetch(site["address"])
            status_asset, asset = fetch(site["address"].rstrip("/") + "/data.json")
            check("R02", "静态入口与相对资源都由真实服务返回", status == 200 and "static fixture" in body and status_asset == 200 and '"kind":"static"' in asset,
                  f"{status}/{status_asset} {body[:60]}")
            declared = [row for row in report.get("inspections", {}).get(static_app, []) if row.get("id") == "index.html"]
            check("S04", "平台同时识别出静态入口候选，且不发明启动命令",
                  bool(declared) and declared[0].get("source") == "STATIC" and declared[0].get("command") is None
                  and declared[0].get("evidence") == "index.html",
                  json.dumps(declared, ensure_ascii=False)[:200])
            prep(static_app, static_plan, "stop")
            check("R07", "静态服务停止后容器消失", containers(static_app) == "", containers(static_app))
            if case == 'static':
                report['checks'].append({'group': 'P2.1', 'name': 'static-identify-install-run', 'status': 'PASS', 'assertion': 'static-identify-install-run', 'detail': 'static identify/install/run passed'})
                report['status'] = 'PASS'
                return 0

            # 2. 真实 Python 框架：容器内建 venv 安装 Flask，安装与运行分开
            flask_app = import_app("Python框架夹具", FLASK)
            flask_plan, _ = plan_for(flask_app, [
                {"id": None, "name": "接口", "directory": ".", "command": ".venv/bin/python app.py",
                 "install": "python3 -m venv .venv && .venv/bin/pip install --no-cache-dir -r requirements.txt", "build": None, "port": 8081},
            ])
            flask_install = prep(flask_app, flask_plan, "install", True)
            check("D03", "真实 Python 框架依赖安装成功且不启动服务",
                  flask_install["state"] == "SUCCEEDED" and flask_install.get("servicesStarted") is False, json.dumps(flask_install, ensure_ascii=False)[:200])
            flask_run = prep(flask_app, flask_plan, "run")
            api = flask_run["services"][0]
            check("R02", "Flask 服务真实就绪", api["state"] == "READY", json.dumps(api, ensure_ascii=False)[:200])
            status, body = fetch(api["address"])
            check("R02", "Flask 返回真实业务内容（不是端口存活）", status == 200 and '"kind":"flask"' in body, f"{status} {body[:80]}")
            prep(flask_app, flask_plan, "stop")
            check("R07", "Flask 服务停止后容器消失", containers(flask_app) == "", containers(flask_app))

            # 3. 真实 Node 框架：npm install 到副本，npm start 启动
            node_app = import_app("Node框架夹具", NODE)
            node_plan, _ = plan_for(node_app, [
                {"id": None, "name": "网页", "directory": ".", "command": "npm start",
                 "install": "npm install --no-audit --no-fund", "build": None, "port": 8082},
            ])
            node_install = prep(node_app, node_plan, "install", True)
            check("D03", "真实 Node 框架依赖安装成功且不启动服务",
                  node_install["state"] == "SUCCEEDED" and node_install.get("servicesStarted") is False, json.dumps(node_install, ensure_ascii=False)[:200])
            node_run = prep(node_app, node_plan, "run")
            web = node_run["services"][0]
            check("R02", "Express 服务真实就绪", web["state"] == "READY", json.dumps(web, ensure_ascii=False)[:200])
            status, body = fetch(web["address"])
            check("R02", "Express 返回真实业务内容", status == 200 and '"kind":"express"' in body, f"{status} {body[:80]}")
            prep(node_app, node_plan, "stop")
            check("R07", "Express 服务停止后容器消失", containers(node_app) == "", containers(node_app))

            # 4. 双服务组合：两个真实服务同时就绪，各自业务内容独立
            both_app = import_app("双服务夹具", {**{"web/" + k: v for k, v in NODE.items()}, **{"api/" + k: v for k, v in FLASK.items()}})
            both_plan, _ = plan_for(both_app, [
                {"id": None, "name": "网页", "directory": "web", "command": "npm start",
                 "install": "npm install --no-audit --no-fund", "build": None, "port": 8082},
                {"id": None, "name": "接口", "directory": "api", "command": ".venv/bin/python app.py",
                 "install": "python3 -m venv .venv && .venv/bin/pip install --no-cache-dir -r requirements.txt", "build": None, "port": 8081},
            ])
            both_install = prep(both_app, both_plan, "install", True)
            check("D05", "双服务共享准备一次成功且不启动服务",
                  both_install["state"] == "SUCCEEDED" and both_install.get("servicesStarted") is False, json.dumps(both_install, ensure_ascii=False)[:200])
            both_run = prep(both_app, both_plan, "run")
            report["bothServices"] = both_run["services"]
            check("R01", "两个已选服务都进入执行", both_run["state"] in ("READY", "PARTIAL") and len(both_run["services"]) == 2,
                  json.dumps(both_run, ensure_ascii=False)[:300])
            kinds = []
            for service in both_run["services"]:
                if service["state"] == "READY" and service.get("address"):
                    _, body = fetch(service["address"])
                    kinds.append("express" if "express" in body else "flask" if "flask" in body else "other")
            check("R02", "双服务各自返回各自业务内容，不串应用", sorted(kinds) == ["express", "flask"], str(kinds))
            check("R04", "双服务运行中容器都属于本应用", containers(both_app) != "", containers(both_app))
            prep(both_app, both_plan, "stop")
            check("R07", "双服务停止后容器全部消失", containers(both_app) == "", containers(both_app))

        # 5. 已确认的 Vue 与 Spring Boot 支持类型，各自构建与真实页面/接口。
        if case in ("all", "extended", "vue"):
            vue_app, vue_plan, vue_runs = real_app("Vue构建页面", VUE, [VUE_SERVICE])
            browser(next(iter(vue_runs.values()))["address"], label="vue")
            prep(vue_app, vue_plan, "stop")
        if case in ("all", "extended", "spring"):
            java_app, java_plan, java_runs = real_app("SpringBoot接口", SPRING_BOOT, [SPRING_SERVICE])
            _, java_body = fetch(next(iter(java_runs.values()))["address"])
            check("R02", "Spring Boot真实接口业务响应", json.loads(java_body) == {"kind": "springboot", "ok": True})
            prep(java_app, java_plan, "stop")

        # 6. 真正的前端请求后端并改变业务状态，不用两个独立 JSON 接口代替全栈。
        for label, backend, backend_service in [("vue-python", FLASK_BUSINESS, FLASK_SERVICE), ("vue-java", SPRING_BOOT, SPRING_SERVICE)]:
            if case not in ("all", "extended", label):
                continue
            files = {**{"web/" + key: value for key, value in VUE.items()}, **{"api/" + key: value for key, value in backend.items()}}
            services = [{**VUE_SERVICE, "directory": "web"}, {**backend_service, "directory": "api"}]
            app, plan, runs = real_app(label, files, services)
            api_name = FLASK_SERVICE['name'] if label == 'vue-python' else SPRING_SERVICE['name']
            browser(runs[VUE_SERVICE['name']]['address'], runs[api_name]['address'], label)
            prep(app, plan, "stop")
            check("R07", label + "停止后全部本应用容器消失", containers(app) == "")
            restarted = prep(app, plan, "run")
            check("R07", label + "停止后重新运行全部就绪", restarted["state"] == "READY")
            ids = report["plans"][-1]["serviceIds"]
            runs = selected_views(services, ids, restarted["services"])
            browser(runs[VUE_SERVICE['name']]['address'], runs[api_name]['address'], label + "-restart")
            stopped = prep(app, plan, "stop")
            check("R07", label + "再次停止确认", stopped["reason"] == "STOPPED" and containers(app) == "")
            if label == 'vue-java':
                pom = REPO / 'temp/code_output' / ('project_' + app) / 'api/pom.xml'
                original = pom.read_bytes()
                try:
                    pom.write_bytes(original.replace(b'1.0.0', b'1.0.1'))
                    stale = call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}")
                    check('D07', '安装后pom变化使旧计划失效', stale['state'] == 'STALE')
                    try:
                        prep(app, plan, 'run')
                    except AssertionError as error:
                        check('D07', '旧pom计划拒绝运行且不自动重装', '40900' in str(error) and containers(app) == '')
                    else:
                        check('D07', '旧pom计划拒绝运行且不自动重装', False)
                finally:
                    pom.write_bytes(original)

        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = str(error)[:500]
    finally:
        # 先落盘失败现场，再根据安装终态和停止确认决定是否删除。
        (evidence / "before-cleanup.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        for app in apps:
            try:
                job = install_jobs.get(app)
                if job and job["state"] not in INSTALL_TERMINAL:
                    report.setdefault("cleanupErrors", []).append({"appId": app, "reason": "INSTALL_ACTIVE_RETAINED"})
                    continue
                if app in plans:
                    latest = call(f"/project/import/apps/{app}/startup/plans/{plans[app]['planId']}")
                    stopped = prep(app, plans[app], "stop") if latest['state'] == 'CONFIRMED' else {'reason': 'STALE'}
                    if stopped["reason"] not in ("STOPPED", "NOT_RUNNING", "STALE") or containers(app):
                        report.setdefault("cleanupErrors", []).append({"appId": app, "reason": "STOP_UNCONFIRMED_RETAINED"})
                        continue
                deleted = call("/app/delete", {"id": app})
                native = subprocess.run([WSL[0], "-d", WSL[2], "-u", "root", "--exec", "test", "-e",
                    "/var/lib/glyahh/import-disks/" + app], capture_output=True, timeout=30)
                clean = deleted is True and containers(app) == "" and native.returncode == 1
                report.setdefault("cleanup", []).append({"appId": app, "businessDelete": deleted is True,
                    "containersGone": containers(app) == "", "nativeVolumeGone": native.returncode == 1})
                if not clean:
                    report.setdefault("cleanupErrors", []).append(app)
            except Exception as error:
                report.setdefault("cleanupErrors", []).append({"appId": app, "errorType": type(error).__name__})
        for task_id in tasks:
            try:
                current = call("/project/import/tasks/" + task_id)
                if current["status"] not in ("SUCCEEDED", "CANCELLED", "FAILED"):
                    current = call(f"/project/import/tasks/{task_id}/cancel?revision={current['revision']}", method="POST")
                    if current["status"] != "CANCELLED":
                        report.setdefault("cleanupErrors", []).append({"taskId": task_id, "state": current["status"]})
            except Exception as error:
                report.setdefault("cleanupErrors", []).append({"taskId": task_id, "errorType": type(error).__name__})
        if report.get("cleanupErrors"):
            report["status"] = "FAIL"
        report["exitCode"] = 0 if report["status"] == "PASS" else 1
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        for row in report["checks"]:
            print(row["status"], row["group"], row["name"], "|", row["detail"][:150])
        print("status:", report["status"], report.get("error", ""))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
