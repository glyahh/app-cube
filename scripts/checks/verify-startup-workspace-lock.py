"""只检查数据库会话锁，使用随机测试身份，不修改业务表或读取工程。"""
import json
import secrets
from pathlib import Path
import psycopg


def main():
    folder = Path("temp/import-startup-business-20261004")
    folder.mkdir(parents=True, exist_ok=True)
    dsn = next(line.split("=", 1)[1].strip().strip('"').strip("'")
               for line in Path(".env").read_text(encoding="utf-8-sig").splitlines()
               if line.startswith("AGENT_POSTGRES_DSN="))
    app_id = secrets.randbelow(1 << 50) + 1
    key = app_id - (1 << 63)
    result = {"status": "RUNNING", "checks": [], "businessAcceptance": "NOT_RUN"}
    try:
        with psycopg.connect(dsn, autocommit=True) as first, psycopg.connect(dsn, autocommit=True) as other:
            assert first.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
            try:
                assert not other.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
                assert not other.execute("SELECT pg_advisory_unlock(%s)", (key,)).fetchone()[0]
                result["checks"].append({"name": "同应用互斥，非持有者不能释放", "status": "PASS"})
                independent = key + 1
                assert other.execute("SELECT pg_try_advisory_lock(%s)", (independent,)).fetchone()[0]
                assert other.execute("SELECT pg_advisory_unlock(%s)", (independent,)).fetchone()[0]
                result["checks"].append({"name": "其他应用锁独立", "status": "PASS"})
            finally:
                assert first.execute("SELECT pg_advisory_unlock(%s)", (key,)).fetchone()[0]
            assert other.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
            assert other.execute("SELECT pg_advisory_unlock(%s)", (key,)).fetchone()[0]
            result["checks"].append({"name": "释放后其他会话可取得", "status": "PASS"})
            assert first.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
            first.close()
            assert other.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
            assert other.execute("SELECT pg_advisory_unlock(%s)", (key,)).fetchone()[0]
            result["checks"].append({"name": "实际持有连接关闭后释放", "status": "PASS"})
        result["status"] = "PASS"
    except Exception:
        result["status"] = "FAIL"
        raise RuntimeError("工作区锁实测失败，未输出连接凭据") from None
    finally:
        (folder / "database-lock-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
