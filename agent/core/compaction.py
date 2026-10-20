"""上下文压缩：四层 + 两个附属。

桌子满了怎么办？四道闸门，从最便宜的做起；只有前三道都不够，才花钱请人抄一遍。

| 层 | 做什么 | 花钱 | 花时间 |
|---|---|---|---|
| 第 0 层 结构性排除 | 东西根本不让它进上下文 | 不 | 不 |
| 第 1 层 确定性清理 | 已进来的做无损降级 | 不（零 LLM） | 不 |
| 第 2 层 轻量裁剪 | 擦掉保护窗口之外的工具输出 | 不（零 LLM） | 不 |
| 第 3 层 摘要 | 切 head/summary/tail + 写摘要 | **花** | **75~103 秒** |

三条铁律：
1. **不许越层** —— 第 1 层能解决就不许用第 2 层，依此类推
2. **不许倒序** —— 先降级（内容还在，只是变短），再擦除（换成占位符）
3. **不许碰不该碰的** —— 每层有明确的「绝不」清单

这套设计里最贵的错误不是「丢信息」，是「丢了我不知道」。所以任何造成内容损失的
层级都必须在**原位留痕**，禁止静默移除。
"""

from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence

from core.settings import clamp_to_window

logger = logging.getLogger(__name__)


# ═══════════════════════════ 窗口与阈值 ═══════════════════════════

def estimate_tokens(text: str) -> int:
    """粗略估 token 数。

    不用 tiktoken：它是 OpenAI 的分词器，对 DeepSeek / Qwen 只是近似，
    装了也换不来准确度，反而多一个依赖。这里按字符类别估：

    - CJK 字符约 1 token/字（中文信息密度高）
    - 其他字符约 1 token/4 字符

    只用于触发判断，不用于计费，量级对就够了。
    """
    if not text:
        return 0
    cjk = len(re.findall(r"[一-鿿　-〿＀-￯]", text))
    rest = len(text) - cjk
    # 向上取整。用 max(1, rest//4) 会在 rest=0（纯中文）时凭空多算一个 token。
    return cjk + (rest + 3) // 4


@dataclass(frozen=True)
class Thresholds:
    """一套与窗口绑定的阈值。换模型时重新算，不要写死。"""

    window: int

    @property
    def trigger(self) -> int:
        """第 3 层触发点。

        小窗口抬高比例：system prompt + 工具 schema 的固定开销能占 20~30K token，
        在 200K 窗口上按 0.50 触发，压完回收的空间还不够抵消这个开销。
        """
        ratio = 0.75 if self.window < 512_000 else 0.50
        return clamp_to_window(64_000, ratio, self.window)

    @property
    def protect_window(self) -> int:
        """第 2 层的保护窗口：这之内的工具输出永不擦除。"""
        return clamp_to_window(40_000, 0.10, self.window)

    @property
    def min_reclaim(self) -> int:
        """第 2 层的最小回收门槛。不够就不提交 —— 不值得动一次手。"""
        return clamp_to_window(20_000, 0.05, self.window)

    @property
    def tail_budget(self) -> int:
        """第 3 层逐字保留的尾部预算。

        设计给的是 `max(10_000, min(25_000, 窗口 × 0.025))`，但那个形式在小窗口上
        会算出大于窗口的值（8K 窗口算出来是 10_000），于是第 3 层要么不触发、
        要么保留下超窗口的尾部 —— 正是失效条件里说的那类静默错误。
        所以最终再夹一道：不超过窗口的一半，给 head 与摘要留出空间。
        """
        raw = max(10_000, min(25_000, self.window * 0.025))
        return int(min(raw, self.window * 0.5))

    def summary_budget(self, content_tokens: int) -> int:
        """摘要自身的预算。

        同样要夹紧：绝对下限在小窗口上不能超过窗口本身。
        """
        raw = max(2_000, min(content_tokens * 0.20, min(self.window * 0.05, 10_000)))
        return int(min(raw, self.window * 0.5))


# ═══════════════════════════ 第 0 层：结构性排除 ═══════════════════════════

# 名字里带这些的工具，输出永不擦除（第 2 层白名单）
NEVER_PRUNE_TOOLS = frozenset({"skill", "skills"})


@dataclass
class Layer0Result:
    content: str
    spilled: bool = False
    spilled_path: str = ""


def apply_layer0(
    content: str,
    *,
    char_budget: int,
    spill_threshold: int,
    spill_dir: Path,
    label: str = "tool",
) -> Layer0Result:
    """工具输出进入历史**之前**的约束。

    **绝不动已经在上下文里的东西** —— 这一层只在入口工作，成本只付一次。
    入口约束与事后清理的成本比大约是 1 : 轮数。

    超过阈值的写盘并在原位留引用，而不是整段塞进上下文。
    """
    if content is None:
        return Layer0Result(content="")

    if len(content) <= char_budget:
        return Layer0Result(content=content)

    # 超阈值：外置 + 留引用
    if len(content) > spill_threshold:
        try:
            spill_dir.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256(content.encode("utf-8", errors="replace")).hexdigest()[:12]
            path = spill_dir / f"{label}-{digest}.txt"
            if not path.exists():
                path.write_text(content, encoding="utf-8")
            head = content[: char_budget // 2]
            tail = content[-char_budget // 2 :]
            return Layer0Result(
                content=(
                    f"{head}\n\n…（此处省略 {len(content) - char_budget} 字符，"
                    f"完整内容已外置到 .scratch/{path.name}，需要时用 read_file 读回）\n\n{tail}"
                ),
                spilled=True,
                spilled_path=str(path),
            )
        except Exception:
            # 外置失败就退化成截断，不能让写入工具输出这件事弄死一轮
            logger.warning("工具输出外置失败，退化为截断", exc_info=True)

    head = content[: char_budget // 2]
    tail = content[-char_budget // 2 :]
    return Layer0Result(
        content=f"{head}\n\n…（此处省略 {len(content) - char_budget} 字符）\n\n{tail}"
    )


# ═══════════════════════════ 消息画像 ═══════════════════════════

def _msg_text(message) -> str:
    content = getattr(message, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            str(b.get("text") or "") if isinstance(b, dict) else str(b) for b in content
        )
    return str(content or "")


def _msg_role(message) -> str:
    return type(message).__name__


def _is_tool_message(message) -> bool:
    return _msg_role(message) == "ToolMessage"


def _is_human(message) -> bool:
    return _msg_role(message) in ("HumanMessage", "HumanMessageChunk")


def _tool_name(message) -> str:
    return str(getattr(message, "name", "") or "")


def total_tokens(messages: Sequence) -> int:
    return sum(estimate_tokens(_msg_text(m)) for m in messages)


# ═══════════════════════════ 压缩结果 ═══════════════════════════

@dataclass
class OverheadEstimator:
    """固定开销：system prompt + 工具 schema。

    **为什么必须单独算**：`state["messages"]` 里没有这两样，但它们实打实占着窗口。
    实测一次真实请求：模型报告输入 8463 token，而消息部分只有几百 ——
    只数消息的话触发点永远到不了，压缩看着像「配了但没生效」。

    估计值会在第一次模型调用后被真实数据校正（供应商回的 `usage.input_tokens`
    减掉我们自己算的消息 token，剩下的就是固定开销）。
    """

    estimate: int = 0
    measured: int = 0

    def current(self) -> int:
        return self.measured or self.estimate

    def observe(self, actual_input_tokens: int, message_tokens: int) -> None:
        """用一次真实调用校正。

        **取历史最小值，不是最大值。** 推导关系是：

            推导出的固定开销 = 真实 input_tokens − 我们数到的消息 token

        而「我们数到的消息 token」永远 ≤ 真实的消息 token（只数 content，漏掉
        工具调用的参数、消息结构本身的开销）。所以推导值是**系统性偏高**的，
        且会话越长偏得越多。

        取 min 就自然落在消息最短的那次观测上 —— 那次的误差最小，最接近真值。
        取 max 会让估计值随会话增长一路上爬，触发点被人为拉低，压缩开始空转。
        """
        if actual_input_tokens <= 0 or message_tokens <= 0:
            return
        derived = actual_input_tokens - message_tokens
        # 两个方向的合理性检查：
        # - 下限 100：system prompt 加工具 schema 不可能低于这个量级。
        #   没有下界的话，一次「消息估得特别准」的观测会给出接近 0 的差额，
        #   而取 min 会让它一路胜出，把估计压到失真。
        # - 上限 95%：差额几乎等于整个输入，说明这次消息估算偏得太狠，不可信。
        if derived < 100 or derived > actual_input_tokens * 0.95:
            return
        self.measured = derived if self.measured <= 0 else min(self.measured, derived)


@dataclass
class CompactionResult:
    messages: list
    layer: str = "none"          # none | layer1 | layer2 | layer3
    before_tokens: int = 0
    after_tokens: int = 0
    # 留痕：丢了什么、丢在哪。**只在真的压了的时候才有内容。**
    notes: list[str] = field(default_factory=list)
    # 告警：尝试过但失败的动作（摘要返回空、摘要调用抛异常、没有摘要函数）。
    # 与 notes 分开，是因为**失败必须可见，哪怕这一轮什么都没压下来** ——
    # 静默失败的后果是模型开始忘事而没人知道。
    warnings: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return self.layer != "none"


def compact(
    messages: Sequence,
    window: int,
    *,
    summarize: Callable[[str, int], str] | None = None,
    overhead: int = 0,
) -> CompactionResult:
    """按铁律依次尝试四层，第一层能解决就停。

    @param summarize 摘要函数 `(待压缩文本, 预算) -> 摘要文本`。
                     没传就跳过第 3 层 —— 不会假装压过。
    @param overhead  固定开销（system prompt + 工具 schema）的 token 数。
                     它在 messages 里看不到，但确实占窗口，不算进去触发点就永远到不了。
    """
    thresholds = Thresholds(window)
    message_tokens = total_tokens(messages)
    before = message_tokens + overhead
    result = CompactionResult(messages=list(messages), before_tokens=before)

    # 没到触发点什么都不做。**摊销不是在没满的时候也压。**
    if before < thresholds.trigger:
        result.after_tokens = before
        return result

    # ── 第 1 层：确定性清理（零 LLM）──────────────────────────
    stage1, notes1 = _layer1(messages)
    total1 = total_tokens(stage1) + overhead
    if total1 < thresholds.trigger:
        return _settle(result, stage1, "layer1", total1, notes1, [], overhead)

    # ── 第 2 层：轻量裁剪（零 LLM）────────────────────────────
    stage2, notes2, reclaimed = _layer2(stage1, thresholds)
    total2 = total_tokens(stage2) + overhead
    if reclaimed >= thresholds.min_reclaim and total2 < thresholds.trigger:
        return _settle(result, stage2, "layer2", total2, notes1 + notes2, [], overhead)

    # ── 第 3 层：摘要（唯一花钱的）────────────────────────────
    if summarize is None:
        # 前三层不够，但没有摘要函数 —— 只能带着裁剪结果返回，并如实说明
        return _settle(result, stage2, "layer2", total2,
                       notes1 + notes2,
                       ["第 3 层未执行：没有提供摘要函数"],
                       overhead)

    stage3, notes3, warnings3 = _layer3(stage2, thresholds, summarize)
    total3 = total_tokens(stage3) + overhead
    return _settle(result, stage3, "layer3", total3,
                   notes1 + notes2 + notes3, warnings3, overhead)


def _settle(
    result: CompactionResult,
    messages: list,
    layer: str,
    after_tokens: int,
    notes: list[str],
    warnings: list[str],
    overhead: int,
) -> CompactionResult:
    """收口。

    **什么都没降下来就不算压过。** 否则会出现「28 次压缩、token 从 8720 变成 8722」
    这种空转：日志刷屏、调用方白白替换一遍消息列表，而占用一点没降。

    但**告警不能跟着清掉** —— 摘要失败了却没压下来，正是最需要被发现的情形。
    """
    result.warnings = list(warnings)

    if after_tokens >= result.before_tokens:
        result.messages = list(result.messages)
        result.layer = "none"
        result.after_tokens = result.before_tokens
        result.notes = []
        return result

    result.messages = messages
    result.layer = layer
    result.after_tokens = after_tokens
    result.notes = notes
    return result


# ═══════════════════════════ 第 1 层 ═══════════════════════════

# 降级豁免线：短于这个长度的工具输出不动它
_PRUNE_MIN_CHARS = 200


def _layer1(messages: Sequence) -> tuple[list, list[str]]:
    """确定性清理：去重、旧工具结果降级成一行。

    **绝不调 LLM**；**绝不改对话正文**（HumanMessage / AIMessage 的 content 原样保留）。
    """
    notes: list[str] = []
    seen: set[str] = set()
    out: list = []
    degraded = 0
    deduped = 0

    for message in messages:
        if _is_tool_message(message):
            text = _msg_text(message)
            # 完全重复的工具输出留一份就够
            digest = hashlib.sha256(text.encode("utf-8", errors="replace")).hexdigest()
            if digest in seen:
                deduped += 1
                out.append(_replace_tool_content(message, "（与前面某次工具输出完全相同，已省略）"))
                continue
            seen.add(digest)

            # 长输出降级成一行。内容还在（有摘要行），只是变短。
            if len(text) > _PRUNE_MIN_CHARS:
                out.append(_replace_tool_content(message, _degrade_line(message, text)))
                degraded += 1
                continue

        out.append(message)

    if deduped:
        notes.append(f"第 1 层：去重省略 {deduped} 条重复工具输出")
    if degraded:
        notes.append(f"第 1 层：{degraded} 条工具输出降级为摘要行（内容仍在磁盘/工作区，未丢失）")
    return out, notes


def _degrade_line(message, text: str) -> str:
    """把长工具输出压成一行。**不是擦除** —— 保留了「这里发生过什么」。"""
    name = _tool_name(message) or "tool"
    first_line = next((ln.strip() for ln in text.splitlines() if ln.strip()), "")
    if len(first_line) > 120:
        first_line = first_line[:120] + "…"
    return f"[{name}] 输出 {len(text)} 字符已折叠；首行：{first_line}"


def _replace_tool_content(message, content: str):
    """复制一条 ToolMessage 并换掉 content。不改原对象。"""
    try:
        return message.model_copy(update={"content": content})
    except Exception:
        # 不是 pydantic 模型（比如 dict）—— 退化成原地改
        if isinstance(message, dict):
            clone = dict(message)
            clone["content"] = content
            return clone
        return message


# ═══════════════════════════ 第 2 层 ═══════════════════════════

_PRUNED_PLACEHOLDER = "（此处原有工具输出，因上下文预算已被裁掉）"


def _layer2(messages: Sequence, thresholds: Thresholds) -> tuple[list, list[str], int]:
    """擦掉保护窗口之外的工具输出。

    **绝不调 LLM**；**绝不碰对话消息**；**不够门槛绝不提交**。

    保护窗口从尾部往前数 —— 最近的工作上下文最重要，不能动。
    """
    # 从后往前累计 token，找出保护窗口的起点
    protected_from = len(messages)
    accumulated = 0
    for index in range(len(messages) - 1, -1, -1):
        accumulated += estimate_tokens(_msg_text(messages[index]))
        if accumulated > thresholds.protect_window:
            protected_from = index + 1
            break
        protected_from = index

    out: list = []
    reclaimed = 0

    for index, message in enumerate(messages):
        if index < protected_from and _is_tool_message(message):
            name = _tool_name(message).lower()
            # 白名单里的工具永不擦除
            if name in NEVER_PRUNE_TOOLS:
                out.append(message)
                continue
            text = _msg_text(message)
            reclaimed += estimate_tokens(text)
            out.append(_replace_tool_content(message, _PRUNED_PLACEHOLDER))
            continue
        out.append(message)

    notes = []
    if reclaimed:
        notes.append(
            f"第 2 层：裁掉 {protected_from} 条消息之前的工具输出，"
            f"回收约 {reclaimed} token（原位留下占位符）"
        )
    return out, notes, reclaimed


# ═══════════════════════════ 第 3 层 ═══════════════════════════

_SUMMARY_PROMPT = """把下面这段对话压缩成一份能让后续继续工作的摘要。

要求：
- 保留用户明确提出的约束与偏好，用户纠正过的原话要照抄
- 保留当前在做什么、做到哪一步、下一步是什么
- 保留关键的文件路径、函数名、配置项
- 不要贴完整代码，只记「在哪、是什么作用」
- 不要新增原文里没有的结论。原文明说「未验证」「推测」的，摘要里也要保留这个限定词

对话内容：
{content}

只输出摘要正文。"""


def _layer3(
    messages: Sequence,
    thresholds: Thresholds,
    summarize: Callable[[str, int], str],
) -> tuple[list, list[str], list[str]]:
    """切 head/summary/tail。

    **前面能解决就不许调**；**绝不切开 tool 调用/结果对**；**绝不动最新一条用户消息**。

    返回 `(messages, notes, warnings)`。warnings 里的内容即使这一轮什么都没压下来
    也会保留下来 —— 静默失败是这套设计里最贵的一类错误。
    """
    # 1. 先定位最新一条用户消息 —— 它在 tail 里，永远不动
    last_user_index = -1
    for index in range(len(messages) - 1, -1, -1):
        if _is_human(messages[index]):
            last_user_index = index
            break

    # 2. 从尾部按预算切出逐字保留的 tail
    tail_start = len(messages)
    accumulated = 0
    for index in range(len(messages) - 1, -1, -1):
        accumulated += estimate_tokens(_msg_text(messages[index]))
        if accumulated > thresholds.tail_budget:
            break
        tail_start = index

    # 3. 校正边界：不能把 tool 调用与它的结果切开
    tail_start = _snap_to_pair_boundary(messages, tail_start)
    # 最新一条用户消息必须留在 tail 里
    tail_start = min(tail_start, last_user_index) if last_user_index >= 0 else tail_start

    head = list(messages[:1])              # 第一条（通常是 system 或首个用户消息）永远保留
    middle = list(messages[1:tail_start])  # 待摘要
    tail = list(messages[tail_start:])

    if not middle:
        return list(messages), [], ["第 3 层尝试过但没有可压缩的中段（尾部预算已覆盖全部）"]

    content = "\n\n".join(
        f"{_msg_role(m).removesuffix('Message')}: {_msg_text(m)}" for m in middle
    )
    budget = thresholds.summary_budget(estimate_tokens(content))

    try:
        summary_text = summarize(content, budget)
    except Exception as exc:
        # 摘要失败就保留原样，不要「无摘要丢弃」中间段
        logger.warning("摘要失败，保留原文：%s", exc)
        return list(messages), [], [f"第 3 层失败：{exc}，中间段已原样保留"]

    # 空判定作用于模型的**原始输出**，不是后处理结果。
    # 否则「内容和后处理占位符」会看起来是非空的，这条检测就失效了。
    if not summary_text or not summary_text.strip():
        logger.warning("摘要模型返回空内容，保留原文")
        return list(messages), [], ["第 3 层失败：摘要为空，中间段已原样保留"]

    marker = (
        f"[以下是早期 {len(middle)} 条对话的摘要，原文已折叠。"
        f"摘要中没有出现的细节不代表不存在，需要时请重新查看工作区。]"
    )
    from langchain_core.messages import SystemMessage

    bridge = SystemMessage(content=f"{marker}\n\n{summary_text.strip()}")

    notes = [
        f"第 3 层：折叠中段 {len(middle)} 条为摘要（约 {budget} token 预算），"
        f"逐字保留尾部 {len(tail)} 条"
    ]
    return head + [bridge] + tail, notes, []


def _snap_to_pair_boundary(messages: Sequence, index: int) -> int:
    """把切点往后挪到不切开 tool 调用对的位置。

    切成「有 tool_calls 的 AIMessage 但缺对应 ToolMessage」会让供应商侧报协议错。
    """
    if index <= 0 or index >= len(messages):
        return index

    # 如果切点正好落在 ToolMessage 中间，说明它对应的 AIMessage 被切走了 —— 前移
    probe = index
    while probe > 0 and _is_tool_message(messages[probe]):
        probe -= 1
    if probe != index:
        # 找到这批 ToolMessage 所属的 AIMessage，从它开始保留
        return probe
    return index
