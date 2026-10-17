"""只挂秘密隔离副本；文件工具记录编辑资格，Shell 不获得业务代码合入权限。"""
from __future__ import annotations

import threading

from deepagents.backends.protocol import WriteResult, EditResult, DeleteResult, FileUploadResponse

from backends.docker_sandbox import DockerSandboxBackend, SandboxError
from backends.secret_free_view import ViewError
from backends.permissions import prepare_import_command


class SecretFreeDockerBackend(DockerSandboxBackend):
    """核心接入，不代替服务端批准、硬磁盘、资源账本与最终版本 CAS。"""

    def __init__(self, view, *, eligible, on_edit_started=None, on_edit_finished=None, permissions=None,
                 state_hash=None, **kwargs):
        view._qualified(eligible)
        self.view = view
        self.eligible = eligible
        self._edit_lock = threading.RLock()
        self._on_edit_started = on_edit_started
        self._on_edit_finished = on_edit_finished
        self._permissions = permissions
        self._state_hash = state_hash
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

    def _perform(self, tool, parameters, operation):
        if self._permissions is None:
            # 非正式平台测试保留原接口；正式容器必须配置服务端许可，不能绕过审批。
            if hasattr(self.manager, 'authorization_hash'):
                from core.import_permissions import ImportPermissionStopped
                raise ImportPermissionStopped('IMPORT_PERMISSION_REQUIRED')
            return operation()
        from backends.import_action import build_action
        return self._permissions.perform(
            lambda: build_action(self.view, self._state_hash(), tool, parameters, eligible=self.eligible), operation)

    def write(self, file_path, content):
        with self._edit_lock:
            try:
                relative = self._editable(file_path)
            except ViewError as error:
                return WriteResult(error=str(error))
            def operation():
                self._start_edit()
                return self._finish_edit(relative, super(SecretFreeDockerBackend, self).write(file_path, content))
            return self._perform('write', {'path': relative, 'content': content}, operation)

    def edit(self, file_path, old_string, new_string, replace_all=False):
        with self._edit_lock:
            try:
                relative = self._editable(file_path)
            except ViewError as error:
                return EditResult(error=str(error))
            def operation():
                self._start_edit()
                return self._finish_edit(relative, super(SecretFreeDockerBackend, self).edit(file_path, old_string, new_string, replace_all))
            return self._perform('edit', {'path': relative, 'oldString': old_string, 'newString': new_string,
                                         'replaceAll': replace_all}, operation)

    def delete(self, file_path):
        with self._edit_lock:
            try:
                relative = self._editable(file_path)
            except ViewError as error:
                return DeleteResult(error=str(error))
            def operation():
                self._start_edit()
                return self._finish_edit(relative, super(SecretFreeDockerBackend, self).delete(file_path))
            return self._perform('delete', {'path': relative}, operation)

    def upload_files(self, files):
        results = []
        with self._edit_lock:
            if self._permissions is None and hasattr(self.manager, 'authorization_hash'):
                from core.import_permissions import ImportPermissionStopped
                raise ImportPermissionStopped('IMPORT_PERMISSION_REQUIRED')
            if self._permissions is not None:
                import base64
                # 整批先校验并批准，禁止批准前半批后继续写入未批准文件。
                parameters = {'files': [{'path': self._editable(path), 'contentBase64': base64.b64encode(content).decode('ascii')}
                                        for path, content in files]}
                def operation():
                    output = []
                    for path, content in files:
                        relative = self._editable(path)
                        self._start_edit()
                        output.append(self._finish_edit(relative, super(SecretFreeDockerBackend, self).upload_files([(path, content)])[0]))
                    return output
                return self._perform('upload_files', parameters, operation)
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
            import shlex
            normalized = self._prepare_command(command)
            return self._perform('execute', {'argv': shlex.split(normalized), 'cwd': '.', 'bindings': []},
                                 lambda: super(SecretFreeDockerBackend, self).execute(normalized, timeout=timeout))

    def close(self):
        # 结束后必须真正停止执行副本里的进程，才允许平台检查待合入文件。
        self.manager.close(self.session_id)
        result = self.manager._docker('ps', '-a', '--filter', 'id=' + self.record.container_id,
                                     '--format', '{{.ID}}', check=False)
        if result.returncode != 0 or result.stdout.strip():
            raise SandboxError('执行副本的容器清理未确认，不能发布代码。')
