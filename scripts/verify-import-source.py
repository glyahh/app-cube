"""公开来源准备的真实 API/数据库/私有目录验证；不执行或提交导入工程。"""
import argparse
import http.cookiejar
import json
import secrets
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
import psycopg


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--output", required=True); parser.add_argument("--commit-provider", choices=["GitHub", "Gitee"])
    parser.add_argument("--rebuild-range", action="store_true")
    args = parser.parse_args(); evidence = Path(args.output).resolve(); evidence.mkdir(parents=True, exist_ok=False)
    checks, fixtures, tasks = [], [], []
    report = {"status": "RUNNING", "checks": checks, "fixtures": fixtures, "sources": [],
              "gitApplicationAcceptance": "NOT_RUN", "privateAcceptance": "NOT_RUN", "projectsExecuted": False}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))

    def client():
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        state = {"csrf": None}
        def call(path, body=None, method=None, expected=20000, csrf=True, record=True):
            headers = {"Origin": "http://localhost:5173"}
            if csrf and state["csrf"]: headers["X-Project-Import-CSRF"] = state["csrf"]
            data = None if body is None else json.dumps(body).encode()
            if data is not None: headers["Content-Type"] = "application/json"
            request = urllib.request.Request("http://127.0.0.1:8124/api" + path, data=data, headers=headers, method=method or ("POST" if data is not None else "GET"))
            try: response = opener.open(request, timeout=15)
            except urllib.error.HTTPError as error: response = error
            with response: result = json.loads(response.read().decode())
            assert result.get("code") == expected, f"{path}: code={result.get('code')}, expected={expected}"
            if record: checks.append({"path": path, "code": result["code"], "status": "PASS"})
            return result.get("data")
        account = "import_source_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); state["csrf"] = call("/project/import/csrf")
        fixtures.append({"ownerId": owner, "account": account, "passwordRecorded": False})
        return owner, call

    try:
        owner, call = client(); foreign, other = client()
        prefix = "/project/import/git/tasks"
        def payload(provider, url): return {"ownerId": owner, "appName": "公开来源验证", "provider": provider, "repositoryUrl": url}
        baseline = payload("GitHub", "https://github.com/octocat/Hello-World")
        call(prefix, baseline, expected=40300, csrf=False)
        call(prefix, {**baseline, "ownerId": foreign}, expected=40900)
        call(prefix, {**baseline, "credentialId": str(uuid.uuid4()), "credentialVersion": 1}, expected=40900)
        call(prefix, {**baseline, "repositoryUrl": "https://127.0.0.1/a/b"}, expected=40000)
        for provider, url in [("GitHub", "https://github.com/octocat/Hello-World"), ("Gitee", "https://gitee.com/sdk/gitee5j")]:
            task = call(prefix, payload(provider, url)); tasks.append(task["taskId"])
            other(prefix + "/" + task["taskId"], expected=40400)
            deadline = time.monotonic() + 75
            while task["status"] == "RESOLVING_SOURCE" and time.monotonic() < deadline:
                time.sleep(0.3); task = call(prefix + "/" + task["taskId"], record=False)
            assert task["status"] == "WAITING_BRANCH", f"source ended {task['status']}, error={task.get('error')}"
            assert task["branches"] and not task["mainTaskCreated"] and task["contentHash"] is None
            assert not {"resources", "executorId", "ownerId"}.intersection(task)
            with psycopg.connect(dsn) as database:
                row = database.execute("SELECT owner_id,status,branches_json,resources_json,main_task_created FROM biz.project_import_source WHERE task_id=%s", (uuid.UUID(task["taskId"]),)).fetchone()
                assert str(row[0]) == owner and row[1] == "WAITING_BRANCH" and row[2] == task["branches"] and row[3] and not row[4]
                assert all(int(resource["pid"]) > 0 and resource["started"] for resource in row[3])
                assert database.execute("SELECT count(*) FROM biz.project_import_task WHERE task_id=%s", (uuid.UUID(task["taskId"]),)).fetchone()[0] == 0
            directory = Path("temp/project_import_private") / (task["taskId"] + ".git")
            assert (directory / "metadata.git/HEAD").is_file()
            report["sources"].append({"provider": provider, "taskId": task["taskId"], "branches": task["branches"], "resourceCount": len(row[3]), "status": "PASS"})
            other(prefix + "/" + task["taskId"] + "/cancel?revision=" + task["revision"], method="POST", expected=40400)
            call(prefix + "/" + task["taskId"] + "/cancel?revision=99", method="POST", expected=40900)
        # 两个来源准备占用真实名额，不能让文件夹从另一入口绕过限额。
        call("/project/import/tasks", {"ownerId": owner, "appName": "不可创建", "files": [{"path": "a", "size": 1}],
             "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}}, expected=42900)
        if args.commit_provider:
            source = next(row for row in report["sources"] if row["provider"] == args.commit_provider)
            task_id = source["taskId"]; task = call(prefix + "/" + task_id)
            branch = next(row for row in task["branches"] if row["name"] == ("master" if args.commit_provider == "GitHub" else "main"))
            call(prefix + "/" + task_id + "/branch", {"ownerId": owner, "revision": 99, "branch": branch["name"]}, expected=40900)
            call(prefix + "/" + task_id + "/branch", {"ownerId": owner, "revision": task["revision"], "branch": "unknown-fixture-branch"}, expected=40000)
            task = call(prefix + "/" + task_id + "/branch", {"ownerId": owner, "revision": task["revision"], "branch": branch["name"], "commit": "b" * 40})
            deadline = time.monotonic() + 330
            while task["status"] == "READING_CONTENT" and time.monotonic() < deadline:
                time.sleep(0.3); task = call(prefix + "/" + task_id, record=False)
            assert task["status"] == "WAITING_CONFIRMATION", f"content ended {task['status']}, error={task.get('error')}"
            assert task["commit"] == branch["commit"] and task["mainTaskCreated"]
            main = call("/project/import/tasks/" + task_id)
            assert main["status"] == "WAITING_CONFIRMATION" and main["contentHash"] == task["contentHash"]
            if args.rebuild_range:
                old_main = dict(main)
                options = {"skipDependencies": True, "skipBuildOutputs": True, "includeSensitive": False}
                body = {"ownerId": owner, "revision": task["revision"], "appName": "固定范围重建", "options": options}
                route = prefix + "/" + task_id + "/range"
                call(route, body, expected=40300, csrf=False)
                other(route, {**body, "ownerId": foreign}, expected=40400)
                call(route, {**body, "revision": "99"}, expected=40900)
                call(route, {**body, "appName": ""}, expected=40000)
                # 只改变本测试的广告分支记录，证明重建不使用后来变化的 heads。
                with psycopg.connect(dsn) as database:
                    frozen = database.execute("SELECT manifest_json FROM biz.project_import_source WHERE task_id=%s", (uuid.UUID(task_id),)).fetchone()[0]
                    changed = [{**item, "commit": "b" * 40} if item["name"] == branch["name"] else item for item in task["branches"]]
                    database.execute("UPDATE biz.project_import_source SET branches_json=%s::jsonb WHERE task_id=%s AND owner_id=%s", (json.dumps(changed), uuid.UUID(task_id), int(owner))); database.commit()
                task = call(route, body)
                deadline = time.monotonic() + 180
                while task["status"] == "READING_CONTENT" and time.monotonic() < deadline:
                    time.sleep(0.15); task = call(prefix + "/" + task_id, record=False)
                assert task["status"] == "WAITING_CONFIRMATION", f"rebuild ended {task['status']}, error={task.get('error')}"
                assert task["commit"] == branch["commit"] and task["definition"]["options"] == options
                main = call("/project/import/tasks/" + task_id)
                assert int(main["revision"]) > int(old_main["revision"]) and main["appName"] == "固定范围重建"
                assert main["contentHash"] == task["contentHash"]
                call("/project/import/tasks/" + task_id + "/commit", {"revision": old_main["revision"], "contentHash": old_main["contentHash"]}, expected=40900)
                with psycopg.connect(dsn) as database:
                    assert database.execute("SELECT manifest_json FROM biz.project_import_source WHERE task_id=%s", (uuid.UUID(task_id),)).fetchone()[0] == frozen
                report["rangeRebuild"] = {"status": "PASS", "fixedCommit": branch["commit"], "oldMainRevision": old_main["revision"],
                    "newMainRevision": main["revision"], "options": options, "advertisedHeadsChange": "CONTROLLED_FIXTURE_ONLY", "snapshotPreserved": True}
            call("/project/import/tasks/" + task_id + "/cancel?revision=" + str(main["revision"]), method="POST", expected=40900)
            call("/project/import/tasks/" + task_id + "/commit", {"revision": main["revision"], "contentHash": "0" * 64}, expected=40900)
            with psycopg.connect(dsn) as database:
                actual_kind = database.execute("SELECT source_kind FROM biz.project_import_task WHERE task_id=%s", (uuid.UUID(task_id),)).fetchone()[0]
                assert actual_kind == "GIT"
                database.execute("UPDATE biz.project_import_source SET status='FAILED' WHERE task_id=%s AND owner_id=%s AND status='WAITING_CONFIRMATION'", (uuid.UUID(task_id), int(owner))); database.commit()
                call("/project/import/tasks/" + task_id + "/commit", {"revision": main["revision"], "contentHash": main["contentHash"]}, expected=40900)
                assert database.execute("SELECT count(*) FROM biz.project_import_publication WHERE task_id=%s", (uuid.UUID(task_id),)).fetchone()[0] == 0
                database.execute("UPDATE biz.project_import_source SET status='WAITING_CONFIRMATION' WHERE task_id=%s AND owner_id=%s AND status='FAILED'", (uuid.UUID(task_id), int(owner))); database.commit()
            # 仅改变本测试来源的有效期，验证过期不能绕过来源门禁发布 App。
            with psycopg.connect(dsn) as database:
                deadline = database.execute("SELECT expires_at FROM biz.project_import_source WHERE task_id=%s AND owner_id=%s", (uuid.UUID(task_id), int(owner))).fetchone()[0]
                database.execute("UPDATE biz.project_import_source SET expires_at=now()-interval '1 second' WHERE task_id=%s AND owner_id=%s", (uuid.UUID(task_id), int(owner))); database.commit()
                try:
                    call("/project/import/tasks/" + task_id + "/commit", {"revision": main["revision"], "contentHash": main["contentHash"]}, expected=40900)
                    assert database.execute("SELECT count(*) FROM biz.project_import_publication WHERE task_id=%s", (uuid.UUID(task_id),)).fetchone()[0] == 0
                finally:
                    database.execute("UPDATE biz.project_import_source SET expires_at=%s WHERE task_id=%s AND owner_id=%s", (deadline, uuid.UUID(task_id), int(owner))); database.commit()
            imported = call("/project/import/tasks/" + task_id + "/commit", {"revision": main["revision"], "contentHash": main["contentHash"]})
            assert imported["taskId"] == task_id and str(imported["appId"]).isdigit()
            assert call(prefix + "/" + task_id)["status"] == "SUCCEEDED"
            with psycopg.connect(dsn) as database:
                receipts = database.execute("SELECT receipts_json FROM biz.project_import_task WHERE task_id=%s", (uuid.UUID(task_id),)).fetchone()[0]
                app_owner = database.execute("SELECT user_id FROM biz.app WHERE id=%s AND is_delete=0", (int(imported["appId"]),)).fetchone()[0]
                assert str(app_owner) == owner
                if args.rebuild_range:
                    assert database.execute("SELECT app_name FROM biz.app WHERE id=%s", (int(imported["appId"]),)).fetchone()[0] == "固定范围重建"
            import hashlib
            workspace = Path("temp/code_output") / ("project_" + str(imported["appId"]))
            for receipt in receipts:
                final = workspace / receipt["path"]
                assert final.is_file() and final.stat().st_size == int(receipt["size"])
                assert hashlib.sha256(final.read_bytes()).hexdigest() == receipt["sha256"]
            assert not (workspace / ".git").exists()
            report["fixedContentPublication"] = {"status": "PASS", "provider": args.commit_provider, "taskId": task_id,
                "appId": str(imported["appId"]), "commit": branch["commit"], "includedFiles": len(receipts), "workspace": str(workspace.resolve()),
                "sourceGateNegative": "CONTROLLED_FIXTURE_STATE_INJECTION", "executionAcceptance": "NOT_RUN"}
        for task_id in tasks:
            task = call(prefix + "/" + task_id)
            if task["status"] == "SUCCEEDED": continue
            cancelled = call(prefix + "/" + task_id + "/cancel?revision=" + task["revision"], method="POST")
            assert cancelled["status"] == "CANCELLED" and cancelled["cleanup"] == "DONE"
            assert not (Path("temp/project_import_private") / (task_id + ".git")).exists()
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["errorType"] = type(error).__name__; report["error"] = str(error); raise
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": len(checks), "evidence": str(evidence)}))


if __name__ == "__main__": main()
