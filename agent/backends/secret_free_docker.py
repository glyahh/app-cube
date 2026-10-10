"""只挂秘密隔离副本；文件工具记录编辑资格，Shell 不获得业务代码合入权限。"""
from __future__ import annotations

import threading

from deepagents.backends.protocol import WriteResult, EditResult, DeleteResult, FileUploadResponse

from backends.docker_sandbox import DockerSandboxBackend, SandboxError
from backends.secret_free_view import ViewError
from backends.permissions import prepare_import_command


class SecretFreeDockerBackend(DockerSandboxBackend):
    """核心接入，不代替服务端批准、硬磁盘、资源账本与最终版本 CAS。"""

    def __init__(self, view, *, eligible, on_edit_started=None, on_edit_finished=None, **kwargs):
        view._qualified(eligible)
        self.view = view
        self.eligible = eligible
        self._edit_lock = threading.RLock()
        self._on_edit_started = on_edit_started
        self._on_edit_finished = on_edit_finished
        super().__init__(view.root, **kwargs)

    def _resolve_path(self, file_path):
        self.view._qualified(self.eligible)
        return super()._resolve_path(file_path)

    def _secret_registry(self):
        registry = super()._secret_registry()
        for value in self.view._secrets:
            registry._remember(value)
        return registry

    def _start_edit(self):
        if self._on_edit_started:
            self._on_edit_started()

    def _finish_edit(self, relative, result):
        if not result.error:
            self.view.record_edit(relative, eligible=self.eligible)
        if self._on_edit_finished:
            self._on_edit_finished(self.view)
        return result

    def _editable(self, file_path):
        self.view._qualified(self.eligible)
        target = self._resolve_path(file_path)
        relative = target.relative_to(self.workspace).as_posix()
        if self.view._protected(relative):
            raise ViewError('VIEW_PROTECTED_PATH')
        self.view.require_editable(relative, eligible=self.eligible)
        return relative

    def write(self, file_path, content):
        with self._edit_lock:
            try:
                relative = self._editable(file_path)
            except ViewError as error:
                return WriteResult(error=str(error))
            self._start_edit()
            return self._finish_edit(relative, super().write(file_path, content))

    def edit(self, file_path, old_string, new_string, replace_all=False):
        with self._edit_lock:
            try:
                relative = self._editable(file_path)
            except ViewError as error:
                return EditResult(error=str(error))
            self._start_edit()
            return self._finish_edit(relative, super().edit(file_path, old_string, new_string, replace_all))

    def delete(self, file_path):
        with self._edit_lock:
            try:
                relative = self._editable(file_path)
            except ViewError as error:
                return DeleteResult(error=str(error))
            self._start_edit()
            return self._finish_edit(relative, super().delete(file_path))

    def upload_files(self, files):
        results = []
        with self._edit_lock:
            for path, content in files:
                try:
                    relative = self._editable(path)
                except ViewError:
                    results.append(FileUploadResponse(path=path, error='permission_denied'))
                    continue
                self._start_edit()
                result = self._finish_edit(relative, super().upload_files([(path, content)])[0])
                results.append(result)
        return results

    def _prepare_command(self, command):
        return prepare_import_command(command, self.workspace)

    def execute(self, command, *, timeout=None):
        with self._edit_lock:
            self.view._qualified(self.eligible)
            return super().execute(command, timeout=timeout)

    def close(self):
        # 结束后必须真正停止执行副本里的进程，才允许平台检查待合入文件。
        self.manager.close(self.session_id)
        result = self.manager._docker('ps', '-a', '--filter', 'id=' + self.record.container_id,
                                     '--format', '{{.ID}}', check=False)
        if result.returncode != 0 or result.stdout.strip():
            raise SandboxError('执行副本的容器清理未确认，不能发布代码。')
