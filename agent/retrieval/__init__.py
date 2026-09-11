"""检索平面。

对代码而言，「切块 → 向量库 → 相似度召回」不是默认最优解。更可靠的主路径是：

    定位文件 → 精确锚点 → 读小范围上下文 → 沿关系追踪 → 跑测试验证

结果带路径、行号和 Git SHA，而不是一个相似度分数。

四个角色：

- `git_epoch`   版本基准，保证结果不陈旧
- `search_trace` 证据索引，可重放、可过期、可审计
- `context_projection` 按符号和调用链组装最小上下文
- `runtime_context` 让工具读到本轮运行上下文
"""

from retrieval.context_projection import (
    ContextProjection,
    Priority,
    Segment,
    TaskContract,
    compile_projection,
)
from retrieval.git_epoch import GitEpoch, snapshot
from retrieval.search_trace import Evidence, EvidenceLedger, SearchResult, SearchTrace, SearchTraceLog

__all__ = [
    "GitEpoch",
    "snapshot",
    "SearchResult",
    "SearchTrace",
    "SearchTraceLog",
    "Evidence",
    "EvidenceLedger",
    "ContextProjection",
    "Segment",
    "TaskContract",
    "Priority",
    "compile_projection",
]
