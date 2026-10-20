"""仅清理性能验收报告指明的专用账户；应用必须已通过正式API删除。"""
import json
import runpy
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    path = Path(sys.argv[1]).resolve()
    if not path.is_relative_to(ROOT / 'temp/logs/chat-performance-20261020'):
        raise ValueError('FIXTURE_REPORT_SCOPE_INVALID')
    report = json.loads(path.read_text(encoding='utf-8'))
    owner = report['ownerId']
    account = report['account']
    if not str(owner).isdigit() or not account.startswith('speed_test_'):
        raise ValueError('FIXTURE_IDENTITY_INVALID')
    helper = runpy.run_path(str(ROOT / 'scripts/debug-agent-trace.py'))
    with helper['connect'](helper['load_env']()) as connection, connection.cursor() as cursor:
        cursor.execute("SET LOCAL statement_timeout='5s'")
        cursor.execute('SELECT is_delete FROM biz."user" WHERE id=%s AND user_account=%s FOR UPDATE', (owner, account))
        row = cursor.fetchone()
        if row is None:
            raise ValueError('FIXTURE_IDENTITY_NOT_FOUND')
        cursor.execute('SELECT count(*) FROM biz.app WHERE user_id=%s AND is_delete=0', (owner,))
        if cursor.fetchone()[0]:
            raise ValueError('FIXTURE_APPLICATIONS_NOT_CLEANED')
        cursor.execute("SELECT count(*) FROM biz.agent_run WHERE user_id=%s AND status IN ('PENDING','RUNNING','WAITING_REVIEW')", (owner,))
        if cursor.fetchone()[0]:
            raise ValueError('FIXTURE_RUN_STILL_ACTIVE')
        cursor.execute('UPDATE biz."user" SET is_delete=1,update_time=now() WHERE id=%s AND user_account=%s', (owner, account))
        cursor.execute('SELECT is_delete FROM biz."user" WHERE id=%s AND user_account=%s', (owner, account))
        if cursor.fetchone()[0] != 1:
            raise ValueError('FIXTURE_CLEANUP_NOT_CONFIRMED')
    report['userLogicalDeleted'] = True
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('performance fixture account cleanup confirmed')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('PERFORMANCE_FIXTURE_CLEANUP_FAILED', file=sys.stderr)
        raise SystemExit(1)
