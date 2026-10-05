"""平台专属一次性启动识别进程；只消费封存 JSON，不加载或运行原工程。"""
import hashlib
import json
import sys
from pathlib import Path


def main():
    # -I 不读取 PYTHONIOENCODING，协议编码必须显式固定。
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    sys.stderr.reconfigure(encoding="utf-8", newline="\n")
    protocol = sys.stdout
    sys.stdout = sys.stderr
    # 只添加平台 Agent 模块目录；-I 排除工程 cwd、PYTHONPATH 与用户站点。
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent"))
    from core.events import Emitter
    from core.settings import load_settings
    from core.startup import run_startup
    request = None
    try:
        with Path(sys.argv[1]).open("rb") as source:
            raw = source.read(96 * 1024 + 1)
        if len(raw) > 96 * 1024 or hashlib.sha256(raw).hexdigest() != sys.argv[2]:
            raise ValueError("请求超限")
        request = json.loads(raw)
        if not isinstance(request, dict) or not isinstance(request.get("runId"), str):
            raise ValueError("请求身份缺失")
        emitter = Emitter(protocol, request["runId"])
        try:
            run_startup(request, load_settings(), emitter)
        finally:
            emitter.end()
        return 0
    except Exception as error:
        # 不回显模型、配置或源码；完整请求只存在于调用者的私有输入目录。
        print("STARTUP_HELPER_FAILED:"+type(error).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
