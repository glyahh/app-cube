"""历史核心断言检查，不是第一项全量验收；历史证据不视为当前版本通过。"""

import hashlib
import http.cookiejar
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import psycopg

BASE = "http://127.0.0.1:8124/api"
ROOT = Path(__file__).resolve().parents[1]
PRIOR = ROOT / "temp" / "import-startup-preparation-20261005"
STAGING = ROOT / "temp" / "project_import_private"
OUTPUT = ROOT / "temp" / "code_output"
OPTIONS = {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}


def env_value(name):
    for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def redact(text, hidden):
    value = str(text)
    for secret in hidden:
        if secret:
            value = value.replace(secret, "[secret]")
    return value[:800]


def load_report(path):
    file = PRIOR / path / "result.json"
    if not file.is_file():
        raise AssertionError("缺少已有证据 " + str(file))
    report = json.loads(file.read_text(encoding="utf-8"))
    if report.get("status") != "PASS":
        raise AssertionError(path + " 不是 PASS")
    return report, str(file.relative_to(ROOT))


def passed_groups(report):
    found = {}
    for check in report.get("checks", []):
        group = check.get("group")
        if group and check.get("status") == "PASS" and group not in found:
            found[group] = check.get("name", "")
    return found


def tree(root):
    files = {}
    for path in Path(root).rglob("*"):
        if path.is_file():
            files[path.relative_to(root).as_posix()] = path.read_bytes()
    return files


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    evidence = ROOT / "temp" / ("import-item1-closure-" + time.strftime("%Y%m%d%H%M%S"))
    evidence.mkdir(parents=True)
    hidden = [env_value("IMPORT_GITHUB_TOKEN"), env_value("AGENT_MODEL_API_KEY"), env_value("AGENT_POSTGRES_DSN")]
    rows = {}
    open_tasks = []
    report = {"status": "RUNNING", "rows": rows, "evidence": str(evidence.relative_to(ROOT))}

    def record(group, status, assertion, evidence_path, command, detail=""):
        rows[group] = {"status": status, "assertion": assertion, "evidence": evidence_path,
                       "command": command, "exitCode": None,
                       "evidenceQualification": "HISTORICAL_UNVERIFIED" if evidence_path.startswith("temp/import-startup-preparation") else "CURRENT_CORE_ASSERTION_ONLY",
                       "detail": redact(detail, hidden)}

    def check(group, assertion, ok, detail=""):
        record(group, "PASS" if ok else "FAIL", assertion, str(evidence.relative_to(ROOT)),
               "python scripts/verify-item1-closure.py", detail)
        if not ok:
            raise AssertionError(group + " " + assertion + " " + redact(detail, hidden))

    def client():
        return urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(opener, token, path, body=None, method=None, expected=20000, timeout=180):
        headers = {"Origin": "http://localhost:5173"}
        if token:
            headers["X-Project-Import-CSRF"] = token
        data = None
        if isinstance(body, bytes):
            data = body
            headers["Content-Type"] = "application/octet-stream"
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            response = opener.open(urllib.request.Request(BASE + path, data=data, headers=headers,
                                                           method=method or ("GET" if body is None else "POST")), timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.loads(response.read().decode("utf-8"))
        if expected is not None and result.get("code") != expected:
            raise AssertionError(redact(path + " -> " + str(result.get("code")) + " " + str(result.get("message")), hidden))
        return result

    def wait_install(opener, token, app_id, job, limit=1800):
        """按任务查询读到终态；受理已返回但任务仍在跑时必须继续等，不能当成安装失败。"""
        deadline = time.monotonic() + limit
        while job["state"] not in ("SUCCEEDED", "FAILED", "REJECTED", "CANCELLED"):
            if time.monotonic() >= deadline:
                raise AssertionError("INSTALL_OBSERVATION_TIMED_OUT")
            time.sleep(2)
            job = call(opener, token, f"/project/import/apps/{app_id}/startup/install-jobs/{job['jobId']}")["data"]
        return job

    def account(opener, suffix):
        name = "closure_" + secrets.token_hex(6) + suffix
        password = secrets.token_urlsafe(18)
        user_id = call(opener, None, "/user/register", {"userAccount": name, "userPassword": password, "checkPassword": password})["data"]
        call(opener, None, "/user/login", {"userAccount": name, "userPassword": password})
        token = call(opener, None, "/project/import/csrf")["data"]
        return str(user_id), token, name, password

    def upload(opener, token, owner, app_name, files, options=None, use_saved=False):
        # 1. 先按清单冻结纳入范围，只上传会被保留的文件。
        body = {"ownerId": owner, "appName": app_name, "files": [{"path": path, "size": len(content)} for path, content in files.items()]}
        if not use_saved:
            body["options"] = options or OPTIONS
        task = call(opener, token, "/project/import/tasks", body)["data"]
        open_tasks.append(("folder", opener, token, task["taskId"]))
        included = task["manifest"]["included"]
        for index, entry in enumerate(included):
            task = call(opener, token, f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}",
                        files[entry["path"]], method="PUT")["data"]
        return task

    def commit(opener, token, task):
        committed = call(opener, token, f"/project/import/tasks/{task['taskId']}/commit",
                         {"revision": task["revision"], "contentHash": task["contentHash"]})["data"]
        return committed

    def cancel(kind, opener, token, task_id):
        current_path = "/project/import/git/tasks/" if kind == "git" else "/project/import/tasks/"
        current = call(opener, token, current_path + task_id)["data"]
        if current["status"] in ("SUCCEEDED", "CANCELLED", "FAILED"):
            return current
        return call(opener, token, current_path + task_id + "/cancel?revision=" + str(current["revision"]), method="POST")["data"]

    def app_ids(opener):
        page = call(opener, None, "/app/my/list/page/vo", {"pageNum": 1, "pageSize": 50})["data"]
        return {str(row["id"]) for row in page["records"]}

    def wait_source(opener, token, task):
        deadline = time.monotonic() + 90
        while task["status"] in ("RESOLVING_SOURCE", "READING_CONTENT") and time.monotonic() < deadline:
            time.sleep(0.4)
            task = call(opener, token, "/project/import/git/tasks/" + task["taskId"])["data"]
        return task

    def inspect(opener, token, owner, app_id):
        return call(opener, token, f"/project/import/apps/{app_id}/startup/inspection",
                    {"ownerId": owner, "scope": "."}, timeout=120)["data"]

    try:
        # 1. 已有证据直接对到组，不重跑导入、启动或凭据。
        lifetime, lifetime_path = load_report("lifetime-1791195147876482600")
        gaps, gaps_path = load_report("gaps-1791195967946240000")
        remaining, remaining_path = load_report("remaining-1791199554804778000")
        source, source_path = load_report("matrix-rerun/source")
        credentials, credentials_path = load_report("matrix-rerun/credentials")
        negative, negative_path = load_report("matrix-rerun/auth-negative")
        lifetime_groups = passed_groups(lifetime)
        gap_groups = passed_groups(gaps)
        remaining_groups = passed_groups(remaining)
        for group in ["D01", "D02", "D03", "D04", "D05", "D06", "D07", "D08", "R01", "R04"]:
            check_name = lifetime_groups.get(group) or gap_groups.get(group)
            record(group, "PASS" if check_name else "FAIL", check_name or "缺少已有通过记录",
                   lifetime_path, "python scripts/verify-startup-lifetime-api.py")
            if not check_name:
                raise AssertionError(group + " 已有证据里没有 PASS")
        record("R05", "PASS", gap_groups["R05"], gaps_path, "python scripts/verify-startup-lifetime-gaps.py", "STALE INSPECTION_STALE")
        record("S07", "PASS", "工作区改一个字节后原计划为 STALE，与 R05 是同一条已有断言",
               gaps_path, "python scripts/verify-startup-lifetime-gaps.py", "STALE INSPECTION_STALE")
        if "STALE" not in json.dumps(gaps, ensure_ascii=False):
            raise AssertionError("S07 缺少 STALE 证据")
        record("R07", "PASS", lifetime_groups["R07"], lifetime_path + " ; src/test/java/com/dbts/glyahhaigeneratecode/projectimport/ImportExecutionRunnerTest.java",
               "python scripts/verify-startup-lifetime-api.py ; ./mvnw.cmd test -Dtest=ImportExecutionRunnerTest",
               "停止后容器消失；单测断言 STOP_FAILED 后不能当成功重试")
        test_source = (ROOT / "src/test/java/com/dbts/glyahhaigeneratecode/projectimport/ImportExecutionRunnerTest.java").read_text(encoding="utf-8")
        if 'assertEquals("STOP_FAILED",result.state())' not in test_source:
            raise AssertionError("R07 单测缺少 STOP_FAILED")
        for group in ["R02", "R03", "R06", "R08", "E04", "E05", "S05", "G02"]:
            record(group, "PASS" if group in remaining_groups else "FAIL", remaining_groups.get(group, "缺少已有通过记录"),
                   remaining_path, "python scripts/verify-startup-remaining.py")
            if group not in remaining_groups:
                raise AssertionError(group + " 已有证据里没有 PASS")
        for group, assertion in {
            "G01": "公开 GitHub 来源可解析到分支",
            "G03": "只能选择当前仓库里的一个分支",
            "G08": "非法地址被拒绝",
            "G09": "越权读来源失败，响应不含 Token",
        }.items():
            record(group, "PASS", assertion, source_path + " ; " + negative_path,
                   "python scripts/verify-import-source.py ; python scripts/verify-import-auth-negative.py")
        for group, assertion in {
            "C01": "凭据写入后响应没有 Token",
            "C02": "凭据按所有者和版本读取",
            "C03": "他人不能使用或删除该凭据",
            "C04": "越权结果里没有 Token",
        }.items():
            record(group, "PASS", assertion, credentials_path + " ; " + negative_path,
                   "python scripts/verify-import-credentials.py ; python scripts/verify-import-auth-negative.py")
        record("G04", "NOT_RUN", "真实私有仓库验收暂缓，不取消产品范围", "", "")

        # 2. 补跑文件夹、偏好、识别、恢复和公开仓库。
        first = client()
        second = client()
        owner, token, account_name, password = account(first, "a")
        hidden.append(password)
        other, other_token, _, _ = account(second, "b")
        defaults = {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}
        saved = {"skipDependencies": True, "skipBuildOutputs": False, "includeSensitive": False}
        initial = call(first, token, "/project/import/preferences")["data"]
        if initial != defaults:
            raise AssertionError("新用户偏好不是默认值 " + str(initial))
        call(first, token, "/project/import/preferences", {"ownerId": owner, **saved}, method="PUT")
        other_pref = call(second, other_token, "/project/import/preferences")["data"]
        relogin = client()
        call(relogin, None, "/user/login", {"userAccount": account_name, "userPassword": password})
        restored = call(relogin, None, "/project/import/preferences")["data"]
        check("U01", "偏好保存后重新登录仍在，且另一个用户看不到",
              initial == defaults and restored == saved and other_pref == defaults)

        preference_files = {"src/keep.txt": b"preference-kept", "node_modules/old.js": b"dependency"}
        preference_task = upload(first, token, owner, "偏好应用", preference_files, use_saved=True)
        preference_included = {item["path"] for item in preference_task["manifest"]["included"]}
        if preference_included != {"src/keep.txt"}:
            raise AssertionError("已保存偏好没有排除依赖 " + str(preference_included))
        preference_app = commit(first, token, preference_task)
        preference_path = OUTPUT / ("project_" + preference_app["appId"]) / "src" / "keep.txt"
        preference_hash = sha(preference_path)
        explicit = upload(first, token, owner, "临时选项", {"node_modules/new.js": b"kept-this-time", "src/new.txt": b"new"},
                          {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False})
        explicit_paths = [item["path"] for item in explicit["manifest"]["included"]]
        still_saved = call(first, token, "/project/import/preferences")["data"]
        cancel("folder", first, token, explicit["taskId"])
        check("U02", "本次临时选项不改长期偏好，旧应用文件不变",
              preference_included == {"src/keep.txt"} and "node_modules/new.js" in explicit_paths
              and still_saved == saved and sha(preference_path) == preference_hash)

        folder_files = {
            "资料/说明 文档.txt": "中文内容".encode(),
            "资料/empty.bin": b"",
            "资料/binary.bin": bytes([0, 255, 13, 10]),
            "node_modules/pkg/a.js": b"dependency fixture",
            "dist/app.js": b"built fixture",
            ".env": b"FICTIONAL_ENV_MARKER_CLOSURE=1\n",
            ".env.example": b"EXAMPLE_MARKER_CLOSURE=1\n",
        }
        folder_options = {"skipDependencies": False, "skipBuildOutputs": True, "includeSensitive": False}
        folder = upload(first, token, owner, "收口文件夹", folder_files, folder_options)
        included_paths = [item["path"] for item in folder["manifest"]["included"]]
        excluded = {item["file"]["path"]: item["reason"] for item in folder["manifest"]["excluded"]}
        check("F01", "中文名和空格文件名进入清单", "资料/说明 文档.txt" in included_paths)
        check("F02", "node_modules 在未跳过依赖时保留", "node_modules/pkg/a.js" in included_paths)
        check("F03", "dist 在跳过构建产物时排除", excluded.get("dist/app.js") == "BUILD_OUTPUT")
        check("F04", ".env 默认排除，示例文件保留", excluded.get(".env") == "SENSITIVE" and ".env.example" in included_paths)
        folder_app = commit(first, token, folder)
        folder_dir = OUTPUT / ("project_" + folder_app["appId"])
        final_paths = {path.relative_to(folder_dir).as_posix() for path in folder_dir.rglob("*") if path.is_file()}
        check("U03", "最终文件和清单一致", final_paths == set(included_paths) and ".env" not in final_paths)
        check("C05", "示例文件进入工程，虚构 .env 内容不进入工程",
              b"EXAMPLE_MARKER_CLOSURE" in (folder_dir / ".env.example").read_bytes()
              and not (folder_dir / ".env").exists()
              and b"FICTIONAL_ENV_MARKER_CLOSURE" not in b"".join(path.read_bytes() for path in folder_dir.rglob("*") if path.is_file()))

        rejected_path = call(first, token, "/project/import/tasks",
                             {"ownerId": owner, "appName": "越界", "files": [{"path": "../x", "size": 1}], "options": OPTIONS},
                             expected=40000)
        check("F05", "上级目录被拒绝", "文件路径无效" in rejected_path["message"], rejected_path["message"])
        empty_range = call(first, token, "/project/import/tasks",
                           {"ownerId": owner, "appName": "空范围", "files": [{"path": ".env", "size": 1}], "options": OPTIONS},
                           expected=40000)
        check("F06", "范围为空时拒绝", "导入范围为空" in empty_range["message"], empty_range["message"])
        check("U04", "坏清单明确失败，没有变成成功任务", empty_range["code"] == 40000 and empty_range.get("data") in (None, {}))

        before_cancel = app_ids(first)
        partial = call(first, token, "/project/import/tasks",
                       {"ownerId": owner, "appName": "中途取消", "files": [{"path": "a.txt", "size": 1}, {"path": "b.txt", "size": 1}], "options": OPTIONS})["data"]
        open_tasks.append(("folder", first, token, partial["taskId"]))
        call(first, token, f"/project/import/tasks/{partial['taskId']}/files/0?revision={partial['revision']}", b"a", method="PUT")
        cancelled = cancel("folder", first, token, partial["taskId"])
        static_code = 0
        try:
            first.open(urllib.request.Request(BASE + "/static/" + partial["taskId"] + "/a.txt"), timeout=15)
            static_code = 200
        except urllib.error.HTTPError as error:
            static_code = error.code
        cancel_keeps_apps = app_ids(first) == before_cancel
        check("E01", "上传中途取消后没有应用，静态入口读不到半成品",
              cancelled["status"] == "CANCELLED" and cancelled["cleanup"] == "DONE"
              and not (STAGING / partial["taskId"]).exists() and static_code == 404 and cancel_keeps_apps,
              cancelled["status"] + " static=" + str(static_code))

        failed = call(first, token, "/project/import/tasks",
                      {"ownerId": owner, "appName": "失败任务", "files": [{"path": "a.txt", "size": 2}], "options": OPTIONS})["data"]
        open_tasks.append(("folder", first, token, failed["taskId"]))
        call(first, token, f"/project/import/tasks/{failed['taskId']}/files/0?revision=0", b"x", method="PUT", expected=50001)
        failed_view = call(first, token, "/project/import/tasks/" + failed["taskId"])["data"]
        credential = call(first, token, "/project/import/credentials",
                          {"ownerId": owner, "provider": "GitHub", "name": "收口虚构凭据", "token": "closure-fixture-token"})
        credential_text = json.dumps(credential, ensure_ascii=False)
        if "closure-fixture-token" in credential_text:
            raise AssertionError("凭据响应带回了虚构 Token")
        recovered = commit(first, token, upload(first, token, owner, "失败后新导入", {"fresh.txt": b"fresh-import"}))
        credential_ids = [item["id"] for item in call(first, token, "/project/import/credentials")["data"]]
        check("E02", "失败后再导入是新任务，旧凭据还在",
              failed_view["status"] == "FAILED" and recovered["taskId"] != failed["taskId"]
              and (OUTPUT / ("project_" + recovered["appId"]) / "fresh.txt").read_bytes() == b"fresh-import"
              and credential["data"]["id"] in credential_ids)

        install_files = {"keep.txt": b"install-original", "fail.py": b"import sys\nsys.exit(3)\n", "requirements.txt": b"# fixture\n"}
        install_source = evidence / "install-source" / "keep.txt"
        install_source.parent.mkdir(parents=True, exist_ok=True)
        install_source.write_bytes(install_files["keep.txt"])
        source_hash = sha(install_source)
        install_task = upload(first, token, owner, "安装失败", install_files)
        install_app = commit(first, token, install_task)
        install_dir = OUTPUT / ("project_" + install_app["appId"])
        project_hash = sha(install_dir / "keep.txt")
        config = call(first, token, f"/project/import/apps/{install_app['appId']}/startup/configuration")["data"]
        call(first, token, f"/project/import/apps/{install_app['appId']}/startup/configuration",
             {"ownerId": owner, "configRevision": int(config["configRevision"]), "contextEpoch": int(config["contextEpoch"]),
              "scope": ".", "services": [{"id": None, "name": "失败安装", "directory": ".", "command": "python3 fail.py",
                                          "install": "python3 fail.py", "build": None, "port": None}]}, method="PUT")
        inspection = inspect(first, token, owner, install_app["appId"])
        manual_ids = [item["id"] for item in inspection["result"]["candidates"] if str(item["id"]).startswith("manual:")]
        plan = call(first, token, f"/project/import/apps/{install_app['appId']}/startup/plans",
                    {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": manual_ids})["data"]
        confirmed = call(first, token, f"/project/import/apps/{install_app['appId']}/startup/plans/{plan['planId']}/confirm",
                         {"ownerId": owner, "planRevision": plan["planRevision"], "aggregateAcknowledged": True})["data"]
        submitted = call(first, token, f"/project/import/apps/{install_app['appId']}/startup/preparations/install",
                         {"ownerId": owner, "planId": confirmed["planId"], "planRevision": confirmed["planRevision"],
                          "requestId": str(uuid.uuid4()), "approved": True})["data"]
        installed = wait_install(first, token, install_app["appId"], submitted)
        check("F07", "安装失败后本地原件哈希不变",
              installed["state"] == "FAILED" and sha(install_source) == source_hash and sha(install_dir / "keep.txt") == project_hash,
              installed["state"] + " " + str(installed.get("failureReason")))
        check("E03", "安装失败后导入工程目录仍在", install_dir.is_dir() and (install_dir / "keep.txt").is_file(), str(install_dir))

        samples = {
            "S01": (ROOT / "src/test/resources/import-acceptance/vue", "Vue 样本只读识别出 npm run dev",
                    lambda result: any(item.get("command") == "npm run dev" for item in result["candidates"])),
            "S02": (ROOT / "src/test/resources/import-acceptance/spring", "Spring 样本只读识别出 spring-boot:run",
                    lambda result: any("spring-boot:run" in (item.get("command") or "") for item in result["candidates"])),
            "S03": (ROOT / "src/test/resources/import-acceptance/python-web", "Python 样本只读识别出声明命令和端口",
                    lambda result: any(item.get("command") == "python3 app.py" and item.get("port") == 8080 for item in result["candidates"])),
            "S04": (ROOT / "src/test/resources/import-acceptance/static-page", "静态页只读识别出入口且不发明命令",
                    lambda result: any(item.get("source") == "STATIC" and item.get("command") is None and "index.html" in (item.get("evidence") or "")
                                       for item in result["candidates"])),
        }
        sample_apps = {}
        for group, (directory, assertion, predicate) in samples.items():
            sample_task = upload(first, token, owner, group + "识别", tree(directory))
            sample_app = commit(first, token, sample_task)
            sample_apps[group] = sample_app["appId"]
            result = inspect(first, token, owner, sample_app["appId"])["result"]
            check(group, assertion, predicate(result), json.dumps(result["candidates"], ensure_ascii=False)[:300])
        vue_config = call(first, token, f"/project/import/apps/{sample_apps['S01']}/startup/configuration")["data"]
        saved_config = call(first, token, f"/project/import/apps/{sample_apps['S01']}/startup/configuration",
                            {"ownerId": owner, "configRevision": int(vue_config["configRevision"]), "contextEpoch": int(vue_config["contextEpoch"]),
                             "scope": ".", "services": [{"id": None, "name": "手动", "directory": ".", "command": "echo kept-command",
                                                         "install": None, "build": None, "port": None}]}, method="PUT")["data"]
        reread = call(first, token, f"/project/import/apps/{sample_apps['S01']}/startup/configuration")["data"]
        rejected_dir = call(first, token, f"/project/import/apps/{sample_apps['S01']}/startup/configuration",
                            {"ownerId": owner, "configRevision": int(reread["configRevision"]), "contextEpoch": int(reread["contextEpoch"]),
                             "scope": ".", "services": [{"id": None, "name": "越界", "directory": "../outside", "command": "echo no",
                                                         "install": None, "build": None, "port": None}]}, method="PUT", expected=40000)
        after_reject = call(first, token, f"/project/import/apps/{sample_apps['S01']}/startup/configuration")["data"]
        check("S06", "手动命令保存后仍在，工程外目录被拒绝且不执行",
              saved_config["services"][0]["command"] == "echo kept-command"
              and reread["services"][0]["command"] == "echo kept-command"
              and rejected_dir["code"] == 40000
              and after_reject["services"][0]["command"] == "echo kept-command",
              rejected_dir["message"])

        same_name = "同名收口"
        first_same = commit(first, token, upload(first, token, owner, same_name, {"one.txt": b"one"}))
        second_same = commit(first, token, upload(first, token, owner, same_name, {"two.txt": b"two"}))
        first_vo = call(first, token, "/app/get/vo?id=" + first_same["appId"])["data"]
        second_vo = call(first, token, "/app/get/vo?id=" + second_same["appId"])["data"]
        check("U05", "同名两次导入得到两个应用",
              first_same["appId"] != second_same["appId"] and first_vo["appName"] == same_name and second_vo["appName"] == same_name
              and (OUTPUT / ("project_" + first_same["appId"]) / "one.txt").is_file()
              and (OUTPUT / ("project_" + second_same["appId"]) / "two.txt").is_file())
        check("U06", "成功提交返回 appId，取消不产生应用",
              cancel_keeps_apps and str(folder_app["appId"]).isdigit()
              and folder_app["redirectPath"] == "/app/" + folder_app["appId"] + "/chat")

        source_task = call(first, token, "/project/import/git/tasks",
                           {"ownerId": owner, "appName": "取消中的来源", "provider": "GitHub",
                            "repositoryUrl": "https://github.com/octocat/Hello-World"})["data"]
        open_tasks.append(("git", first, token, source_task["taskId"]))
        source_task = wait_source(first, token, source_task)
        cancelled_source = cancel("git", first, token, source_task["taskId"])
        publication = 0
        with psycopg.connect(env_value("AGENT_POSTGRES_DSN")) as database:
            publication = database.execute("SELECT count(*) FROM biz.project_import_publication WHERE task_id=%s",
                                           (uuid.UUID(source_task["taskId"]),)).fetchone()[0]
        check("G10", "取消中的来源任务没有可用应用",
              source_task["status"] == "WAITING_BRANCH" and cancelled_source["status"] == "CANCELLED"
              and not cancelled_source.get("mainTaskCreated") and publication == 0 and not (STAGING / (source_task["taskId"] + ".git")).exists(),
              cancelled_source["status"])

        def import_public(app_name, mutate):
            task = call(first, token, "/project/import/git/tasks",
                        {"ownerId": owner, "appName": app_name, "provider": "GitHub",
                         "repositoryUrl": "https://github.com/octocat/Hello-World"})["data"]
            open_tasks.append(("git", first, token, task["taskId"]))
            task = wait_source(first, token, task)
            if task["status"] != "WAITING_BRANCH" or len(task["branches"]) < 1:
                raise AssertionError("公开仓库没有进入单分支选择 " + task["status"] + " " + str(task.get("error")))
            branch = next(item for item in task["branches"] if item["name"] == "master")
            if mutate:
                unknown = call(first, token, f"/project/import/git/tasks/{task['taskId']}/branch",
                               {"ownerId": owner, "revision": task["revision"], "branch": "unknown-fixture-branch"}, expected=40000)
                task = call(first, token, "/project/import/git/tasks/" + task["taskId"])["data"]
                multi = call(first, token, f"/project/import/git/tasks/{task['taskId']}/branch",
                             {"ownerId": owner, "revision": task["revision"], "branch": ["master", "main"]}, expected=40000)
                task = call(first, token, "/project/import/git/tasks/" + task["taskId"])["data"]
                check("G06", "不存在的分支和多个分支都被拒绝", unknown["code"] == 40000 and multi["code"] == 40000, unknown["message"])
            task = call(first, token, f"/project/import/git/tasks/{task['taskId']}/branch",
                        {"ownerId": owner, "revision": task["revision"], "branch": branch["name"]})["data"]
            task = wait_source(first, token, task)
            if task["status"] != "WAITING_CONFIRMATION" or task["commit"] != branch["commit"]:
                raise AssertionError("读取版本和所选分支不一致")
            if mutate:
                check("G05", "公开仓库只选中一个分支", task["commit"] == branch["commit"] and branch["name"] == "master", task["commit"])
                with psycopg.connect(env_value("AGENT_POSTGRES_DSN")) as database:
                    changed = [{**item, "commit": "b" * 40} if item["name"] == branch["name"] else item for item in task["branches"]]
                    database.execute("UPDATE biz.project_import_source SET branches_json=%s::jsonb WHERE task_id=%s AND owner_id=%s",
                                     (json.dumps(changed), uuid.UUID(task["taskId"]), int(owner)))
                    database.commit()
                rebuilt = call(first, token, f"/project/import/git/tasks/{task['taskId']}/range",
                               {"ownerId": owner, "revision": task["revision"], "appName": app_name,
                                "options": {"skipDependencies": True, "skipBuildOutputs": True, "includeSensitive": False}})["data"]
                rebuilt = wait_source(first, token, rebuilt)
                check("G07", "广告 commit 变化后实际读取版本不变",
                      rebuilt["status"] == "WAITING_CONFIRMATION" and rebuilt["commit"] == branch["commit"], rebuilt.get("commit"))
                task = rebuilt
            main = call(first, token, "/project/import/tasks/" + task["taskId"])["data"]
            return commit(first, token, main), branch["commit"]

        first_repo, first_commit = import_public("收口公开仓库一", True)
        second_repo, second_commit = import_public("收口公开仓库二", False)
        check("G11", "同一公开仓库导入两次得到两个应用，且没有推送",
              first_repo["appId"] != second_repo["appId"] and first_commit == second_commit
              and (OUTPUT / ("project_" + first_repo["appId"])).is_dir()
              and (OUTPUT / ("project_" + second_repo["appId"])).is_dir())

        final_folder = call(first, token, "/app/get/vo?id=" + folder_app["appId"])["data"]
        check("E06", "本轮应用结束前仍能按所有者读到，名称没有被覆盖",
              str(final_folder["userId"]) == owner and final_folder["appName"] == "收口文件夹"
              and (folder_dir / "资料" / "说明 文档.txt").read_bytes() == "中文内容".encode())

        # 3. 停止失败单测若没有现成报告，就补跑一次，把退出码留在本次证据里。
        runner = subprocess.run(["cmd", "/c", "mvnw.cmd", "test", "-Dtest=ImportExecutionRunnerTest", "-q"],
                                cwd=ROOT, capture_output=True, text=True, timeout=300, encoding="utf-8", errors="replace")
        (evidence / "runner-test.txt").write_text(redact(runner.stdout[-1000:] + "\n" + runner.stderr[-1000:], hidden), encoding="utf-8")
        if runner.returncode != 0:
            raise AssertionError("ImportExecutionRunnerTest 退出码 " + str(runner.returncode))
        rows["R07"]["exitCode"] = 0
        rows["R07"]["detail"] += " ; ImportExecutionRunnerTest exit 0"

        required = [f"F{i:02d}" for i in range(1, 8)] + [f"G{i:02d}" for i in range(1, 12) if i != 4]
        required += [f"C{i:02d}" for i in range(1, 6)] + [f"U{i:02d}" for i in range(1, 7)]
        required += [f"D{i:02d}" for i in range(1, 9)] + [f"S{i:02d}" for i in range(1, 8)]
        required += [f"R{i:02d}" for i in range(1, 9)] + [f"E{i:02d}" for i in range(1, 7)]
        missing = [group for group in required if rows.get(group, {}).get("status") != "PASS"]
        if missing:
            raise AssertionError("未收口 " + ",".join(missing))
        report["status"] = "PASS"
        report["firstRequirement"] = "NOT_COMPLETE"
        report["acceptanceScope"] = "CORE_ASSERTIONS_ONLY"
        report["unverified"] = ["原矩阵全部子场景", "当前代码及环境资格", "真实 Vue/Spring 运行", "磁盘硬限制", "隔离 Agent 编辑", "持续日志", "私有仓库暂缓验收"]
        report["groups"] = 57
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = redact(error, hidden)
        raise
    finally:
        for kind, opener, token, task_id in open_tasks:
            try:
                cancel(kind, opener, token, task_id)
            except Exception as error:
                report.setdefault("cleanupErrors", []).append(redact(error, hidden))
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "evidence": report["evidence"], "error": report.get("error", "")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
