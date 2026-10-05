"""事务内独立 schema 核对 V23 与实际仓库 SQL；不批准模型任务，不改生产业务行。"""
import json
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import psycopg


def main():
    evidence = Path("temp/import-startup-approval-20261004") / ("schema-"+str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "migrationApplied": False, "workflow": "NOT_RUN", "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    try:
        with psycopg.connect(dsn) as db:
            schema = "startup_approval_probe_"+uuid.uuid4().hex
            db.execute("CREATE SCHEMA "+schema)
            scan, app, owner, task, content, revision, epoch, rules = db.execute("SELECT scan_id,app_id,owner_id,import_task_id,content_hash,config_revision,context_epoch,rules_version FROM biz.project_import_startup_inspection ORDER BY created_at DESC LIMIT 1").fetchone()
            rules = re.search(r'RULES_VERSION="([^"]+)"', Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupInspectionRepository.java").read_text(encoding="utf-8")).group(1)
            # 显式受控夹具，不把历史过期证据修改成当前成功；原表全部只读。
            tables = {"project_import_startup_inspection": "inspection", "project_import_startup_configuration": "configuration", "project_import_publication": "publication", "app": "app"}
            for original, target in tables.items():
                db.execute(f"CREATE TABLE {schema}.{target} AS SELECT * FROM biz.{original} WHERE "+("id=%s" if original == "app" else "app_id=%s"), (app,))
            db.execute(f"UPDATE {schema}.inspection SET state='SUCCEEDED',rules_version=%s WHERE scan_id=%s", (rules, scan))
            db.execute(f"UPDATE {schema}.configuration SET config_revision=%s,context_epoch=%s", (revision, epoch))
            table = schema+".approval"
            ddl = Path("src/main/resources/db/migration/V23__project_import_startup_approval.sql").read_text(encoding="utf-8").replace("biz.project_import_startup_approval", table)
            db.execute(ddl)
            source = Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupApprovalRepository.java").read_text(encoding="utf-8")
            sql = re.findall(r'"""(.*?)"""', source, re.S)
            for original, target in {"project_import_startup_approval": "approval", **tables}.items():
                sql = [statement.replace("biz."+original, schema+"."+target) for statement in sql]
            insert, transition = (statement.replace("?", "%s") for statement in sql)
            approval, request = uuid.uuid4(), uuid.uuid4()
            values = (approval, request, owner, app, scan, task, content, revision, epoch, rules, '["backend"]', "a"*64, "APPROVE", "APPROVED", datetime.now(timezone.utc)+timedelta(minutes=3), scan, owner, app, task, content, revision, epoch, rules)
            assert db.execute(insert, values).rowcount == 1
            rejected = False
            try:
                with db.transaction(): db.execute(insert, (uuid.uuid4(), *values[1:]))
            except psycopg.errors.UniqueViolation: rejected = True
            assert rejected
            report["checks"].append({"name": "实际插入及owner/request唯一约束", "status": "PASS"})
            other = list(values); other[0] = uuid.uuid4(); other[1] = uuid.uuid4(); other[2] = owner+1; other[16] = owner+1
            assert db.execute(insert, other).rowcount == 0
            report["checks"].append({"name": "另一owner不能借原扫描建立批准", "status": "PASS"})
            def move(next_state, revision=0, previous="APPROVED", result=None, cancelled=False):
                return db.execute(transition, (next_state, result, None, cancelled, approval, owner, app, scan, revision, previous, next_state, rules)).rowcount
            assert move("RUNNING") == 1
            db.execute(f"UPDATE {schema}.configuration SET context_epoch=context_epoch+1")
            assert move("SUCCEEDED", 1, "RUNNING", "{}") == 0
            db.execute(f"UPDATE {schema}.configuration SET context_epoch=context_epoch-1")
            db.execute(f"UPDATE {schema}.inspection SET state='STALE' WHERE scan_id=%s", (scan,))
            assert move("SUCCEEDED", 1, "RUNNING", "{}") == 0
            db.execute(f"UPDATE {schema}.inspection SET state='SUCCEEDED' WHERE scan_id=%s", (scan,))
            report["checks"].append({"name": "发布成功实际SQL再次核对epoch与扫描资格", "status": "PASS"})
            db.execute(f"UPDATE {table} SET expires_at=now()-interval '1 second' WHERE approval_id=%s", (approval,))
            assert move("SUCCEEDED", 1, "RUNNING", "{}") == 0
            db.execute(f"UPDATE {table} SET expires_at=now()+interval '3 minutes' WHERE approval_id=%s", (approval,))
            report["checks"].append({"name": "过期批准不能提交模型成功", "status": "PASS"})
            assert move("CANCELLING", 1, "RUNNING", cancelled=True) == 1
            assert move("SUCCEEDED", 1, "RUNNING", "{}") == 0
            assert move("CANCELLED", 2, "CANCELLING", cancelled=True) == 1
            state = db.execute(f"SELECT state,cancel_requested,result_json FROM {table} WHERE approval_id=%s", (approval,)).fetchone()
            assert state == ("CANCELLED", True, None)
            rejected = False
            try:
                with db.transaction(): db.execute(f"UPDATE {table} SET state='FAILED' WHERE approval_id=%s", (approval,))
            except psycopg.errors.CheckViolation: rejected = True
            assert rejected
            report["checks"].append({"name": "取消保留意图、旧租约不串写且无成功候选", "status": "PASS"})
            db.rollback()
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", errorType=type(error).__name__)
        raise RuntimeError("批准SQL预检失败，见脱敏报告") from None
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
