"""专用虚构凭据的身份失败验收；不使用宿主 Token、不读取真实私有仓库。"""
import argparse
import http.cookiejar
import json
import secrets
import time
import urllib.request
import uuid
from pathlib import Path
import psycopg


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--output", required=True)
    output = Path(parser.parse_args().output).resolve(); output.mkdir(parents=True, exist_ok=False)
    report = {"status": "RUNNING", "checks": [], "sources": [], "privateRepositoryAcceptance": "NOT_RUN", "realCredentialsUsed": False}
    markers = []
    csrf = None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(path, data=None, method=None, expected=20000):
        headers = {"Origin": "http://localhost:5173"}
        if csrf: headers["X-Project-Import-CSRF"] = csrf
        if data is not None: headers["Content-Type"] = "application/json"
        request = urllib.request.Request("http://127.0.0.1:8124/api" + path, data=None if data is None else json.dumps(data).encode(),
            headers=headers, method=method or ("GET" if data is None else "POST"))
        with opener.open(request, timeout=45) as response: raw = response.read().decode()
        assert not any(marker in raw for marker in markers), "Secret returned by business endpoint"
        value = json.loads(raw); assert value["code"] == expected, (path, value["code"], expected)
        report["checks"].append({"path": path, "code": value["code"], "status": "PASS"})
        return value.get("data")

    try:
        account = "import_auth_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); csrf = call("/project/import/csrf")
        report["ownerId"] = owner; report["account"] = account; report["passwordRecorded"] = False
        dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
        for provider, url in [("GitHub", "https://github.com/octocat/Hello-World"), ("Gitee", "https://gitee.com/sdk/gitee5j")]:
            marker = "INVALID_IMPORT_AUTH_" + secrets.token_hex(16); markers.append(marker)
            credential = call("/project/import/credentials", {"ownerId": owner, "provider": provider, "name": "认证负例", "token": marker})
            baseline = {"ownerId": owner, "appName": "认证失败不得匿名导入", "provider": provider, "repositoryUrl": url,
                "credentialId": credential["id"], "credentialVersion": credential["version"]}
            call("/project/import/git/tasks", {**baseline, "credentialVersion": "99"}, expected=40900)
            created = call("/project/import/git/tasks", baseline)
            deadline = time.monotonic() + 45
            while True:
                source = call("/project/import/git/tasks/" + created["taskId"])
                if source["status"] != "RESOLVING_SOURCE": break
                assert time.monotonic() < deadline, "Bounded authentication did not finish"
                time.sleep(0.25)
            assert source["status"] == "FAILED", source["status"]
            assert source["error"] in ("AUTH_INVALID", "AUTH_FORBIDDEN"), source["error"]
            assert source["cleanup"] == "DONE" and source["branches"] == [] and not source["mainTaskCreated"]
            assert not Path("temp/project_import_private", source["taskId"] + ".git").exists()
            with psycopg.connect(dsn) as database:
                assert database.execute("SELECT count(*) FROM biz.project_import_task WHERE task_id=%s", (uuid.UUID(source["taskId"]),)).fetchone()[0] == 0
                assert database.execute("SELECT count(*) FROM biz.project_import_publication WHERE task_id=%s", (uuid.UUID(source["taskId"]),)).fetchone()[0] == 0
            report["sources"].append({"provider": provider, "taskId": source["taskId"], "status": "PASS", "error": source["error"], "anonymousFallback": False})
            deleted = call(f"/project/import/credentials/{credential['id']}?ownerId={owner}&revision=0", method="DELETE")
            assert all(stop["status"] == "CANCELLED" and stop["cleanup"] == "DONE" for stop in deleted["stops"])
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["errorType"] = type(error).__name__; raise
    finally:
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": len(report["checks"]), "evidence": str(output)}))


if __name__ == "__main__": main()
