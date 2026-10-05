"""事务内独立 schema 核对 V25 与实际计划仓库 SQL；全部回滚，不改生产业务行，不安装、构建或运行导入工程。"""
import json
import re
import time
import uuid
from pathlib import Path

import psycopg


def main():
    evidence = Path("temp/import-startup-freeze-plan-20261005") / ("schema-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "migrationApplied": False, "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    try:
        with psycopg.connect(dsn) as db:
            schema = "startup_plan_probe_" + uuid.uuid4().hex
            db.execute("CREATE SCHEMA " + schema)
            scan, app, owner, task, content, revision, epoch = db.execute("SELECT scan_id,app_id,owner_id,import_task_id,content_hash,config_revision,context_epoch FROM biz.project_import_startup_inspection ORDER BY created_at DESC LIMIT 1").fetchone()
            rules = re.search(r'RULES_VERSION="([^"]+)"', Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupInspectionRepository.java").read_text(encoding="utf-8")).group(1)
            # 显式受控夹具：复制到探针 schema 后改成当前资格，原表全部只读。
            tables = {"project_import_startup_inspection": "inspection", "project_import_startup_configuration": "configuration", "project_import_publication": "publication", "app": "app"}
            for original, target in tables.items():
                db.execute(f"CREATE TABLE {schema}.{target} AS SELECT * FROM biz.{original} WHERE " + ("id=%s" if original == "app" else "app_id=%s"), (app,))
            db.execute(f"UPDATE {schema}.inspection SET state='SUCCEEDED',rules_version=%s WHERE scan_id=%s", (rules, scan))
            db.execute(f"UPDATE {schema}.configuration SET config_revision=%s,context_epoch=%s", (revision, epoch))
            db.execute(f"UPDATE {schema}.publication SET state='READY'")
            db.execute(f"UPDATE {schema}.app SET is_delete=0,user_id=%s", (owner,))
            table = schema + ".plan"
            ddl = Path("src/main/resources/db/migration/V25__project_import_startup_plan.sql").read_text(encoding="utf-8").replace("biz.project_import_startup_plan", table)
            db.execute(ddl)
            source = Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupPlanRepository.java").read_text(encoding="utf-8")
            blocks = re.findall(r'"""(.*?)"""', source, re.S)
            lines = re.findall(r'"(UPDATE biz\.project_import_startup_plan [^"]*)"', source)
            assert len(blocks) == 2 and len(lines) == 2

            def local(statement):
                for original, target in {"project_import_startup_plan": "plan", **tables}.items():
                    statement = statement.replace("biz." + original, schema + "." + target)
                return statement.replace("?", "%s")
            insert, confirm = (local(statement) for statement in blocks)
            stale, supersede = (local(statement) for statement in sorted(lines, key=lambda s: "SUPERSEDED" in s))

            def create(plan_revision, plan_owner=owner, body=None):
                plan = uuid.uuid4()
                count = db.execute(insert, (plan, plan_owner, app, scan, task, content, revision, epoch, rules, plan_revision, json.dumps(body or {"schema": 1}),
                                            scan, plan_owner, app, task, content, revision, epoch, rules)).rowcount
                return plan, count

            def row(plan):
                return db.execute(f"SELECT state,revision,aggregate_acknowledged,confirmed_at IS NOT NULL FROM {table} WHERE plan_id=%s", (plan,)).fetchone()

            first, count = create("a" * 64)
            assert count == 1 and row(first) == ("DRAFT", 0, False, False)
            assert create("a" * 64)[1] == 0
            assert create("b" * 64, owner + 1)[1] == 0
            report["checks"].append({"name": "实际插入、同内容有效计划唯一（ON CONFLICT 不重复）、另一 owner 不能借原识别建计划", "status": "PASS"})

            db.execute(f"UPDATE {schema}.configuration SET context_epoch=context_epoch+1")
            assert create("c" * 64)[1] == 0
            db.execute(f"UPDATE {schema}.configuration SET context_epoch=context_epoch-1")
            db.execute(f"UPDATE {schema}.inspection SET state='STALE' WHERE scan_id=%s", (scan,))
            assert create("c" * 64)[1] == 0
            db.execute(f"UPDATE {schema}.inspection SET state='SUCCEEDED' WHERE scan_id=%s", (scan,))
            report["checks"].append({"name": "上下文代次变化或识别 STALE 时实际插入 SQL 拒绝建计划", "status": "PASS"})

            def approve(plan, current_revision, plan_revision, acknowledged=False):
                db.execute(supersede, (app, plan))
                return db.execute(confirm, (acknowledged, plan, owner, app, current_revision, plan_revision, rules)).rowcount
            assert approve(first, 1, "a" * 64) == 0
            assert approve(first, 0, "f" * 64) == 0
            db.execute(f"UPDATE {schema}.publication SET state='COPYING'")
            assert approve(first, 0, "a" * 64) == 0
            db.execute(f"UPDATE {schema}.publication SET state='READY'")
            assert approve(first, 0, "a" * 64, True) == 1 and row(first) == ("CONFIRMED", 1, True, True)
            report["checks"].append({"name": "确认 SQL 核对计划 revision/planRevision 与发布资格；通过后记录聚合确认", "status": "PASS"})

            second, _ = create("d" * 64)
            assert approve(second, 0, "d" * 64) == 1
            assert row(first)[0] == "SUPERSEDED" and row(second)[0] == "CONFIRMED"
            rejected = False
            try:
                with db.transaction(): db.execute(f"UPDATE {table} SET state='CONFIRMED',confirmed_at=now() WHERE plan_id=%s", (create("e" * 64)[0],))
            except psycopg.errors.UniqueViolation: rejected = True
            assert rejected
            report["checks"].append({"name": "新确认替代旧确认；每个应用最多一份已确认计划（唯一索引）", "status": "PASS"})

            for statement in (f"UPDATE {table} SET plan_json='{{\"schema\":2}}' WHERE plan_id=%s", f"UPDATE {table} SET plan_revision=repeat('9',64) WHERE plan_id=%s",
                              f"UPDATE {table} SET content_hash=repeat('9',64) WHERE plan_id=%s", f"UPDATE {table} SET state='DRAFT',confirmed_at=NULL WHERE plan_id=%s"):
                rejected = False
                try:
                    with db.transaction(): db.execute(statement, (second,))
                except psycopg.errors.CheckViolation: rejected = True
                assert rejected, statement
            report["checks"].append({"name": "触发器冻结计划内容与身份；已确认不能退回草稿", "status": "PASS"})

            assert db.execute(stale, (json.dumps({"reason": "PLAN_CHANGED", "diff": []}), second, owner, app, 0, "CONFIRMED")).rowcount == 0
            assert db.execute(stale, (json.dumps({"reason": "PLAN_CHANGED", "diff": [{"path": "contentHash", "before": "a", "after": "b"}]}), second, owner, app, 1, "CONFIRMED")).rowcount == 1
            assert row(second)[0] == "STALE"
            for statement in (f"UPDATE {table} SET state='CONFIRMED' WHERE plan_id=%s", f"UPDATE {table} SET state='DRAFT',confirmed_at=NULL,aggregate_acknowledged=false WHERE plan_id=%s"):
                rejected = False
                try:
                    with db.transaction(): db.execute(statement, (second,))
                except psycopg.errors.CheckViolation: rejected = True
                assert rejected, statement
            for statement in (f"UPDATE {table} SET state='STALE',stale_json=NULL WHERE plan_id=%s", f"UPDATE {table} SET confirmed_at=now() WHERE plan_id=%s"):
                draft, _ = create(uuid.uuid4().hex + uuid.uuid4().hex)
                rejected = False
                try:
                    with db.transaction(): db.execute(statement, (draft,))
                except psycopg.errors.CheckViolation: rejected = True
                assert rejected, statement
            report["checks"].append({"name": "失效 SQL 按 revision CAS 并保存差异；失效计划不能恢复；STALE 必须有差异记录、草稿不能带确认时间", "status": "PASS"})
            db.rollback()
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", errorType=type(error).__name__)
        raise RuntimeError("计划SQL预检失败，见脱敏报告") from None
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
