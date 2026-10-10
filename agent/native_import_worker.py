"""原生导入Worker的严格持久入口；正式模型仍由既有runtime准入决定。"""
import json
import os
from pathlib import Path
import sys
import argparse

from env_utils import load_env_file
from core.local_postgres_relay import LocalPostgresRelay


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--edit-id')
    parser.add_argument('--lease-id')
    args = parser.parse_args()
    identity = None
    if args.edit_id is not None or args.lease_id is not None:
        try:
            from core.import_worker_identity import register
            identity = register(args.edit_id, args.lease_id)
        except Exception:
            print('NATIVE_IMPORT_WORKER_IDENTITY_INVALID', file=sys.stderr)
            return 1
    load_env_file()
    import app
    try:
        from psycopg.conninfo import conninfo_to_dict, make_conninfo
        dsn = os.environ.get('AGENT_POSTGRES_DSN', '')
        target = conninfo_to_dict(dsn)
        if target.get('host') not in ('localhost', '127.0.0.1', '::1') or target.get('port', '5432') != '5432':
            print('NATIVE_POSTGRES_TARGET_INVALID', file=sys.stderr)
            return 1
        with LocalPostgresRelay(Path(__file__).resolve().parent.parent) as relay:
            os.environ['AGENT_POSTGRES_DSN'] = make_conninfo(dsn, host=str(relay.root), port=5432,
                                                          connect_timeout=10, application_name='glyahh-native-worker-' + str(os.getpid()))

            def ready(output):
                stat = Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()
                frame = {'event': 'worker.ready', 'nativePid': str(os.getpid()), 'nativeStartTicks': stat[19],
                         'persistence': 'POSTGRES', 'importOnly': True}
                if identity is not None:
                    frame.update(editId=identity['editId'], leaseId=identity['leaseId'])
                output.write(json.dumps(frame) + '\n')
                output.flush()

            try:
                from core.import_edit_runtime import run_import_edit
                return app.main(require_persistent=True, import_only=True, on_ready=ready, import_runner=run_import_edit)
            finally:
                from core import streaming_runtime
                if streaming_runtime._loop is not None and not streaming_runtime._loop.is_closed():
                    app._shutdown_pools()
    except Exception:
        print('NATIVE_IMPORT_WORKER_UNAVAILABLE', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
