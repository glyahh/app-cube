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
    logger.info("agent 运行时已就绪，等待 stdio 请求")

    for line in _sys.stdin:
        line = line.strip()
        if not line:
            continue
        handle_line(line, settings)

    logger.info("stdin 已关闭，退出")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
