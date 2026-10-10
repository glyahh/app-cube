"""平台侧的秘密隔离副本核心；不授予执行权限，也不直接覆盖应用工作区。"""
from __future__ import annotations

import hashlib
import base64
import errno
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import time

from backends.secret_paths import secret_name
from backends.workspace_secrets import WorkspaceSecrets
from core.masking import _ENV_ASSIGN, _SECRET_NAME, _GENERIC_KEY


class ViewError(RuntimeError):
    """只返回稳定代码，不将文件内容或秘密附在异常中。"""


class SecretFreeView:
    MARKER = re.compile(rb'__GLYAHH_SECRET_[0-9a-f]{32}__')
    CODE = frozenset(('.py', '.java', '.js', '.ts', '.jsx', '.tsx', '.vue', '.go', '.rs', '.c', '.cpp', '.cs', '.kt'))
    STRINGS = re.compile(r'''("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')''', re.DOTALL)

    @staticmethod
    def _protected(path):
        return '.git' in Path(path).parts or secret_name(path)

    @staticmethod
    def _qualified(eligible):
        if not callable(eligible) or eligible() is not True:
            raise ViewError('VIEW_REVOKED')

    @staticmethod
    def _deadline(eligible):
        deadline = time.monotonic() + 60
        def qualified():
            if time.monotonic() > deadline:
                raise ViewError('VIEW_TIMEOUT')
            return eligible() is True
        return qualified

    @staticmethod
    def _path(path):
        path = Path(path).absolute()
        for part in (path, *path.parents):
            if part.is_symlink() or getattr(part, 'is_junction', lambda: False)():
                raise ViewError('VIEW_LINK')
        return path

    @classmethod
    def _inventory(cls, root, max_files, max_bytes, eligible):
        root = cls._path(root)
        if not root.is_dir():
            raise ViewError('VIEW_MISSING')
        files, directories = {}, {'.'}
        size = count = 0
        def fail(_):
            raise ViewError('VIEW_IO')
        try:
            for directory, folders, names in os.walk(root, onerror=fail, followlinks=False):
                cls._qualified(eligible)
                for name in (*folders, *names):
                    path = cls._path(Path(directory) / name)
                    relative = path.relative_to(root).as_posix()
                    count += 1
                    if count > max_files:
                        raise ViewError('VIEW_FILE_LIMIT')
                    attrs = path.lstat()
                    if stat.S_ISDIR(attrs.st_mode):
                        directories.add(relative)
                        continue
                    if not stat.S_ISREG(attrs.st_mode) or attrs.st_nlink > 1:
                        raise ViewError('VIEW_LINK_OR_SPECIAL_FILE')
                    size += attrs.st_size
                    if size > max_bytes:
                        raise ViewError('VIEW_BYTE_LIMIT')
                    digest = hashlib.sha256()
                    read_bytes = 0
                    with path.open('rb') as stream:
                        while block := stream.read(1 << 20):
                            cls._qualified(eligible)
                            read_bytes += len(block)
                            if read_bytes > attrs.st_size:
                                raise ViewError('VIEW_SOURCE_CHANGED')
                            digest.update(block)
                    end = path.lstat()
                    if (attrs.st_size, attrs.st_mtime_ns, attrs.st_ino) != (end.st_size, end.st_mtime_ns, end.st_ino):
                        raise ViewError('VIEW_SOURCE_CHANGED')
                    files[relative] = (digest.hexdigest(), attrs.st_size, stat.S_IMODE(attrs.st_mode))
        except OSError as error:
            raise ViewError('VIEW_IO') from error
        return files, directories

    @staticmethod
    def _hash(state):
        files, directories = state
        return hashlib.sha256(json.dumps({'files': files, 'directories': sorted(directories)},
                                       sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    @staticmethod
    def _new(root, source):
        root = SecretFreeView._path(root)
        if root.is_relative_to(source) or source.is_relative_to(root):
            raise ViewError('VIEW_OVERLAP')
        if root.exists():
            raise ViewError('VIEW_DESTINATION_EXISTS')
        root.mkdir(mode=0o700, parents=False)
        return root

    @classmethod
    def create(cls, source, root, *, app_id, owner_id, run_id, eligible,
               max_files=100_000, max_bytes=1 << 30):
        # 1. 核对业务身份与完整源版本。这里不接受 Prompt 充当资格回调。
        if any(not isinstance(value, str) or not re.fullmatch(r'[1-9][0-9]{0,18}', value)
               or int(value) > 9223372036854775807 for value in (app_id, owner_id, run_id)):
            raise ViewError('VIEW_IDENTITY')
        if not isinstance(max_files, int) or not isinstance(max_bytes, int) or max_files < 1 or max_bytes < 1:
            raise ViewError('VIEW_LIMIT')
        cls._qualified(eligible)
        eligible = cls._deadline(eligible)
        source = cls._path(source)
        original = cls._inventory(source, max_files, max_bytes, eligible)
        registry = WorkspaceSecrets(source, secret_name)
        registry.refresh()
        # 工程中的明确密钥常量也要隔离，不只处理 .env；不把正常 tokenizer 表达式当秘密。
        for name in original[0]:
            if cls._protected(name):
                continue
            raw = source.joinpath(name).read_bytes()
            try:
                text = raw.decode('utf-8')
            except UnicodeDecodeError:
                continue
            for match in _ENV_ASSIGN.finditer(text):
                if _SECRET_NAME.search(match.group(2)) and match.group(3).startswith(('"', "'")):
                    value = match.group(3)
                    try:
                        value = json.loads(value) if value.startswith('"') else value[1:-1]
                    except json.JSONDecodeError:
                        raise ViewError('VIEW_SECRET_LITERAL')
                    registry._remember(value)
            for match in _GENERIC_KEY.finditer(text):
                registry._remember(match.group(0))
        instance = cls()
        instance.source, instance.app_id, instance.owner_id, instance.run_id = source, app_id, owner_id, run_id
        instance.max_files, instance.max_bytes = max_files, max_bytes
        instance._original = original
        instance.source_hash = cls._hash(original)
        instance._secrets = sorted(registry.values, key=len, reverse=True)
        instance._markers = {}
        instance._bindings = {}
        instance._authorized = {}
        instance.retained_outputs = {}
        instance.root = cls._new(root, source)
        try:
            # 2. 敏感路径完全不挂入副本；其他文件和空目录保持，不静默丢弃大文件。
            for directory in sorted(original[1] - {'.'}, key=lambda value: (value.count('/'), value)):
                if not cls._protected(directory):
                    instance.root.joinpath(directory).mkdir(exist_ok=True)
            for name, metadata in original[0].items():
                cls._qualified(eligible)
                if cls._protected(name):
                    continue
                if any(value in name for value in instance._secrets if len(value) >= 6):
                    raise ViewError('VIEW_SECRET_IN_NAME')
                raw = source.joinpath(name).read_bytes()
                if hashlib.sha256(raw).hexdigest() != metadata[0]:
                    raise ViewError('VIEW_SOURCE_CHANGED')
                safe, bindings = instance._sanitize(raw, name)
                instance._bindings[name] = bindings
                target = instance.root.joinpath(name)
                target.write_bytes(safe)
                target.chmod(metadata[2])
            if cls._inventory(source, max_files, max_bytes, eligible) != original:
                raise ViewError('VIEW_SOURCE_CHANGED')
            instance._baseline = cls._inventory(instance.root, max_files, max_bytes, eligible)
            instance.view_hash = cls._hash(instance._baseline)
            cls._qualified(eligible)
            return instance
        except BaseException:
            shutil.rmtree(instance.root)
            raise

    def _sanitize(self, raw, name):
        if self.MARKER.search(raw):
            raise ViewError('VIEW_RESERVED_MARKER')
        bindings = {}
        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError:
            if any(value.encode() in raw for value in self._secrets if len(value) >= 6):
                raise ViewError('VIEW_SECRET_IN_BINARY')
            return raw, bindings

        def substitute(text):
            values = [value for value in self._secrets if value in text]
            if not values:
                return text
            def replace(match):
                value = match.group(0)
                marker = self._markers.setdefault(value, '__GLYAHH_SECRET_' + secrets.token_hex(16) + '__')
                old_count = bindings.get(marker.encode(), (b'', 0))[1]
                bindings[marker.encode()] = (value.encode(), old_count + 1)
                return marker
            # 只处理原文本；不能让短秘密再次替换已生成的占位符。
            patterns = [re.escape(value) if len(value) >= 6 else r'(?<![A-Za-z0-9_])' + re.escape(value) + r'(?![A-Za-z0-9_])'
                        for value in values]
            return re.sub('|'.join(patterns), replace, text)

        # 代码只替换字符串内的值，避免把同名函数/变量改坏；无法安全处理时明确拒绝。
        if Path(name).suffix.lower() in self.CODE:
            text = self.STRINGS.sub(lambda match: substitute(match.group(0)), text)
            outside = self.STRINGS.sub('', raw.decode('utf-8'))
            if any(value in outside for value in self._secrets if len(value) >= 6):
                raise ViewError('VIEW_SECRET_LITERAL')
        else:
            text = substitute(text)
        return text.encode(), bindings

    def save_private_state(self, path, *, eligible, replace=False):
        """仅平台保存恢复所需映射；目标不得位于模型挂载的视图内。"""
        self._qualified(eligible)
        target = self._path(path)
        if target.is_relative_to(self.root) or self.root.is_relative_to(target):
            raise ViewError('VIEW_PRIVATE_STATE_OVERLAP')
        state = {
            'formatVersion': 1, 'appId': self.app_id, 'ownerId': self.owner_id, 'runId': self.run_id,
            'source': str(self.source), 'root': str(self.root), 'sourceHash': self.source_hash,
            'viewHash': self.view_hash, 'maxFiles': self.max_files, 'maxBytes': self.max_bytes,
            'original': [self._original[0], sorted(self._original[1])],
            'baseline': [self._baseline[0], sorted(self._baseline[1])],
            'secrets': self._secrets, 'markers': self._markers,
            'bindings': {name: {marker.decode(): [base64.b64encode(value).decode(), count]
                               for marker, (value, count) in bindings.items()}
                         for name, bindings in self._bindings.items()},
            'authorized': self._authorized,
        }
        data = json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
        if len(data) > 32 << 20:
            raise ViewError('VIEW_PRIVATE_STATE_LIMIT')
        try:
            temporary = self._path(str(target) + '.pending') if replace else target
            with temporary.open('xb') as stream:
                temporary.chmod(0o600)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            self._qualified(eligible)
            if replace:
                os.replace(temporary, target)
                if os.name != 'nt':
                    descriptor = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
                    try:
                        os.fsync(descriptor)
                    finally:
                        os.close(descriptor)
        except OSError as error:
            if error.errno == errno.ENOSPC:
                raise ViewError('VIEW_DISK_QUOTA') from error
            raise ViewError('VIEW_PRIVATE_STATE_IO') from error
        return hashlib.sha256(data).hexdigest()

    @classmethod
    def load_private_state(cls, path, *, state_hash, root, app_id, owner_id, run_id, eligible):
        """仅平台恢复已核对摘要的JSON映射；不接受模型提供的原配置或对象。"""
        cls._qualified(eligible)
        if not isinstance(state_hash, str) or not re.fullmatch('[0-9a-f]{64}', state_hash):
            raise ViewError('VIEW_PRIVATE_STATE_HASH')
        target, root = cls._path(path), cls._path(root)
        if target.is_relative_to(root) or root.is_relative_to(target):
            raise ViewError('VIEW_PRIVATE_STATE_OVERLAP')
        try:
            attrs = target.lstat()
            if not stat.S_ISREG(attrs.st_mode) or attrs.st_nlink != 1 or attrs.st_size > 32 << 20:
                raise ViewError('VIEW_PRIVATE_STATE_LIMIT')
            if os.name != 'nt' and (attrs.st_uid != os.geteuid() or stat.S_IMODE(attrs.st_mode) != 0o600):
                raise ViewError('VIEW_PRIVATE_STATE_PERMISSION')
            with target.open('rb') as stream:
                data = stream.read((32 << 20) + 1)
            if len(data) > 32 << 20 or hashlib.sha256(data).hexdigest() != state_hash:
                raise ViewError('VIEW_PRIVATE_STATE_HASH')
            state = json.loads(data)
            if (state['formatVersion'], state['appId'], state['ownerId'], state['runId'], state['root']) != \
                    (1, app_id, owner_id, run_id, str(root)):
                raise ViewError('VIEW_IDENTITY')
            if any(not isinstance(value, str) or not re.fullmatch('[1-9][0-9]{0,18}', value)
                   or int(value) > 9223372036854775807 for value in (app_id, owner_id, run_id)):
                raise ViewError('VIEW_IDENTITY')
            instance = cls()
            instance.root, instance.source = root, cls._path(state['source'])
            if root.is_relative_to(instance.source) or instance.source.is_relative_to(root):
                raise ViewError('VIEW_OVERLAP')
            instance.app_id, instance.owner_id, instance.run_id = app_id, owner_id, run_id
            instance.max_files, instance.max_bytes = state['maxFiles'], state['maxBytes']
            if type(instance.max_files) is not int or type(instance.max_bytes) is not int \
                    or not 1 <= instance.max_files <= 100_000 or not 1 <= instance.max_bytes <= 8 << 30:
                raise ViewError('VIEW_LIMIT')
            instance._original = ({k: tuple(v) for k, v in state['original'][0].items()}, set(state['original'][1]))
            instance._baseline = ({k: tuple(v) for k, v in state['baseline'][0].items()}, set(state['baseline'][1]))
            instance.source_hash, instance.view_hash = state['sourceHash'], state['viewHash']
            if cls._hash(instance._original) != instance.source_hash or cls._hash(instance._baseline) != instance.view_hash:
                raise ViewError('VIEW_PRIVATE_STATE_HASH')
            instance._secrets, instance._markers = state['secrets'], state['markers']
            instance._bindings = {name: {marker.encode(): (base64.b64decode(value, validate=True), count)
                                       for marker, (value, count) in bindings.items()}
                                  for name, bindings in state['bindings'].items()}
            instance._authorized = {k: None if v is None else tuple(v) for k, v in state['authorized'].items()}
            instance.retained_outputs = {}
            if cls._inventory(instance.source, instance.max_files, instance.max_bytes, eligible) != instance._original:
                raise ViewError('VIEW_SOURCE_CHANGED')
            cls._qualified(eligible)
            return instance
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            raise ViewError('VIEW_PRIVATE_STATE_INVALID') from error

    def require_editable(self, relative, *, eligible):
        """工具修改前核对基线，防止把执行器留下的源码差异一并授权。"""
        self._qualified(eligible)
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts or relative in ('', '.') or self._protected(relative):
            raise ViewError('VIEW_PROTECTED_PATH')
        if self._derived(relative):
            raise ViewError('VIEW_DERIVED_PATH')
        state = self._inventory(self.root, self.max_files, self.max_bytes, eligible)
        name = path.as_posix()
        expected = self._authorized.get(name, self._baseline[0].get(name))
        if state[0].get(name) != expected:
            raise ViewError('VIEW_UNAUTHORIZED_CHANGE')

    def record_edit(self, relative, *, eligible):
        """只由通过权限检查的文件工具调用；执行器不能根据最终差异替脚本补授权。"""
        self._qualified(eligible)
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts or relative in ('', '.') or self._protected(relative):
            raise ViewError('VIEW_PROTECTED_PATH')
        if self._derived(relative):
            raise ViewError('VIEW_DERIVED_PATH')
        target = self._path(self.root / path)
        if not target.is_relative_to(self.root):
            raise ViewError('VIEW_OVERLAP')
        if not target.exists():
            self._authorized[path.as_posix()] = None
            return
        if target.is_dir():
            raise ViewError('VIEW_DIRECTORY_EDIT')
        state = self._inventory(self.root, self.max_files, self.max_bytes, eligible)
        self._authorized[path.as_posix()] = state[0][path.as_posix()]

    @staticmethod
    def _derived(path):
        return any(part in {'.git', '__pycache__', 'node_modules', '.venv', 'venv', '.pytest_cache',
                            '.mypy_cache', '.ruff_cache', '.cache', 'dist', 'target', 'build', 'coverage'}
                   for part in Path(path).parts)

    def candidate(self, root, *, eligible):
        # 3. 仅产出待发布候选；调用方仍须核对 owner、任务、版本并在共同写锁下最终发布。
        self._qualified(eligible)
        eligible = self._deadline(eligible)
        current = self._inventory(self.source, self.max_files, self.max_bytes, eligible)
        if current != self._original:
            raise ViewError('VIEW_SOURCE_CHANGED')
        edited = self._inventory(self.root, self.max_files, self.max_bytes, eligible)
        material = {}
        for name in edited[0]:
            if secret_name(name) and not self._derived(name):
                raise ViewError('VIEW_PROTECTED_PATH')
            if name not in self._authorized:
                if edited[0][name] != self._baseline[0].get(name):
                    if name in self._baseline[0] and not self._derived(name):
                        raise ViewError('VIEW_UNAUTHORIZED_CHANGE')
                    self.retained_outputs[name] = edited[0][name]
                continue
            if self._authorized[name] != edited[0][name]:
                raise ViewError('VIEW_EDIT_CHANGED_AFTER_AUTHORIZATION')
            raw = self.root.joinpath(name).read_bytes()
            if hashlib.sha256(raw).hexdigest() != edited[0][name][0]:
                raise ViewError('VIEW_EDIT_CHANGED')
            bindings = self._bindings.get(name, {})
            markers = set(self.MARKER.findall(raw))
            if markers != set(bindings) or any(raw.count(marker) != count for marker, (_, count) in bindings.items()):
                raise ViewError('VIEW_SECRET_BINDING')
            if bindings:
                # 原字段与秘密所在整行必须保留；仅占位符数量不变不能证明绑定未被调换。
                original = self.source.joinpath(name).read_bytes()
                if hashlib.sha256(original).hexdigest() != self._original[0][name][0]:
                    raise ViewError('VIEW_SOURCE_CHANGED')
                baseline, expected_bindings = self._sanitize(original, name)
                if expected_bindings != bindings or hashlib.sha256(baseline).hexdigest() != self._baseline[0][name][0]:
                    raise ViewError('VIEW_SECRET_BINDING')
                def bound_lines(content):
                    from collections import Counter
                    return Counter(line for line in content.split(b'\n') if any(marker in line for marker in bindings))
                if bound_lines(raw) != bound_lines(baseline):
                    raise ViewError('VIEW_SECRET_BINDING')
            if edited[0][name] == self._baseline[0].get(name):
                continue
            for marker, (value, _) in bindings.items():
                raw = raw.replace(marker, value)
            material[name] = raw
        for name in self._baseline[0]:
            if name not in edited[0]:
                if name not in self._authorized:
                    if not self._derived(name):
                        raise ViewError('VIEW_UNAUTHORIZED_CHANGE')
                elif self._authorized[name] is not None:
                    raise ViewError('VIEW_EDIT_CHANGED_AFTER_AUTHORIZATION')
                elif self._bindings.get(name):
                    raise ViewError('VIEW_SECRET_BINDING')
        destination = self._path(root)
        if destination.is_relative_to(self.root) or self.root.is_relative_to(destination):
            raise ViewError('VIEW_OVERLAP')
        target = self._new(destination, self.source)
        try:
            directories = {directory for directory in self._original[1]
                           if self._authorized.get(directory, False) is not None}
            for name in material:
                directories.update(parent.as_posix() for parent in Path(name).parents)
            for directory in sorted(directories, key=lambda value: (value.count('/'), value)):
                if directory != '.':
                    target.joinpath(directory).mkdir(exist_ok=True)
            for name, metadata in self._original[0].items():
                self._qualified(eligible)
                if name in self._authorized and self._authorized[name] is None:
                    continue
                output = target.joinpath(name)
                original = self.source.joinpath(name).read_bytes()
                if hashlib.sha256(original).hexdigest() != metadata[0]:
                    raise ViewError('VIEW_SOURCE_CHANGED')
                output.write_bytes(material.pop(name, original))
                output.chmod(edited[0][name][2] if name in self._authorized else metadata[2])
            for name, raw in material.items():
                self._qualified(eligible)
                target.joinpath(name).write_bytes(raw)
                target.joinpath(name).chmod(edited[0][name][2])
            if self._inventory(self.source, self.max_files, self.max_bytes, eligible) != current:
                raise ViewError('VIEW_SOURCE_CHANGED')
            if self._inventory(self.root, self.max_files, self.max_bytes, eligible) != edited:
                raise ViewError('VIEW_EDIT_CHANGED')
            self._inventory(target, self.max_files, self.max_bytes, eligible)
            self._qualified(eligible)
            return target
        except BaseException:
            shutil.rmtree(target)
            raise
