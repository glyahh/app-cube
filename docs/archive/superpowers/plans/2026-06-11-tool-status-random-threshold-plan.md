# 工具卡片状态切换阈值随机化 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将「写入文件」/「修改文件」工具卡片的 tier2 状态切换阈值从全局固定常量改为每张卡片独立随机，使多张卡片状态切换时刻错开。

**Architecture:** 仅改 2 个文件，不新增。`toolWaitStatus.ts` 中新增随机阈值生成函数，`resolveToolWaitStatus` 增加可选覆盖参数；`AppChatView.vue` 中 segment 创建时生成阈值并传入。

**Tech Stack:** Vue 3 + TypeScript（纯前端改动，无后端/数据库变更）

---

### Task 1: toolWaitStatus.ts — 新增 `generateTier2Threshold` 函数

**Files:**
- Modify: `ai-generate-code-frontend/src/utils/toolWaitStatus.ts`

- [ ] **Step 1: 定位并读取目标文件**

确保当前工作目录在 `ai-generate-code-frontend/`。

```bash
cd /d/mainJava/all\ Code/program/glyahh-ai-generate-code/ai-generate-code-frontend
```

- [ ] **Step 2: 在 toolWaitStatus.ts 的常量定义之后新增 `generateTier2Threshold` 函数**

在 `TOOL_MODIFY_STATUS_TIER_2_MS` 常量之后、`export type ToolWaitStatusMotion` 之前插入：

```typescript
/**
 * 根据工具类型生成随机化的 tier2 阈值（毫秒），
 * 使多张工具卡片的第二段状态切换时刻错开。
 *
 * - 写入文件：基准 15000ms，±40% → [9000, 21000]
 * - 修改文件：基准 7000ms，±20%  → [5600, 8400]
 *
 * 在每张 tool_request segment 创建时调用一次，
 * 结果存入 segment，生命周期内不变。
 */
export function generateTier2Threshold(toolName: string): number {
  if (toolName === WRITE_FILE) {
    const base = TOOL_WRITE_STATUS_TIER_2_MS // 15000
    const range = base * 0.4 // 6000
    return Math.floor(base - range + Math.random() * range * 2)
  }
  // MODIFY_FILE
  const base = TOOL_MODIFY_STATUS_TIER_2_MS // 7000
  const range = base * 0.2 // 1400
  return Math.floor(base - range + Math.random() * range * 2)
}
```

- [ ] **Step 3: 修改 `resolveToolWaitStatus` 签名，增加可选 `tier2ThresholdOverride` 参数**

替换原函数签名：

```typescript
export function resolveToolWaitStatus(
  toolName: string,
  elapsedMs: number,
  tier2ThresholdOverride?: number,
): ToolWaitStatusView | null {
  if (!isToolWaitStatusTool(toolName)) return null
  if (elapsedMs < TOOL_WAIT_STATUS_DELAY_MS) {
    return { show: false, text: '', iconSrc: '', motion: 'none' }
  }

  if (toolName === WRITE_FILE) {
    // 使用覆盖阈值（若传入），否则回退到全局常量
    const t2 = tier2ThresholdOverride ?? TOOL_WRITE_STATUS_TIER_2_MS
    if (elapsedMs < t2) {
      return {
        show: true,
        text: '正在写文件中',
        iconSrc: writeNormalIcon,
        motion: 'write-jitter',
      }
    }
    return {
      show: true,
      text: '文件有点大 已增加算力',
      iconSrc: writeBoostIcon,
      motion: 'write-jitter-fast',
    }
  }

  if (toolName === MODIFY_FILE) {
    const t2 = tier2ThresholdOverride ?? TOOL_MODIFY_STATUS_TIER_2_MS
    if (elapsedMs < t2) {
      return {
        show: true,
        text: '正在思考修改区域',
        iconSrc: modifyThinkingIcon,
        motion: 'none',
      }
    }
    return {
      show: true,
      text: '修改即将完成',
      iconSrc: modifyDoneIcon,
      motion: 'none',
    }
  }

  return null
}
```

**改动要点：**
- 原函数体中的 `TOOL_WRITE_STATUS_TIER_2_MS` 替换为 `t2`
- 原函数体中的 `TOOL_MODIFY_STATUS_TIER_2_MS` 替换为 `t2`
- `tier1` 判断（`TOOL_WAIT_STATUS_DELAY_MS`）不变

- [ ] **Step 4: 运行 lint 和 build 验证工具文件本身无语法错误**

```bash
cd /d/mainJava/all\ Code/program/glyahh-ai-generate-code/ai-generate-code-frontend
npx vue-tsc --noEmit src/utils/toolWaitStatus.ts 2>&1 | head -20
npx eslint src/utils/toolWaitStatus.ts 2>&1 | head -20
```

---

### Task 2: AppChatView.vue — 类型字段 + segment 创建 + 调用链

**Files:**
- Modify: `ai-generate-code-frontend/src/page/App/AppChatView.vue`

- [ ] **Step 1: 在第 171-179 行的 `UiToolRequestSegment` 类型中新增 `tier2ThresholdMs` 字段**

```typescript
type UiToolRequestSegment = {
  kind: 'tool_request'
  rawLabel: string
  toolName: string
  /** pending 胶囊开始计时（流式 tool_request 创建时写入） */
  createdAt?: number
  segmentId?: string
  /** 随机生成的 tier2 阈值（毫秒），取代全局常量，使多卡片切换时刻错开 */
  tier2ThresholdMs?: number
}
```

- [ ] **Step 2: 在文件顶部 import 中添加 `generateTier2Threshold`**

找到 `import { resolveToolWaitStatus, ... }` 的行（~line 54），追加导入：

```typescript
import {
  isToolWaitStatusTool,
  resolveToolWaitStatus,
  generateTier2Threshold,
  type ToolWaitStatusView,
} from '@/utils/toolWaitStatus'
```

- [ ] **Step 3: 在 segment 创建处（~line 1325-1329）传入随机阈值**

将：

```typescript
state.segments.push({
  kind: 'tool_request',
  rawLabel,
  toolName,
  createdAt: Date.now(),
})
```

改为：

```typescript
state.segments.push({
  kind: 'tool_request',
  rawLabel,
  toolName,
  createdAt: Date.now(),
  tier2ThresholdMs: generateTier2Threshold(toolName),
})
```

- [ ] **Step 4: 在 `getToolRequestStatusView` 函数（~line 2435-2440）中传入覆盖值**

将：

```typescript
return resolveToolWaitStatus(seg.toolName, workflowNowTs.value - seg.createdAt)
```

改为：

```typescript
return resolveToolWaitStatus(
  seg.toolName,
  workflowNowTs.value - seg.createdAt,
  seg.tier2ThresholdMs,
)
```

- [ ] **Step 5: 运行 lint 和 build 验证整体无问题**

```bash
cd /d/mainJava/all\ Code/program/glyahh-ai-generate-code/ai-generate-code-frontend
npm run lint 2>&1 | tail -20
npm run build 2>&1 | tail -30
```

- [ ] **Step 6: 提交**

```bash
cd /d/mainJava/all\ Code/program/glyahh-ai-generate-code
git add ai-generate-code-frontend/src/utils/toolWaitStatus.ts ai-generate-code-frontend/src/page/App/AppChatView.vue
git commit -m "feat: 工具卡片 tier2 阈值随机化，多卡片切换时刻错开

- 新增 generateTier2Threshold(toolName) 在 segment 创建时生成随机阈值
- 写入文件 ±40%（9000~21000ms），修改文件 ±20%（5600~8400ms）
- resolveToolWaitStatus 增加 tier2ThresholdOverride 可选参数
- UiToolRequestSegment 新增 tier2ThresholdMs 字段
- tier1（3000ms）保持全局固定不变"
```
