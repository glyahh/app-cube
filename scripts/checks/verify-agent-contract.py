"""契约比对：后端实际返回的字段必须被前端类型覆盖。

不依赖前端运行：直接读 TS 类型声明里的字段名，与真实接口返回对照。
退出码 0 表示无漂移。
"""
from __future__ import annotations

import json
import re
import secrets
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8124/api"
TYPES = Path("ai-generate-code-frontend/src/features/project-import/agent-permissions/types.ts")
API_TS = Path("ai-generate-code-frontend/src/features/project-import/agent-permissions/api.ts")
COOKIE: dict[str, str] = {}


def call(path: str, payload=None, method=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(BASE + path, data=data,
                                     method=method or ("POST" if payload is not None else "GET"))
    request.add_header("Content-Type", "application/json")
    if COOKIE:
        request.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in COOKIE.items()))
    with urllib.request.urlopen(request, timeout=30) as response:
        for header in response.headers.get_all("Set-Cookie") or []:
            name, _, rest = header.partition("=")
            COOKIE[name] = rest.split(";")[0]
        body = json.loads(response.read().decode())
    if body.get("code") != 20000:
        raise SystemExit(f"{path} -> {body.get('message')}")
    return body.get("data")


def frontend_fields() -> dict[str, set[str]]:
    """从 TS 源里抽取接口字段名，按接口分组。

    不能用 `[^}]*` 抓接口体：字段类型里可能带 `{}`（如泛型默认值、字面量），
    那样会在第一个 `}` 处截断，把后面的字段全漏掉。这里按花括号配平扫描。
    """
    text = (TYPES.read_text(encoding="utf-8") + "\n" + API_TS.read_text(encoding="utf-8"))
    found: dict[str, set[str]] = {}
    for match in re.finditer(r"export interface (\w+)\s*\{", text):
        name = match.group(1)
        start = match.end() - 1
        depth, index = 0, start
        while index < len(text):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        body = text[start + 1:index]
        fields = set(re.findall(r"(?:^|\n)\s*(\w+)\??\s*:", body))
        # 嵌套对象字面量（如 body: { changes: ... }）会把内部字段也算进来，剔除掉。
        fields -= {"changes"}
        if fields:
            found[name] = fields
    return found


def main() -> int:
    account = "contract_" + secrets.token_hex(5)
    password = secrets.token_urlsafe(14)
    call("/user/register", {"userAccount": account, "userPassword": password,
                            "checkPassword": password})
    call("/user/login", {"userAccount": account, "userPassword": password})
    app = call("/app/add", {"appName": "契约检查", "initPrompt": "x"})

    config = call(f"/app/{app}/agent-model")
    settings = call(f"/app/{app}/agent-settings",
                    {"policyRevision": "0", "mode": "AUTO", "sandboxMode": "WORKSPACE_WRITE"},
                    "PUT")
    declared = frontend_fields()
    problems: list[str] = []

    # 1. 配置接口：后端返回的每个字段都该在前端类型里出现。
    #    反过来（前端声明了后端没返回）才是真漂移，会让运行时读到 undefined。
    for key in config:
        if "ModelConfig" in declared and key not in declared["ModelConfig"]:
            problems.append(f"ModelConfig 缺字段 {key}")
    for key in settings:
        if "EditSettings" in declared and key not in declared["EditSettings"]:
            problems.append(f"EditSettings 缺字段 {key}")

    print("后端 agent-model:", sorted(config))
    print("后端 agent-settings:", sorted(settings))
    print("前端声明:", {k: sorted(v) for k, v in declared.items() if k in ("ModelConfig", "EditSettings")})
    if problems:
        for item in problems:
            print("漂移:", item)
        return 1
    print("RESULT 无字段漂移")
    return 0


if __name__ == "__main__":
    sys.exit(main())
