"""第一项步骤5：隔离执行副本的真实核对。[受控夹具] 只运行本脚本生成并导入的小脚本工程，不运行用户真实工程；执行入口仍未对用户开放。"""
import hashlib
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import urlsplit, unquote
import psycopg

WSL = ["wsl.exe", "-d", os.environ.get("AGENT_WSL_DISTRO", "Ubuntu"), "--exec"]
FIXTURE = {
    "index.html": "<html><body>isolation fixture</body></html>\n",
    "README.md": "[受控夹具] 步骤5隔离执行核对用的小脚本，不是用户工程。\n",
    "tools/ok.sh": "echo \"ok from isolated copy\"; pwd; id -u; echo changed > generated.txt; ls; "
                   "echo \"swap.max=$(cat /sys/fs/cgroup/memory.swap.max 2>/dev/null || echo NA) "
                   "swaptotal=$(awk '/^SwapTotal:/{print $2}' /proc/meminfo)\"; exit 0\n",
    "tools/fail.sh": "echo failing on purpose >&2; exit 3\n",
    "tools/tamper.sh": "[ -e /input ] && echo INPUT_PRESENT || echo INPUT_ABSENT\n"
                       "echo x > /work/index.html && echo WORK_COPY_WRITE_OK\n"
                       "ls /mnt/d >/dev/null 2>&1 && echo HOST_DRIVE_VISIBLE || echo HOST_DRIVE_HIDDEN\n"
                       "echo \"nonce_env=$(env | grep -c GLYAHH_)\"\n"
                       "echo \"nonce_pid1=$(tr '\\0' '\\n' < /proc/1/environ | grep -c GLYAHH_)\"\n"
                       "exit 0\n",
    "tools/mem.py": "def r(p):\n    try:\n        return open(p).read().strip()\n    except Exception:\n        return 'NA'\n"
                    "chunks = []\nfor i in range(48):\n    b = bytearray(8 * 1024 * 1024)\n    for j in range(0, len(b), 4096):\n        b[j] = 1\n    chunks.append(b)\n"
                    "print('allocated_mb', len(chunks) * 8, flush=True)\n"
                    "print('memory.max', r('/sys/fs/cgroup/memory.max'), 'memory.peak', r('/sys/fs/cgroup/memory.peak'), 'swap.current', r('/sys/fs/cgroup/memory.swap.current'), 'swap.max', r('/sys/fs/cgroup/memory.swap.max'), flush=True)\n",
    "tools/fork.py": "import os, time\npids = []\nfails = 0\nfor i in range(100):\n    try:\n        p = os.fork()\n        if p == 0:\n            time.sleep(20)\n            os._exit(0)\n        pids.append(p)\n"
                     "    except OSError:\n        fails += 1\n"
                     "rd = lambda n: open('/sys/fs/cgroup/' + n).read().strip() if os.path.exists('/sys/fs/cgroup/' + n) else 'NA'\n"
                     "print('forked', len(pids), 'failed', fails, 'pids.max', rd('pids.max'), 'pids.current', rd('pids.current'), 'pids.peak', rd('pids.peak'), flush=True)\n"
                     "for p in pids:\n    os.kill(p, 9)\n",
    "tools/disk.sh": "dd if=/dev/zero of=/work/big bs=1M count=100 2>&1 | tail -1\necho \"work_big_bytes=$(stat -c %s /work/big)\"\n"
                     "dd if=/dev/zero of=/tmp/big bs=1M count=100 2>&1 | tail -1\necho \"tmp_big_bytes=$(stat -c %s /tmp/big)\"\n"
                     "touch /rootfile 2>&1\ndf -k /work /tmp | cat\nexit 0\n",
    "tools/out.sh": "yes glyahh-output-flood-0123456789\n",
    "tools/sleep.sh": "echo sleeping $1\nsleep \"$1\"\n",
    "tools/net.py": "import os, socket\n"
                    "def tcp(h, p):\n    try:\n        s = socket.create_connection((h, p), timeout=3)\n        s.close()\n        return 'CONNECTED'\n    except Exception as e:\n        return type(e).__name__\n"
                    "print('ifaces', sorted(os.listdir('/sys/class/net')), flush=True)\n"
                    "for h, p in [('1.1.1.1', 443), ('8.8.8.8', 53), ('172.17.0.1', 8124), ('10.255.255.254', 53), ('host.docker.internal', 8124)]:\n    print('tcp', h, p, tcp(h, p), flush=True)\n"
                    "try:\n    print('dns', socket.getaddrinfo('github.com', 443)[:1])\nexcept Exception as e:\n    print('dns', type(e).__name__)\n",
    "tools/cpu.sh": "a=$(awk '/usage_usec/{print $2}' /sys/fs/cgroup/cpu.stat)\nstart=$(date +%s%N)\n"
                    "for i in 1 2 3 4; do ( end=$(( $(date +%s) + 4 )); while [ $(date +%s) -lt $end ]; do :; done ) & done\nwait\n"
                    "stop=$(date +%s%N)\nb=$(awk '/usage_usec/{print $2}' /sys/fs/cgroup/cpu.stat)\n"
                    "echo \"cpu.max=$(cat /sys/fs/cgroup/cpu.max) wall_ms=$(( (stop-start)/1000000 )) cpu_used_ms=$(( (b-a)/1000 )) throttled=$(awk '/nr_throttled/{print $2}' /sys/fs/cgroup/cpu.stat)\"\n",
}


def tree(path):
    path = Path(path)
    return {str(p.relative_to(path)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(path.rglob("*")) if p.is_file()}


def docker(*args, timeout=60):
    return subprocess.run(WSL + ["docker", *args], capture_output=True, timeout=timeout)


def main():
    evidence = Path("temp/import-startup-isolation-20261005") / ("real-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "firstRequirement": "NOT_COMPLETE", "executionEntry": "CLOSED_TO_USERS", "fixture": "CONTROLLED_SCRIPTS_ONLY", "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    parts = urlsplit(dsn)
    harness_env = dict(os.environ, GLYAHH_HARNESS_JDBC_URL=f"jdbc:postgresql://{parts.hostname}:{parts.port or 5432}{parts.path}",
                       GLYAHH_HARNESS_DB_USER=unquote(parts.username or ""), GLYAHH_HARNESS_DB_PASSWORD=unquote(parts.password or ""))
    root = Path("temp/project_import_exec").resolve()

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    state = {"csrf": None}

    def call(path, body=None, method=None, expected=20000):
        headers = {"Origin": "http://localhost:5173"}
        if state["csrf"]: headers["X-Project-Import-CSRF"] = state["csrf"]
        if isinstance(body, bytes): data = body; headers["Content-Type"] = "application/octet-stream"
        elif body is not None: data = json.dumps(body).encode(); headers["Content-Type"] = "application/json"
        else: data = None
        try: response = opener.open(urllib.request.Request("http://127.0.0.1:8124/api" + path, data=data, headers=headers, method=method or ("GET" if body is None else "POST")), timeout=60)
        except urllib.error.HTTPError as error: response = error
        with response: result = json.loads(response.read())
        assert result["code"] == expected, f"Unexpected business code {result['code']} for {path}: {result.get('message')}"
        return result.get("data")

    def db(sql, params=()):
        with psycopg.connect(dsn) as conn:
            return conn.execute(sql, params).fetchall()

    def harness(mode, fixture_path, log_name, wait=True):
        command = ["cmd", "/c", "mvnw.cmd", "-q", "test", "-Dtest=ImportExecutionRealHarnessTest", "-Dsurefire.failIfNoSpecifiedTests=false",
                   f"-Dimport.exec.harness={fixture_path}", f"-Dimport.exec.mode={mode}"]
        log = (evidence / log_name).open("w", encoding="utf-8")
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=harness_env)
        if not wait: return process, log
        code = process.wait(timeout=1200); log.close()
        assert code == 0, f"harness {mode} exit {code}"
        return None

    def provision(app_name):
        """导入一份受控夹具并确认静态入口计划，返回 (app, workspace, protected, task_id, plan)。"""
        files = {p: c.encode() for p, c in FIXTURE.items()}
        task = call("/project/import/tasks", {"ownerId": owner, "appName": app_name, "files": [{"path": p, "size": len(c)} for p, c in files.items()],
                                              "options": {"skipDependencies": False, "skipBuildOutputs": False, "includeSensitive": True}})
        for index, content in enumerate(files.values()):
            task = call(f"/project/import/tasks/{task['taskId']}/files/{index}?revision={task['revision']}", content, method="PUT")
        committed = call(f"/project/import/tasks/{task['taskId']}/commit", {"revision": task["revision"], "contentHash": task["contentHash"]})
        imported = committed["appId"]
        imported_workspace = (Path("temp/code_output") / ("project_" + imported)).resolve()
        inspection = call(f"/project/import/apps/{imported}/startup/inspection", {"ownerId": owner, "scope": "."})
        candidate = next(c["id"] for c in inspection["result"]["candidates"] if c["directory"] == "." and c["id"].endswith("index.html"))
        plans = f"/project/import/apps/{imported}/startup/plans"
        confirmed = call(plans, {"ownerId": owner, "scanId": inspection["scanId"], "serviceIds": [candidate]})
        confirmed = call(f"{plans}/{confirmed['planId']}/confirm", {"ownerId": owner, "planRevision": confirmed["planRevision"], "aggregateAcknowledged": False})
        assert confirmed["state"] == "CONFIRMED" and confirmed["executable"] is False
        return imported, imported_workspace, Path("temp/project_import_private") / task["taskId"], task["taskId"], confirmed

    try:
        account = "isolation_" + secrets.token_hex(8); password = secrets.token_urlsafe(24)
        owner = str(call("/user/register", {"userAccount": account, "userPassword": password, "checkPassword": password}))
        call("/user/login", {"userAccount": account, "userPassword": password}); state["csrf"] = call("/project/import/csrf")
        app, workspace, protected, task_id, plan = provision("隔离执行夹具")
        defaults_app, defaults_workspace, _, _, defaults_plan = provision("隔离默认额度夹具")
        report.update(appId=app, taskId=task_id, ownerId=owner, planId=plan["planId"], planRevision=plan["planRevision"])
        before_ws, before_protected = tree(workspace), tree(protected)
        assert before_ws and before_protected and len(before_ws) == len(FIXTURE)
        report["checks"].append({"name": "[受控夹具] 导入小脚本工程并确认计划（静态入口目录 .）；计划 executable 仍为 false", "status": "PASS",
                                 "workspaceFiles": len(before_ws), "protectedFiles": len(before_protected)})

        fixture = evidence / "fixture.json"; output = evidence / "harness-results.json"
        fixture.write_text(json.dumps({"ownerId": int(owner), "appId": int(app), "planId": plan["planId"], "workspace": str(workspace), "executionRoot": str(root),
                                       "output": str(output.resolve())}), encoding="utf-8")
        defaults_fixture = evidence / "fixture-defaults.json"; defaults_output = evidence / "harness-defaults.json"
        defaults_fixture.write_text(json.dumps({"ownerId": int(owner), "appId": int(defaults_app), "planId": defaults_plan["planId"], "workspace": str(defaults_workspace),
                                                "executionRoot": str(root), "output": str(defaults_output.resolve())}), encoding="utf-8")
        started = time.time()
        harness("defaults", defaults_fixture.as_posix(), "harness-defaults.log")
        harness("cases", fixture.as_posix(), "harness-cases.log")
        results = {r["case"]: r for r in json.loads(output.read_text(encoding="utf-8"))}
        results.update({r["case"]: r for r in json.loads(defaults_output.read_text(encoding="utf-8"))})
        report["harnessSeconds"] = round(time.time() - started, 1)

        def check(name, condition, case):
            r = results[case]
            assert condition(r), f"{case}: {json.dumps(r, ensure_ascii=False)[:1500]}"
            report["checks"].append({"name": name, "status": "PASS", "case": case, "state": r["state"], "reason": r["reason"], "executionId": r["executionId"]})

        clean = lambda r: r["containersLeft"] == "" and r["filesLeft"] == "" and r["db_finished"] == "true" and r["db_cleanup"] == "DONE"
        log = lambda r: r.get("logTail", "")
        # 生产默认限额要求内存硬上限：交换区无法被限制时必须拒绝；能被限制时必须先真实钉住 swap 才允许运行。
        check("默认生产限额：内存硬上限不可保证时 REFUSED(MEMORY_LIMIT_UNENFORCEABLE) 且不建副本/容器；"
              "可保证时容器内 swap.max=0 或宿主无 swap 后才运行",
              lambda r: (r["state"] == "REFUSED" and "MEMORY_LIMIT_UNENFORCEABLE" in r["reason"]
                         and r["db_container_id"] is None and r["logBytes"] == -1 and clean(r))
              or (r["state"] == "SUCCEEDED" and r.get("exitCode") == 0
                  and ("swap.max=0" in log(r) or "swaptotal=0" in log(r)) and clean(r)), "hard-memory-default")
        check("正常脚本在独立副本 /work 以 uid 65534 运行并成功；容器删除确认、副本清理", lambda r: r["state"] == "SUCCEEDED" and r.get("exitCode") == 0
              and "ok from isolated copy" in log(r) and "/work" in log(r) and "65534" in log(r) and r["db_container_id"] and re.match(r"20\d\d-", r["db_container_started"] or "") and clean(r), "ok")
        check("非零退出 → FAILED(EXIT_NONZERO, 3)，已导入工程保留", lambda r: r["state"] == "FAILED" and r["reason"] == "EXIT_NONZERO" and r.get("exitCode") == 3 and clean(r), "exit-nonzero")
        check("篡改检查：容器只挂受限卷 /work，看不到宿主工程挂载（无 /input）；副本可写、宿主盘不可见；守卫 nonce 不进入工程环境",
              lambda r: r["state"] == "SUCCEEDED" and "INPUT_ABSENT" in log(r) and "WORK_COPY_WRITE_OK" in log(r)
              and "HOST_DRIVE_HIDDEN" in log(r) and "nonce_env=0" in log(r) and "nonce_pid1=0" in log(r) and clean(r), "tamper")
        mem = re.search(r"allocated_mb (\d+)", log(results["memory-gap"])); swap = re.search(r"swap\.current (\d+)", log(results["memory-gap"]))
        check("[已知缺口实测] 验证模式下 128MiB 内存限额未阻止 384MB 分配（溢出进交换区）——证明本机内存不是硬上限，生产模式因此拒绝", lambda r: mem and int(mem.group(1)) >= 384
              and swap and int(swap.group(1)) > 0 and clean(r), "memory-gap")
        fork = re.search(r"forked (\d+) failed (\d+) pids.max (\d+) pids.current (\d+) pids.peak (\S+)", log(results["fork"]))
        check("进程数：pids.max=32，100 次 fork 中超出部分失败，峰值不超过 32", lambda r: fork and int(fork.group(1)) < 32 and int(fork.group(2)) > 0 and fork.group(3) == "32"
              and (fork.group(5) == "NA" or int(fork.group(5)) <= 32) and clean(r), "fork")
        work = re.search(r"work_big_bytes=(\d+)", log(results["disk"])); tmp = re.search(r"tmp_big_bytes=(\d+)", log(results["disk"]))
        check("磁盘：/work 写 100MB 止于受限卷额度、/tmp 止于 8MiB，根文件系统只读", lambda r: work and int(work.group(1)) <= 32 << 20 and tmp and int(tmp.group(1)) <= 8 << 20
              and log(r).count("Read-only file system") >= 1 and clean(r), "disk")
        check("输出：无限输出在超过 256KiB 时由平台 kill 容器（退出码 137），日志恰好截在上限", lambda r: r["state"] == "FAILED" and r["reason"] == "OUTPUT_LIMIT" and r.get("exitCode") == 137 and r["logBytes"] == 256 << 10 and clean(r), "output")
        check("耗时：sleep 600 在 5 秒 wall 处强制结束 → FAILED(TIMEOUT)", lambda r: r["state"] == "FAILED" and r["reason"] == "TIMEOUT" and clean(r), "timeout")
        net = log(results["network"])
        check("网络：仅 lo 接口；外网/宿主网关/宿主名全部连接失败，DNS 失败（--network none，无防火墙改动）", lambda r: "ifaces ['lo']" in net and "CONNECTED" not in net
              and re.search(r"^dns (gaierror|[A-Za-z]+Error)", net, re.M) and len(re.findall(r"^tcp ", net, re.M)) == 5 and clean(r), "network")
        cpu = re.search(r"cpu\.max=50000 100000 wall_ms=(\d+) cpu_used_ms=(\d+) throttled=(\d+)", log(results["cpu"]))
        check("CPU：cpu.max=50000/100000；4 个忙循环 4 秒只消耗约 0.5 核", lambda r: cpu and int(cpu.group(2)) <= int(cpu.group(1)) * 0.5 * 1.3 and int(cpu.group(3)) > 0 and clean(r), "cpu")
        check("内容身份与确认计划不符 → FAILED(PLAN_CONTENT_CHANGED)，不创建容器", lambda r: r["state"] == "FAILED" and r["reason"] == "PLAN_CONTENT_CHANGED" and r["db_container_id"] is None and clean(r), "content-drift")
        check("停止：运行中容器进程在 WSL 中真实消失、容器不存在后才记 STOPPED", lambda r: r["state"] == "STOPPED" and r["liveCgroupHasContainer"] and r["pidGoneInWsl"] == "GONE"
              and r["ledgerAfterStop"] == "STOPPED" and clean(r), "stop")
        check("同一应用已有未结束执行时，新执行被拒绝（PREVIOUS_EXECUTION_UNRESOLVED）", lambda r: r["state"] == "REFUSED" and r["reason"] == "PREVIOUS_EXECUTION_UNRESOLVED" and clean(r), "concurrent-second")

        def unchanged(label):
            assert tree(workspace) == before_ws, "workspace changed"
            assert tree(protected) == before_protected, "protected snapshot changed"
            assert db("SELECT is_delete FROM biz.app WHERE id=%s", (int(app),))[0][0] == 0
            current = call(f"/project/import/apps/{app}/startup/plans/{plan['planId']}")
            assert current["state"] == "CONFIRMED" and current["executable"] is False
            report["checks"].append({"name": f"{label}：原工程与保护快照逐文件 SHA-256 不变；应用未删除；计划仍 CONFIRMED 且 executable=false", "status": "PASS"})
        unchanged("全部用例（含失败/超限/停止）后")
        rows = db("SELECT state,count(*) FROM biz.project_import_startup_execution WHERE app_id=%s GROUP BY state", (int(app),))
        report["ledgerStates"] = dict(rows)

        if "--restart" in sys.argv:
            marker = evidence / "crash-marker.json"
            crash_fixture = evidence / "fixture-crash.json"
            crash_fixture.write_text(json.dumps({**json.loads(fixture.read_text(encoding="utf-8")), "output": str(marker.resolve())}), encoding="utf-8")
            process, crash_log = harness("crash", crash_fixture.as_posix(), "harness-crash.log", wait=False)
            deadline = time.time() + 600
            while not marker.exists() and time.time() < deadline and process.poll() is None: time.sleep(1)
            assert marker.exists(), "crash marker missing"
            crash = json.loads(marker.read_text(encoding="utf-8"))
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(crash["executorPid"])], capture_output=True)
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(process.pid)], capture_output=True)
            process.wait(timeout=60); crash_log.close()
            time.sleep(2)
            jvm = subprocess.run(["tasklist", "/FI", f"PID eq {crash['executorPid']}", "/NH"], capture_output=True, text=True, errors="replace").stdout
            running = docker("inspect", "--format", "{{.State.Running}}", crash["containerId"]).stdout.decode().strip()
            row = db("SELECT state,container_id FROM biz.project_import_startup_execution WHERE execution_id=%s", (crash["executionId"],))[0]
            assert str(crash["executorPid"]) not in jvm and running == "true" and row[0] == "RUNNING" and row[1] == crash["containerId"]
            assert (root / (crash["executionId"] + ".workspace")).is_dir()
            orphan_id = str(uuid.uuid4()); orphan_name = "glyahh-imp-exec-orphan-" + secrets.token_hex(4)
            started_orphan = docker("run", "-d", "--name", orphan_name, "--label", "glyahh.import.execution=" + orphan_id, "--network", "none", "--read-only",
                                    "--user", "65534:65534", "--cap-drop", "ALL", "glyahh-sandbox-runtime:24.04", "sleep", "300")
            assert started_orphan.returncode == 0
            orphan_files = str(uuid.uuid4())
            (root / (orphan_files + ".workspace")).mkdir(); (root / (orphan_files + ".workspace") / "left.txt").write_text("[受控夹具] 残留副本", encoding="utf-8")
            (root / (orphan_files + ".env")).write_text("X=1\n", encoding="utf-8")
            report["checks"].append({"name": "[受控夹具] 强制结束执行 JVM：执行者消失后容器仍在运行、账本仍 RUNNING、副本仍在（真实残留）；另加无登记带标签容器与残留副本",
                                     "status": "PASS", "executionId": crash["executionId"], "containerId": crash["containerId"]})
            with (evidence / "restart.log").open("w", encoding="utf-8") as restart_log:
                completed = subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", "scripts/restart-backend.ps1", "-TimeoutSeconds", "180", "-LogDirectory", str(evidence / "runtime")],
                                           stdout=restart_log, stderr=subprocess.STDOUT, timeout=260, check=False)
            assert completed.returncode == 0
            # 对账在启动后后台线程中完成：等账本终态且后端日志出现对账完成行，再核对容器与文件。
            deadline = time.time() + 180
            while time.time() < deadline:
                row = db("SELECT state,reason,cleanup FROM biz.project_import_startup_execution WHERE execution_id=%s", (crash["executionId"],))[0]
                finished = b"rows=" in b"".join(p.read_bytes() for p in (evidence / "runtime").glob("*.log"))
                if row[0] != "RUNNING" and finished: break
                time.sleep(2)
            gone = docker("inspect", "--format", "{{.Id}}", crash["containerId"])
            orphan_gone = docker("inspect", "--format", "{{.Id}}", orphan_name)
            backend_log = b"".join(p.read_bytes() for p in (evidence / "runtime").glob("*.log"))
            assert tuple(row) == ("INTERRUPTED", "EXECUTOR_GONE", "DONE"), row
            assert gone.returncode != 0 and b"no such" in gone.stderr.lower() and orphan_gone.returncode != 0 and b"no such" in orphan_gone.stderr.lower()
            assert not (root / (crash["executionId"] + ".workspace")).exists() and not (root / (orphan_files + ".workspace")).exists() and not (root / (orphan_files + ".env")).exists()
            assert (root / (crash["executionId"] + ".output.log")).exists() and b"rows=" in backend_log
            report["checks"].append({"name": "真实标准重启后对账：残留执行记为 INTERRUPTED(EXECUTOR_GONE) 且清理 DONE；遗留容器与无登记容器均确认不存在；残留副本删除，输出日志保留；不自动重跑",
                                     "status": "PASS"})
            unchanged("重启对账后")
            report["ledgerStates"] = dict(db("SELECT state,count(*) FROM biz.project_import_startup_execution WHERE app_id=%s GROUP BY state", (int(app),)))
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["failure"] = (type(error).__name__ + ":" + str(error))[:2000]
        report["failureLine"] = traceback.extract_tb(error.__traceback__)[-1].lineno
        raise RuntimeError("隔离执行核对失败，未输出凭据") from None
    finally: (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report["status"], report["evidence"])


if __name__ == "__main__":
    main()
