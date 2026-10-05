"""事务内独立 schema 核对 V26 与实际执行账本 SQL；全部回滚，不改业务行，不创建容器，不运行导入工程。"""
import json
import re
import time
import uuid
from pathlib import Path

import psycopg


def main():
    evidence = Path("temp/import-startup-isolation-20261005") / ("schema-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    try:
        with psycopg.connect(dsn) as db:
            schema = "startup_execution_probe_" + uuid.uuid4().hex
            db.execute("CREATE SCHEMA " + schema)
            # 外键只读引用一条已有计划（受控夹具或历史计划），探针行只写进临时 schema。
            plan, owner, app, revision, content = db.execute("SELECT plan_id,owner_id,app_id,plan_revision,content_hash FROM biz.project_import_startup_plan ORDER BY created_at DESC LIMIT 1").fetchone()
            table = schema + ".execution"
            ddl = Path("src/main/resources/db/migration/V26__project_import_startup_execution.sql").read_text(encoding="utf-8")
            ddl = ddl.replace("biz.project_import_startup_execution_frozen", schema + ".frozen").replace("biz.project_import_startup_execution", table)
            db.execute(ddl)
            slot = Path("src/main/resources/db/migration/V28__project_import_startup_execution_slot.sql").read_text(encoding="utf-8")
            slot = slot.replace("biz.project_import_startup_execution_frozen", schema + ".frozen")
            slot = slot.replace("DROP INDEX biz.project_import_startup_execution_active_idx", "DROP INDEX " + schema + ".project_import_startup_execution_active_idx")
            slot = slot.replace("biz.project_import_startup_execution", table)
            db.execute(slot)
            source = Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportExecutionLedger.java").read_text(encoding="utf-8")
            local = lambda statement: statement.replace("biz.project_import_startup_execution", table).replace("?", "%s")
            insert, finish = (local(s) for s in re.findall(r'"""(.*?)"""', source, re.S) if "biz.project_import_startup_execution" in s)
            updates = {re.search(r"SET (\w+)", s).group(1) + ("_running" if "state='RUNNING'" in s else "_stopping" if "state='STOPPING'" in s else ""): local(s)
                       for s in re.findall(r'"(UPDATE biz\.project_import_startup_execution [^"]*)"', source)}
            assert set(updates) == {"container_id", "state_running", "state_stopping", "cleanup"}, updates

            def open_row(state="PREPARING", reason=None, slot_name="task"):
                ident = uuid.uuid4()
                db.execute(insert, (ident, owner, app, plan, revision, content, ".", "c" * 64, json.dumps({"network": "none"}), 123, "2026-10-05T00:00:00Z",
                                    "glyahh-imp-exec-" + str(ident), state, reason, "DONE" if state == "REFUSED" else "PENDING", state, slot_name))
                return ident

            def row(ident):
                return db.execute(f"SELECT state,revision,cleanup,finished_at IS NOT NULL,container_id FROM {table} WHERE execution_id=%s", (ident,)).fetchone()

            def rejected(statement, params, error):
                try:
                    with db.transaction(): db.execute(statement, params)
                except error: return True
                return False

            first = open_row()
            assert row(first) == ("PREPARING", 0, "PENDING", False, None)
            assert rejected(insert, (uuid.uuid4(), owner, app, plan, revision, content, ".", "c" * 64, "{}", 123, "2026-10-05T00:00:00Z", "n", "PREPARING", None, "PENDING", "PREPARING", "task"), psycopg.errors.UniqueViolation)
            other_slot = open_row(slot_name="service:web")
            assert row(other_slot)[0] == "PREPARING"
            refused = open_row("REFUSED", "MEMORY_LIMIT_UNENFORCEABLE", "install")
            assert row(refused)[:4] == ("REFUSED", 0, "DONE", True)
            assert rejected(insert, (uuid.uuid4(), owner, app, plan, revision, content, ".", "c" * 64, "{}", 123, "2026-10-05T00:00:00Z", "n", "REFUSED", None, "DONE", "REFUSED", "install"), psycopg.errors.CheckViolation)
            report["checks"].append({"name": "实际插入 SQL：同一槽位只允许一个未结束执行，其他槽位可以同时未结束；拒绝记录直接结束且不占用；拒绝必须有原因", "status": "PASS"})

            container = "d" * 64
            assert db.execute(updates["container_id"], (container, first, 1)).rowcount == 0
            assert db.execute(updates["container_id"], (container, first, 0)).rowcount == 1
            assert db.execute(updates["container_id"], ("e" * 64, first, 1)).rowcount == 0
            assert rejected(f"UPDATE {table} SET container_id=%s WHERE execution_id=%s", ("e" * 64, first), psycopg.errors.CheckViolation)
            assert rejected(f"UPDATE {table} SET limits_json='{{}}'::jsonb WHERE execution_id=%s", (first,), psycopg.errors.CheckViolation)
            report["checks"].append({"name": "容器登记按 revision CAS 且只能登记一次；已登记容器不能换成别的容器；容器 id 格式与身份/限额不可改写", "status": "PASS"})

            assert db.execute(updates["state_running"], ("2026-10-05T00:00:01Z", first, 1)).rowcount == 1 and row(first)[:2] == ("RUNNING", 2)
            assert db.execute(updates["state_stopping"], (first, 2)).rowcount == 1 and row(first)[:2] == ("STOPPING", 3)
            assert db.execute(updates["cleanup"], (first, 3)).rowcount == 0
            assert db.execute(finish, ("STOPPED", "STOP_REQUESTED", 137, 66, "DONE", first, 2)).rowcount == 0
            assert db.execute(finish, ("STOPPED", "STOP_REQUESTED", 137, 66, "DONE", first, 3)).rowcount == 1 and row(first)[:4] == ("STOPPED", 4, "DONE", True)
            assert db.execute(finish, ("FAILED", "X", None, 0, "DONE", first, 4)).rowcount == 0
            assert rejected(f"UPDATE {table} SET state='RUNNING',finished_at=NULL WHERE execution_id=%s", (first,), psycopg.errors.CheckViolation)
            assert rejected(f"UPDATE {table} SET cleanup='FAILED' WHERE execution_id=%s", (first,), psycopg.errors.CheckViolation)
            report["checks"].append({"name": "运行/停止意图/结束均按 revision CAS；结束行不能重开或改写结果，已清理不能改回失败", "status": "PASS"})

            second = open_row()
            assert db.execute(finish, ("STOP_FAILED", "CONTAINER_NOT_CONFIRMED_GONE", None, 0, "FAILED", second, 0)).rowcount == 1
            third = open_row()
            assert row(third)[0] == "PREPARING"
            assert db.execute(updates["cleanup"], (second, 0)).rowcount == 0 and db.execute(updates["cleanup"], (second, 1)).rowcount == 1
            assert row(second)[:3] == ("STOP_FAILED", 2, "DONE")
            assert db.execute(updates["cleanup"], (second, 2)).rowcount == 0
            assert rejected(updates["container_id"], ("not-a-container", third, 0), psycopg.errors.CheckViolation)
            assert rejected(f"UPDATE {table} SET state='FAILED',reason=NULL,finished_at=now() WHERE execution_id=%s", (third,), psycopg.errors.CheckViolation)
            report["checks"].append({"name": "停止未确认（STOP_FAILED, cleanup FAILED）只能在核对后补记 DONE 一次，状态历史不变；FAILED 必须有原因；容器 id 必须是 64 位十六进制", "status": "PASS"})
            db.rollback()
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", errorType=type(error).__name__, error=str(error)[:300])
        raise RuntimeError("执行账本SQL预检失败，见脱敏报告") from None
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
