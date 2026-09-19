# 工具卡片状态切换阈值随机化

## 背景

用户与 AI 对话过程中，多个「写入文件」/「修改文件」工具请求卡片的状态切换（包括文案变化与图标动画切换）使用全局固定阈值常量。所有卡片在同一固定时刻同步切换，观感机械，与后端真实执行节奏脱节。

### 当前实现

- `src/utils/toolWaitStatus.ts` 定义了 3 个全局固定常量：
  - `TOOL_WAIT_STATUS_DELAY_MS = 3000` — 首次显示延迟
  - `TOOL_WRITE_STATUS_TIER_2_MS = 15000` — 写入文件的第二段切换
  - `TOOL_MODIFY_STATUS_TIER_2_MS = 7000` — 修改文件的第二段切换
- `resolveToolWaitStatus(toolName, elapsedMs)` 是无状态纯函数，给定相同 elapsedMs 返回相同结果
- `AppChatView.vue` 通过 `workflowNowTs` 每 1 秒刷新，`getToolRequestStatusView` 计算 `elapsedMs = workflowNowTs - seg.createdAt` 后传入 `resolveToolWaitStatus`
- 同一消息中多张工具卡片的 `createdAt` 几乎相同（在同一 `processAssistantChunkIntoUiState` 调用中创建），导致所有卡片同步跳转

## 目标

将 tier2 阈值改为**每张卡片独立生成并固定**的随机值，使同一会话中多张卡片的状态切换时刻明显错开。tier1（首次显示）保持全局 3s 不变。

## 约束

- 仅改前端，最小改动量
- 随机阈值在 `tool_request` segment 创建时生成，同卡片生命周期内不抖动
- 写入文件 ±40% 浮动，修改文件 ±20% 浮动
- 保留 `prefers-reduced-motion` CSS 行为
- `npm run build` 与 `npm run lint` 通过

## 方案

**方案 A（采用）**：在 `UiToolRequestSegment` 上新增 `tier2ThresholdMs` 字段，创建时由 `generateTier2Threshold(toolName)` 生成并固化。`resolveToolWaitStatus` 接受可选覆盖参数取代全局常量。

**不采用的方案**：
- **方案 B（Map 缓存）**：`resolveToolWaitStatus` 内部维护 `Map<segmentId, threshold>` — 有状态函数难测试，Map 需手动清理
- **方案 C（Composable）**：抽取 `useCardThresholdStore()` — 过度工程，仅 2 行数值逻辑不值得独立 composable

## 详细设计

### 改动范围

仅改 2 个文件，不新增文件：

| 文件 | 改动 |
|------|------|
| `src/utils/toolWaitStatus.ts` | 新增 `generateTier2Threshold(toolName)`；修改 `resolveToolWaitStatus` 签名增加可选 `tier2ThresholdOverride` |
| `src/page/App/AppChatView.vue` | 类型 `UiToolRequestSegment` 新增字段；segment 创建时调用生成函数；调用 `resolveToolWaitStatus` 时传入覆盖值 |

### 随机策略

| 工具类型 | 基准值 | 浮动范围 | 最小值 | 最大值 |
|----------|--------|----------|--------|--------|
| 写入文件 | 15000ms | ±40% | 9000ms | 21000ms |
| 修改文件 | 7000ms | ±20% | 5600ms | 8400ms |

使用 `Math.random()` 均匀分布。`Math.floor` 取整到毫秒。

### 单调递增保证

tier1 = 3000ms，写入 tier2 最小值 9000ms，修改 tier2 最小值 5600ms。tier2 始终大于 tier1，单调递增天然成立。

### 数据流

```
Segment 创建（line ~1325-1329）    每 1s tick (workflowNowTs)        模板渲染
├─ generateTier2Threshold(toolName)  ├─ getToolRequestStatusView()   ├─ ToolRequestHint
│  → random(9000~21000) / → 5600~8400  │  elapsed = now - createdAt   │  show/showStatus
│  → seg.tier2ThresholdMs            │  resolveToolWaitStatus(        │  text, icon, motion
│  → push state.segments             │    toolName, elapsed,         │
│                                    │    seg.tier2ThresholdMs)      │
│                                    │  → ToolWaitStatusView         │
```

### 验收

1. 连续触发多个「写入文件」工具请求，卡片状态切换时刻明显错开
2. 连续触发多个「修改文件」工具请求，卡片状态切换时刻明显错开
3. 单张卡片单调递增：show:false→show:true→tier2 变化，无来回闪烁
4. `npm run build` 通过，`npm run lint` 通过
5. `prefers-reduced-motion` 下动画行为不变

## 伪代码

```typescript
// toolWaitStatus.ts

//// 新增：根据工具类型生成随机 tier2 阈值
export function generateTier2Threshold(toolName: string): number {
  if (toolName === WRITE_FILE) {
    // ±40%
    const base = TOOL_WRITE_STATUS_TIER_2_MS // 15000
    const range = base * 0.4 // 6000
    return Math.floor(base - range + Math.random() * range * 2) // [9000, 21000]
  }
  // MODIFY_FILE: ±20%
  const base = TOOL_MODIFY_STATUS_TIER_2_MS // 7000
  const range = base * 0.2 // 1400
  return Math.floor(base - range + Math.random() * range * 2) // [5600, 8400]
}

//// 修改：接受可选阈值覆盖
export function resolveToolWaitStatus(
  toolName: string,
  elapsedMs: number,
  tier2ThresholdOverride?: number,
): ToolWaitStatusView | null {
  // tier1（首次显示）不变：elapsedMs < 3000 → { show: false }
  if (!isToolWaitStatusTool(toolName)) return null
  if (elapsedMs < TOOL_WAIT_STATUS_DELAY_MS) {
    return { show: false, text: '', iconSrc: '', motion: 'none' }
  }

  // tier2：使用覆盖值（若有）代替全局常量
  const t2 = tier2ThresholdOverride ??
    (toolName === WRITE_FILE ? TOOL_WRITE_STATUS_TIER_2_MS : TOOL_MODIFY_STATUS_TIER_2_MS)

  if (elapsedMs < t2) {
    // tier1 展示态
    ...
  }
  // tier2 展示态
  ...
}
```

```typescript
// AppChatView.vue — UiToolRequestSegment 类型
type UiToolRequestSegment = {
  kind: 'tool_request'
  rawLabel: string
  toolName: string
  createdAt?: number
  segmentId?: string
  /** 随机生成的 tier2 阈值（毫秒），取代全局常量 TOOL_X_TIER_2_MS */
  tier2ThresholdMs?: number
}

// 创建处（~line 1325-1329）
state.segments.push({
  kind: 'tool_request',
  rawLabel,
  toolName,
  createdAt: Date.now(),
  tier2ThresholdMs: generateTier2Threshold(toolName),
})

// getToolRequestStatusView（~line 2439）
return resolveToolWaitStatus(
  seg.toolName,
  workflowNowTs.value - seg.createdAt,
  seg.tier2ThresholdMs,  // <-- 传入段级别覆盖
)
```

## 不变区域

- `ToolRequestHint.vue` — 不改
- CSS 动画定义和 `@media (prefers-reduced-motion: reduce)` — 不改
- `workflowNowTs` tick 间隔 — 不改
- tier1 的 `TOOL_WAIT_STATUS_DELAY_MS = 3000` — 不改
