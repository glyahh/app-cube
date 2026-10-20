"""第一项 I1-C：平台秘密全链路哨兵验收。

做法：用专用虚构 PAT（不使用宿主真实 Token、不读私有仓库）走真实凭据与 Git 来源接口，
再对全部可观察面做独立扫描，确认哨兵没有出现在任何不该出现的地方：

1. 凭据接口的全部响应
2. Git 来源任务的响应与脱敏错误文本
3. 数据库 biz 相关表的文本列
4. 磁盘 temp 下的落盘文件
5. 真实执行容器的 env/cmd 与执行日志

只使用虚构哨兵；不打印宿主凭据；不改业务代码。
"""

import http.cookiejar
import json
import os
import pathlib
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

import psycopg

REPO = pathlib.Path(__file__).resolve().parents[2]
WSL = ["wsl.exe", "-d", os.environ.get("AGENT_WSL_DISTRO", "Ubuntu"), "--exec"]
BASE = "http://127.0.0.1:8124/api"


def main() -> int:
    evidence = REPO / "temp" / ("import-secret-chain-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "evidence": str(evidence), "fixture": "FICTITIOUS_SENTINELS_ONLY"}
    sentinel = "FAKE_IMPORT_SECRET_" + secrets.token_hex(16)
    marker = "CHAIN-" + secrets.token_hex(4)
    report["sentinelClass"] = "fictitious-pat"
    report["marker"] = marker

    def check(group, name, ok, detail=""):
        report["checks"].append({"group": group, "name": name, "status": "PASS" if ok else "FAIL", "detail": str(detail)[:400]})
        if not ok:
            raise AssertionError(group + " " + name + " " + str(detail)[:400])

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {"value": None}
    seen = {"responses": []}
    app = None
    source_id = None
    credential = None

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
            raw = response.read()
        text = raw.decode("utf-8", "replace")
        seen["responses"].append({"path": path, "bytes": len(raw)})
        check("C03", "完整接口响应不含虚构 PAT：" + path, sentinel not in text, "响应字节=" + str(len(raw)))
        result = json.loads(text)
        seen["code"] = result.get("code")
        if expected is not None:
            assert result["code"] == expected, f"{path} -> {result.get('code')} {result.get('message')}"
        return result.get("data")

    def dsn_value():
        for line in (REPO / ".env").read_text(encoding="utf-8-sig").splitlines():
            if line.startswith("AGENT_POSTGRES_DSN="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
        raise RuntimeError("缺少 AGENT_POSTGRES_DSN")

    def db_executions(app):
        with psycopg.connect(dsn_value()) as db:
            return db.execute("SELECT execution_id,exit_code,state FROM biz.project_import_startup_execution "
                              "WHERE app_id=%s ORDER BY created_at", (int(app),)).fetchall()

    def scan_database():
        """扫描 biz 下导入相关表的文本列；范围明确，不声称覆盖全部业务表。"""
        dsn = dsn_value()
        hits = []
        with psycopg.connect(dsn) as db:
            columns = db.execute(
                "SELECT table_name, column_name FROM information_schema.columns "
                "WHERE table_schema='biz' AND data_type IN ('text','character varying','jsonb','json') "
                "AND (table_name LIKE 'project_import%' OR table_name LIKE 'app%' OR table_name LIKE 'chat_history%') "
                "ORDER BY table_name, column_name").fetchall()
            for table, column in columns:
                found = db.execute(
                    f'SELECT count(*) FROM biz."{table}" WHERE "{column}"::text LIKE %s',
                    ("%" + sentinel + "%",)).fetchone()[0]
                if found:
                    hits.append(f"{table}.{column}={found}")
        return columns, hits

    def scan_disk():
        hits = []
        scanned = 0
        errors = []
        marker_bytes = sentinel.encode()
        for path in (REPO / "temp").rglob("*"):
            if not path.is_file():
                continue
            try:
                if not path.resolve().is_relative_to((REPO / "temp").resolve()):
                    raise OSError("暂存文件指向范围外")
                scanned += 1
                tail = b""
                # 不按扩展名、依赖目录或文件大小跳过；跨读取边界的标记同样能命中。
                with path.open("rb") as stream:
                    while chunk := stream.read(512 * 1024):
                        data = tail + chunk
                        if marker_bytes in data:
                            hits.append(str(path.relative_to(REPO)))
                            break
                        tail = data[-(len(marker_bytes) - 1):]
            except OSError as error:
                errors.append({"path": str(path.relative_to(REPO)), "errorType": type(error).__name__})
        report["diskScanErrors"] = errors
        if errors:
            raise AssertionError("磁盘哨兵扫描未完成，失败文件数=" + str(len(errors)))
        return scanned, hits

    try:
        account = "chain_" + secrets.token_hex(8)
        password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password})
        csrf["value"] = call("/project/import/csrf")

        # 1. 凭据接口：保存虚构 PAT，全部响应不得回显
        credential = call("/project/import/credentials", {"ownerId": owner, "provider": "GitHub", "name": marker, "token": sentinel})
        listed = call("/project/import/credentials")
        renamed = call(f"/project/import/credentials/{credential['id']}/name",
                       {"ownerId": owner, "revision": int(credential["revision"]), "name": marker + "-2"}, method="PUT")
        leaked = [row for row in seen["responses"] if row["path"].startswith("/project/import/credentials")]
        blob = json.dumps({"credential": credential, "listed": listed, "renamed": renamed}, ensure_ascii=False)
        check("C03", "凭据接口创建/列表/改名响应不回显虚构 PAT", sentinel not in blob, f"响应数={len(leaked)}")

        # 2. Git 来源：用虚构凭据对公开仓库发起准备，失败或成功都不得回显秘密
        source = None
        source_id = None
        try:
            source = call("/project/import/git/tasks", {"ownerId": owner, "appName": "哨兵来源夹具", "provider": "GitHub",
                                                        "repositoryUrl": "https://github.com/octocat/Hello-World",
                                                        "credentialId": credential["id"], "credentialVersion": int(credential["version"]),
                                                        "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
            source_id = source.get("taskId") if isinstance(source, dict) else None
        except AssertionError as error:
            source = {"error": str(error)[:200]}
        check("C03", "Git 来源任务响应与错误文本不含虚构 PAT", sentinel not in json.dumps(source, ensure_ascii=False), str(source)[:200])

        # 2b. 地址里嵌入凭据必须被拒绝，不能把秘密带进 URL
        embedded = call("/project/import/git/tasks", {"ownerId": owner, "appName": "嵌入密钥地址", "provider": "GitHub",
                                                     "repositoryUrl": "https://" + sentinel + "@github.com/octocat/Hello-World",
                                                     "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}},
                        expected=None)
        check("G08", "地址中嵌入凭据的来源请求被拒绝", seen.get("code") != 20000, f"code={seen.get('code')} data={str(embedded)[:80]}")

        # 3. 数据库：凭据表只应有密文，任何列不得出现明文哨兵
        columns, db_hits = scan_database()
        check("C03", "biz 导入相关表文本列不含虚构 PAT 明文", not db_hits, f"扫描列={len(columns)} 命中={db_hits}")

        # 4. 磁盘：临时区、暂存区与私有目录不得出现明文哨兵
        scanned, disk_hits = scan_disk()
        check("C03", "temp 落盘文件不含虚构 PAT 明文", not disk_hits, f"扫描文件={scanned} 命中={disk_hits[:5]}")

        # 5. 真实执行：安装输出里放工程自己的虚构密钥，运行后检查存活容器与落盘日志
        project_secret = "sk-live" + secrets.token_hex(16)
        env_secret = "hunter2" + secrets.token_hex(6)
        serve_app = (
            "from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n"
            "class Handler(BaseHTTPRequestHandler):\n"
            "    def do_GET(self):\n"
            "        body = b'{\"ok\":true}'\n"
            "        self.send_response(200)\n"
            "        self.send_header('Content-Type', 'application/json')\n"
            "        self.send_header('Content-Length', str(len(body)))\n"
            "        self.end_headers()\n"
            "        self.wfile.write(body)\n"
            "    def log_message(self, fmt, *args):\n"
            "        return\n"
            "ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()\n")
        files = {"app.py": serve_app.encode(), "requirements.txt": b"# fixture\n"}
        task = call("/project/import/tasks", {"ownerId": owner, "appName": "哨兵执行夹具",
                                             "files": [{"path": path, "size": len(content)} for path, content in files.items()],
                                             "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        app = committed["appId"]
        install_cmd = "printf '%s\\n' '" + project_secret + "' 'PASSWORD=" + env_secret + "' 'installed'"
        config = call(f"/project/import/apps/{app}/startup/configuration")
        call(f"/project/import/apps/{app}/startup/configuration",
             {"ownerId": owner, "configRevision": int(config["configRevision"]), "contextEpoch": int(config["contextEpoch"]),
              "scope": ".", "services": [{"id": None, "name": "网页", "directory": ".", "command": "python3 app.py",
                                          "install": install_cmd, "build": None, "port": 8080}]}, method="PUT")
        inspection = call(f"/project/import/apps/{app}/startup/inspection", {"ownerId": owner, "scope": "."})
        plan = call(f"/project/import/apps/{app}/startup/plans",
                    {"ownerId": owner, "scanId": inspection["scanId"],
                     "serviceIds": [next(row["id"] for row in inspection["result"]["candidates"] if row["name"] == "网页")]})
        plan = call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}/confirm",
                    {"ownerId": owner, "planRevision": plan["planRevision"], "aggregateAcknowledged": False})
        installed = call(f"/project/import/apps/{app}/startup/preparations/install",
                         {"ownerId": owner, "planId": plan["planId"], "planRevision": plan["planRevision"],
                          "requestId": str(uuid.uuid4()), "approved": True})
        # 安装只受理；按任务查询读到终态，POST 超时不等于安装失败。
        deadline = time.monotonic() + 1800
        while installed["state"] not in ("SUCCEEDED", "FAILED", "REJECTED", "CANCELLED"):
            if time.monotonic() >= deadline:
                raise AssertionError("INSTALL_OBSERVATION_TIMED_OUT")
            time.sleep(2)
            installed = call(f"/project/import/apps/{app}/startup/install-jobs/{installed['jobId']}")
        check("C03", "安装准备响应不含虚构 PAT", sentinel not in json.dumps(installed, ensure_ascii=False), installed["state"])

        rows = db_executions(app)
        install_log = (REPO / "temp/project_import_exec") / (str(rows[-1][0]) + ".output.log")
        log_text = install_log.read_text(encoding="utf-8", errors="replace")
        check("C05", "安装日志保留可诊断信息", "installed" in log_text, install_log.name)
        check("C05", "工程自己打印的密钥形态在落盘日志中被遮盖",
              project_secret not in log_text and env_secret not in log_text and "***" in log_text,
              log_text.replace("\n", " ")[:160])

        # 6. 存活容器：env/cmd 不得含平台秘密
        started = call(f"/project/import/apps/{app}/startup/preparations/run",
                       {"ownerId": owner, "planId": plan["planId"], "planRevision": plan["planRevision"]})
        service = started["services"][0]
        check("R02", "哨兵夹具服务真实就绪", service["state"] == "READY" and (service.get("address") or "").startswith("http://127.0.0.1:"),
              json.dumps(service, ensure_ascii=False)[:200])
        inspect = subprocess.run(WSL + ["docker", "ps", "--filter", "label=glyahh.import.app=" + str(app), "--format", "{{.ID}}"],
                                 capture_output=True, timeout=60)
        container_ids = inspect.stdout.decode().split()
        env_text = ""
        for container in container_ids:
            env_text += subprocess.run(WSL + ["docker", "inspect", container, "--format", "{{json .Config}}"],
                                       capture_output=True, timeout=60).stdout.decode("utf-8", "replace")
        check("C03", "运行中执行容器 Config/Env/命令不含虚构 PAT", container_ids and sentinel not in env_text,
              f"容器数={len(container_ids)}")
        call(f"/project/import/apps/{app}/startup/preparations/stop",
             {"ownerId": owner, "planId": plan["planId"], "planRevision": plan["planRevision"]})

        logs = []
        for log in (REPO / "temp/project_import_exec").glob("*.output.log"):
            try:
                if sentinel.encode() in log.read_bytes():
                    logs.append(log.name)
            except OSError:
                continue
        check("C03", "执行日志落盘内容不含虚构 PAT", not logs, f"命中={logs}")

        report["status"] = "PASS"
    except Exception as error:  # 失败如实保留，不吞
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["error"] = str(error).replace(sentinel, "[REDACTED]")[:400]
    finally:
        # 失败同样走专属资源清理；任何清理失败都不能以退出码 0 收口。
        report["cleanup"] = {}
        try:
            if app is not None:
                deleted = call("/app/delete", {"id": str(app)})
                assert deleted is True, "专属应用删除未确认"
                report["cleanup"]["appDeleted"] = True
            if source_id:
                current = call(f"/project/import/git/tasks/{source_id}")
                if current["status"] not in ("SUCCEEDED", "CANCELLED", "FAILED"):
                    current = call(f"/project/import/git/tasks/{source_id}/cancel?revision={int(current['revision'])}", method="POST")
                assert current["status"] in ("SUCCEEDED", "CANCELLED", "FAILED"), "来源任务未结束"
                report["cleanup"]["sourceState"] = current["status"]
            if credential is not None:
                for row in call("/project/import/credentials"):
                    if row["id"] == credential["id"]:
                        call(f"/project/import/credentials/{row['id']}?ownerId={owner}&revision={int(row['revision'])}", method="DELETE")
                assert all(row["id"] != credential["id"] for row in call("/project/import/credentials")), "凭据删除未确认"
                report["cleanup"]["credentialsRemoved"] = True
        except Exception as error:
            report["status"] = "FAIL"
            report["cleanupError"] = type(error).__name__
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({k: report[k] for k in report if k not in {"checks"}}, ensure_ascii=False))
        for row in report["checks"]:
            print(row["status"], row["group"], row["name"], "|", row["detail"][:160])
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
