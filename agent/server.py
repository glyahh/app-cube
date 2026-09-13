"""Agent 装配。

工厂是「装配中心」，不是第二个业务调度器 —— 调度在 `core/runtime.py`。
这样三种改动互不干扰：

- 改审批流程 → 动 runtime
- 换模型/工具组合 → 动这里
- 改事件格式 → 动 streaming_runtime

**每次 run 重建 agent 对象**，状态交给 checkpointer 与 store 按 threadId 持久化。
不必为了续聊长期持有整个 agent；测试时也更容易替换模型和存储。
"""

from __future__ import annotations

import logging

from prompt import get_system_prompt

logger = logging.getLogger(__name__)


def build_agent(*, settings, backend, checkpointer, store, task_kind: str, tools=None,
                workspace=None, window: int = 0, overhead=None, compaction_out: list | None = None):
    """按本轮任务类型装配一个可运行的 deep agent。

    `tools` 传本项目自带的工具（git_snapshot 等）。框架的
    ls/read/write/glob/grep 由 backend 提供，不在这里重复注册。

    `workspace` 用于给第 0 层压缩指定外置目录；不传就跳过第 0 层的写盘能力。

    `overhead` 是固定开销估计器（system prompt + 工具 schema）。调用方持有它，
    并在每轮模型调用后用真实 usage 校正 —— 它不在 messages 里，但确实占窗口。
    """
    from pathlib import Path

    from deepagents import create_deep_agent
    from langchain.agents.middleware import ModelCallLimitMiddleware

    from core.compaction import OverheadEstimator
    from core.middleware import (
        CompactionMiddleware,
        ToolErrorMiddleware,
        ToolOutputBudgetMiddleware,
        make_summarizer,
    )
    from core.model import make_main_model, make_summarizer_model
    from core.model_window import resolve_window

    model = make_main_model(settings.model)

    # 窗口走动态解析（配置 > /models 探测 > 内置表 > 保守默认），不硬编码。
    # 所有压缩阈值都是它的函数，估错了整条链路会静默失效。
    effective_window = window or resolve_window(
        settings.model.model_id,
        settings.model.base_url,
        settings.model.api_key,
        override=settings.model.context_window_override,
        use_system_proxy=settings.model.use_system_proxy,
    ).window

    spill_dir = (Path(workspace) / ".scratch") if workspace else Path(".scratch")
    system_prompt = get_system_prompt(task_kind)

    # 固定开销的初值：system prompt 可以精确算，工具 schema 只能先给个保守值，
    # 第一次模型调用后会被真实 usage 校正。
    estimator = overhead if overhead is not None else OverheadEstimator()
    if estimator.estimate <= 0:
        from core.compaction import estimate_tokens

        estimator.estimate = estimate_tokens(system_prompt) + 3_000

    # 第 1~3 层：占用水位驱动，超阈值才压。
    # 摘要模型复用主模型 —— 容量小于主模型会导致「无摘要丢弃」。
    compaction = CompactionMiddleware(
        window=effective_window,
        overhead=estimator,
        summarize=make_summarizer(make_summarizer_model(settings.model)),
    )
    if compaction_out is not None:
        # 把实例交回调用方，便于把压缩统计挂到终态事件上做观测
        compaction_out.append(compaction)

    middleware = [
        # 顺序有意义。异常兜底放最外，内层抛出的异常才会被转成可修正的 ToolMessage。
        ToolErrorMiddleware(),
        # 第 0 层：工具输出进上下文之前的入口约束。成本只付一次。
        ToolOutputBudgetMiddleware(
            char_budget=settings.limits.tool_output_max_chars,
            spill_threshold=settings.limits.tool_output_spill_chars,
            spill_dir=spill_dir,
        ),
        # 模型调用次数上限。exit_behavior="end" 让超限时优雅收尾而不是抛错，
        # 这样前面积累的产出不会因为最后一步超限而全部作废。
        ModelCallLimitMiddleware(
            run_limit=settings.limits.max_model_calls,
            exit_behavior="end",
        ),
        compaction,
    ]

    agent = create_deep_agent(
        model=model,
        tools=list(tools) if tools else None,
        system_prompt=system_prompt,
        backend=backend,
        middleware=middleware,
        checkpointer=checkpointer,
        store=store,
        name="glyahh-agent",
    )

    logger.debug("已装配 agent：task_kind=%s model=%s window=%d tools=%d",
                 task_kind, settings.model.model_id, effective_window, len(tools or ()))
    return agent
