"""每个 Backend 独立保存已知配置秘密，只用于输出遮盖，不发给模型。"""
from __future__ import annotations

import base64
import json
import os
import threading
from pathlib import Path
from urllib.parse import quote

from core.masking import _ENV_ASSIGN, _SECRET_NAME


class SecretBoundaryError(RuntimeError):
    """防护检查失败时明确阻止操作，不能返回未核对的原文。"""


class WorkspaceSecrets:
    MAX_ENTRIES = 100_000
    MAX_BYTES = 16 << 20
    MAX_VALUES = 4096

    def __init__(self, workspace: Path, sensitive):
        self.workspace = workspace
        self.sensitive = sensitive
        self.values: set[str] = set()
        self.lock = threading.RLock()

    def refresh(self):
        # 1. 有界检查当前配置；不跟随链接，也不读取工程外的文件。
        with self.lock:
            count = size = 0
            def walk_error(_):
                raise SecretBoundaryError('无法核对敏感配置，已阻止未经保护的工具输出。')
            try:
                for directory, folders, names in os.walk(self.workspace, onerror=walk_error, followlinks=False):
                    for name in (*folders, *names):
                        count += 1
                        if count > self.MAX_ENTRIES:
                            raise SecretBoundaryError('敏感配置核对超过文件数量限制，操作未继续。')
                        path = Path(directory) / name
                        relative = path.relative_to(self.workspace).as_posix()
                        if path.is_symlink():
                            raise SecretBoundaryError('工作区含未核对的链接，操作未继续。')
                        if name in folders or not (self.sensitive(relative) or name.lower() in
                                ('.env.example', '.env.sample', '.env.template')):
                            continue
                        if path.stat().st_size > self.MAX_BYTES - size:
                            raise SecretBoundaryError('敏感配置核对超过内容限制，操作未继续。')
                        with path.open('rb') as stream:
                            raw = stream.read(self.MAX_BYTES - size + 1)
                        size += len(raw)
                        if size > self.MAX_BYTES:
                            raise SecretBoundaryError('敏感配置核对超过内容限制，操作未继续。')
                        # 二进制密钥也登记整体编码，不能因 UTF-8 失败放弃防护。
                        # 先登记整份敏感文件的编码形态，覆盖脚本把配置整体读出后再编码的路径。
                        self._remember_bytes(raw)
                        try:
                            text = raw.decode('utf-8')
                        except UnicodeDecodeError:
                            self._remember_bytes(raw)
                            continue
                        structured = name.lower().startswith('.env') or path.suffix.lower() == '.json'
                        if not structured and text.strip():
                            self._remember(text.strip())
                        for match in _ENV_ASSIGN.finditer(text):
                            if not _SECRET_NAME.search(match.group(2)):
                                continue
                            value = match.group(3)
                            if value.startswith('"'):
                                try:
                                    value = json.loads(value)
                                except json.JSONDecodeError:
                                    value = value.strip('"')
                            elif value.startswith("'"):
                                value = value.strip("'")
                            self._remember(value)
            except OSError as error:
                raise SecretBoundaryError('无法核对敏感配置，操作未继续。') from error

    def _remember_bytes(self, raw: bytes):
        if not raw:
            return
        # 最多展开两层。无限递归会把登记集合变成资源消耗点；两层覆盖常见
        # base64/hex 管道，同时保留原始值由 _remember 负责登记。
        forms = {base64.b64encode(raw).decode(), raw.hex(), raw.hex().upper()}
        second = set()
        for value in forms:
            encoded = value.encode()
            second.update((base64.b64encode(encoded).decode(), encoded.hex(), encoded.hex().upper()))
        self.values.update(forms | second)
        if len(self.values) > self.MAX_VALUES:
            raise SecretBoundaryError('敏感配置值数量超过限制，操作未继续。')

    def _remember(self, value: str):
        if not value:
            return
        self._remember_bytes(value.encode())
        self.values.update((value, quote(value, safe=''), json.dumps(value, ensure_ascii=False)[1:-1]))
        if len(self.values) > self.MAX_VALUES:
            raise SecretBoundaryError('敏感配置值数量超过限制，操作未继续。')

    def mask(self, text: str) -> str:
        # 2. 长值先替换，防止短值打断完整匹配；保留同一 Backend 已删除/替换的旧配置值。
        with self.lock:
            for value in sorted(self.values, key=len, reverse=True):
                text = text.replace(value, '***')
        return text
