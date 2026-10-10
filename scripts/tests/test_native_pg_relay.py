"""通道边界及生命周期；本地echo进程替身，不作为PostgreSQL验收。"""
import contextlib
from pathlib import Path
import socket
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'agent'))
from core.local_postgres_relay import LocalPostgresRelay, RelayError


@unittest.skipUnless(sys.platform == 'linux', 'NATIVE_LINUX_REQUIRED')
class RelayTest(unittest.TestCase):
    @contextlib.contextmanager
    def fixture(self, *, code=None, limit=16):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'echo.py'
            script.write_text(code or 'import os\nwhile data := os.read(0,65536):\n os.write(1,data)\n')
            relay = LocalPostgresRelay(Path(directory), max_connections=limit)
            with patch.object(relay, '_command', return_value=[sys.executable, '-u', str(script)]):
                try:
                    yield relay.start()
                finally:
                    relay.close()

    def connect(self, relay):
        client = socket.socket(socket.AF_UNIX)
        client.settimeout(3)
        client.connect(str(relay.path))
        return client

    def test_binary_half_close_and_private_socket_cleanup(self):
        with self.fixture() as relay:
            folder = relay.root
            self.assertEqual(folder.stat().st_mode & 0o777, 0o700)
            self.assertEqual(relay.path.stat().st_mode & 0o777, 0o600)
            with self.connect(relay) as client:
                data = bytes(range(256)) * 512
                client.sendall(data)
                client.shutdown(socket.SHUT_WR)
                returned = bytearray()
                while block := client.recv(65536):
                    returned.extend(block)
                self.assertEqual(returned, data)
        self.assertFalse(folder.exists())
        relay.close()

    def test_connection_quota_rejects_second_connection(self):
        with self.fixture(limit=1) as relay:
            with self.connect(relay) as first:
                first.sendall(b'first')
                self.assertEqual(first.recv(5), b'first')
                with self.connect(relay) as second:
                    self.assertEqual(second.recv(1), b'')

    def test_close_stops_exact_owned_unresponsive_child(self):
        with self.fixture(code='import time\ntime.sleep(60)\n') as relay:
            client = self.connect(relay)
            deadline = time.monotonic() + 3
            while not relay._processes:
                self.assertLess(time.monotonic(), deadline)
                time.sleep(0.01)
            children = list(relay._processes)
            relay.close()
            self.assertTrue(all(child.poll() is not None for child in children))
            self.assertFalse(relay.root.exists())
            client.close()

    def test_missing_executable_closes_client_without_claiming_success(self):
        with self.fixture() as relay:
            with patch.object(relay, '_command', return_value=['/nonexistent/item1-test-executable']):
                with self.connect(relay) as client:
                    self.assertEqual(client.recv(1), b'')

    def test_invalid_quota_is_refused(self):
        for limit in [0, 17, True, '1']:
            with self.assertRaises(RelayError):
                LocalPostgresRelay(Path.cwd(), max_connections=limit)


if __name__ == '__main__':
    unittest.main()
