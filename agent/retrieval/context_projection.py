"""上下文投影编译器。

调研报告第 7.3 节列了七条编译规则，这里是它的落点：

1. 先放任务合同、允许路径、禁止路径
2. 再放直接命中点，然后放一跳定义/调用关系
3. 只有存在解释缺口时才扩展到二跳
4. 同一文件按符号合并，去重复行
5. 每段保留路径、行号、Git SHA 和来源查询
6. 超预算时优先保留：定义 > 写入点 > 边界校验 > 测试 > 最近变更
7. 结论标记 observed / inferred / unknown

**它不是「把代码塞进上下文」**，而是按符号和调用链组装最小投影。
整文件注入制造噪声，只给相似片段又会丢控制流 —— 两头都不对。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import IntEnum
from typing import Iterable


class Priority(IntEnum):
    """数字越小越先保留。超预算时从大往小丢。"""

    DEFINITION = 1      # 定义（函数/类/类型）
    WRITE_SITE = 2      # 写入点
    BOUNDARY = 3        # 边界校验（参数校验、权限判断）
    TEST = 4            # 测试
    RECENT_CHANGE = 5   # 最近变更
    CALL_SITE = 6       # 调用点
    REFERENCE = 7       # 普通引用


@dataclass
class Segment:
    """投影里的一段。"""

    path: str
    start_line: int
    end_line: int
    text: str
    source_query: str
    git_sha: str = ""
    priority: Priority = Priority.REFERENCE

    def header(self) -> str:
        """段头。路径 + 行号 + SHA + 来源查询 —— 规则 5 要求的四样都在。"""
        sha = f" @{self.git_sha[:12]}" if self.git_sha else ""
        return f"=== {self.path}:{self.start_line}-{self.end_line}{sha} （来自 {self.source_query}）==="

    def render(self) -> str:
        return f"{self.header()}\n{self.text}"

    @property
    def key(self) -> tuple[str, int, int]:
        return (self.path, self.start_line, self.end_line)


@dataclass
class TaskContract:
    """规则 1 的「任务合同」。"""

    objective: str
    allowed_paths: tuple[str, ...] = ()
    forbidden_paths: tuple[str, ...] = ()
    notes: tuple[str, ...] = ()


@dataclass
class ContextProjection:
    """编译结果。"""

    contract: TaskContract
    segments: list[Segment] = field(default_factory=list)
    # 被预算挤掉的段，按优先级从低到高
    dropped: list[Segment] = field(default_factory=list)
    budget_chars: int = 0

    @property
    def used_chars(self) -> int:
        return sum(len(s.render()) for s in self.segments)

    def render(self) -> str:
        parts = ["## 任务合同", self.contract.objective]
        if self.contract.allowed_paths:
            parts.append(f"允许的路径：{', '.join(self.contract.allowed_paths)}")
        if self.contract.forbidden_paths:
            parts.append(f"禁止的路径：{', '.join(self.contract.forbidden_paths)}")
        for note in self.contract.notes:
            parts.append(f"- {note}")

        if self.segments:
            parts.append("\n## 相关代码")
            parts.extend(s.render() for s in self.segments)

        if self.dropped:
            # 规则 6 的配套：丢了多少要写出来，不能静默截断。
            # 静默截断读起来像「已经覆盖全了」，实际没有。
            paths = sorted({s.path for s in self.dropped})
            parts.append(f"\n（因超出预算省略了 {len(self.dropped)} 段：{', '.join(paths[:10])}）")

        return "\n".join(parts)

    def summary(self) -> dict:
        return {
            "segmentCount": len(self.segments),
            "droppedCount": len(self.dropped),
            "usedChars": self.used_chars,
            "budgetChars": self.budget_chars,
        }


def compile_projection(
    contract: TaskContract,
    candidates: Iterable[Segment],
    budget_chars: int,
) -> ContextProjection:
    """按预算编译投影。

    顺序：去重合并 → 排序 → 按预算装载 → 记下被丢的。
    """
    merged = _merge_and_dedup(candidates)

    # 优先级升序；同优先级按路径+行号稳定排序，保证可重放
    ordered = sorted(merged, key=lambda s: (s.priority, s.path, s.start_line))

    projection = ContextProjection(contract=contract, budget_chars=budget_chars)

    # 段的固定开销（段头）必须算进去，否则会超出预算一点点
    used = len(contract.objective)
    for segment in ordered:
        cost = len(segment.render())
        if used + cost > budget_chars:
            projection.dropped.append(segment)
            continue
        projection.segments.append(segment)
        used += cost

    projection.segments.sort(key=lambda s: (s.path, s.start_line))
    return projection


def _merge_and_dedup(candidates: Iterable[Segment]) -> list[Segment]:
    """规则 4：同一文件的重叠/相邻段合并，去掉重复行。

    合并时保留更高的优先级（数字更小）与更早的来源查询 ——
    因为「为什么读这段」比「第几次读」重要。
    """
    by_path: dict[str, list[Segment]] = {}
    for seg in candidates:
        if not seg.text.strip():
            continue
        by_path.setdefault(seg.path, []).append(seg)

    out: list[Segment] = []
    for path, segs in by_path.items():
        ordered = sorted(segs, key=lambda s: (s.start_line, s.end_line))
        current = ordered[0]
        for nxt in ordered[1:]:
            if nxt.start_line <= current.end_line + 1:
                # 重叠或紧邻 → 合并
                start = current.start_line
                end = max(current.end_line, nxt.end_line)
                current = Segment(
                    path=path,
                    start_line=start,
                    end_line=end,
                    text=_join_unique(current.text, nxt.text),
                    source_query=current.source_query,
                    git_sha=current.git_sha or nxt.git_sha,
                    priority=min(current.priority, nxt.priority),
                )
            else:
                out.append(current)
                current = nxt
        out.append(current)
    return out


def _join_unique(left: str, right: str) -> str:
    """按行拼接并去重，保持原有顺序。"""
    seen: set[str] = set()
    lines: list[str] = []
    for line in (left + "\n" + right).splitlines():
        stripped = line.rstrip()
        if stripped and stripped in seen:
            continue
        if stripped:
            seen.add(stripped)
        lines.append(line)
    return "\n".join(lines)
