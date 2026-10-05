"""用整体回滚的独立 schema 验证 V19；不修改既有业务行，不输出连接串。"""
import json
from pathlib import Path
import psycopg
import uuid


def main():
    folder = Path("temp/import-startup-config-20261004"); folder.mkdir(parents=True, exist_ok=True)
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'") for line in Path(".env").read_text(encoding="utf-8-sig").splitlines() if line.startswith("AGENT_POSTGRES_DSN="))
    report = {"status": "RUNNING", "checks": []}
    try:
        with psycopg.connect(dsn) as db:
            ddl = Path("src/main/resources/db/migration/V19__project_import_startup_configuration.sql").read_text(encoding="utf-8")
            schema = "startup_probe_" + uuid.uuid4().hex
            db.execute("CREATE SCHEMA " + schema)
            db.execute("SET LOCAL search_path TO " + schema + ",biz,public")
            ddl = ddl.replace("CREATE TABLE biz.project_import_startup_configuration", "CREATE TABLE startup_configuration_probe")
            db.execute(ddl)
            app, owner, task = db.execute("SELECT app_id,owner_id,task_id FROM biz.project_import_publication WHERE state='READY' ORDER BY app_id DESC LIMIT 1").fetchone()
            db.execute("INSERT INTO startup_configuration_probe(app_id,owner_id,import_task_id) VALUES(%s,%s,%s)", (app, owner, task))
            for assignment, value in [("config_revision", -1), ("context_epoch", -1), ("services_json", "{}")]:
                rejected = False
                try:
                    with db.transaction(): db.execute(f"UPDATE startup_configuration_probe SET {assignment}=%s WHERE app_id=%s", (value, app))
                except psycopg.errors.CheckViolation: rejected = True
                assert rejected
            report["checks"].append({"name": "负版本与非数组配置被真实数据库拒绝", "status": "PASS"})
            command = "UPDATE startup_configuration_probe SET config_revision=config_revision+1,context_epoch=context_epoch+1 WHERE app_id=%s AND owner_id=%s AND import_task_id=%s AND config_revision=0 AND context_epoch=0"
            assert db.execute(command, (app, owner, task)).rowcount == 1
            assert db.execute(command, (app, owner, task)).rowcount == 0
            assert db.execute("SELECT config_revision,context_epoch FROM startup_configuration_probe").fetchone() == (1, 1)
            report["checks"].append({"name": "旧版本重复 CAS 不更新；版本与上下文一起增加", "status": "PASS"})
            db.rollback()
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["errorType"] = type(error).__name__; report["sqlState"] = getattr(error, "sqlstate", None)
        raise RuntimeError("配置数据库预检失败，未输出凭据") from None
    finally: (folder / "schema-preflight.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__": main()
