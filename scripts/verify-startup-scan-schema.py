"""事务内独立 schema 预检 V21/V22 和仓库实际 SQL；全部回滚，不执行工程。"""
import json
import argparse
import re
import uuid
from pathlib import Path
from datetime import datetime, timedelta, timezone
import psycopg


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, default=Path("temp/import-startup-scan-20261004"))
    folder = parser.parse_args().evidence_dir
    folder.mkdir(parents=True, exist_ok=True)
    report = {"status": "RUNNING", "checks": [], "migrationApplied": False, "workflow": "NOT_RUN"}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    try:
        with psycopg.connect(dsn) as db:
            schema = "startup_scan_probe_" + uuid.uuid4().hex
            table = schema + ".scan_probe"
            db.execute("CREATE SCHEMA " + schema)
            ddl = Path("src/main/resources/db/migration/V21__project_import_startup_scan.sql").read_text(encoding="utf-8").replace("biz.project_import_startup_scan", table)
            db.execute(ddl)
            cancellation = Path("src/main/resources/db/migration/V22__project_import_scan_cancel_intent.sql").read_text(encoding="utf-8").replace("biz.project_import_startup_scan", table)
            db.execute(cancellation)
            app, owner, task, revision, epoch = db.execute("SELECT app_id,owner_id,import_task_id,config_revision,context_epoch FROM biz.project_import_startup_configuration ORDER BY app_id DESC LIMIT 1").fetchone()
            source = Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupScanRepository.java").read_text(encoding="utf-8")
            statements = [sql.replace("biz.project_import_startup_scan", table).replace("project_import_startup_scan.", "scan_probe.").replace("?", "%s") for sql in re.findall(r'"""(.*?)"""', source, re.S)]
            insert = next(sql for sql in statements if "INSERT INTO" in sql)
            transition = next(sql for sql in statements if "SET status=" in sql)
            resource = next(sql for sql in statements if "SET resources_json=" in sql)
            qualified = next(sql for sql in statements if "SELECT count(*)" in sql)
            scan, request, executor = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
            now = datetime.now(timezone.utc)
            values = (scan, request, app, owner, task, revision, epoch, ".", "a" * 64, executor, 123, now, now + timedelta(minutes=2), app, owner, task, revision, epoch)
            assert db.execute(insert, values).rowcount == 1
            other = list(values); other[0] = uuid.uuid4()
            rejected = False
            try:
                with db.transaction(): db.execute(insert, other)
            except psycopg.errors.UniqueViolation: rejected = True
            assert rejected
            report["checks"].append({"name": "冻结身份插入与同owner请求唯一约束", "status": "PASS"})
            assert db.execute(transition, ("SCANNING", None, "PENDING", None, False, scan, owner, app, task, 0, executor, "QUEUED", "SCANNING")).rowcount == 1
            assert db.execute(qualified, (scan, owner, app, 1, executor)).fetchone()[0] == 1
            assert db.execute(qualified, (scan, owner + 1, app, 1, executor)).fetchone()[0] == 0
            assert db.execute(qualified, (scan, owner, app, 0, executor)).fetchone()[0] == 0
            report["checks"].append({"name": "实际租约SQL核对owner、revision与当前配置资格", "status": "PASS"})
            journal = json.dumps([{"pid": 222, "started": now.isoformat()}])
            assert db.execute(resource, (journal, scan, owner, 1, executor)).rowcount == 1
            db.execute(f"UPDATE {table} SET context_epoch=context_epoch+1 WHERE scan_id=%s", (scan,))
            assert db.execute(transition, ("SUCCEEDED", "b" * 64, "DONE", None, False, scan, owner, app, task, 1, executor, "SCANNING", "SUCCEEDED")).rowcount == 0
            db.execute(f"UPDATE {table} SET context_epoch=context_epoch-1 WHERE scan_id=%s", (scan,))
            report["checks"].append({"name": "提交成功时再次用实际SQL核对当前上下文，不依赖先前资格查询", "status": "PASS"})
            assert db.execute(transition, ("CANCELLING", None, "PENDING", None, True, scan, owner, app, task, 1, executor, "SCANNING", "CANCELLING")).rowcount == 1
            assert db.execute(resource, (journal, scan, owner, 1, executor)).rowcount == 0
            assert db.execute(transition, ("SUCCEEDED", "b" * 64, "DONE", None, False, scan, owner, app, task, 1, executor, "SCANNING", "SUCCEEDED")).rowcount == 0
            assert db.execute(qualified, (scan, owner, app, 1, executor)).fetchone()[0] == 0
            report["checks"].append({"name": "取消后旧租约不能追加资源、返回成功或通过资格", "status": "PASS"})
            rejected = False
            try:
                with db.transaction(): db.execute(f"UPDATE {table} SET status='CANCELLED' WHERE scan_id=%s", (scan,))
            except psycopg.errors.CheckViolation: rejected = True
            assert rejected
            assert db.execute(transition, ("CANCELLED", None, "DONE", None, False, scan, owner, app, task, 2, executor, "CANCELLING", "CANCELLED")).rowcount == 1
            assert db.execute(f"SELECT cancel_requested FROM {table} WHERE scan_id=%s", (scan,)).fetchone()[0]
            assert db.execute(f"SELECT status,revision,cleanup FROM {table} WHERE scan_id=%s", (scan,)).fetchone() == ("CANCELLED", 3, "DONE")
            report["checks"].append({"name": "数据库拒绝无清理确认的终态，完成取消版本正确", "status": "PASS"})
            db.rollback()
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["errorType"] = type(error).__name__
        report["sqlState"] = getattr(error, "sqlstate", None)
        raise RuntimeError("扫描数据库预检失败，未输出连接信息") from None
    finally:
        (folder / "schema-preflight.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__": main()
