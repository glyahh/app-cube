"""事务内独立 schema 核对 V27 与安装/运行登记 SQL；全部回滚，不改业务行，不执行安装或运行。"""
import json
import re
import time
import uuid
from pathlib import Path

import psycopg


def main():
    evidence = Path("temp/import-startup-preparation-20261005") / ("schema-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    report = {"status": "RUNNING", "checks": [], "evidence": str(evidence)}
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    try:
        with psycopg.connect(dsn) as db:
            schema = "startup_preparation_probe_" + uuid.uuid4().hex
            db.execute("CREATE SCHEMA " + schema)
            db.execute("SET search_path TO " + schema)
            plan, owner, app, revision, content = db.execute(
                "SELECT plan_id,owner_id,app_id,plan_revision,content_hash FROM biz.project_import_startup_plan WHERE state='CONFIRMED' ORDER BY created_at DESC LIMIT 1").fetchone()
            table = schema + ".preparation"
            ddl = Path("src/main/resources/db/migration/V27__project_import_startup_preparation.sql").read_text(encoding="utf-8")
            ddl = ddl.replace("biz.project_import_startup_preparation_frozen", schema + ".frozen").replace("biz.project_import_startup_preparation", table)
            for reference in (" REFERENCES biz.\"user\"(id)", " REFERENCES biz.project_import_publication(app_id)", " REFERENCES biz.project_import_startup_plan(plan_id)"):
                ddl = ddl.replace(reference, "")
            db.execute(ddl)
            source = Path("src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupPreparationRepository.java").read_text(encoding="utf-8")
            statements = [item.replace("biz.project_import_startup_preparation", table).replace("biz.project_import_startup_plan", "biz.project_import_startup_plan").replace("?", "%s")
                          for item in re.findall(r'"""(.*?)"""', source, re.S)]
            insert = next(item for item in statements if "INSERT INTO" in item)
            update = next(item for item in statements if item.strip().startswith("UPDATE"))
            ident = uuid.uuid4()
            services = json.dumps([{"serviceId": "web", "phase": "NOT_STARTED", "state": "REFUSED", "reason": "MEMORY_LIMIT_UNENFORCEABLE", "executionId": None}])
            assert db.execute(insert, (ident, owner, app, plan, revision, content, "INSTALL", "APPROVED", "REFUSED", "MEMORY_LIMIT_UNENFORCEABLE", services, plan, owner, app, revision, content)).rowcount == 1
            row = db.execute(f"SELECT state,revision,reason FROM {table} WHERE preparation_id=%s", (ident,)).fetchone()
            assert row == ("REFUSED", 0, "MEMORY_LIMIT_UNENFORCEABLE")
            report["checks"].append({"name": "插入只接受当前已确认计划；拒绝记录带原因且不表示已启动", "status": "PASS"})
            assert db.execute(update, ("APPROVED", "NONE_READY", "MEMORY_LIMIT_UNENFORCEABLE", services, ident, owner, app, 0)).rowcount == 1
            assert db.execute(f"SELECT revision,state FROM {table} WHERE preparation_id=%s", (ident,)).fetchone() == (1, "NONE_READY")
            try:
                with db.transaction():
                    db.execute(f"UPDATE {table} SET revision=revision WHERE preparation_id=%s", (ident,))
                report["checks"].append({"name": "版本必须递增", "status": "FAIL"})
            except psycopg.errors.CheckViolation:
                report["checks"].append({"name": "旧版本不能原样写回，revision 必须加一", "status": "PASS"})
            try:
                with db.transaction():
                    db.execute(f"UPDATE {table} SET plan_revision=%s,revision=revision+1 WHERE preparation_id=%s", ("c" * 64, ident))
                report["checks"].append({"name": "身份冻结", "status": "FAIL"})
            except psycopg.errors.CheckViolation:
                report["checks"].append({"name": "计划版本等身份列不能改写", "status": "PASS"})
            other = uuid.uuid4()
            try:
                with db.transaction():
                    db.execute(insert, (other, owner, app, plan, revision, content, "INSTALL", "REJECTED", "REJECTED", "INSTALL_DECLINED", "[]", plan, owner, app, revision, content))
                report["checks"].append({"name": "唯一行", "status": "FAIL"})
            except psycopg.errors.UniqueViolation:
                report["checks"].append({"name": "同一计划版本的安装只保留一行", "status": "PASS"})
            db.rollback()
            report["status"] = "PASS" if all(item["status"] == "PASS" for item in report["checks"]) else "FAIL"
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = type(error).__name__
        raise
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
