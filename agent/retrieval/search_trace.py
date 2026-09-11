"""检索记录。

调研报告里对它的定位很明确：**是证据索引，不是结论**。

- 必须可重放：给定的查询 + Git SHA，能重现同样的结果
- 必须可过期：绑定 Git SHA，SHA 变了就说明结果旧了
- 必须可审计：留下了什么、追踪了哪些关系、排除过什么，都记下来
- **不能写入权威的完成状态** —— 找到证据不等于任务完成

Phase 2 只写到结构化日志。等排障真的需要跨进程查历史时再加表。
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SearchResult:
    """一条命中。带路径与行号 —— 这也是「为什么比相似度分数可信」的一部分。"""

    path: str
    start: int = 0
    end: int = 0
    note: str = ""

    def to_dict(self) -> dict:
        out = {"path": self.path}
        if self.start:
            out["start"] = self.start
        if self.end:
            out["end"] = self.end
        if self.note:
            out["note"] = self.note
        return out


@dataclass(frozen=True)
class SearchTrace:
    """一次检索的完整记录。"""

    trace_id: str
    project_id: str
    git_sha: str
    query: dict[str, Any]
    results: tuple[SearchResult, ...] = ()
    relations_followed: tuple[str, ...] = ()
    # 排除性检查也很重要：「没找到别的写入点」是一条真结论，
    # 不记下来就没法判断当时到底查没查过。
    negative_checks: tuple[str, ...] = ()
    timestamp_ms: int = 0
    elapsed_ms: int = 0

    def to_dict(self) -> dict:
        return {
            "traceId": self.trace_id,
            "projectId": self.project_id,
            "gitSha": self.git_sha,
            "query": self.query,
            "results": [r.to_dict() for r in self.results],
            "relationsFollowed": list(self.relations_followed),
            "negativeChecks": list(self.negative_checks),
            "timestamp": self.timestamp_ms,
            "elapsedMs": self.elapsed_ms,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)


class SearchTraceLog:
    """一轮 run 里的检索记录收集器。

    会话级、进程内。它**不**参与权威状态 —— 丢了只影响排障，不影响执行结果。
    """

    def __init__(self, project_id: str, git_sha: str = "") -> None:
        self.project_id = project_id
        self.git_sha = git_sha
        self._traces: list[SearchTrace] = []

    def new_trace_id(self) -> str:
        """时间有序 + 随机后缀。便于按时间排，也不会撞。"""
        return f"st_{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}"

    def record(
        self,
        *,
        kind: str,
        query: str,
        results: list[SearchResult] | None = None,
        scope: str = "",
        relations: list[str] | None = None,
        negative_checks: list[str] | None = None,
        elapsed_ms: int = 0,
    ) -> SearchTrace:
        trace = SearchTrace(
            trace_id=self.new_trace_id(),
            project_id=self.project_id,
            git_sha=self.git_sha,
            query={"kind": kind, "pattern": query, **({"scope": scope} if scope else {})},
            results=tuple(results or ()),
            relations_followed=tuple(relations or ()),
            negative_checks=tuple(negative_checks or ()),
            timestamp_ms=int(time.time() * 1000),
            elapsed_ms=elapsed_ms,
        )
        self._traces.append(trace)
        # 落到结构化日志。Phase 2 不建表 —— 需要跨进程查历史时再说。
        logger.info("SEARCH_TRACE %s", trace.to_json())
        return trace

    def all(self) -> list[SearchTrace]:
        return list(self._traces)

    def summary(self) -> dict:
        """给 run.completed 事件带的检索统计。"""
        total_results = sum(len(t.results) for t in self._traces)
        by_kind: dict[str, int] = {}
        for t in self._traces:
            kind = str(t.query.get("kind") or "unknown")
            by_kind[kind] = by_kind.get(kind, 0) + 1
        return {
            "traceCount": len(self._traces),
            "resultCount": total_results,
            "byKind": by_kind,
            "gitSha": self.git_sha,
        }


@dataclass
class Evidence:
    """一条证据。

    调研报告要求「任何结论都要标记 observed / inferred / unknown」——
    这个字段就是那条要求的落点。模型看到的每条证据都带着它是怎么来的。
    """

    claim: str
    source: str
    # observed = 读到了文件/跑过命令；inferred = 推出来的；unknown = 没验证
    confidence: str = "unknown"
    detail: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "claim": self.claim,
            "source": self.source,
            "confidence": self.confidence,
            **({"detail": self.detail} if self.detail else {}),
        }


class EvidenceLedger:
    """本轮收集到的证据。按来源可追溯。"""

    def __init__(self) -> None:
        self._items: list[Evidence] = []

    def add(self, claim: str, source: str, confidence: str = "unknown", **detail) -> Evidence:
        if confidence not in ("observed", "inferred", "unknown"):
            raise ValueError(f"confidence 只能是 observed/inferred/unknown，收到 {confidence!r}")
        item = Evidence(claim=claim, source=source, confidence=confidence, detail=detail)
        self._items.append(item)
        return item

    def all(self) -> list[Evidence]:
        return list(self._items)

    def to_list(self) -> list[dict]:
        return [e.to_dict() for e in self._items]
