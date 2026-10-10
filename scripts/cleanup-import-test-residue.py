"""清理导入验收在测试账号下留下的本机数据：受限卷、持久副本、工作区、暂存目录与应用记录。

背景：验收脚本用随机账号（前缀见 PREFIXES）注册并导入受控夹具，跑完只保证容器与账本终态，
应用记录、冻结副本、受限卷、工作区与上传暂存会留在本机。本脚本按账号前缀识别这些残留并清理：
- 受限卷经官方 helper release（umount + 删镜像），不走直接 rm；
- 副本、工作区、暂存目录逐路径校验后删除，遇到符号链接停止；
- 该应用有容器或未结束执行时整条跳过，不强行清理；
- 全部动作写入证据目录；默认 dry-run，执行必须指定本轮 --fixture-report。

不做的事：不删输出日志（执行审计保留）、不碰非测试账号的应用、不删 execution/task 台账行。
账号前缀仅用于预览，不能作为删除授权；执行时按证据里的 ID 和账号共同核对。
"""

import argparse
import json
import pathlib
import re
import shutil
import stat
import subprocess
import sys
import time

import psycopg

REPO = pathlib.Path(__file__).resolve().parent.parent
WSL = ["wsl.exe", "-d", "Ubuntu", "--exec"]

# 验收脚本注册账号用的固定前缀（scripts/*.py 的 account = "<prefix>" + token_hex）。
PREFIXES = (
    "approval_", "chain_", "closure_", "frames_", "gap_", "import_auth_", "import_cred_",
    "import_recovery_", "import_source_", "import_test_", "isolation_", "life_", "model_",
    "plan_", "probe_", "publish_", "remain_", "repair_", "startup_",
)

EXEC_ROOT = REPO / "temp" / "project_import_exec"
APPS_ROOT = EXEC_ROOT / "apps"
CODE_OUTPUT = REPO / "temp" / "code_output"
IMPORT_PRIVATE = REPO / "temp" / "project_import_private"
STARTUP_PRIVATE = REPO / "temp" / "project_startup_private"
VOLUME_HELPER = REPO / "scripts" / "import_disk_volume.py"


def wsl_path(path: pathlib.Path) -> str:
    """Windows 路径转 WSL 挂载路径；本仓库在 D: 盘。"""
    drive = path.drive.rstrip(":").lower()
    rest = str(path)[len(path.drive):].replace("\\", "/")
    return f"/mnt/{drive}{rest}"


def docker_containers(app_id: int) -> str:
    result = subprocess.run(WSL + ["docker", "ps", "-aq", "--filter", f"label=glyahh.import.app={app_id}"],
                            capture_output=True, timeout=60)
    if result.returncode != 0:
        raise RuntimeError("DOCKER_INVENTORY_UNCONFIRMED")
    return result.stdout.decode(errors="replace").strip()


def source_resources_stopped(resources):
    if not isinstance(resources, list):
        return False
    if not resources:
        return True
    if any(not isinstance(r, dict) or not str(r.get('pid', '')).isdigit() or not r.get('started') for r in resources):
        return False
    # 逐个核对冻结PID及启动时刻；复用的PID不属于本轮来源，不终止任何进程。
    script = "$rows=@($input | Out-String | ConvertFrom-Json); foreach($r in $rows){" \
             "$p=Get-Process -Id ([int]$r.pid) -ErrorAction SilentlyContinue; if($p){" \
             "$s=[DateTimeOffset]::Parse($r.started); if([Math]::Abs(($p.StartTime.ToUniversalTime()-$s.UtcDateTime).TotalMilliseconds)-le 1){exit 1}}}; exit 0"
    result = subprocess.run(['powershell', '-NoProfile', '-Command', script], input=json.dumps(resources).encode(),
                            capture_output=True, timeout=30)
    return result.returncode == 0


def release_volume(app_id: int, record: dict) -> str:
    """调用官方 helper 释放受限卷；helper 自己会拒绝仍有容器的应用。"""
    # -u root 必须放在 --exec 之前：--exec 之后的所有参数都会原样当命令执行。
    args = ["wsl.exe", "-d", "Ubuntu", "-u", "root", "--exec",
            "python3", wsl_path(VOLUME_HELPER), "release",
            "--app-id", str(app_id),
            "--content-hash", record["contentHash"],
            "--bytes", str(record["diskBytes"])]
    result = subprocess.run(args, capture_output=True, timeout=300)
    if result.returncode != 0:
        return "RELEASE_FAILED:" + result.stderr.decode(errors="replace")[:160]
    return "RELEASED"


def guard_no_links(path: pathlib.Path) -> None:
    """删除前检查：目录树里出现链接或特殊节点就停止（对齐 Java 侧 deleteBounded 的语义）。"""
    if path.is_symlink() or path.is_junction():
        raise OSError("路径是链接，停止删除：" + str(path))
    if not path.exists():
        return
    if path.is_file():
        return
    for child in path.rglob("*"):
        if child.is_symlink() or child.is_junction():
            raise OSError("目录树含链接，停止删除：" + str(child))


def delete_tree(path: pathlib.Path) -> int:
    """删除目录或文件，返回释放的字节数（估算）。"""
    if any(parent.is_symlink() or parent.is_junction() for parent in (path, *path.parents)):
        raise OSError("DELETE_LINK_ANCESTOR")
    resolved = path.resolve()
    if not any(resolved != root.resolve() and resolved.is_relative_to(root.resolve())
               for root in (APPS_ROOT, CODE_OUTPUT, IMPORT_PRIVATE, STARTUP_PRIVATE)):
        raise OSError("DELETE_OUTSIDE_FIXTURE_ROOT")
    if not path.exists() and not path.is_symlink():
        return 0
    guard_no_links(path)
    size = directory_bytes(path)
    # Git对象在Windows可能只读；仅清除已核验本轮目录内节点的只读属性。
    for node in ([path, *path.rglob('*')] if path.is_dir() else [path]):
        guard_no_links(node)
        node.chmod(node.stat().st_mode | stat.S_IWRITE)
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink()
    return size


def directory_bytes(path: pathlib.Path) -> int:
    if path.is_file():
        return path.stat().st_size
    total = 0
    for child in path.rglob("*"):
        if child.is_file() and not child.is_symlink():
            total += child.stat().st_size
    return total


def fixture_owners(reports):
    owners = {}
    for report in reports:
        data = json.loads(pathlib.Path(report).read_text(encoding="utf-8"))
        fixtures = data.get("fixtures")
        if not isinstance(fixtures, list) or not fixtures:
            raise ValueError("FIXTURE_REPORT_REQUIRED")
        found = False
        for fixture in fixtures:
            if not isinstance(fixture, dict):
                raise ValueError("FIXTURE_IDENTITY_INVALID")
            if "ownerId" not in fixture and "account" not in fixture:
                continue
            owner, account = str(fixture.get("ownerId", "")), fixture.get("account")
            if not re.fullmatch(r"[1-9][0-9]*", owner) or not isinstance(account, str) or not account.startswith(PREFIXES):
                raise ValueError("FIXTURE_IDENTITY_INVALID")
            if int(owner) in owners and owners[int(owner)] != account:
                raise ValueError("FIXTURE_IDENTITY_CONFLICT")
            owners[int(owner)] = account
            found = True
        if not found:
            raise ValueError("FIXTURE_REPORT_REQUIRED")
    return owners


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--fixture-report", action="append", default=[])
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.execute and not args.fixture_report:
        parser.error("--execute requires explicit --fixture-report; prefix alone is not deletion authority")
    selected = fixture_owners(args.fixture_report)
    execute = args.execute
    evidence = pathlib.Path(args.output).resolve() if args.output else REPO / "temp" / ("cleanup-import-test-residue-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "mode": "EXECUTE" if execute else "DRY_RUN", "evidence": str(evidence),
              "apps": [], "skipped": [], "dockerRemoved": [], "freedBytes": 0}

    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'")
               for line in (REPO / ".env").read_text(encoding="utf-8-sig").splitlines()
               if line.startswith("AGENT_POSTGRES_DSN="))

    try:
        with psycopg.connect(dsn) as conn:
            pattern = "^(" + "|".join(re.escape(p) for p in PREFIXES) + ")"
            if selected:
                rows = conn.execute("SELECT id,user_account FROM biz.user WHERE id = ANY(%s)", (list(selected),)).fetchall()
                if len(rows) != len(selected) or any(selected[row[0]] != row[1] for row in rows):
                    raise RuntimeError("FIXTURE_OWNER_CHANGED")
                test_users = list(selected)
            else:
                test_users = [row[0] for row in conn.execute(
                    "SELECT id FROM biz.user WHERE user_account ~ %s", (pattern,)).fetchall()]
            report["testUsers"] = len(test_users)
            apps = conn.execute(
                "SELECT id, app_name FROM biz.app WHERE user_id = ANY(%s) ORDER BY id",
                (test_users,)).fetchall()
            tasks = conn.execute(
                "SELECT task_id,status FROM biz.project_import_task WHERE owner_id = ANY(%s)",
                (test_users,)).fetchall()
            sources = conn.execute("SELECT task_id,status,cleanup_state,resources_json FROM biz.project_import_source WHERE owner_id = ANY(%s)",
                                   (test_users,)).fetchall()
            terminal = {"SUCCEEDED", "CANCELLED", "FAILED", "EXPIRED"}
            unsafe_tasks = {row[0] for row in tasks if row[1] not in terminal}
            safe_sources = {row[0] for row in sources if row[1] in terminal and source_resources_stopped(row[3])}
            unsafe_tasks.update(row[0] for row in sources if row[0] not in safe_sources)
            executions = conn.execute(
                "SELECT execution_id, app_id, state, cleanup FROM biz.project_import_startup_execution "
                "WHERE app_id = ANY(%s)", ([row[0] for row in apps],)).fetchall()
            report["testApps"] = len(apps)
            report["testTasks"] = len(tasks)
            busy = {row[1] for row in executions if row[2] == "RUNNING" or row[3] != "DONE"}
            executions_by_app: dict[int, list[str]] = {}
            for execution_id, app_id, _state, _cleanup in executions:
                executions_by_app.setdefault(app_id, []).append(str(execution_id))

            for app_id, app_name in apps:
                entry = {"appId": app_id, "name": app_name, "freedBytes": 0, "actions": []}
                if app_id in busy:
                    report["skipped"].append({**entry, "reason": "EXECUTION_UNFINISHED"})
                    continue
                live = docker_containers(app_id)
                if live:
                    report["skipped"].append({**entry, "reason": "CONTAINERS_REMAIN", "containers": live[:120]})
                    continue

                volume_file = APPS_ROOT / f"{app_id}.volume.json"
                if volume_file.is_file():
                    record = json.loads(volume_file.read_text(encoding="utf-8"))
                    if execute:
                        state = release_volume(app_id, record)
                    else:
                        state = "WOULD_RELEASE"
                    entry["actions"].append({"volume": state})
                    if state.startswith("RELEASE_FAILED"):
                        report["skipped"].append({**entry, "reason": state})
                        continue

                targets = [
                    APPS_ROOT / str(app_id),
                    APPS_ROOT / f"{app_id}.source",
                    APPS_ROOT / f"{app_id}.volume.json",
                    APPS_ROOT / f"{app_id}.manifest.json",
                    CODE_OUTPUT / f"project_{app_id}",
                ]
                for execution_id in executions_by_app.get(app_id, []):
                    targets.append(STARTUP_PRIVATE / execution_id)
                for target in targets:
                    if execute:
                        freed = delete_tree(target)
                    else:
                        freed = directory_bytes(target) if target.exists() else 0
                    if freed:
                        entry["freedBytes"] += freed
                        entry["actions"].append({"path": str(target.relative_to(REPO)), "freed": freed})
                if execute:
                    conn.execute("UPDATE biz.app SET is_delete=1 WHERE id = %s", (app_id,))
                report["apps"].append(entry)

            for task_id in {row[0] for row in tasks} | {row[0] for row in sources}:
                if task_id in unsafe_tasks:
                    report["skipped"].append({"taskId": str(task_id), "reason": "TASK_OR_SOURCE_UNFINISHED"})
                    continue
                target = IMPORT_PRIVATE / str(task_id)
                freed = delete_tree(target) if execute else (directory_bytes(target) if target.exists() else 0)
                if freed:
                    report["freedBytes"] += freed
                git_target = IMPORT_PRIVATE / (str(task_id) + ".git")
                report["freedBytes"] += delete_tree(git_target) if execute else (directory_bytes(git_target) if git_target.exists() else 0)
                if execute and task_id in safe_sources:
                    conn.execute("UPDATE biz.project_import_source SET cleanup_state='DONE',revision=revision+1,update_time=now() WHERE task_id=%s AND status IN ('SUCCEEDED','CANCELLED','FAILED','EXPIRED')", (task_id,))

            if execute and not report['skipped']:
                for owner, account in selected.items():
                    conn.execute('UPDATE biz."user" SET is_delete=1 WHERE id=%s AND user_account=%s', (owner, account))

            report["freedBytes"] += sum(row["freedBytes"] for row in report["apps"])

        # 对话沙箱容器：只删已退出的 glyahh-sbx-*，运行中的留给会话/活跃任务自己收尾。
        # 精确清理不能按名称删除其他轮次的对话容器。
        stale = []
        if not selected:
            listed = subprocess.run(WSL + ["docker", "ps", "-aq", "--filter", "name=glyahh-sbx-",
                                           "--filter", "status=exited"], capture_output=True, timeout=60)
            if listed.returncode != 0:
                raise RuntimeError("DOCKER_INVENTORY_UNCONFIRMED")
            stale = [line for line in listed.stdout.decode().splitlines() if line.strip()]
        report["staleSandboxContainers"] = len(stale)
        if execute and stale:
            removed = subprocess.run(WSL + ["docker", "rm", *stale], capture_output=True, timeout=300)
            report["dockerRemoved"] = [line for line in removed.stdout.decode().splitlines() if line.strip()]

        report["status"] = "NOT_COMPLETE" if report["skipped"] else "DONE"
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = (type(error).__name__ + ": " + str(error))[:400]
    finally:
        (evidence / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("mode:", report["mode"], "| status:", report["status"])
    print("test users:", report.get("testUsers"), "| test apps:", report.get("testApps"),
          "| tasks:", report.get("testTasks"), "| skipped:", len(report.get("skipped", [])))
    print("freed bytes:", report.get("freedBytes", 0), f"({report.get('freedBytes', 0) / 1e9:.2f} GB)")
    print("stale sandbox containers:", report.get("staleSandboxContainers"))
    if report.get("error"):
        print("error:", report["error"])
    print("evidence:", evidence)
    return 0 if report["status"] == "DONE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
