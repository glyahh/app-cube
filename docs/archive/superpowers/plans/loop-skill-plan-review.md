# Loop Skill 市场实施计划审查意见

> 审查范围：`.cursor/plans/loop_skill_市场_2387853e.plan.md` + 用户需求描述
> 审查方式：逐项核对现有代码库模式（AppController、UserAppApplyServiceImpl、ChatToGenCodeImpl、UserPersonalizationServiceImpl、RedisCacheManagerConfig、GlobalHeader、HomeView、AppChatView）

---

## 🔴 必须修改（逻辑矛盾或技术隐患）

### 1. Redis 通配删除需替换为精确定位

**位置**：计划 §二-4「写路径：loop update/delete → 删相关 `app:loop:ids:`*」

**问题**：Redis 没有通配删除命令。`KEYS loop:app:loop:ids:`* 阻塞 Redis；`SCAN` 虽不阻塞但 O(N)。

**代码事实**：现有项目在 `UserPersonalizationServiceImpl` 使用精确定位删除：

```java
// 项目现有模式 — 精确 key 删除，非通配
stringRedisTemplate.delete(buildAppKey(userId));
stringRedisTemplate.delete(buildStyleKey(userId));
```

**建议**：维护反向索引 `loop:app_ids:{loopId}`（Redis Set）。Loop 删除时读该 Set → 逐 key 删 `app:loop:ids:{appId}`。

---

### 2. SSE 注入顺序与现有实现矛盾

**位置**：计划 §二-3「用户本轮 message > Loop 注入 > 个性化前缀 > SystemMessage」

**问题**：现有 `injectPersonalizationPrompt` 是**前缀**注入，而非夹在消息和 SystemMessage 之间。

**代码事实**（ChatToGenCodeImpl.java:286-289）：

```java
private String injectPersonalizationPrompt(String message, Long userId) {
    String injectBlock = userPersonalizationService.buildInjectPrompt(userId);
    return StrUtil.isBlank(injectBlock) ? message : injectBlock + message;
    // 结果: [personalization] + 用户消息
}
```

**建议**：统一描述为「`[personalization] + 用户本轮消息 + [loop_skill]`」，并在注入服务中明确实现为后缀拼接：

```java
enhanced = injectPersonalizationPrompt(message, userId);       // → [personalization] + 消息
enhanced = loopInjectService.injectIfPresent(enhanced, ...);   // → [personalization] + 消息 + [loop_skill]
```

---

### 3. `/loop/get/vo` 端点应使用 @GetMapping

**位置**：计划 §二「`POST /loop/get/vo`」

**问题**：与现有 AppController 模式不一致。

**代码事实**（AppController.java:82-85）：

```java
@GetMapping("/get/vo")
public BaseResponse<AppVO> getMyAppVOById(@RequestParam String id, HttpServletRequest request) {
```

**建议**：`/loop/get/vo` 统一为 `@GetMapping`，保持全仓库约定一致。

---

## 🟡 建议补充（功能性缺口）

### 4. 缺少 LoopQueryRequest，不支持搜索和排序

**位置**：计划 §二「`/loop/my/list/page/vo`」「`/loop/good/list/page/vo`」

**代码事实**（AppQueryRequest.java）：

```java
// App 的分页查询 request 继承 PageRequest，支持 searchText
public class AppQueryRequest extends PageRequest implements Serializable {
    private String searchText;
    // 其他查询字段...
}
```

**建议**：定义 `LoopQueryRequest extends PageRequest`，含 `searchText`（模糊匹配 `loopName` + `description`）。两个分页端点均接收此参数。

---

### 5. workflowJson 缺少服务端校验

**位置**：计划 §一 workflowJson 定义、§二 POST /loop/add

**建议**：在 `LoopWorkflowCompiler` 中增加 `validateWorkflowJson(String json)` 方法：

- 校验 JSON 结构符合 `standard_v1` schema
- 校验 5 步 `key` 不重复、不缺失（`role`/`context`/`constraints`/`workflow`/`output`）
- 各步 `content` 用 `StrUtil.maxLength` 截断（参考 UserPersonalizationServiceImpl.java:92-93）
- content 总长度上限：`compiledPrompt` 建议 `< 65535`（MySQL text 上限）

---

### 6. `validateLoop` 中"用户自有"定义需明确

**位置**：计划 §二-2「校验 loopId 属于 app_loop 库或用户本人拥有的 Loop」

**问题**：双条件 OR 的边界模糊。

**建议**：明确选定一种定义，**推荐 (a)** `loop.userId == currentUserId`：

- 与 AppServiceImpl 的 `loginUser.getId().equals(app.getUserId())` 模式一致
- 仅需一次 `loopService.getById(loopId)` + 字段比对，无需额外跨表

---

### 7. 导入格式未定义

**位置**：计划 §二「`POST /loop/import`：multipart：.md / .json」

**建议**：补充格式规范示例：

**.md 格式**（frontmatter + body，body 直写 compiledPrompt）：

```markdown
---
name: 我的技能
description: 一个示例技能
visibility: public
---
## 角色设定
你是一个XX专家

## 约束与边界
- 输出简洁

## 执行步骤
1. 分析需求
2. 生成代码
```

**.json 格式**：

```json
{
  "loopName": "我的技能",
  "description": "示例技能",
  "visibility": "public",
  "workflowJson": {
    "templateId": "standard_v1",
    "steps": [
      { "key": "role", "label": "角色设定", "content": "你是一个XX专家" },
      { "key": "constraints", "label": "约束与边界", "content": "输出简洁" },
      { "key": "workflow", "label": "执行步骤", "content": "1. 分析需求\n2. 生成代码" }
    ]
  }
}
```

---

### 8. 缺少字段校验规格

**位置**：计划 §一 loop 表定义


| 字段               | 建议规则                    | 说明             |
| ---------------- | ----------------------- | -------------- |
| `loopName`       | `notBlank`，`max=128`    | 必填             |
| `description`    | `max=512`               | 选填             |
| `workflowJson`   | 服务端 JSON schema 校验      | 防脏数据           |
| `compiledPrompt` | `max=65535` 或 `text` 类型 | 可能较长           |
| `cover`          | 默认值 `''`                | 首次创建不可能有 cover |


---

## 🔵 交互/体验增强建议

### 9. LoopPickerTrigger N=0 时的行为

**建议**：N=0 时不隐藏按钮，显示「Loop」但点击后 Popover 内展示引导**「暂无 Loop，前往市场添加」**，带跳转链接。

### 10. AppLoopInjectBar 在 20+ Loop 时加搜索

**建议**：Radio 列表上方加搜索 input，动态过滤选项列表。

### 11. 空状态/加载态/错误态

**建议**：所有前端列表页（市场、我的 Loop、Picker、InjectBar）补充：

- `a-skeleton` 加载骨架屏
- 空数据引导文案 + 插图
- try/catch 错误 toast（参考现有 HomeView 的 message.warning 模式）

### 12. 审批通过后加提示

**建议**：管理端 approve 操作成功后，在响应中明确提示「已通过，Loop 已上架精选」，方便管理员转达用户。

---

## 🟢 实现一致性建议

### 13. 实施顺序改为并行

**原计划**：纯串行（DDL → CRUD → 注入 → 前端 → 审批）

**建议**（参考项目「并行 Agent 执行规则」）：


| Day   | 后端                                         | 前端                         |
| ----- | ------------------------------------------ | -------------------------- |
| Day 1 | DDL + Entity/Mapper + LoopWorkflowCompiler | 路由注册 + GlobalHeader + 页面骨架 |
| Day 2 | Loop CRUD API + import                     | 我的 Loop + LoopCreateView   |
| Day 3 | AppLoop API + LoopInjectService + SSE 改造   | LoopMarketView             |
| Day 4 | 审批 API + good_loop_page 缓存                 | 首页 Picker + ChatBar        |
| Day 5 | openapi2ts + 全链路验收                         | 管理端审批 UI                   |


---

### 14. `createApp` + `bindLoops` 需事务包裹

**位置**：计划 §二「扩展 AppAddRequest，AppServiceImpl.createApp 成功后内联绑定」

**建议**：

```java
@Transactional(rollbackFor = Exception.class)
public long createApp(User loginUser, AppAddRequest appAddRequest) {
    // ... 现有逻辑 ...
    boolean save = this.save(app);
    ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "创建应用失败");
    // 新增：绑定 Loop
    if (CollectionUtil.isNotEmpty(appAddRequest.getLoopIds())) {
        appLoopService.bindLoops(app.getId(), appAddRequest.getLoopIds());
    }
    return app.getId();
}
```

### 15. 前端 SSE 传参方式

**位置**：计划 §二「ChatToGenCodeController 增 @RequestParam Long loopId」

**发现**：现有 AppChatView.vue:2596-2602 用 `URLSearchParams` 构建 SSE URL：

```typescript
const query = new URLSearchParams({ appId: String(appId.value), message: prompt })
const url = `${apiBase}${endpoint}?${query.toString()}`
```

**建议**：`selectedLoopId` 有值时追加 `loopId` 参数：

```typescript
if (selectedLoopId.value) {
  query.set('loopId', String(selectedLoopId.value))
}
```

无需大改现有 URL 构建逻辑。

---

### 16. 全链路验收 checklist 补充

在现有验收标准基础上，建议补充以下逐项核验点：


| #   | 核验项              | 期望                                                |
| --- | ---------------- | ------------------------------------------------- |
| 1   | 未登录访问 `/loop`    | 重定向到登录页或显示登录提示                                    |
| 2   | 创建 Loop 时不填名称    | 前端阻止提交 + 后端 `ThrowUtils.throwIf`                  |
| 3   | 导入非法 `.md`       | 前端预览报错 + 后端返回 `PARAMS_ERROR`                      |
| 4   | 首页不选 Loop 创建应用   | 「无 Loop」照常创建，不传 loopIds 不报错                       |
| 5   | 聊天不选 Loop 直接发送   | SSE URL 无 loopId 参数，后端不注入不报错                      |
| 6   | 传入不属于本应用的 loopId | 后端跳过注入（非 4xx/5xx）                                 |
| 7   | 精选列表缓存 5min      | 管理员更新 priority 后，清缓存，下次请求新数据                      |
| 8   | 空值占位             | Redis 未命中时写入空值占位`"{}"` + TTL 60s                  |
| 9   | 并发创建应用+绑定 Loop   | `@Transactional` 确保 `createApp` 和 `bindLoops` 原子性 |
| 10  | 亮/暗主题切换          | 卡片栅格使用 `--bg-card`、`--text-base` 等 CSS 变量         |
| 11  | 375px 宽度         | 无横向滚动，Picker/Radio 列表可操作                          |


---

### 总结优先级


| 优先级     | 数量  | 说明                                                 |
| ------- | --- | -------------------------------------------------- |
| 🔴 必须改  | 3   | Redis 通配删除、注入顺序、端点命名                               |
| 🟡 建议补  | 5   | LoopQueryRequest、JSON 校验、validateLoop 定义、导入格式、字段校验 |
| 🔵 体验改  | 4   | N=0 行为、搜索、空/加载/错误态、审批通知                            |
| 🟢 实现一致 | 4   | 并行实施、事务、SSE 传参、验收 checklist                        |


