"""凭据接口、密文与任务撤销的本机验收；只使用虚构 Token，不请求远端。"""
import argparse
import http.cookiejar
import json
import os
import secrets
import urllib.error
import urllib.request
import uuid
from pathlib import Path
import psycopg


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--output", required=True)
    args = parser.parse_args(); evidence = Path(args.output).resolve(); evidence.mkdir(parents=True, exist_ok=False)
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    checks, fixtures, markers = [], [], []
    report = {"status": "RUNNING", "checks": checks, "fixtures": fixtures, "realPlatformAcceptance": "NOT_RUN", "leaseFixtures": "CONTROLLED_DB_BINDING_NOT_GIT_IMPORT"}

    def client():
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        state = {"csrf": None}

        def call(path, data=None, method=None, expected=20000, csrf=True):
            headers = {"Origin": "http://localhost:5173"}
            if csrf and state["csrf"]: headers["X-Project-Import-CSRF"] = state["csrf"]
            if isinstance(data, bytes): body = data; headers["Content-Type"] = "application/octet-stream"
            elif data is None: body = None
            else: body = json.dumps(data).encode(); headers["Content-Type"] = "application/json"
            request = urllib.request.Request("http://127.0.0.1:8124/api" + path, data=body, headers=headers, method=method or ("POST" if data is not None else "GET"))
            try: response = opener.open(request, timeout=30)
            except urllib.error.HTTPError as error: response = error
            with response: text = response.read().decode(); actual = json.loads(text)
            assert not any(marker in text for marker in markers), "Secret returned in HTTP response"
            if actual.get("code") != expected: raise RuntimeError(f"{path}: code={actual.get('code')}, expected={expected}")
            checks.append({"path": path, "code": actual["code"], "status": "PASS"})
            return actual.get("data")

        account = "import_cred_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); state["csrf"] = call("/project/import/csrf")
        fixtures.append({"ownerId": owner, "account": account, "passwordRecorded": False})
        return owner, call

    try:
        owner, call = client(); foreign, other = client()

        def create(provider, name):
            token = "FAKE_IMPORT_TEST_" + secrets.token_hex(16); markers.append(token)
            return call("/project/import/credentials", {"ownerId": owner, "provider": provider, "name": name, "token": token})

        one = create("GitHub", "读取一"); two = create("GitHub", "读取二"); gitee = create("Gitee", "读取三")
        other("/project/import/git/tasks", {"ownerId": foreign, "appName": "越权认证不得启动", "provider": "GitHub", "repositoryUrl": "https://github.com/octocat/Hello-World",
            "credentialId": one["id"], "credentialVersion": one["version"]}, expected=40900)
        call("/project/import/git/tasks", {"ownerId": owner, "appName": "平台错配不得启动", "provider": "Gitee", "repositoryUrl": "https://gitee.com/sdk/gitee5j",
            "credentialId": one["id"], "credentialVersion": one["version"]}, expected=40900)
        call(f"/project/import/credentials/{one['id']}/name", {"ownerId": owner, "name": "不应保存"}, "PUT", expected=40000)
        assert len({one["id"], two["id"], gitee["id"]}) == 3
        assert len(call("/project/import/credentials")) == 3 and other("/project/import/credentials") == []
        call("/project/import/credentials", {"ownerId": owner, "provider": "GitHub", "name": "x", "token": markers[0]}, expected=40300, csrf=False)
        other(f"/project/import/credentials/{one['id']}/token", {"ownerId": foreign, "revision": 0, "token": markers[0]}, "PUT", expected=40400)
        renamed = call(f"/project/import/credentials/{one['id']}/name", {"ownerId": owner, "revision": 0, "name": "改备注"}, "PUT")
        assert renamed["version"] == "1" and renamed["revision"] == "1"
        call(f"/project/import/credentials/{one['id']}/token", {"ownerId": owner, "revision": 0, "token": markers[0]}, "PUT", expected=40900)

        with psycopg.connect(dsn) as database:
            encrypted = database.execute("SELECT ciphertext,nonce,key_id FROM biz.project_import_credential_version WHERE credential_id=%s AND version=1", (uuid.UUID(one["id"]),)).fetchone()
            assert encrypted and encrypted[2] == "v1" and not any(marker in encrypted[0] for marker in markers)
            report["ciphertextStoredWithoutPlaintext"] = True
            database.commit()

            def ready_task(credential, git_source=False):
                created = call("/project/import/tasks", {"ownerId": owner, "appName": "凭据租约测试", "files": [{"path": "index.html", "size": 1}], "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}})
                ready = call(f"/project/import/tasks/{created['taskId']}/files/0?revision=0", b"x", "PUT")
                # 专用任务的受控绑定仅验证提交撤销条件，不冒充 Git 拉取成功。
                with database.transaction():
                    database.execute("INSERT INTO biz.project_import_credential_lease(task_id,credential_id,credential_version,owner_id,repository_url) VALUES (%s,%s,%s,%s,%s)",
                        (uuid.UUID(ready["taskId"]), uuid.UUID(credential["id"]), int(credential["version"]), int(owner),
                        "https://" + ("github.com" if credential["provider"] == "GitHub" else "gitee.com") + "/example/controlled-fixture.git"))
                    if git_source:
                        definition = {"appName": "凭据租约测试", "source": {"provider": credential["provider"], "owner": "example", "repository": "controlled-fixture",
                            "cloneUrl": "https://" + ("github.com" if credential["provider"] == "GitHub" else "gitee.com") + "/example/controlled-fixture.git"},
                            "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}}
                        import hashlib
                        snapshot = {"branch": "main", "commit": "a" * 40, "blobs": [{"path": "index.html", "objectId": hashlib.sha1(b"blob 1\0x").hexdigest(), "size": 1, "executable": False}]}
                        database.execute("""INSERT INTO biz.project_import_source(task_id,owner_id,definition_json,status,credential_id,credential_version,
                            branch_name,commit_id,manifest_json,main_task_created,content_hash,executor_id,executor_pid,executor_start,expires_at)
                            VALUES (%s,%s,%s::jsonb,'WAITING_CONFIRMATION',%s,%s,'main',%s,%s::jsonb,true,%s,%s,%s,now(),now()+interval '15 minutes')""",
                            (uuid.UUID(ready["taskId"]), int(owner), json.dumps(definition), uuid.UUID(credential["id"]), int(credential["version"]), "a" * 40,
                            json.dumps(snapshot), ready["contentHash"], uuid.uuid4(), os.getpid()))
                        database.execute("UPDATE biz.project_import_task SET source_kind='GIT' WHERE task_id=%s AND owner_id=%s", (uuid.UUID(ready["taskId"]), int(owner)))
                        directory = Path("temp/project_import_private", ready["taskId"] + ".git"); directory.mkdir()
                        (directory / "controlled-fixture").write_text("credential gate fixture, not Git content", encoding="utf-8")
                        fixtures.append({"linkedSourceTaskId": ready["taskId"], "controlledBinding": True, "networkExecuted": False})
                database.commit(); return ready

            related = ready_task(one, git_source=True); unrelated = ready_task(two, git_source=True)
            replacement = call(f"/project/import/credentials/{one['id']}/token", {"ownerId": owner, "revision": renamed["revision"], "token": markers[1]}, "PUT")
            assert replacement["credential"]["version"] == "2"
            assert replacement["stops"] == [{"taskId": related["taskId"], "status": "CANCELLED", "cleanup": "DONE", "error": None}]
            assert call(f"/project/import/tasks/{related['taskId']}")["status"] == "CANCELLED"
            assert not Path("temp/project_import_private", related["taskId"]).exists()
            assert not Path("temp/project_import_private", related["taskId"] + ".git").exists()
            assert call(f"/project/import/tasks/{unrelated['taskId']}")["status"] == "WAITING_CONFIRMATION"
            assert database.execute("SELECT state FROM biz.project_import_credential_lease WHERE task_id=%s", (uuid.UUID(related["taskId"]),)).fetchone()[0] == "REVOKED"
            database.commit()
            completed = call(f"/project/import/tasks/{unrelated['taskId']}/commit", {"revision": unrelated["revision"], "contentHash": unrelated["contentHash"]})
            assert call(f"/project/import/git/tasks/{unrelated['taskId']}")["status"] == "SUCCEEDED"
            report["credentialBoundSourcePublication"] = "CONTROLLED_FIXTURE_PASS"
            deleted = call(f"/project/import/credentials/{two['id']}?ownerId={owner}&revision=0", method="DELETE")
            assert deleted["credential"]["state"] == "DELETED" and deleted["stops"] == []
            assert call(f"/project/import/tasks/{unrelated['taskId']}/result")["appId"] == completed["appId"]
            assert database.execute("SELECT key_id,nonce,ciphertext,state FROM biz.project_import_credential_version WHERE credential_id=%s", (uuid.UUID(two["id"]),)).fetchone() == (None, None, None, "DELETED")
            database.commit()
            fixtures.append({"completedAppId": completed["appId"], "taskId": unrelated["taskId"], "retainedApplication": True})
            def preparatory_source(credential):
                source_id = uuid.uuid4()
                definition = {"appName": "来源撤销夹具", "source": {"provider": credential["provider"], "owner": "example", "repository": "controlled-fixture",
                    "cloneUrl": "https://" + ("github.com" if credential["provider"] == "GitHub" else "gitee.com") + "/example/controlled-fixture.git"},
                    "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": False}}
                directory = Path("temp/project_import_private") / (str(source_id) + ".git")
                directory.mkdir(); (directory / "controlled-fixture").write_text("no Git execution", encoding="utf-8")
                with database.transaction():
                    database.execute("""INSERT INTO biz.project_import_source(task_id,owner_id,definition_json,status,credential_id,credential_version,
                        executor_id,executor_pid,executor_start,expires_at) VALUES (%s,%s,%s::jsonb,'WAITING_BRANCH',%s,%s,%s,%s,now(),now()+interval '15 minutes')""",
                        (source_id, int(owner), json.dumps(definition), uuid.UUID(credential["id"]), int(credential["version"]), uuid.uuid4(), os.getpid()))
                database.commit()
                fixtures.append({"sourceTaskId": str(source_id), "controlledBinding": True, "networkExecuted": False})
                return str(source_id), directory

            preparatory, source_directory = preparatory_source(replacement["credential"])
            other_source, other_directory = preparatory_source(gitee)
            note = call(f"/project/import/credentials/{one['id']}/name", {"ownerId": owner, "revision": replacement["credential"]["revision"], "name": "来源备注"}, "PUT")
            assert call(f"/project/import/git/tasks/{preparatory}")["status"] == "WAITING_BRANCH"
            replaced_source = call(f"/project/import/credentials/{one['id']}/token", {"ownerId": owner, "revision": note["revision"], "token": markers[1]}, "PUT")
            assert replaced_source["stops"] == [{"taskId": preparatory, "status": "CANCELLED", "cleanup": "DONE", "error": None}]
            assert not source_directory.exists() and other_directory.is_dir()
            assert call(f"/project/import/git/tasks/{other_source}")["status"] == "WAITING_BRANCH"
            report["preparatorySourceReplacement"] = "CONTROLLED_FIXTURE_PASS"
            # 撤销先于提交的顺序：仅撤销本测试租约，确认不能创建 App。
            revocation_first = ready_task(gitee)
            with database.transaction():
                database.execute("UPDATE biz.project_import_credential_lease SET state='REVOKED' WHERE task_id=%s AND owner_id=%s", (uuid.UUID(revocation_first["taskId"]), int(owner)))
            database.commit()
            call(f"/project/import/tasks/{revocation_first['taskId']}/commit", {"revision": revocation_first["revision"], "contentHash": revocation_first["contentHash"]}, expected=40900)
            assert database.execute("SELECT count(*) FROM biz.project_import_publication WHERE task_id=%s", (uuid.UUID(revocation_first["taskId"]),)).fetchone()[0] == 0
            database.commit()
            current = call(f"/project/import/tasks/{revocation_first['taskId']}")
            call(f"/project/import/tasks/{revocation_first['taskId']}/cancel?revision={current['revision']}", method="POST")
            for credential in call("/project/import/credentials"):
                removed = call(f"/project/import/credentials/{credential['id']}?ownerId={owner}&revision={credential['revision']}", method="DELETE")
                if credential["id"] == gitee["id"]:
                    assert removed["stops"] == [{"taskId": other_source, "status": "CANCELLED", "cleanup": "DONE", "error": None}]
                    assert not other_directory.exists()
                    report["preparatorySourceDeletion"] = "CONTROLLED_FIXTURE_PASS"
            assert call("/project/import/credentials") == []
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["errorType"] = type(error).__name__; raise
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": len(checks), "evidence": str(evidence)}))


if __name__ == "__main__": main()
