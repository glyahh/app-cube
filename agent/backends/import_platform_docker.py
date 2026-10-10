"""采用平台登记的编辑容器，不创建第二个容器或授予最终发布权限。"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import time

from backends.docker_sandbox import DockerSandboxManager, SandboxRecord, SandboxError


class ImportPlatformDockerManager:
    _docker = staticmethod(DockerSandboxManager._docker)

    def __init__(self, identity, container_id, *, eligible):
        if os.name != 'posix' or os.geteuid() != 0 or not re.fullmatch('[0-9a-f]{64}', container_id):
            raise SandboxError('IMPORT_PLATFORM_IDENTITY_INVALID')
        path = Path(__file__).resolve().parents[2] / 'scripts/import_disk_volume.py'
        spec = importlib.util.spec_from_file_location('import_edit_platform', path)
        self.platform = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.platform)
        normalized = self.platform.identity(identity['appId'], identity['contentHash'], identity['diskBytes'],
                                            owner_id=identity['ownerId'], run_id=identity['runId'], edit_id=identity['editId'])
        if normalized != identity:
            raise SandboxError('IMPORT_PLATFORM_IDENTITY_INVALID')
        self.identity, self.container_id, self.eligible = normalized, container_id, eligible
        self.folder = self.platform.resource_folder(normalized)
        self.record = None

    def _qualified(self):
        if not self.eligible():
            raise SandboxError('IMPORT_EDIT_REVOKED')

    def acquire(self, session_id, workspace, *, run_id=''):
        self._qualified()
        expected = self.folder / 'work/view'
        if self.record is not None or session_id != 'app:' + self.identity['appId'] or run_id != self.identity['runId'] \
                or Path(workspace) != expected:
            raise SandboxError('IMPORT_PLATFORM_IDENTITY_INVALID')
        receipt = self.platform.edit_container(self.identity, start_id=self.container_id)
        if receipt.get('state') != 'CONTAINER_RUNNING' or receipt.get('containerId') != self.container_id:
            raise SandboxError('IMPORT_PLATFORM_START_UNCONFIRMED')
        self._qualified()
        now = time.time()
        self.record = SandboxRecord(session_id, self.container_id, expected, 'glyahh-sandbox-runtime:24.04',
                                    now, now, run_id, active=True)
        return self.record

    def execute(self, record, command, timeout):
        self._qualified()
        with self.platform.locked():
            if self._load_record().get('editState', 'READY') != 'READY':
                raise SandboxError('IMPORT_PLATFORM_EDIT_UNCONFIRMED')
        if record is not self.record or not record.active or type(timeout) is not int or not 1 <= timeout <= 120:
            raise SandboxError('IMPORT_PLATFORM_EXECUTION_INVALID')
        self.platform.edit_container(self.identity, start_id=self.container_id)
        self._qualified()
        return self._docker('exec', '-i', '-w', '/workspace', self.container_id,
                            'timeout', '-k', '2', str(timeout), 'sh', '-c', command, check=False, timeout=timeout + 5)

    def _load_record(self):
        self.platform.no_links(self.folder)
        path = self.folder / 'record.json'
        self.platform.no_links(path)
        record = json.loads(path.read_text(encoding='utf-8'))
        self.platform.match(self.identity, record)
        self.platform.mounted(self.folder, record)
        bound = record.get('editContainer', {})
        if bound.get('id') != self.container_id or bound.get('state') != 'CREATED' \
                or record.get('candidateState') is not None or record.get('viewState') != 'SEALED':
            raise SandboxError('IMPORT_PLATFORM_EDIT_INVALID')
        return record

    def edit_started(self):
        self._qualified()
        with self.platform.locked():
            record = self._load_record()
            if record.get('editState', 'READY') != 'READY':
                raise SandboxError('IMPORT_PLATFORM_EDIT_UNCONFIRMED')
            record['editState'] = 'APPLYING'
            self.platform.write_record(self.folder, record)

    def edit_finished(self, view):
        self._qualified()
        with self.platform.locked():
            record = self._load_record()
            if record.get('editState') != 'APPLYING' or view.root != self.folder / 'work/view':
                raise SandboxError('IMPORT_PLATFORM_EDIT_UNCONFIRMED')
            state_hash = view.save_private_state(self.folder / 'work/private-state.json',
                                                eligible=self.eligible, replace=True)
            # 文件工具在平台进程中写入；权限仍必须允许容器内固定UID读取和检查。
            for root, directories, files in os.walk(view.root, followlinks=False):
                for name in directories + files:
                    self._qualified()
                    path = Path(root) / name
                    self.platform.no_links(path)
                    attrs = path.lstat()
                    if not (stat.S_ISREG(attrs.st_mode) or stat.S_ISDIR(attrs.st_mode)):
                        raise SandboxError('IMPORT_PLATFORM_EDIT_INVALID')
                    os.chown(path, 65534, 65534)
            self.platform.command(['sync', '-f', str(view.root)])
            self._qualified()
            record.update(editState='READY', authorizedStateHash=state_hash)
            self.platform.write_record(self.folder, record)
            return state_hash

    def authorization_hash(self):
        self._qualified()
        with self.platform.locked():
            record = self._load_record()
            if record.get('editState', 'READY') != 'READY':
                raise SandboxError('IMPORT_PLATFORM_EDIT_UNCONFIRMED')
            return record.get('authorizedStateHash', record['viewStateHash'])

    def close(self, session_id):
        if self.record is not None and session_id != self.record.session_id:
            raise SandboxError('IMPORT_PLATFORM_IDENTITY_INVALID')
        receipt = self.platform.edit_container(self.identity)
        if receipt.get('state') != 'STOP_CONFIRMED' or receipt.get('containerId') != self.container_id:
            raise SandboxError('IMPORT_PLATFORM_STOP_UNCONFIRMED')
        if self.record is not None:
            self.record.active = False

    def release(self, session_id):
        self.close(session_id)
