"""原生身份回收反例，只操作本轮临时目录和专用子进程。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

SOURCE = Path(__file__).resolve().parents[2] / 'agent/core/import_worker_identity.py'
spec = importlib.util.spec_from_file_location('worker_identity', SOURCE)
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


class WorkerIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        identity.ROOT = Path(self.temporary.name)
        self.edit, self.lease = str(uuid.uuid4()), str(uuid.uuid4())

    def test_stop_before_registration_creates_tombstone(self):
        self.assertTrue(identity.stop(self.edit, self.lease)['nativeStopped'])
        with self.assertRaisesRegex(ValueError, 'IMPORT_WORKER_ALREADY_REGISTERED'):
            identity.register(self.edit, self.lease)

    def test_pid_reuse_does_not_signal_current_process(self):
        record = identity.register(self.edit, self.lease)
        record['nativeStartTicks'] = '1' if record['nativeStartTicks'] != '1' else '2'
        identity.save(identity.ROOT / (self.edit + '.json'), record)
        self.assertTrue(identity.stop(self.edit, self.lease)['nativeStopped'])
        self.assertIsNotNone(identity.ticks(os.getpid()))

    def test_only_matching_owned_child_is_stopped(self):
        code = ('import importlib.util,sys,time; from pathlib import Path; '
                's=importlib.util.spec_from_file_location("worker_identity",sys.argv[1]); '
                'm=importlib.util.module_from_spec(s);s.loader.exec_module(m); '
                'm.ROOT=Path(sys.argv[2]);m.register(sys.argv[3],sys.argv[4]); '
                'print("READY",flush=True);time.sleep(30)')
        child = subprocess.Popen([sys.executable, '-u', '-c', code, str(SOURCE), str(identity.ROOT), self.edit, self.lease], stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(), 'READY')
            self.assertTrue(identity.stop(self.edit, self.lease)['nativeStopped'])
            child.wait(timeout=5)
            self.assertIsNotNone(child.returncode)
            self.assertEqual(json.loads((identity.ROOT / (self.edit + '.json')).read_text())['state'], 'STOPPED')
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)
            child.stdout.close()

    def test_wrong_lease_cannot_stop_registered_worker(self):
        identity.register(self.edit, self.lease)
        with self.assertRaisesRegex(ValueError, 'IMPORT_WORKER_IDENTITY_INVALID'):
            identity.stop(self.edit, str(uuid.uuid4()))


if __name__ == '__main__':
    unittest.main()
