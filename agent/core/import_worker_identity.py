"""平台私有原生进程身份；停止墓碑阻止晚到启动，PID复用不会误杀。"""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import signal
import time
import uuid

ROOT = Path('/var/lib/glyahh/import-workers')


def ticks(pid):
    try:
        parts = Path('/proc', str(pid), 'stat').read_text().rsplit(')', 1)[1].split()
        return None if parts[0] == 'Z' else parts[19]
    except FileNotFoundError:
        return None


@contextlib.contextmanager
def locked(edit_id, lease_id):
    if os.geteuid() != 0 or str(uuid.UUID(edit_id)) != edit_id or str(uuid.UUID(lease_id)) != lease_id:
        raise ValueError('IMPORT_WORKER_IDENTITY_INVALID')
    for path in (ROOT, *ROOT.parents):
        if path.is_symlink():
            raise ValueError('IMPORT_WORKER_ROOT_UNTRUSTED')
    ROOT.mkdir(parents=True, mode=0o700, exist_ok=True)
    attrs = ROOT.stat()
    if attrs.st_uid != 0 or attrs.st_mode & 0o077:
        raise ValueError('IMPORT_WORKER_ROOT_UNTRUSTED')
    descriptor = os.open(ROOT / '.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        path = ROOT / (edit_id + '.json')
        if path.is_symlink():
            raise ValueError('IMPORT_WORKER_RECORD_UNTRUSTED')
        record = json.loads(path.read_text()) if path.exists() else None
        if record is not None and (record.get('editId'), record.get('leaseId')) != (edit_id, lease_id):
            raise ValueError('IMPORT_WORKER_IDENTITY_INVALID')
        yield path, record
    finally:
        os.close(descriptor)


def save(path, record):
    temporary = path.with_suffix('.pending')
    if temporary.exists() or temporary.is_symlink():
        raise ValueError('IMPORT_WORKER_RECORD_UNCONFIRMED')
    with temporary.open('x') as stream:
        os.chmod(temporary, 0o600)
        json.dump(record, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def register(edit_id, lease_id):
    with locked(edit_id, lease_id) as (path, record):
        if record is not None:
            raise ValueError('IMPORT_WORKER_ALREADY_REGISTERED')
        record = {'editId': edit_id, 'leaseId': lease_id, 'nativePid': str(os.getpid()),
                  'nativeStartTicks': ticks(os.getpid()), 'state': 'STARTED'}
        if record['nativeStartTicks'] is None:
            raise ValueError('IMPORT_WORKER_IDENTITY_INVALID')
        save(path, record)
        return record


def stop(edit_id, lease_id):
    with locked(edit_id, lease_id) as (path, record):
        if record is None:
            record = {'editId': edit_id, 'leaseId': lease_id, 'nativePid': None, 'nativeStartTicks': None, 'state': 'STOPPED'}
            save(path, record)
        if record['state'] not in ('STARTED', 'STOPPED'):
            raise ValueError('IMPORT_WORKER_RECORD_UNCONFIRMED')
        pid = record.get('nativePid')
        before = record.get('nativeStartTicks')
        if pid is not None:
            if not isinstance(pid, str) or not pid.isdigit() or int(pid) <= 1 or not isinstance(before, str) or not before.isdigit():
                raise ValueError('IMPORT_WORKER_IDENTITY_INVALID')
            pid = int(pid)
            if ticks(pid) == before:
                os.kill(pid, signal.SIGTERM)
                deadline = time.monotonic() + 2
                while ticks(pid) == before and time.monotonic() < deadline:
                    time.sleep(0.05)
                if ticks(pid) == before:
                    os.kill(pid, signal.SIGKILL)
                    deadline = time.monotonic() + 3
                    while ticks(pid) == before and time.monotonic() < deadline:
                        time.sleep(0.05)
                if ticks(pid) == before:
                    raise ValueError('IMPORT_WORKER_STOP_UNCONFIRMED')
        record['state'] = 'STOPPED'
        save(path, record)
        return {**record, 'nativeStopped': True}
