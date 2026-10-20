"""模型文件工具的秘密边界；不改写工程原文件，也不限制普通业务代码编辑。"""
from __future__ import annotations

import asyncio
import hashlib
from dataclasses import replace
from pathlib import Path, PurePosixPath

from deepagents.backends.protocol import ReadResult, GrepResult, FileDownloadResponse

from backends.sensitive_paths import SensitivePathGuard
from core.masking import mask
from backends.workspace_secrets import WorkspaceSecrets
from backends.secret_paths import secret_name


class SecretSafeFilesMixin:
    """直接继承协议方法，保留框架的类级能力探测与异步调用。"""

    @staticmethod
    def _secret_name(path: str) -> bool:
        return secret_name(path)

    def _secret_target(self, path: str) -> bool:
        if self._secret_name(path):
            return True
        # 按框架实际解析的目标再检查，不能用一个普通名称的链接绕过。
        target = self._resolve_path(path)
        return self._secret_name(target.as_posix())

    def _secret_registry(self):
        if not hasattr(self, '_workspace_secret_registry'):
            self._workspace_secret_registry = WorkspaceSecrets(self.workspace, self._secret_name)
        return self._workspace_secret_registry

    def _refresh_secrets(self):
        self._secret_registry().refresh()

    def _mask_output(self, text: str) -> str:
        self._refresh_secrets()
        return mask(self._secret_registry().mask(text))

    def _mask_file(self, text: str, path: str) -> str:
        code = PurePosixPath(path).suffix.lower() in (
            '.py', '.java', '.js', '.ts', '.jsx', '.tsx', '.vue', '.go', '.rs', '.c', '.cpp', '.cs', '.kt')
        # 源码里的 tokenizer = build() 是正常表达式，不能按环境赋值把业务代码藏掉。
        return mask(self._secret_registry().mask(text), source=code)

    def read(self, file_path: str, offset: int = 0, limit: int = 2000):
        try:
            if self._secret_target(file_path):
                return ReadResult(error='敏感配置仅供沙箱使用，模型不可读取。')
        except OSError:
            return ReadResult(error='读取路径无效。')
        self._refresh_secrets()
        result = super().read(file_path, offset, limit)
        if result.file_data and result.file_data.get('encoding') == 'utf-8':
            data = dict(result.file_data)
            content = self._mask_file(data['content'], file_path)
            # 同一 run 内重复读取同一范围时不再返回全文：模型每改一处就重读整份文件，
            # 会让上下文按文件体积线性膨胀。内容变化后哈希不同，自然失效。
            digests = getattr(self, '_read_digests', None)
            if digests is None:
                digests = self._read_digests = {}
            key = (PurePosixPath(file_path).as_posix(), offset, limit)
            digest = hashlib.sha256(content.encode('utf-8')).hexdigest()
            if digests.get(key) == digest:
                total = result.total_lines or result.end_line or 0
                return replace(result, file_data={
                    'content': (f'（该文件自上次读取后未变化，共 {total} 行。'
                                '为节省上下文这里不重复返回全文；'
                                '需要特定范围请用 offset/limit 重新读取。）'),
                    'encoding': 'utf-8'})
            digests[key] = digest
            data['content'] = content
            result = replace(result, file_data=data)
        return replace(result, error=mask(result.error) or None)

    def grep(self, pattern: str, path=None, glob=None, *, max_count=None, context_lines=0):
        try:
            if path and self._secret_target(path):
                return GrepResult(error='敏感配置仅供沙箱使用，模型不可搜索。')
        except (OSError, RuntimeError, ValueError):
            return GrepResult(error='搜索路径无效。')
        self._refresh_secrets()
        result = super().grep(pattern, path, glob, max_count=max_count, context_lines=context_lines)
        matches = []
        excluded = False
        for hit in result.matches or []:
            try:
                denied = self._secret_target(hit['path'])
            except (OSError, RuntimeError, ValueError):
                denied = True
            if denied:
                excluded = True
                continue
            safe = dict(hit)
            safe['text'] = self._mask_file(safe['text'], safe['path'])
            for name in ('context_before', 'context_after'):
                if name in safe:
                    safe[name] = [{**line, 'text': self._mask_file(line['text'], safe['path'])} for line in safe[name]]
            matches.append(safe)
        error = mask(result.error) or None
        if excluded:
            error = (error + ' ' if error else '') + '敏感配置匹配已排除。'
        return replace(result, matches=matches if result.matches is not None else None, error=error)

    async def agrep(self, pattern: str, path=None, glob=None, *, max_count=None, context_lines=0):
        return await asyncio.to_thread(self.grep, pattern, path, glob,
                                       max_count=max_count, context_lines=context_lines)

    def download_files(self, paths):
        self._refresh_secrets()
        responses = []
        for path in paths:
            try:
                denied = self._secret_target(path)
            except (OSError, RuntimeError, ValueError):
                denied = True
            if denied:
                responses.append(FileDownloadResponse(path=path, error='permission_denied'))
                continue
            response = super().download_files([path])[0]
            if response.content is not None:
                try:
                    text = response.content.decode('utf-8')
                except UnicodeDecodeError:
                    text = None
                if text is not None:
                    response = replace(response, content=self._mask_file(text, path).encode('utf-8'))
            responses.append(response)
        return responses
