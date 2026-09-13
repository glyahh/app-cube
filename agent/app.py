"""Agent 运行时入口：stdio 主循环。

由 Java 侧以子进程方式拉起，常驻。每个 Java 进程持有一个进程池（默认 3，最多 5）。

    for line in stdin:  →  跑一次 run  →  往 stdout 写多行事件  →  写终止帧

为什么是子进程而不是 HTTP 服务：Java 已经提供 REST 层，Python 再起一个 web 层
就是重复，还要搭上端口、CORS、鉴权、心跳、熔断一整套。子进程方案把这些全省了，
而且取消直接变成杀进程 —— 不用写协作式取消加轮询。进程死了 stdout 立刻 EOF，
不需要心跳来区分「在思考」和「已崩溃」。

为什么常驻：实测 `import langgraph` 5.35 秒、`import deepagents` 7.11 秒。
每次 run 起新进程会让首屏卡死。
"""

from __future__ import annotations

import sys

# ---------------------------------------------------------------------------
# stdout 保护。必须在任何其他 import 之前执行。
#
# stdout 是协议通道，而第三方库随时可能 print。把真 stdout 抓在手里，
# 让 sys.stdout 指向 stderr —— 之后所有 print 都进 stderr，不污染协议。
# ---------------------------------------------------------------------------
_PROTOCOL_OUT = sys.stdout
sys.stdout = sys.stderr
# ---------------------------------------------------------------------------

import json  # noqa: E402
import logging  # noqa: E402
import sys as _sys  # noqa: E402

from core.events import Emitter, ErrorCode, parse_request  # noqa: E402
from core.runtime import run_one  # noqa: E402
from core.settings import load_settings  # noqa: E402

logger = logging.getLogger("agent")


def _configure_logging(level: str) -> None:
    """日志一律走 stderr。stdout 已经被协议占用了。"""
    handler = logging.StreamHandler(_sys.stderr)
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s")
    )
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())


def handle_line(line: str, settings) -> None:
    """处理一行请求。异常一律收口成 error 帧，绝不让主循环退出。"""
    try:
        request = parse_request(line)
    except Exception as exc:
        # 连 runId 都拿不到，发不了协议帧，只能记日志。Java 侧会等超时。
        logger.error("请求无法解析，已丢弃：%s | 原文=%r", exc, line[:200])
        return

    emitter = Emitter(_PROTOCOL_OUT, request["runId"])
    try:
        run_one(request, settings, emitter)
    except Exception as exc:  # noqa: BLE001 — 主循环必须活下来
        logger.exception("run 失败：runId=%s", request["runId"])
        emitter.error(ErrorCode.INTERNAL, str(exc))
    finally:
        # 终止帧必须发。漏发的话 Java 会一直阻塞在读上，只能靠超时兜底。
        emitter.end()


def main() -> int:
    settings = load_settings()
    _configure_logging(settings.log_level)

    # 启动预检：提前初始化持久化层，打出明确状态。
    # 不等到第一个请求 —— 那时候失败信息会和业务日志混在一起不好找。
    _precheck_persistence(settings)

    logger.info("agent 运行时已就绪，等待 stdio 请求")

    for line in _sys.stdin:
        line = line.strip()
        if not line:
            continue
        handle_line(line, settings)

    # 退出清理：显式关闭连接池，释放 PostgreSQL 连接。
    _shutdown_pools()
    logger.info("stdin 已关闭，退出")
    return 0


def _precheck_persistence(settings) -> None:
    """启动时预热持久化层。

    在进入 stdin 主循环前完成 checkpointer 和 store 的初始化。
    这样 Java 侧能从 stderr 里立刻看到持久化状态，不用等到第一个请求。
    """
    from core.persistence import get_checkpointer, get_store
    from core.streaming_runtime import _event_loop

    loop = _event_loop()
    try:
        loop.run_until_complete(get_checkpointer(settings.postgres_dsn))
        loop.run_until_complete(get_store(settings.postgres_dsn))
    except Exception as exc:
        # 不阻断启动 —— 降级逻辑已在 get_checkpointer 内部处理
        logger.warning("持久化预检异常：%s", exc)


def _shutdown_pools() -> None:
    """关闭连接池和事件循环。"""
    from core.persistence import close_pools
    from core.streaming_runtime import _event_loop

    loop = _event_loop()
    if not loop.is_closed():
        try:
            loop.run_until_complete(close_pools())
        except Exception as exc:
            logger.warning("关闭连接池异常：%s", exc)
        finally:
            loop.close()
    logger.info("连接池已关闭")


if __name__ == "__main__":
    raise SystemExit(main())
