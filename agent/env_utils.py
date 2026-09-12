"""`.env` 加载。

刻意不用 python-dotenv：这个格式简单到不值得为它加一个依赖。
语义参照参考项目 —— **空值不覆盖**，已存在的非空环境变量优先于文件。
"""

from __future__ import annotations

import os
from pathlib import Path

# 仓库根。.env 放在这里，Java 和 Python 读同一份，避免两边配置漂移。
AGENT_DIR = Path(__file__).resolve().parent
REPO_ROOT = AGENT_DIR.parent
DEFAULT_ENV_FILE = REPO_ROOT / ".env"


def env_file_path() -> Path:
    """实际要读的 .env。

    `AGENT_ENV_FILE` 可以覆盖 —— 测试用它指向一个不存在的文件，
    从而拿到「无密钥」的确定性环境，不必真的去调模型。
    """
    override = os.environ.get("AGENT_ENV_FILE", "").strip()
    return Path(override) if override else DEFAULT_ENV_FILE


def load_env_file(path: Path | None = None) -> None:
    """把 .env 里的非空键值写进 os.environ。已存在的非空值不覆盖。"""
    target = path or env_file_path()
    if not target.exists():
        return
    for raw in target.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and value and not os.environ.get(key, "").strip():
            os.environ[key] = value


def get_env(name: str, default: str = "") -> str:
    """读环境变量；每次调用前补一次 .env（幂等，保证子进程晚加载也能拿到）。"""
    load_env_file()
    return os.environ.get(name, default)


_TRUE = {"1", "true", "yes", "on"}
_FALSE = {"0", "false", "no", "off", ""}


def get_env_bool(name: str, default: bool = False) -> bool:
    """读布尔环境变量。

    不能写成 `bool(get_env(name))` —— 非空字符串一律为真，`"false"` 会被读成 `True`。
    无法识别的值回落到 default 而不是抛错：配置写错不该让进程起不来。
    """
    raw = get_env(name, "").strip().lower()
    if raw in _TRUE:
        return True
    if raw in _FALSE:
        return default if raw == "" else False
    return default



def require_env(name: str) -> str:
    value = get_env(name)
    if not value:
        raise RuntimeError(f"缺少必需的环境变量：{name}")
    return value
