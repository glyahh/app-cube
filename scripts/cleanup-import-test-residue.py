"""清理导入验收在测试账号下留下的本机数据：受限卷、持久副本、工作区、暂存目录与应用记录。

背景：验收脚本用随机账号（前缀见 PREFIXES）注册并导入受控夹具，跑完只保证容器与账本终态，
应用记录、冻结副本、受限卷、工作区与上传暂存会留在本机。本脚本按账号前缀识别这些残留并清理：
- 受限卷经官方 helper release（umount + 删镜像），不走直接 rm；
- 副本、工作区、暂存目录逐路径校验后删除，遇到符号链接停止；
- 该应用有容器或未结束执行时整条跳过，不强行清理；
- 全部动作写入证据目录；默认 dry-run，--execute 才真正删除。

不做的事：不删输出日志（执行审计保留）、不碰非测试账号的应用、不删 execution/task 台账行。
不改用户真实数据；如未来前缀变动，先跑 dry-run 核对清单再 --execute。
"""

import json
import pathlib
import re
import shutil
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
    return result.stdout.decode(errors="replace").strip()


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
    if path.is_symlink():
        raise OSError("路径是链接，停止删除：" + str(path))
    if not path.exists():
        return
    if path.is_file():
        return
    for child in path.rglob("*"):
        if child.is_symlink():
            raise OSError("目录树含链接，停止删除：" + str(child))


def delete_tree(path: pathlib.Path) -> int:
    """删除目录或文件，返回释放的字节数（估算）。"""
    if not path.exists() and not path.is_symlink():
        return 0
    guard_no_links(path)
    size = directory_bytes(path)
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


def main() -> int:
    execute = "--execute" in sys.argv
    evidence = REPO / "temp" / ("cleanup-import-test-residue-" + str(time.time_ns()))
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "mode": "EXECUTE" if execute else "DRY_RUN", "evidence": str(evidence),
              "apps": [], "skipped": [], "dockerRemoved": [], "freedBytes": 0}

    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'")
               for line in (REPO / ".env").read_text(encoding="utf-8-sig").splitlines()
               if line.startswith("AGENT_POSTGRES_DSN="))

    try:
        with psycopg.connect(dsn) as conn:
            pattern = "^(" + "|".join(re.escape(p) for p in PREFIXES) + ")"
            test_users = [row[0] for row in conn.execute(
                "SELECT id FROM biz.user WHERE user_account ~ %s", (pattern,)).fetchall()]
            report["testUsers"] = len(test_users)
            apps = conn.execute(
                "SELECT id, app_name FROM biz.app WHERE user_id = ANY(%s) ORDER BY id",
                (test_users,)).fetchall()
            tasks = conn.execute(
                "SELECT task_id FROM biz.project_import_task WHERE owner_id = ANY(%s)",
                (test_users,)).fetchall()
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

            for (task_id,) in tasks:
                target = IMPORT_PRIVATE / str(task_id)
                freed = delete_tree(target) if execute else (directory_bytes(target) if target.exists() else 0)
                if freed:
                    report["freedBytes"] += freed

            report["freedBytes"] += sum(row["freedBytes"] for row in report["apps"])

        # 对话沙箱容器：只删已退出的 glyahh-sbx-*，运行中的留给会话/活跃任务自己收尾。
        listed = subprocess.run(WSL + ["docker", "ps", "-aq", "--filter", "name=glyahh-sbx-",
                                       "--filter", "status=exited"], capture_output=True, timeout=60)
        stale = [line for line in listed.stdout.decode().splitlines() if line.strip()]
        report["staleSandboxContainers"] = len(stale)
        if execute and stale:
            removed = subprocess.run(WSL + ["docker", "rm", *stale], capture_output=True, timeout=300)
            report["dockerRemoved"] = [line for line in removed.stdout.decode().splitlines() if line.strip()]

        report["status"] = "DONE"
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
