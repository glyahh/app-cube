"""用两个临时测试账号核对真实导入暂存 API；不执行工程、不创建 App、不记录密码。"""

import argparse
import hashlib
import http.cookiejar
import json
import secrets
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8124/api")
    parser.add_argument("--output", required=True)
    parser.add_argument("--staging-root", default="temp/project_import_private")
    parser.add_argument("--commit", action="store_true")
    args = parser.parse_args()
    evidence = Path(args.output).resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    staging = Path(args.staging_root).resolve()
    checks, tasks, users = [], [], []
    login_credentials = {}

    def client():
        return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(opener, path, method="GET", body=None, csrf=None, origin="http://localhost:5173"):
        headers = {"Origin": origin}
        if csrf:
            headers["X-Project-Import-CSRF"] = csrf
        if body is None:
            data = None
        elif isinstance(body, bytes):
            data = body
            headers["Content-Type"] = "application/octet-stream"
        else:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            response = opener.open(urllib.request.Request(args.base + path, data=data, headers=headers, method=method), timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def expect(name, response, code=20000, http=200):
        status, body = response
        assert status == http and body.get("code") == code, f"{name}: HTTP={status}, code={body.get('code')}"
        checks.append({"name": name, "status": "PASS", "http": status, "businessCode": body["code"]})
        return body.get("data")

    def account(suffix):
        opener = client()
        name = "import_test_" + secrets.token_hex(8) + suffix
        password = secrets.token_urlsafe(24)
        user_id = expect("register-test-user-" + suffix, request(opener, "/user/register", "POST", {
            "userAccount": name, "userPassword": password, "checkPassword": password,
        }))
        expect("login-test-user-" + suffix, request(opener, "/user/login", "POST", {
            "userAccount": name, "userPassword": password,
        }))
        token = expect("session-csrf-" + suffix, request(opener, "/project/import/csrf"))
        users.append({"userId": str(user_id), "account": name, "passwordRecorded": False})
        login_credentials[str(user_id)] = (name, password)
        return opener, str(user_id), token

    def create(opener, owner, token, files, options=None):
        body = {"ownerId": owner, "appName": "接口验证工程", "files": files, "options": options or {
            "skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False,
        }}
        view = expect("create-independent-task", request(opener, "/project/import/tasks", "POST", body, token))
        tasks.append((opener, token, view["taskId"]))
        return view

    result = {"time": datetime.now().astimezone().isoformat(), "base": args.base, "users": users,
              "checks": checks, "taskResults": [], "importBusinessAcceptance": "NOT_RUN", "applicationsCreated": False}
    try:
        expect("anonymous-before-body", request(client(), "/project/import/tasks", "POST", {}), 40100, 401)
        first, owner, token = account("a")
        second, other, other_token = account("b")
        defaults = {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}
        saved = {**defaults, "skipDependencies": True}
        assert expect("initial-import-preference", request(first, "/project/import/preferences")) == defaults
        expect("preference-save-requires-csrf", request(first, "/project/import/preferences", "PUT", {"ownerId": owner, **saved}), 40300, 403)
        expect("preference-owner-switch-rejected", request(first, "/project/import/preferences", "PUT", {"ownerId": other, **saved}, token), 40900)
        expect("incomplete-preference-rejected", request(first, "/project/import/preferences", "PUT", {"ownerId": owner}, token), 40000)
        assert expect("explicit-save-preference", request(first, "/project/import/preferences", "PUT", {"ownerId": owner, **saved}, token)) == saved
        assert expect("other-user-default-unchanged", request(second, "/project/import/preferences")) == defaults
        relogin = client()
        name, password = login_credentials[owner]
        expect("relogin-test-user", request(relogin, "/user/login", "POST", {"userAccount": name, "userPassword": password}))
        assert expect("preference-survives-new-session", request(relogin, "/project/import/preferences")) == saved
        inherited = expect("task-inherits-saved-preference", request(first, "/project/import/tasks", "POST", {
            "ownerId": owner, "appName": "偏好验证", "files": [{"path": "src/a", "size": 0}, {"path": "node_modules/a", "size": 0}],
        }, token))
        tasks.append((first, token, inherited["taskId"]))
        assert len(inherited["manifest"]["included"]) == 1
        expect("change-default-after-task-created", request(first, "/project/import/preferences", "PUT", {"ownerId": owner, **defaults}, token))
        assert expect("frozen-task-not-rewritten", request(first, "/project/import/tasks/" + inherited["taskId"]))["manifest"] == inherited["manifest"]
        expect("cancel-preference-fixture", request(first, f"/project/import/tasks/{inherited['taskId']}/cancel?revision={inherited['revision']}", "POST", csrf=token))
        expect("restore-test-user-saved-preference", request(first, "/project/import/preferences", "PUT", {"ownerId": owner, **saved}, token))
        entries = {
            "工程/src/首页 文件.js": "console.log('known test fixture')".encode(),
            "工程/empty.bin": b"", "工程/binary.bin": bytes([0, 255, 13, 10]),
            "工程/node_modules/pkg/index.js": b"dependency fixture", "工程/dist/index.html": b"static fixture",
            "工程/.env": b"FAKE_IMPORT_SECRET_DO_NOT_SEND_TO_MODEL", "工程/.env.example": b"EXAMPLE=placeholder",
        }
        source = evidence / "source"
        for relative, data in entries.items():
            path = source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        hashes = {path: hashlib.sha256(data).hexdigest() for path, data in entries.items()}
        files = [{"path": path, "size": len(data)} for path, data in entries.items()]
        directory_path = "工程/中文 空目录/空子目录"
        (source / directory_path).mkdir(parents=True)
        files.append({"path": directory_path, "size": 0, "directory": True})
        payload = {"ownerId": owner, "appName": "x", "files": files, "options": {
            "skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False,
        }}
        expect("missing-csrf", request(first, "/project/import/tasks", "POST", payload), 40300, 403)
        expect("foreign-origin", request(first, "/project/import/tasks", "POST", payload, token, "https://untrusted.invalid"), 40300, 403)
        expect("wrong-owner", request(first, "/project/import/tasks", "POST", {**payload, "ownerId": other}, token), 40900)
        expect("invalid-path", request(first, "/project/import/tasks", "POST", {**payload, "files": [{"path": "../bad", "size": 1}]}, token), 40000)

        view = create(first, owner, token, files)
        assert expect("temporary-options-do-not-save", request(first, "/project/import/preferences")) == saved
        identifier = view["taskId"]
        included = view["manifest"]["included"]
        assert len(included) == 7 and len(view["manifest"]["excluded"]) == 1
        expect("foreign-task-read", request(second, "/project/import/tasks/" + identifier), 40400)
        expect("foreign-task-cancel", request(second, f"/project/import/tasks/{identifier}/cancel?revision={view['revision']}", "POST", csrf=other_token), 40400)
        expect("out-of-order-upload", request(first, f"/project/import/tasks/{identifier}/files/1?revision={view['revision']}", "PUT", b"", token), 40900)
        for index, entry in enumerate(included):
            old_revision = view["revision"]
            content = b"" if entry.get("directory") else entries[entry["path"]]
            view = expect("upload-file-" + str(index), request(first, f"/project/import/tasks/{identifier}/files/{index}?revision={old_revision}", "PUT", content, token))
            if index == 0:
                expect("stale-upload-revision", request(first, f"/project/import/tasks/{identifier}/files/{index}?revision={old_revision}", "PUT", entries[entry["path"]], token), 40900)
            stored = staging / identifier / f"{index}.bin"
            assert stored.resolve().is_relative_to(staging)
            assert hashlib.sha256(stored.read_bytes()).hexdigest() == hashlib.sha256(content).hexdigest()
        assert view["status"] == "WAITING_CONFIRMATION" and len(view["contentHash"]) == 64
        result["taskResults"].append({"taskId": identifier, "ownerId": owner, "revision": view["revision"],
                                     "observedStatus": view["status"], "contentHash": view["contentHash"], "actualFiles": len(included)})
        refreshed = expect("authoritative-refresh", request(first, "/project/import/tasks/" + identifier))
        assert refreshed == view
        page = expect("no-half-created-app", request(first, "/app/my/list/page/vo", "POST", {"pageNum": 1, "pageSize": 10}))
        assert not page["records"]

        failed = create(first, owner, token, [{"path": "a", "size": 2}])
        expect("short-file-rejected", request(first, f"/project/import/tasks/{failed['taskId']}/files/0?revision=0", "PUT", b"x", token), 50001)
        failure = expect("persisted-failure", request(first, "/project/import/tasks/" + failed["taskId"]))
        assert failure["status"] == "FAILED" and failure["cleanup"] == "DONE"
        assert not (staging / failed["taskId"]).exists()
        result["taskResults"].append({"taskId": failed["taskId"], "ownerId": owner, "observedStatus": "FAILED", "cleanup": "DONE"})
        for relative, expected in hashes.items():
            assert hashlib.sha256((source / relative).read_bytes()).hexdigest() == expected
        result["sourceHashes"] = hashes
        result["sourceUnchanged"] = True
        if args.commit:
            identifier = view["taskId"]
            confirmation = {"revision": view["revision"], "contentHash": view["contentHash"]}
            expect("foreign-commit-rejected", request(second, f"/project/import/tasks/{identifier}/commit", "POST", confirmation, other_token), 40400)
            expect("wrong-content-confirmation-rejected", request(first, f"/project/import/tasks/{identifier}/commit", "POST", {**confirmation, "contentHash": "0" * 64}, token), 40900)
            committed = expect("real-application-commit", request(first, f"/project/import/tasks/{identifier}/commit", "POST", confirmation, token))
            app_id = str(committed["appId"])
            assert committed["taskId"] == identifier and committed["redirectPath"] == f"/app/{app_id}/chat"
            assert expect("idempotent-commit", request(first, f"/project/import/tasks/{identifier}/commit", "POST", confirmation, token))["appId"] == app_id
            actual = expect("committed-task-authoritative-state", request(first, f"/project/import/tasks/{identifier}"))
            assert actual["status"] == "SUCCEEDED"
            app = expect("real-application-owner-and-name", request(first, f"/app/get/vo?id={app_id}"))
            assert str(app["userId"]) == owner and app["appName"] == "接口验证工程"
            expect("foreign-application-read-rejected", request(second, f"/app/get/vo?id={app_id}"), 40101)
            workspace = expect("real-workspace-source", request(first, f"/app/{app_id}/workspace"))
            assert workspace["hasSource"] and workspace["workspaceDirName"] == "project_" + app_id
            target = Path("temp/code_output").resolve() / ("project_" + app_id)
            for entry in included:
                if entry.get("directory"):
                    assert (target / entry["path"]).is_dir()
                    assert not list((target / entry["path"]).iterdir())
                else:
                    assert hashlib.sha256((target / entry["path"]).read_bytes()).hexdigest() == hashes[entry["path"]]
            assert not (target / "工程/.env").exists()
            expect("cancel-successful-import-rejected", request(first, f"/project/import/tasks/{identifier}/cancel?revision={actual['revision']}", "POST", csrf=token), 40900)
            result["applicationsCreated"] = True
            result["folderCommitVerification"] = {"appId": app_id, "taskId": identifier, "contentHash": actual["contentHash"], "status": "PASS", "snapshotRetained": True}
        result["status"] = "PASS"
    except Exception as error:
        result["status"] = "FAIL"
        result["error"] = str(error)
        raise
    finally:
        for opener, csrf, identifier in tasks:
            try:
                current = expect("cleanup-query", request(opener, "/project/import/tasks/" + identifier))
                if current["status"] == "SUCCEEDED":
                    result.setdefault("retainedSuccessfulTasks", []).append(identifier)
                    continue
                cancelled = expect("cancel-owned-test-task", request(opener, f"/project/import/tasks/{identifier}/cancel?revision={current['revision']}", "POST", csrf=csrf))
                assert cancelled["status"] == "CANCELLED" and cancelled["cleanup"] == "DONE"
                assert not (staging / identifier).exists()
            except Exception as error:
                result["status"] = "FAIL"
                result.setdefault("cleanupErrors", []).append({"taskId": identifier, "error": str(error)})
        (evidence / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": result["status"], "checks": len(checks), "evidence": str(evidence)}, ensure_ascii=False))
        if result["status"] != "PASS":
            raise RuntimeError("真实导入暂存验证失败，见脱敏证据。")


if __name__ == "__main__":
    main()
