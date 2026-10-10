"""原生Worker的私有Unix连接经stdio访问Windows回环数据库，不开放TCP端口。"""
from __future__ import annotations

import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time


class RelayError(RuntimeError):
    pass


class LocalPostgresRelay:
    def __init__(self, repository: Path, *, max_connections=16):
        if type(max_connections) is not int or not 1 <= max_connections <= 16:
            raise RelayError('PG_RELAY_LIMIT_INVALID')
        self.repository = Path(repository).resolve()
        self._slots = threading.BoundedSemaphore(max_connections)
        self._lock = threading.Lock()
        self._clients = set()
        self._workers = set()
        self._processes = set()
        self._stop = threading.Event()
        self._directory = None
        self._listener = None

    def _command(self):
        def windows(path):
            parts = path.parts
            if len(parts) < 4 or parts[1] != 'mnt' or len(parts[2]) != 1 or not parts[2].isalpha():
                raise RelayError('PG_RELAY_PLATFORM_REQUIRED')
            return parts[2].upper() + ':\\' + '\\'.join(parts[3:])
        executable = self.repository / 'agent/.venv/Scripts/python.exe'
        script = self.repository / 'scripts/import_pg_stdio.py'
        if not executable.is_file() or not script.is_file():
            raise RelayError('PG_RELAY_RUNTIME_MISSING')
        return [str(executable), '-u', windows(script)]

    def start(self):
        if os.name != 'posix' or not hasattr(socket, 'AF_UNIX') or self._directory is not None:
            raise RelayError('PG_RELAY_START_INVALID')
        self._command()
        self._directory = tempfile.TemporaryDirectory(prefix='glyahh-agent-pg-')
        self.root = Path(self._directory.name)
        os.chmod(self.root, 0o700)
        self.path = self.root / '.s.PGSQL.5432'
        self._listener = socket.socket(socket.AF_UNIX)
        try:
            self._listener.bind(str(self.path))
            os.chmod(self.path, 0o600)
            self._listener.listen(16)
            self._listener.settimeout(0.25)
            self._acceptor = threading.Thread(target=self._accept, daemon=True)
            self._acceptor.start()
            return self
        except OSError as error:
            self._listener.close()
            self._directory.cleanup()
            self._directory = None
            raise RelayError('PG_RELAY_START_FAILED') from error

    def _accept(self):
        while not self._stop.is_set():
            try:
                client, _ = self._listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            if not self._slots.acquire(blocking=False):
                client.close()
                continue
            with self._lock:
                if self._stop.is_set():
                    client.close()
                    self._slots.release()
                    return
                self._clients.add(client)
                worker = threading.Thread(target=self._bridge, args=(client,), daemon=True)
                self._workers.add(worker)
                worker.start()

    def _bridge(self, client):
        process = None
        uploader = None
        try:
            process = subprocess.Popen(self._command(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, bufsize=0)
            with self._lock:
                self._processes.add(process)
                if self._stop.is_set():
                    process.terminate()

            def upload():
                try:
                    while data := client.recv(65536):
                        remaining = memoryview(data)
                        while remaining:
                            count = process.stdin.write(remaining)
                            if not count:
                                return
                            remaining = remaining[count:]
                except (OSError, ValueError):
                    pass
                finally:
                    process.stdin.close()

            uploader = threading.Thread(target=upload, daemon=True)
            uploader.start()
            while data := os.read(process.stdout.fileno(), 65536):
                client.sendall(data)
        except (OSError, ValueError, RelayError):
            pass
        finally:
            try:
                client.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            client.close()
            if uploader is not None:
                uploader.join(2)
            if process is not None:
                process.stdin.close()
                process.stdout.close()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=2)
            with self._lock:
                self._clients.discard(client)
                self._workers.discard(threading.current_thread())
                if process is not None:
                    self._processes.discard(process)
            self._slots.release()

    def close(self):
        if self._directory is None:
            return
        self._stop.set()
        self._listener.close()
        self._acceptor.join(2)
        with self._lock:
            clients = list(self._clients)
            workers = list(self._workers)
            processes = list(self._processes)
        for client in clients:
            try:
                client.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            client.close()
        for process in processes:
            try:
                process.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                process.terminate()
        deadline = time.monotonic() + 8
        for worker in workers:
            worker.join(max(0, deadline - time.monotonic()))
        with self._lock:
            if self._workers or self._clients or self._processes or self._acceptor.is_alive():
                raise RelayError('PG_RELAY_CLEANUP_UNCONFIRMED')
        self._directory.cleanup()
        self._directory = None

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.close()
