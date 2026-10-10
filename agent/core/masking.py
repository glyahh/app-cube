"""输出脱敏。

任何要进入事件流、日志、或回给模型的内容都先过一遍这里。

为什么必须有：`tool.finished` 的 `outputPreview` 里很容易带上模型回显的密钥、
shell 环境变量、或者 `git config` 里存的凭据。这些东西一旦进了事件流，
就会顺着 SSE 流到前端、进日志文件、再被别的轮次读回来。
"""

from __future__ import annotations

import os
import re

# 形如 sk-xxxx / LTAIxxxx 的密钥
_GENERIC_KEY = re.compile(r"\b(sk-[A-Za-z0-9_\-]{8,}|LTAI[A-Za-z0-9]{8,})\b")
# Bearer token
_BEARER = re.compile(r"(Bearer\s+)[A-Za-z0-9._\-]{8,}", re.I)
# KEY=value 形式的敏感赋值
_ENV_ASSIGN = re.compile(
    r'''(?<![A-Z0-9_])(["']?)([A-Z0-9_]+)\1\s*(?:=|:)\s*("[^"\r\n]*"?|'[^'\r\n]*'?|[^\s,;}]+)''',
    re.I,
)
_SECRET_NAME = re.compile(r"KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL", re.I)

# 环境变量里实际的密钥值，启动时抓一份，逐字替换
_SENSITIVE_ENV_NAMES = (
    "DEEPSEEK_API_KEY", "AGENT_MODEL_API_KEY", "AGENT_SUMMARIZER_API_KEY",
    "DASHSCOPE_API_KEY", "ZHIPU_API_KEY",
    "ALIYUN_OSS_ACCESS_KEY_ID", "ALIYUN_OSS_ACCESS_KEY_SECRET",
    "PEXELS_API_KEY", "GITEE_TOKEN", "SCM_GITEE_TOKEN",
)


def mask(text: str | None, limit: int | None = None, *, source: bool = False) -> str:
    """脱敏。可选截断，用于工具输出预览。"""
    if not text:
        return ""

    masked = text
    for name in _SENSITIVE_ENV_NAMES:
        value = os.environ.get(name, "")
        if value and len(value) >= 6:
            masked = masked.replace(value, "***")

    masked = _GENERIC_KEY.sub("***", masked)
    masked = _BEARER.sub(r"\1***", masked)
    masked = _ENV_ASSIGN.sub(lambda m: f"{m.group(1)}{m.group(2)}{m.group(1)}=***"
                            if _SECRET_NAME.search(m.group(2)) and (not source or m.group(3).startswith(('"', "'")))
                            else m.group(0), masked)

    if limit is not None and len(masked) > limit:
        masked = masked[:limit] + "…（已截断）"
    return masked
