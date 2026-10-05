"""补跑能落地的导入测试：双服务宿主请求、缺陷副本修复、CPU 节流、副本字节上限、并行导入、端口占用、真实模型补充、GitHub 私有仓库。"""
import json
import os
import secrets
import subprocess
import threading
import time
import traceback
import http.cookiejar
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import psycopg

WSL = ["wsl.exe", "-d", os.environ.get("AGENT_WSL_DISTRO", "Ubuntu"), "--exec"]
IMAGE = "glyahh-sandbox-runtime:24.04"
API = """from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"service":"api","item":"sample"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *args):
        return
ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
"""
WEB_PY = """from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
PAGE = "<!doctype html><html><body><p>前端页面</p></body></html>".encode()
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)
    def log_message(self, fmt, *args):
        return
ThreadingHTTPServer(("0.0.0.0", 4173), Handler).serve_forever()
"""
CPU = r"""import pathlib, threading, time
end = time.time() + 4
def burn():
    x = 0
    while time.time() < end:
        x += 1
threads = [threading.Thread(target=burn) for _ in range(8)]
[item.start() for item in threads]
[item.join() for item in threads]
text = pathlib.Path("/sys/fs/cgroup/cpu.stat").read_text(encoding="utf-8", errors="replace")
pathlib.Path("/work/cpu-stat.txt").write_text(text, encoding="utf-8")
"""
DISK = r"""import os
fd = os.open("/work/big.bin", os.O_CREAT | os.O_RDWR, 0o644)
os.ftruncate(fd, (1 << 30) + 4096)
os.close(fd)
"""


def docker(*args, timeout=60):
    return subprocess.run(WSL + ["docker", *args], capture_output=True, timeout=timeout)


def env_value(name):
    for line in Path(".env").read_text(encoding="utf-8-sig").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("missing " + name)


def redact(text, secrets_to_hide):
    value = str(text)
    for secret in secrets_to_hide:
        if secret:
            value = value.replace(secret, "[secret]")
    return value[:800]


def main():
    evidence = Path("temp/import-startup-preparation-20261005") / ("remaining-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    github_token = env_value("IMPORT_GITHUB_TOKEN")
    model_key = env_value("AGENT_MODEL_API_KEY")
    hidden = [github_token, model_key]
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE",
              "skipped": {"G04": "没有 Gitee Token", "R07-live-unkillable": "不制造杀不掉的容器；停止失败判定由已有单测覆盖",
                          "kernel-disk": "不写满磁盘，只测执行器字节上限"},
              "evidence": str(evidence)}
    dsn = env_value("AGENT_POSTGRES_DSN")
    apps = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}
    owner = {"id": None}

    def check(group, name, ok, detail=""):
        safe = redact(detail, hidden)
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": safe[:500]})
        if not ok:
            raise AssertionError(group + " " + name + " " + safe[:500])

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
        if expected is not None and result.get("code") != expected:
            raise AssertionError(redact(f"{path} -> {result.get('code')} {result.get('message')}", hidden))
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

    def confirm(app, inspection, service_ids):
        plan = call(f"/project/import/apps/{app}/startup/plans", {"ownerId": owner["id"], "scanId": inspection["scanId"], "serviceIds": service_ids})
        return call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}/confirm",
                    {"ownerId": owner["id"], "planRevision": plan["planRevision"], "aggregateAcknowledged": True})

    def prep(app, plan, kind, approved=None, timeout=180):
        body = {"ownerId": owner["id"], "planId": plan["planId"], "planRevision": plan["planRevision"]}
        if approved is not None:
            body["approved"] = approved
        return call(f"/project/import/apps/{app}/startup/preparations/{kind}", body, timeout=timeout)

    def output_text(execution_id):
        path = Path("temp/project_import_exec") / (execution_id + ".output.log")
        return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""

    try:
        account = "remain_" + secrets.token_hex(6)
        password = secrets.token_urlsafe(18)
        owner["id"] = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")

        # 1. 宿主机分别请求两个本机地址，核对前端页面和接口内容。
        dual = import_app("双服务宿主请求", {"web/app.py": WEB_PY.encode(), "api/app.py": API.encode()})
        inspection = configure(dual, [
            {"id": None, "name": "web", "directory": "web", "command": "python3 app.py", "install": None, "build": None, "port": 4173},
            {"id": None, "name": "api", "directory": "api", "command": "python3 app.py", "install": None, "build": None, "port": 8080},
        ])
        ids = {row["name"]: row["id"] for row in inspection["result"]["candidates"]}
        plan = confirm(dual, inspection, [ids["web"], ids["api"]])
        running = prep(dual, plan, "run")
        by_service = {row["serviceId"]: row for row in running["services"]}
        web_row, api_row = by_service[ids["web"]], by_service[ids["api"]]
        if not web_row.get("address") or not api_row.get("address"):
            check("R02", "宿主机同时访问前端页面和接口并核对内容", False, json.dumps(running["services"], ensure_ascii=False)[:500])
        web_body = urllib.request.urlopen(web_row["address"], timeout=10).read().decode()
        api_body = urllib.request.urlopen(api_row["address"], timeout=10).read().decode()
        check("R02", "宿主机同时访问前端页面和接口并核对内容", web_row["state"] == "READY" and api_row["state"] == "READY"
              and web_row["address"] != api_row["address"] and "前端页面" in web_body and '"item":"sample"' in api_body,
              web_row.get("address") + " " + api_row.get("address") + " " + api_body[:80])
        prep(dual, plan, "stop")

        # 2. 只改执行副本，原件保持 pang。
        defect = import_app("缺陷窄闭环", {"answer.py": b'print("pang")\n'})
        inspection = configure(defect, [{"id": None, "name": "answer", "directory": ".", "command": "python3 answer.py", "install": None, "build": None, "port": None}])
        answer_id = next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "answer")
        defect_plan = confirm(defect, inspection, [answer_id])
        first = prep(defect, defect_plan, "run")
        first_exec = first["services"][0]["executionId"]
        original = (Path("temp/code_output") / ("project_" + defect) / "answer.py").read_text(encoding="utf-8")
        copy = Path("temp/project_import_exec/apps") / defect / "answer.py"
        check("E04", "原件打印 pang，执行副本与原件分开", "pang" in output_text(first_exec) and original.strip() == 'print("pang")' and copy.is_file(),
              output_text(first_exec).strip())
        copy.write_text('print("pong")\n', encoding="utf-8")
        second = prep(defect, defect_plan, "run")
        second_exec = second["services"][0]["executionId"]
        original_after = (Path("temp/code_output") / ("project_" + defect) / "answer.py").read_text(encoding="utf-8")
        check("E05", "副本改为 pong 后再跑成功，原件仍是 pang", second_exec != first_exec and "pong" in output_text(second_exec)
              and original_after.strip() == 'print("pang")' and copy.read_text(encoding="utf-8").strip() == 'print("pong")',
              output_text(second_exec).strip())

        # 3. CPU 配额下忙循环应出现节流记录。
        cpu_app = import_app("CPU节流", {"burn.py": CPU.encode(), "requirements.txt": b"# fixture\n"})
        inspection = configure(cpu_app, [{"id": None, "name": "cpu", "directory": ".", "command": "python3 burn.py", "install": "python3 burn.py", "build": None, "port": None}])
        cpu_id = next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "cpu")
        cpu_plan = confirm(cpu_app, inspection, [cpu_id])
        cpu_install = prep(cpu_app, cpu_plan, "install", True, timeout=180)
        stat = (Path("temp/project_import_exec/apps") / cpu_app / "cpu-stat.txt").read_text(encoding="utf-8", errors="replace")
        throttled = next(int(line.split()[1]) for line in stat.splitlines() if line.startswith("nr_throttled"))
        check("R08", "CPU 配额使忙循环出现节流", cpu_install["state"] == "SUCCEEDED" and throttled > 0, "nr_throttled=" + str(throttled))

        # 4. 副本逻辑大小超过 1GiB 时安装失败，不记成功。
        disk_app = import_app("副本字节上限", {"pad.py": DISK.encode(), "requirements.txt": b"# fixture\n"})
        inspection = configure(disk_app, [{"id": None, "name": "disk", "directory": ".", "command": "python3 pad.py", "install": "python3 pad.py", "build": None, "port": None}])
        disk_id = next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "disk")
        disk_plan = confirm(disk_app, inspection, [disk_id])
        disk_install = prep(disk_app, disk_plan, "install", True, timeout=180)
        check("R08", "副本超过字节上限时安装失败", disk_install["state"] == "FAILED" and disk_install["reason"] == "DISK_QUOTA" and disk_install["servicesStarted"] is False,
              disk_install["state"] + " " + str(disk_install.get("reason")))

        # 5. 两份导入同时进行，得到两个独立应用。
        left, right = {}, {}

        def side(box, name):
            try:
                box["app"] = import_app(name, {"readme.txt": name.encode()})
            except Exception as error:
                box["error"] = redact(error, hidden)

        threads = [threading.Thread(target=side, args=(left, "并行甲")), threading.Thread(target=side, args=(right, "并行乙"))]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        check("R06", "并行导入得到两个独立应用", "error" not in left and "error" not in right and left["app"] != right["app"]
              and (Path("temp/code_output") / ("project_" + left["app"]) / "readme.txt").read_text(encoding="utf-8") == "并行甲"
              and (Path("temp/code_output") / ("project_" + right["app"]) / "readme.txt").read_text(encoding="utf-8") == "并行乙",
              str(left.get("app")) + " " + str(right.get("app")) + " " + left.get("error", "") + right.get("error", ""))

        # 6. 先用容器占住宿主端口，再按同样的 127.0.0.1 发布方式创建第二只，应失败。
        holder = docker("run", "-d", "--name", "glyahh-port-holder", "-p", "127.0.0.1:18765:80", IMAGE, "sleep", "30")
        try:
            # 创建时尚不占端口，启动时才会绑定；第二次启动应被拒绝。
            docker("create", "--name", "glyahh-port-probe", "-p", "127.0.0.1:18765:8080", IMAGE, "sleep", "1")
            started = docker("start", "glyahh-port-probe")
            detail = (started.stderr + started.stdout).decode("utf-8", "replace")
            check("R03", "固定宿主端口已被占用时容器启动失败", holder.returncode == 0 and started.returncode != 0 and "port is already allocated" in detail,
                  detail)
        finally:
            docker("rm", "-f", "glyahh-port-probe")
            docker("rm", "-f", "glyahh-port-holder")

        # 7. 没有启动脚本的目录交给真实模型补充。
        mystery = import_app("模型补充", {
            "api/package.json": b'{"name":"api","packageManager":"npm@10.0.0"}\n',
            "api/server.js": b'const http=require("http"); http.createServer(function(req,res){res.end("ok");}).listen(8080);\n',
            "api/README.md": "启动命令：node server.js\n".encode(),
        })
        inspection = configure(mystery, [])
        scopes = inspection["result"].get("scopes") or []
        unidentified = [row for row in scopes if row.get("directory") == "api" and row.get("status") == "UNIDENTIFIED"]
        check("S05", "固定扫描留下未识别目录", bool(unidentified), json.dumps(scopes, ensure_ascii=False)[:300])
        approval = call(f"/project/import/apps/{mystery}/startup/approvals",
                        {"ownerId": owner["id"], "requestId": str(uuid.uuid4()), "scanId": inspection["scanId"], "directories": ["api"], "approved": True})
        deadline = time.monotonic() + 170
        while approval["state"] in ("APPROVED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(2)
            approval = call(f"/project/import/apps/{mystery}/startup/approvals/{approval['approvalId']}")
        current = call(f"/project/import/apps/{mystery}/startup/inspections/{inspection['scanId']}")
        published = (current.get("inspection") or current).get("result") or {}
        agent_rows = [row for row in published.get("candidates") or [] if row.get("source") == "AGENT" and row.get("directory") == "api" and row.get("command")]
        check("S05", "真实模型补充出有依据的候选", approval["state"] == "SUCCEEDED" and agent_rows and not current.get("servicesStarted", False),
              approval["state"] + " " + str(approval.get("error")) + " " + json.dumps(agent_rows, ensure_ascii=False)[:300])

        # 8. 用已保存的 GitHub Token 导入一个私有仓库，文件与 commit 一致，证据不含 Token。
        repo = github_repo(github_token)
        credential = call("/project/import/credentials", {"ownerId": owner["id"], "provider": "GitHub", "name": "g02", "token": github_token})
        source = call("/project/import/git/tasks", {"ownerId": owner["id"], "appName": "私有来源", "provider": "GitHub",
                                                    "repositoryUrl": repo["html_url"], "credentialId": credential["id"],
                                                    "credentialVersion": int(credential["version"])})
        deadline = time.monotonic() + 90
        while source["status"] == "RESOLVING_SOURCE" and time.monotonic() < deadline:
            time.sleep(1)
            source = call("/project/import/git/tasks/" + source["taskId"])
        branch = next(row for row in source["branches"] if row["name"] in ("main", "master"))
        source = call("/project/import/git/tasks/" + source["taskId"] + "/branch",
                      {"ownerId": owner["id"], "revision": source["revision"], "branch": branch["name"]})
        deadline = time.monotonic() + 180
        while source["status"] == "READING_CONTENT" and time.monotonic() < deadline:
            time.sleep(1)
            source = call("/project/import/git/tasks/" + source["taskId"])
        main_task = call("/project/import/tasks/" + source["taskId"])
        imported = call("/project/import/tasks/" + source["taskId"] + "/commit",
                        {"revision": main_task["revision"], "contentHash": main_task["contentHash"]})
        apps.append(imported["appId"])
        finished = call("/project/import/git/tasks/" + source["taskId"])
        workspace = Path("temp/code_output") / ("project_" + imported["appId"])
        texts = []
        for path in workspace.rglob("*"):
            if path.is_file():
                texts.append(path.read_text(encoding="utf-8", errors="replace"))
        readme = (workspace / "README.md").read_text(encoding="utf-8", errors="replace") if (workspace / "README.md").is_file() else ""
        check("G02", "GitHub 私有仓库文件与 commit 一致且 Token 不在工作区",
              finished["status"] == "SUCCEEDED" and finished.get("commit") == branch["commit"] and "glyahh-g02" in readme and github_token not in "\n".join(texts),
              "commit=" + str(finished.get("commit")) + " files=" + str(sum(1 for _ in texts)))

        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = redact(error, hidden)
        report["trace"] = redact(traceback.format_exc(), hidden)[-1500:]
    finally:
        for app in apps:
            listed = docker("ps", "-aq", "--filter", "label=glyahh.import.app=" + str(app))
            for container_id in listed.stdout.decode().split():
                docker("rm", "-f", container_id)
        blob = json.dumps(report, ensure_ascii=False, indent=2)
        if github_token in blob or model_key in blob:
            report["status"] = "FAIL"
            report["error"] = "证据中出现了密钥"
            blob = redact(blob, hidden)
        (evidence / "result.json").write_text(blob if isinstance(blob, str) else json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({key: report[key] for key in report if key != "trace"}, ensure_ascii=False))


def github_repo(token):
    def request(method, url, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "User-Agent": "glyahh-import-g02", "X-GitHub-Api-Version": "2022-11-28"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        try:
            response = urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = json.loads(response.read().decode())
        if response.status >= 300:
            raise AssertionError("GitHub " + method + " " + str(response.status))
        return payload

    name = "glyahh-import-g02-probe"
    user = request("GET", "https://api.github.com/user")["login"]
    try:
        request("GET", "https://api.github.com/repos/" + user + "/" + name)
    except AssertionError:
        request("POST", "https://api.github.com/user/repos", {"name": name, "private": True, "auto_init": True, "description": "glyahh-g02"})
        time.sleep(2)
    readme = request("GET", "https://api.github.com/repos/" + user + "/" + name + "/contents/README.md")
    request("PUT", "https://api.github.com/repos/" + user + "/" + name + "/contents/README.md",
            {"message": "mark fixture", "content": "Z2x5YWhoLWcwMgo=", "sha": readme["sha"]})
    return request("GET", "https://api.github.com/repos/" + user + "/" + name)


if __name__ == "__main__":
    main()
