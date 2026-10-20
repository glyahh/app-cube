"""只读取证：把一轮 Agent 执行涉及的日志与数据库状态一次打出来。

用法（仓库根目录）：
  agent/.venv/Scripts/python.exe -I scripts/debug-agent-trace.py --minutes 15
  agent/.venv/Scripts/python.exe -I scripts/debug-agent-trace.py --app 469688129883688960 --minutes 30

只读：不修改数据库、不调用模型、不启动进程。
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ROOT / '.env'
    if not path.is_file():
        return values
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
    return values


def connect(values: dict[str, str]):
    import psycopg
    dsn = 'postgresql://%s:%s@%s:%s/%s' % (
        values.get('POSTGRES_USER', 'postgres'), values.get('POSTGRES_PASSWORD', ''),
        values.get('POSTGRES_HOST', 'localhost'), values.get('POSTGRES_PORT', '5432'),
        values.get('POSTGRES_DB', 'gly_ai_generate_code'))
    return psycopg.connect(dsn, connect_timeout=8)


def latest_log() -> Path | None:
    files = glob.glob(str(ROOT / 'temp' / 'logs' / 'debug-*' / 'backend.stdout.log'))
    if not files:
        files = glob.glob(str(ROOT / 'temp' / 'logs' / '*' / 'backend.stdout.log'))
    return Path(max(files, key=os.path.getmtime)) if files else None


JAVA_TIME = re.compile(r'^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})')
PY_TIME = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})')
KEYWORDS = ('backend ready', '许可请求', '登记工具许可', '关闭编辑会话', '开启编辑会话',
            '命令失败', '工具许可', 'Traceback', 'SandboxError', 'TOOL_PERMISSION',
            'run.started', 'run.completed', 'import.permission', 'Permission gate')


def log_window(path: Path, since: datetime) -> list[str]:
    lines: list[str] = []
    with path.open(encoding='utf-8', errors='replace') as stream:
        for line in stream:
            match = JAVA_TIME.match(line) or PY_TIME.match(line)
            if not match:
                continue
            try:
                stamp = datetime.fromisoformat(match.group(1))
            except ValueError:
                continue
            if stamp < since:
                continue
            if any(key in line for key in KEYWORDS):
                lines.append(line.rstrip())
    return lines


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', type=int, default=0)
    parser.add_argument('--minutes', type=int, default=15)
    args = parser.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    since = datetime.now() - timedelta(minutes=args.minutes)
    app_filter = args.app or None

    log_path = latest_log()
    print('=== 日志：%s（最近 %d 分钟）===' % (log_path, args.minutes) if log_path else '=== 未找到日志 ===')
    if log_path:
        for line in log_window(log_path, since):
            print(line)

    with connect(load_env()) as connection, connection.cursor() as cursor:
        print('\n=== agent_run ===')
        cursor.execute("""
            SELECT id, app_id, status, error_code, error_message, started_at, finished_at
            FROM biz.agent_run
            WHERE started_at >= %s AND (%s::bigint IS NULL OR app_id = %s)
            ORDER BY started_at DESC LIMIT 10
            """, (since, app_filter, app_filter))
        for row in cursor.fetchall():
            print(row)

        print('\n=== agent_edit_session ===')
        cursor.execute("""
            SELECT session_id, app_id, state, approval_mode, sandbox_mode,
                   created_at, expires_at, closed_at
            FROM biz.agent_edit_session
            WHERE created_at >= %s AND (%s::bigint IS NULL OR app_id = %s)
            ORDER BY created_at DESC LIMIT 10
            """, (since, app_filter, app_filter))
        for row in cursor.fetchall():
            print(row)

        print('\n=== agent_tool_permission ===')
        cursor.execute("""
            SELECT p.permission_id, s.app_id, p.tool, p.status, p.risk,
                   p.decision_source, p.action_json, p.created_at
            FROM biz.agent_tool_permission p
            JOIN biz.agent_edit_session s ON s.session_id = p.session_id
            WHERE p.created_at >= %s AND (%s::bigint IS NULL OR s.app_id = %s)
            ORDER BY p.created_at DESC LIMIT 10
            """, (since, app_filter, app_filter))
        for row in cursor.fetchall():
            print(row)

        print('\n=== agent_candidate_review ===')
        cursor.execute("""
            SELECT r.review_id, s.app_id, r.status, r.risk, r.revision,
                   r.decision_source, r.created_at
            FROM biz.agent_candidate_review r
            JOIN biz.agent_edit_session s ON s.session_id = r.session_id
            WHERE r.created_at >= %s AND (%s::bigint IS NULL OR s.app_id = %s)
            ORDER BY r.created_at DESC LIMIT 10
            """, (since, app_filter, app_filter))
        for row in cursor.fetchall():
            print(row)

    return 0


if __name__ == '__main__':
    raise SystemExit(main())
