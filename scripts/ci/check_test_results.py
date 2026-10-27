#!/usr/bin/env python3
"""JUnit/Playwright/Pytest 结果门禁：结果文件必须存在、有真实 case、不得失败或跳过。"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main(paths: list[str]) -> int:
    if not paths:
        print("no result report supplied", file=sys.stderr)
        return 2
    seen = 0
    problems: list[str] = []
    for raw in paths:
        for path in sorted(Path().glob(raw)) if any(c in raw for c in "*?[") else [Path(raw)]:
            if not path.is_file():
                problems.append(f"missing report: {path}")
                continue
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as error:
                problems.append(f"invalid XML: {path}: {error}")
                continue
            cases = list(root.iter("testcase"))
            failed = sum(1 for case in cases if case.find("failure") is not None)
            errors = sum(1 for case in cases if case.find("error") is not None)
            skipped = [case.get("name", "?") for case in cases if case.find("skipped") is not None]
            seen += len(cases)
            print(f"{path}: tests={len(cases)} failures={failed} errors={errors} skipped={len(skipped)}")
            if not cases:
                problems.append(f"no test cases: {path}")
            if failed or errors:
                problems.append(f"failed or errored tests: {path}")
            if skipped:
                problems.append(f"skipped tests are not merge evidence: {path}: {', '.join(skipped[:5])}")
    if seen == 0:
        problems.append("no test cases collected")
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
