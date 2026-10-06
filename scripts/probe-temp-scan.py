"""在 temp/ 下持续遍历并读取文件，用于复现并发文件访问对导入提交的影响。

只读，不修改任何文件。默认持续 N 秒。
"""

import pathlib
import sys
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
SKIP = {".git", "node_modules", ".venv", "target", "dist", "__pycache__"}


def main() -> int:
    seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    deadline = time.time() + seconds
    passes = 0
    reads = 0
    while time.time() < deadline:
        for path in (REPO / "temp").rglob("*"):
            if time.time() >= deadline:
                break
            if not path.is_file() or any(part in SKIP for part in path.parts):
                continue
            try:
                path.read_bytes()
                reads += 1
            except OSError:
                continue
        passes += 1
    print(f"扫描完成：{passes} 轮，读取 {reads} 个文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
