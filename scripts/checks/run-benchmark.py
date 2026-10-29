"""运行固定任务基准：复制 seed、驱动 Agent、执行确定性评分。"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TASK_ROOT = REPO_ROOT / "benchmarks" / "tasks"
DRIVER = REPO_ROOT / "scripts" / "checks" / "agent_driver.py"


def load_tasks(selected: set[str]) -> list[dict]:
    tasks = []
    for path in sorted(TASK_ROOT.glob("*/task.json")):
        task = json.loads(path.read_text(encoding="utf-8"))
        if not selected or task["id"] in selected:
            tasks.append(task)
    missing = selected - {task["id"] for task in tasks}
    if missing:
        raise SystemExit(f"未知任务: {', '.join(sorted(missing))}")
    return tasks


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8192), b''):
            digest.update(chunk)
    return digest.hexdigest()


def changed_protected_files(task: dict, seed_root: Path, workspace: Path) -> list[str]:
    """Agent 改了测试或 vendor 文件时，评分不能算通过。"""
    changed = []
    for name in task.get('protected', []):
        source = seed_root / name
        target = workspace / name
        if not source.is_file():
            raise SystemExit(f'protected file missing in seed: {name}')
        if not target.is_file() or sha256(target) != sha256(source):
            changed.append(name)
    return changed


def run_grade(task: dict, workspace: Path) -> list[dict]:
    results = []
    for grade in task.get("grade", []):
        timeout = grade.get("timeoutSec", task.get("timeoutSec", 900))
        try:
            completed = subprocess.run(grade["cmd"], cwd=workspace, shell=True,
                                       capture_output=True, text=True, timeout=timeout)
            missing = [name for name in grade.get("files", []) if not (workspace / name).exists()]
            results.append({
                "cmd": grade["cmd"],
                "ok": completed.returncode == grade.get("expectExit", 0) and not missing,
                "exitCode": completed.returncode,
                "missingFiles": missing,
                "stderr": completed.stderr[-1000:],
            })
        except subprocess.TimeoutExpired:
            results.append({"cmd": grade["cmd"], "ok": False, "exitCode": None,
                            "missingFiles": [], "stderr": "timeout"})
    return results


def run_task(task: dict, work_root: Path, skip_agent: bool, apply_solution: bool) -> dict:
    workspace = work_root / task["id"]
    if workspace.exists():
        shutil.rmtree(workspace)
    task_root = TASK_ROOT / task["id"]
    seed = task_root / task.get("seed", "seed")
    shutil.copytree(seed, workspace)
    if apply_solution:
        solution = task_root / "solution"
        if solution.is_dir():
            shutil.copytree(solution, workspace, dirs_exist_ok=True)
    protected_changed = changed_protected_files(task, seed, workspace)

    driver_ok = None
    driver_output = work_root / f"{task['id']}-driver.json"
    if not skip_agent:
        completed = subprocess.run(
            [sys.executable, str(DRIVER), "--workspace", str(workspace),
             "--prompt", task["prompt"], "--timeout", str(task.get("timeoutSec", 900)),
             "--output", str(driver_output)],
            capture_output=True, text=True, timeout=task.get("timeoutSec", 900) + 60)
        driver_ok = completed.returncode == 0

    grades = run_grade(task, workspace)
    ok = not protected_changed and all(grade["ok"] for grade in grades) and (driver_ok is not False)
    return {
        "id": task["id"],
        "ok": ok,
        "agentExecuted": not skip_agent,
        "driverOk": driver_ok,
        "driverOutput": str(driver_output) if driver_output.exists() else None,
        "protectedChanged": protected_changed,
        "grades": grades,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", action="append", default=[])
    parser.add_argument("--skip-agent", action="store_true", help="只校验 seed 与评分，不调用模型")
    parser.add_argument("--apply-solution", action="store_true", help="复制参考修复后再评分")
    parser.add_argument("--work-root", type=Path, default=REPO_ROOT / "temp" / "benchmark")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    tasks = load_tasks(set(args.task))
    args.work_root.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    results = [run_task(task, args.work_root, args.skip_agent, args.apply_solution) for task in tasks]

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "mode": "grade-only" if args.skip_agent else "agent",
        "solutionApplied": args.apply_solution,
        "tasks": results,
        "ok": all(item["ok"] for item in results),
        "elapsedMs": int((time.monotonic() - started) * 1000),
    }
    output = args.output or (
        REPO_ROOT / "docs" / "reports" / "evidence"
        / f"{datetime.now():%Y%m%d}-p5-benchmark" / f"benchmark-{datetime.now():%Y%m%d-%H%M%S}.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"ok": report["ok"], "mode": report["mode"],
                      "tasks": [{"id": item["id"], "ok": item["ok"]} for item in results]},
                     ensure_ascii=False))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
