"""导入动作的固定协议；执行端与平台各自从实际视图重算，不信任提交的摘要。"""
from __future__ import annotations

import base64
from collections import Counter
import hashlib
import json
from pathlib import PurePosixPath
import re
import shlex

from backends.permissions import prepare_import_command
from backends.secret_free_view import ViewError
from backends.workspace_secrets import WorkspaceSecrets
from core.masking import _BEARER, _ENV_ASSIGN, _GENERIC_KEY, _SECRET_NAME

VERSION = 'import-action-v1'
HEX = re.compile('[0-9a-f]{64}')
FIELDS = {
    'write': {'path', 'content'},
    'edit': {'path', 'oldString', 'newString', 'replaceAll'},
    'delete': {'path'},
    'upload_files': {'files'},
    'execute': {'argv', 'cwd', 'bindings'},
}
MAX_ACTION_BYTES = 1 << 20


def _fail():
    raise ViewError('IMPORT_ACTION_INVALID')


def canonical_bytes(value):
    """递归字典序、UTF-8、无空白；只接受跨语言能精确表示的整数。"""
    def check(item):
        if item is None or type(item) is bool:
            return
        if type(item) is int:
            if not 0 <= item <= 2 ** 53:
                _fail()
        elif isinstance(item, str):
            if any(0xD800 <= ord(char) <= 0xDFFF for char in item):
                _fail()
        elif isinstance(item, list):
            for child in item:
                check(child)
        elif isinstance(item, dict):
            for key, child in item.items():
                if not isinstance(key, str):
                    _fail()
                check(key)
                check(child)
        else:
            _fail()
    check(value)
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    if len(raw) > MAX_ACTION_BYTES:
        raise ViewError('IMPORT_ACTION_LIMIT')
    return raw


def action_hash(action):
    return hashlib.sha256(canonical_bytes(action)).hexdigest()


def normalized_path(name):
    if not isinstance(name, str) or not name or len(name) > 4096 or '\\' in name or ':' in name \
            or any(ord(char) < 32 for char in name):
        _fail()
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or path.as_posix() in ('', '.'):
        _fail()
    return path.as_posix()


def _safe(view, raw, *, markers=False):
    # 先拒绝，不能把替换后的秘密当作获批动作，也不能把原秘密送进事件。
    registry = WorkspaceSecrets(view.root, view._protected)
    for value in view._secrets:
        registry._remember(value)
    if any(value.encode() in raw for value in registry.values):
        raise ViewError('IMPORT_ACTION_SECRET')
    text = raw.decode('utf-8', errors='replace')
    if _GENERIC_KEY.search(text) or _BEARER.search(text) or b'-----BEGIN ' in raw:
        raise ViewError('IMPORT_ACTION_SECRET')
    for match in _ENV_ASSIGN.finditer(text):
        value = match.group(3).strip('"\'')
        if _SECRET_NAME.search(match.group(2)) and value and not re.fullmatch('__GLYAHH_SECRET_[0-9a-f]{32}__', value):
            raise ViewError('IMPORT_ACTION_SECRET')
    if not markers and view.MARKER.search(raw):
        raise ViewError('VIEW_SECRET_BINDING')


def _content(view, name, raw, before):
    _safe(view, raw, markers=True)
    bindings = view._bindings.get(name, {})
    if set(view.MARKER.findall(raw)) != set(bindings) \
            or any(raw.count(marker) != count for marker, (_, count) in bindings.items()):
        raise ViewError('VIEW_SECRET_BINDING')
    def lines(data):
        return Counter(line for line in data.split(b'\n') if any(marker in line for marker in bindings))
    if lines(raw) != lines(before):
        raise ViewError('VIEW_SECRET_BINDING')
    if len(raw) > 16 << 20:
        raise ViewError('IMPORT_ACTION_LIMIT')


def build_action(view, state_hash, tool, parameters, *, eligible):
    """原硬校验后冻结准确参数、全部改动前置文件与命令工程输入。"""
    canonical_bytes(parameters)
    if not isinstance(state_hash, str) or not HEX.fullmatch(state_hash) or tool not in FIELDS \
            or not isinstance(parameters, dict) or set(parameters) != FIELDS[tool]:
        _fail()
    view._qualified(eligible)
    params = json.loads(canonical_bytes(parameters))
    inventory = view._inventory(view.root, view.max_files, view.max_bytes, eligible)
    def summary(name):
        name = normalized_path(name)
        _safe(view, name.encode())
        target = view._path(view.root / name)
        if target.is_dir():
            raise ViewError('VIEW_DIRECTORY_EDIT')
        value = inventory[0].get(name)
        return {'path': name, 'exists': value is not None, 'bytes': value[1] if value else 0,
                'sha256': value[0] if value else None}
    preconditions = []
    if tool == 'execute':
        if params['cwd'] != '.' or not isinstance(params['argv'], list) or not params['argv'] \
                or any(not isinstance(token, str) for token in params['argv']) or not isinstance(params['bindings'], list):
            _fail()
        argv = shlex.split(prepare_import_command(shlex.join(params['argv']), view.root))
        if argv != params['argv']:
            _fail()
        for token in argv:
            _safe(view, token.encode())
        # 非产物、非秘密的全部工程文件，比仅按扩展名找脚本更严格；新增文件也改变绑定。
        params['bindings'] = [summary(name) for name in sorted(inventory[0])
                              if not view._derived(name) and not view._protected(name)]
        for binding in params['bindings']:
            name = binding['path']
            expected = view._authorized.get(name, view._baseline[0].get(name))
            if inventory[0][name] != expected:
                raise ViewError('VIEW_UNAUTHORIZED_CHANGE')
    else:
        if tool == 'upload_files':
            if not isinstance(params['files'], list) or not params['files']:
                _fail()
            entries = params['files']
        else:
            entries = [params]
        names = set()
        for entry in entries:
            if not isinstance(entry, dict) or (tool == 'upload_files' and set(entry) != {'path', 'contentBase64'}):
                _fail()
            name = normalized_path(entry.get('path'))
            if name != entry['path'] or name in names:
                _fail()
            names.add(name)
            view.require_editable(name, eligible=eligible)
            preconditions.append(summary(name))
            target = view._path(view.root / name)
            before = target.read_bytes() if target.exists() else b''
            if tool == 'delete':
                if view._bindings.get(name):
                    raise ViewError('VIEW_SECRET_BINDING')
                if not target.exists():
                    raise ViewError('IMPORT_ACTION_MISSING_FILE')
            elif tool == 'edit':
                if not isinstance(params['oldString'], str) or not params['oldString'] \
                        or not isinstance(params['newString'], str) or type(params['replaceAll']) is not bool:
                    _fail()
                _safe(view, params['oldString'].encode(), markers=True)
                _safe(view, params['newString'].encode(), markers=True)
                # 与实际 SDK 的通用换行读取/替换完全一致。
                text = before.decode('utf-8').replace('\r\n', '\n').replace('\r', '\n')
                old = params['oldString'].replace('\r\n', '\n').replace('\r', '\n')
                new = params['newString'].replace('\r\n', '\n').replace('\r', '\n')
                count = text.count(old)
                if not count or (count != 1 and not params['replaceAll']):
                    raise ViewError('IMPORT_ACTION_EDIT_NOT_UNIQUE')
                after = text.replace(old, new, -1 if params['replaceAll'] else 1).encode()
                _content(view, name, after, before.replace(b'\r\n', b'\n').replace(b'\r', b'\n'))
            else:
                if tool == 'write':
                    if not isinstance(params['content'], str):
                        _fail()
                    raw = params['content'].encode()
                else:
                    if not isinstance(entry['contentBase64'], str):
                        _fail()
                    try:
                        raw = base64.b64decode(entry['contentBase64'], validate=True)
                    except ValueError:
                        _fail()
                    if base64.b64encode(raw).decode() != entry['contentBase64']:
                        _fail()
                _content(view, name, raw, before)
    action = {'version': VERSION, 'tool': tool, 'viewStateHash': state_hash,
              'parameters': params, 'preconditions': sorted(preconditions, key=lambda value: value['path'])}
    canonical_bytes(action)
    view._qualified(eligible)
    return action


def verify_action(view, state_hash, action, *, eligible):
    canonical_bytes(action)
    if not isinstance(action, dict) or set(action) != {'version', 'tool', 'viewStateHash', 'parameters', 'preconditions'} \
            or action['version'] != VERSION or action['viewStateHash'] != state_hash:
        _fail()
    expected = build_action(view, state_hash, action['tool'], action['parameters'], eligible=eligible)
    if canonical_bytes(action) != canonical_bytes(expected):
        raise ViewError('IMPORT_ACTION_CHANGED')
    return expected


def action_inspection(action):
    """平台风险底线和同类范围；Java仍需按冻结模式独立决定。"""
    tool, params = action['tool'], action['parameters']
    names = [value['path'] for value in action['preconditions']]
    high = tool in {'execute', 'delete', 'upload_files'} or any(
        name.startswith('.') or PurePosixPath(name).name.lower().startswith(('readme', 'config', 'application', 'package', 'requirements', 'compose'))
        or PurePosixPath(name).name in {'pyproject.toml', 'pom.xml', 'Dockerfile', 'uv.lock'}
        or name.startswith(('scripts/', 'migrations/')) or '/migration/' in name
        or PurePosixPath(name).suffix in {'.sh', '.ps1', '.bat', '.cmd', '.yml', '.yaml', '.lock', '.sql'} for name in names)
    risk = 'HIGH' if high else 'ORDINARY'
    scope = {'tool': tool, 'risk': risk, 'objects': names if high else sorted({str(PurePosixPath(name).parent) for name in names})}
    if tool == 'execute':
        scope['command'] = params
    title = {'write': '写入文件', 'edit': '修改文件', 'delete': '删除文件', 'upload_files': '上传文件', 'execute': '执行命令'}[tool]
    return {'riskFloor': risk, 'scopeKey': hashlib.sha256(canonical_bytes(scope)).hexdigest(),
            'display': {'title': title, 'object': ', '.join(names) if names else params['argv'][0],
                        'reason': '命令或敏感工程变更需独立确认' if high else '普通文件修改'}}
