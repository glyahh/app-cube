# 浏览器端到端测试报告 — AI 记忆分层注入

**测试时间：** 2026/06/20 02:30 - 05:50 UTC+8
**测试账号：** 普通用户 / 11451400
**被测应用ID：** 425659309480640512（HTML 应用）
**测试环境：** http://localhost:5173/

---

## 测试1：Loop「精简」功能验证

### 步骤
1. 在 Loop 市场创建「精简」Loop（私有，角色设定为「精简输出」）
2. 进入应用对话页，选中「精简」Loop 按钮
3. 发送 prompt：「给我创作一个个人网站」

### 结果
- **loop_id 已落库** ✅
  - 第1轮：`loopId: 425466326852526100`
  - 第2轮（太难了）：`loopId: 425466326852526100`
  - 第4轮（给我创作一个个人网站）：`loopId: 425466326852526100`
  - 无 Loop 的消息：`loopId: null`
- **AI 回答精简度：部分生效** ⚠️
  - 带 Loop 的回复生成的是完整个人主页 HTML（约 80 行 CSS + HTML），长度与无 Loop 的响应相近
  - 推测原因：底层模型（deepseek-v4-flash）对 Loop 指令的遵循度有限；且 HTML 代码生成器的输出格式天然冗长

### 数据库验证
```sql
SELECT id, messageType, loopId FROM chat_history WHERE appId = 425659309480640512;
```
| id | messageType | loopId |
|----|-------------|--------|
| 425659311368077300 | user | **425466326852526100** |
| 425659456851705860 | ai | null |
| 425659546681114600 | user | **425466326852526100** |
| 425659686930251800 | ai | null |
| 425664767268438000 | user | null |
| 425664928711393300 | ai | null |
| 425668485913243650 | user | null |
| 425668694412095500 | ai | null |
| 425818018659856400 | user | **425466326852526100** |
| 425818186348130300 | ai | null |

---

## 测试2：用户风格设置验证

### 步骤
1. 进入「用户设置 → 个性化」页面
2. 填写：应用风格=「请生成精美的、带丰富交互效果的网页应用，使用渐变色和动画」
3. 填写：回答风格=「请用轻松活泼的语气回答，多用表情符号和俏皮话」
4. 点击保存

### 结果
- **user_personalization 表为空** ❌
  - `SELECT * FROM user_personalization WHERE userId = 384072054693875700;` → 无结果
  - 推测：样式值未成功写库，可能是前端的保存操作有误（textarea 填充不生效），或需要前端点击特定的「保存配置」按钮
  - **待排查**：验证 UI 中「保存配置」按钮的点击是否正确触发 API 调用

---

## 测试3：记忆压缩（memory_shrink）验证

### 步骤
- 共 4 轮用户对话，已超 `MAX_ROUNDS_BEFORE_SUMMARY = 3` 阈值

### 结果
- **memory_shrink 表为空** ❌
  - `SELECT * FROM memory_shrink WHERE appId = 425659309480640512;` → 无结果
  - 预期：压缩应在第 4 轮时触发（`trySummarizeOldestRoundsIfNeeded`），将最早 2 轮写入 summary
  - 可能原因：压缩的 AI 调用（deepseek-v4-flash）可能失败或返回格式不匹配，导致 catch 住异常后跳过

---

## 测试4：新字段兼容性验证

### 结果
- **chat_history.loopId 列存在** ✅
- **旧数据 loopId=null 正常读取** ✅
- **column 类型为 BIGINT NULL** ✅

---

## 发现的问题汇总

| # | 问题 | 严重度 | 说明 |
|---|------|--------|------|
| P1 | **Loop 精简效果不明显** | 重要 | 带 Loop 和不带 Loop 的 AI 回答长度接近，Loop 约束未有效传递给模型 |
| P2 | **用户风格未落库** | 重要 | user_personalization 表为空，需检查前端保存逻辑或 API 调用 |
| P3 | **压缩未触发** | 重要 | 4 轮对话后 memory_shrink 仍为空，`trySummarizeOldestRoundsIfNeeded` 可能异常跳过 |
| P4 | **loopId 入库与注入脱钩** | 次要 | 即使 validateLoop 失败（Loop 已删/不属于该应用），loopId 仍会写入 chat_history，与实际注入状态不一致 |
| P5 | **MemorySessionInjectSupport 修复验证** | 需要重启验证 | 代码改动后未重新启动服务，当前测试验证的是旧版风格注入路径（user message 前缀） |
| P6 | **deepseek-v4-flash 模型对 SystemMessage 风格指令遵循度** | 需研究 | 风格指令以 SystemMessage 注入后，模型对 SystemMessage 中约束性指令的遵循度低于 user message 前缀 |

---

## 建议后续步骤

1. **重新启动后端服务**后复测风格注入和 Loop 效果（当前测试时服务未重启，使用的是旧代码）
2. **修复风格保存**：定位 `user_personalization` 表为空的原因——可能是前端保存操作路径不对或 API 未调用
3. **修复压缩异常**：检查 `trySummarizeOldestRoundsIfNeeded` 日志，确认 AI 摘要调用是否异常
4. **增强 Loop 约束**：考虑在 Loop 的「约束与边界」中添加更强烈的精简指令（如「所有回答不超过 100 字」）
