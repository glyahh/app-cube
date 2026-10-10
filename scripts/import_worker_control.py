"""只停止平台登记的本轮原生进程；不执行工程或按进程名清理。"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
from core.import_worker_identity import stop

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--edit-id', required=True)
    parser.add_argument('--lease-id', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(stop(args.edit_id, args.lease_id)))
    except Exception:
        print('IMPORT_WORKER_STOP_UNCONFIRMED', file=sys.stderr)
        raise SystemExit(1)
