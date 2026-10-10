"""平台复制和模型文件工具共用的敏感文件规则；不依赖模型框架。"""
from pathlib import PurePosixPath

from backends.sensitive_paths import SensitivePathGuard


def secret_name(path: str) -> bool:
    parts = PurePosixPath(path.replace('\\', '/')).parts
    name = parts[-1].lower() if parts else ''
    if any(part.lower() in ('keys', 'secrets', 'certs', 'ssl', '.ssh') for part in parts):
        return True
    if name in ('.env.example', '.env.sample', '.env.template'):
        return False
    return (name.startswith('.env') or name in SensitivePathGuard.EXCLUDED_FILE_NAMES
            or PurePosixPath(name).suffix.lower().lstrip('.') in
            ('pem', 'key', 'p12', 'pfx', 'jks', 'keystore', 'secret'))
