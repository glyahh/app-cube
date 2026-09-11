"""工具异常兜底。

一次工具调用失败不该终止整轮。把它变成一条**结构化、可修正**的 ToolMessage
还给模型，让它自己换个做法 —— 这比直接 `sink.error` 结束掉强得多。

两类失败必须分开对待：
- **可恢复**（命令被白名单拒、文件不存在、超时）→ 给模型明确原因 + 建议
- **不可恢复**（权限、配置错误）→ 同样还给模型，但要它停止重试同一件事

参考项目在这一层踩过的坑：它把所有异常都当可重试处理，结果模型对着一个权限问题
无限重试。所以下面的提示里对「被拒绝」明确写了「不要重复同一条命令」。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import ToolMessage

logger = logging.getLogger(__name__)

# 按异常类型给可执行的下一步提示。模型看得到这些文字。
_HINTS = {
    "CommandRejected": "命令被安全策略拒绝。请换一条合规命令，不要重复提交同一条。",
    "FileNotFoundError": "文件不存在。先用 ls 或 glob 确认真实路径，再重试。",
    "IsADirectoryError": "这是一个目录，不是文件。先列出目录内容。",
    "PermissionError": "文件系统拒绝了访问。确认路径不是目录、也没被别的进程占用。",
    "TimeoutError": "操作超时。缩小范围，或者先用更小的命令查看状态。",
    "UnicodeDecodeError": "文件不是 UTF-8 文本（可能是二进制）。换用文本文件或跳过它。",
}


class ToolErrorMiddleware(AgentMiddleware):
    """把工具异常转成结构化 ToolMessage。"""

    # 同步与异步两条路径行为必须一致 —— 只实现一条会让流式与非流式不一样。
    def wrap_tool_call(self, request, handler):
        try:
            return handler(request)
        except Exception as exc:  # noqa: BLE001 — 这里就是要兜住一切
            return _error_tool_message(request, exc)

    async def awrap_tool_call(self, request, handler):
        try:
            return await handler(request)
        except Exception as exc:  # noqa: BLE001
            return _error_tool_message(request, exc)


def _error_tool_message(request: Any, exc: Exception) -> ToolMessage:
    tool_name = _tool_name(request)
    tool_call_id = _tool_call_id(request)
    error_type = type(exc).__name__

    hint = _HINTS.get(error_type, "工具执行失败。读一下 error 字段，调整参数后重试。")

    # 原始异常留给日志；返回给模型的内容要脱敏（工具输出里常混进密钥）
    from core.masking import mask

    payload = {
        "ok": False,
        "tool": tool_name,
        "error_type": error_type,
        "error": mask(str(exc))[:600],
        "hint": hint,
    }

    logger.warning("工具 %s 抛异常：%s: %s", tool_name, error_type, exc)
    logger.debug("工具异常详情", exc_info=True)

    return ToolMessage(
        content=json.dumps(payload, ensure_ascii=False),
        tool_call_id=tool_call_id,
        name=tool_name,
        status="error",
    )


def _tool_name(request: Any) -> str:
    """防御式取值 —— 不同版本这里可能是 dict 也可能是对象。"""
    tool_call = getattr(request, "tool_call", None)
    if isinstance(tool_call, dict):
        return str(tool_call.get("name") or "unknown_tool")
    if tool_call is not None:
        return str(getattr(tool_call, "name", "unknown_tool"))
    tool = getattr(request, "tool", None)
    return str(getattr(tool, "name", "unknown_tool")) if tool else "unknown_tool"


def _tool_call_id(request: Any) -> str:
    tool_call = getattr(request, "tool_call", None)
    if isinstance(tool_call, dict):
        return str(tool_call.get("id") or "")
    if tool_call is not None:
        return str(getattr(tool_call, "id", "") or "")
    return ""
