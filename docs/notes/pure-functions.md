# 纯函数 + 数据驱动状态切换 — 项目实战分析

> 类型：技术说明与学习复盘；旧架构、类名和数字按原记录时点理解。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

## 问题回顾

在 `ai-generate-code-frontend/` 中，工具请求卡片（"写入文件" / "修改文件"）的状态切换由以下机制驱动：

```
workflowNowTs (每 1 秒 setInterval tick)
        │
        ▼
getToolRequestStatusView()
        │  elapsedMs = workflowNowTs - seg.createdAt
        ▼
resolveToolWaitStatus(toolName, elapsedMs)
        │  对比全局常量:
        │  TOOL_WAIT_STATUS_DELAY_MS   = 3000
        │  TOOL_WRITE_STATUS_TIER_2_MS = 15000
        │  TOOL_MODIFY_STATUS_TIER_2_MS = 7000
        ▼
ToolWaitStatusView { show, text, iconSrc, motion }
```

**问题根因：** `resolveToolWaitStatus` 是个纯函数——给定相同 `(toolName, elapsedMs)`，永远返回相同的 `ToolWaitStatusView`。而所有 tool_request segment 的 `createdAt` 几乎相同（在同一轮 SSE 流式解析中创建），加上 `workflowNowTs` 是全局 tick，意味着所有卡片的 `elapsedMs` 在同一时刻撞上同一个全局常量。结果：多张卡片同步切换，观感机械。

## 纯函数 vs 有状态函数

### 纯函数定义

> 相同的输入 → 永远相同的输出；无副作用。

改动前的 `resolveToolWaitStatus`：

```typescript
// 纯函数：给定 (toolName, elapsedMs) → 唯一确定的 ToolWaitStatusView
export function resolveToolWaitStatus(
  toolName: string,
  elapsedMs: number,
): ToolWaitStatusView | null {
  if (elapsedMs < TOOL_WAIT_STATUS_DELAY_MS) return { show: false, ... }
  if (toolName === WRITE_FILE) {
    if (elapsedMs < TOOL_WRITE_STATUS_TIER_2_MS) { ... }
    else { ... }
  }
  if (toolName === MODIFY_FILE) {
    if (elapsedMs < TOOL_MODIFY_STATUS_TIER_2_MS) { ... }
    else { ... }
  }
  ...
}
```

**优点：**
- 可测试：`assert(resolveToolWaitStatus(WRITE_FILE, 5000))` 永远返回同一个结果
- 可预测：不依赖外部状态，不改变外部状态
- 无竞态：不读写共享变量

**它为什么会造成同步切换问题？因为问题不在于纯函数本身，而在于输入层的耦合。**

## 为什么「把纯函数改成有状态」不是好方案

一个直观但错误的解法：

```typescript
// ❌ 坏方案：把状态塞进函数内部
const _cardTimers = new Map<string, number>()

export function resolveToolWaitStatus(toolName: string, elapsedMs: number, segmentId: string) {
  if (!_cardTimers.has(segmentId)) {
    _cardTimers.set(segmentId, generateRandomThreshold()) // 首次调用时生成
  }
  const threshold = _cardTimers.get(segmentId)!
  // ... 用 threshold 判断
}
```

**问题：**
1. **隐式状态** — `_cardTimers` Map 在模块级“悬挂”着，测试间不清空会导致相互污染
2. **生命周期耦合** — segment 销毁时谁去清理 Map？漏清理 = 内存泄漏
3. **不可预测** — 同一 `(toolName, elapsedMs, segmentId)` 在首次调用 vs 后续调用返回不同结果
4. **并发不安全** — 流式渲染中 tick 和 segment 创建可能交错执行

## 最终方案：在数据源层注入随机性，保持纯函数不变

**核心思想：不改变判断逻辑的纯度，而是在数据产生时就为每张卡片固化差异化数据。**

```
数据产生层（segment 创建时）        判断层（纯函数）
──────────────────────           ─────────────────────
segment.createdAt = Date.now()   resolveToolWaitStatus(
segment.tier2ThresholdMs =         toolName,
  generateTier2Threshold(          elapsedMs,         ← workflowNowTs - createdAt
    toolName                       tier2ThresholdMs   ← 传入覆盖值
  )                              )
                                    ↓
                                  ToolWaitStatusView
                                    (唯一确定)
```

### 具体实现（3 个关键改动点）

**① 数据产生：工具函数生成随机阈值**

`src/utils/toolWaitStatus.ts` — 新增 `generateTier2Threshold`：

```typescript
export function generateTier2Threshold(toolName: string): number {
  if (toolName === WRITE_FILE) {
    const base = TOOL_WRITE_STATUS_TIER_2_MS // 15000
    const range = base * 0.4                 // 6000
    return Math.round(base - range + Math.random() * range * 2)
  }
  // MODIFY_FILE
  const base = TOOL_MODIFY_STATUS_TIER_2_MS // 7000
  const range = base * 0.2                  // 1400
  return Math.round(base - range + Math.random() * range * 2)
}
```

这是**纯的随机生成函数**：给定 `toolName` 返回一个来自确定区间的随机整数。它不关心谁调用、不存储结果。

**② 数据承载：segment 固化阈值**

`src/page/App/AppChatView.vue` — 创建 segment 时调用并存入：

```typescript
state.segments.push({
  kind: 'tool_request',
  rawLabel,
  toolName,
  createdAt: Date.now(),
  tier2ThresholdMs: generateTier2Threshold(toolName), // ← 数据在此固化
})
```

`tier2ThresholdMs` 在 segment 创建时生成并写入对象。此后**永不修改**——同一张卡片的整个生命周期内，这个值保持不变。segment 被垃圾回收时，其 `tier2ThresholdMs` 自然消失，无需手动清理。

**③ 数据传递：纯函数接受覆盖参数而非硬编码常量**

`src/utils/toolWaitStatus.ts` — `resolveToolWaitStatus` 保持纯函数形态：

```typescript
export function resolveToolWaitStatus(
  toolName: string,
  elapsedMs: number,
  tier2ThresholdOverride?: number, // ← 可选覆盖，不存在时仍取全局常量
): ToolWaitStatusView | null {
  ...
  if (toolName === WRITE_FILE) {
    const t2 = tier2ThresholdOverride ?? TOOL_WRITE_STATUS_TIER_2_MS // ← 降级策略
    if (elapsedMs < t2) { ... }
    ...
  }
}
```

关键设计：`tier2ThresholdOverride` 是**可选参数**。不传入时，`??` 运算符降级为全局常量。这意味着：

- **新场景传覆盖值** → 每张卡片用自己固化的随机阈值，相互错开
- **旧场景不传值** → 行为与改动前完全一致，零侵入
- **可测试** → 测试中传固定值 `tier2ThresholdOverride = 12000` → 永远返回确定结果

**④ 数据消费：调用时传入卡片独有数据**

`src/page/App/AppChatView.vue` — `getToolRequestStatusView` 桥梁函数：

```typescript
function getToolRequestStatusView(m: ChatMessage, idx: number): ToolWaitStatusView | null {
  const segs = getMessageUiSegments(m)
  const seg = segs[idx]
  if (!seg || seg.kind !== 'tool_request' || !seg.createdAt) return null
  return resolveToolWaitStatus(
    seg.toolName,
    workflowNowTs.value - seg.createdAt,   // ← 全局 tick 计算 elapsed
    seg.tier2ThresholdMs,                  // ← 每张卡片独有的随机阈值
  )
}
```

## 为什么这是「数据驱动」而非「状态驱动」

| 维度 | 状态驱动（State-Driven） | 数据驱动（Data-Driven） |
|------|------------------------|------------------------|
| 差异来源 | 函数内部维护 Map/cache，首次调用时缓存随机值 | segment 创建时生成差异化数据，作为普通字段存储 |
| 生命周期 | 隐式存活在模块级闭包中，需手动清理 | 与 segment 对象同生同死，GC 自动回收 |
| 可测试性 | 需要 beforeEach/afterEach 清理状态 | 纯函数测试，无环境依赖 |
| 可预测性 | 同一输入在不同时间调用可能不同 | 同一输入永远相同输出 |
| 数据流向 | 函数拉取（pull）隐式状态 | 数据推入（push）函数参数 |

**这条线的本质区别：差异性是固化在数据对象中的字段，而不是存储在函数外围的隐式状态。**

## 扩展到其他场景的启发

这个模式可以推广到任何「多实例共享同一判断逻辑但需要差异化行为」的场景：

```typescript
// ❌ 坏：共享全局常量
function shouldExpire(createdAt: number): boolean {
  return Date.now() - createdAt > SESSION_TTL  // 所有 session 同时过期
}

// ✅ 好：数据携带差异化参数
type Session = {
  createdAt: number
  ttlMs: number  // 创建时随机生成 [TTL_MIN, TTL_MAX]
}
function shouldExpire(session: Session): boolean {
  return Date.now() - session.createdAt > session.ttlMs
}
```

判断逻辑不变、可测试、可预测。差异性存在于数据中，而不是状态中。
