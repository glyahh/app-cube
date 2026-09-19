# Loop Skill 市场设计文档

> 版本：v2 | 日期：2026-06-17 | 状态：待实施

## 1. 概述

在 glyahh-ai-generate-code 中新增 Loop（Skill）市场全链路：后端独立领域模型 + Redis 缓存注入、顶栏「Loop」市场页、用户菜单「我的 Loop」、标准 5 步模板创作/导入、首页多选入库、聊天页单选注入。复用现有 App 精选/审批架构。

Loop 与 skill 同义：可复用的指令文档，预加载进生成上下文，控制 AI 的行为模式与输出风格。

## 2. 架构图

```
Frontend                         Backend                           Storage
───────                          ───────                           ───────
LoopMarketView ──→ LoopController ──→ LoopService ──→ MySQL (loop)
MyLoopView      ──→               ──→              ──→ Redis (loop:compiled:{id})
LoopCreateView  ──→               ──→ LoopWorkflowCompiler
LoopEditView    ──→
                                  
HomeView        ──→ AppLoopController ──→ AppLoopService ──→ MySQL (app_loop)
LoopPickerTrigger                               
                                  
AppChatView     ──→ ChatToGenCodeController ──→ ChatToGenCodeImpl
AppLoopInjectBar                                    └─→ LoopInjectService ──→ Redis/MySQL
                                                        
AdminLoopManage  ──→ LoopController(admin)  ──→ user_loop_apply
AdminApplyManage
```

## 3. 数据模型

### 3.1 `loop` 表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint | 雪花 ID |
| `loopName` | varchar(128) | 名称 |
| `description` | varchar(512) | 市场简介 |
| `cover` | varchar(256) | 封面/icon key（OSS 或渐变色占位） |
| `userId` | bigint | 创建者 |
| `priority` | int | 精选阈值 >= 99 |
| `workflowJson` | text | 标准模板步骤 JSON |
| `compiledPrompt` | text | 编译后的注入文本 |
| `sourceType` | varchar(32) | `created` / `imported` |
| `visibility` | varchar(32) | `private` / `public` |
| `isDelete` | tinyint | 逻辑删除 |
| `createTime` | datetime | |
| `updateTime` | datetime | |

标准 5 步模板 v1：
```json
{
  "templateId": "standard_v1",
  "steps": [
    {"key": "role", "label": "角色设定", "placeholder": "你扮演…", "content": ""},
    {"key": "context", "label": "背景上下文", "content": ""},
    {"key": "constraints", "label": "约束与边界", "content": ""},
    {"key": "workflow", "label": "执行步骤", "content": ""},
    {"key": "output", "label": "输出格式", "content": ""}
  ]
}
```

### 3.2 `app_loop` 表

| 字段 | 类型 | 说明 |
|------|------|------|
| `appId` | bigint | |
| `loopId` | bigint | |
| `addedFrom` | varchar(32) | `creation` / `chat` / `market` |
| `createTime` | datetime | |

唯一索引 `(appId, loopId)`。

### 3.3 `user_loop_apply` 表

镜像 `user_app_apply`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint | 雪花 |
| `loopId` | bigint | |
| `userId` | bigint | |
| `operate` | tinyint | 1=申请精选 |
| `status` | tinyint | 0=待审 1=通过 2=拒绝 |
| `applyReason` | varchar(512) | |
| `reviewUserId` | bigint | |
| `reviewRemark` | varchar(512) | |
| `reviewTime` | datetime | |
| `isDelete` / 时间戳 | | |

## 4. 后端 API

### 4.1 Loop CRUD

遵循 `backend-design` SKILL 规范：`BaseResponse<T>` + `ThrowUtils` + `@RequiredArgsConstructor`。

公共请求体 `LoopQueryRequest extends PageRequest`，含 `searchText`（模糊匹配 `loopName` + `description`），两个分页端点均接收。

| 端点 | 说明 | 关键逻辑 |
|------|------|----------|
| `POST /loop/add` | 创建 | `LoopWorkflowCompiler` 编译 `workflowJson→compiledPrompt`，Redis 写缓存；校验 `loopName` 非空、`workflowJson` 格式合法 |
| `POST /loop/update` | 更新 | 重编译，删 Redis `loop:compiled:{id}` |
| `POST /loop/delete` | 逻辑删除 | 删 Redis `loop:compiled:{id}`；读 Redis Set `loop:app_ids:{loopId}` → 逐 key 删 `app:loop:ids:{appId}`，再删该 Set |
| `GET /loop/get/vo` | 详情 | @GetMapping，含 workflowJson |
| `POST /loop/my/list/page/vo` | 我的 Loop 分页 | userId 过滤 + searchText 模糊匹配 |
| `POST /loop/good/list/page/vo` | 精选市场 | `priority>=99 && isDelete=0 && visibility=public`，`@Cacheable` 5min，+ searchText 模糊匹配 |
| `POST /loop/import` | 导入 | 解析 `.md`/`.json`→workflowJson+compiledPrompt；支持前端「自动解析」按钮传入预填格式 |
| `POST /loop/apply` | 申请精选 | 写 `user_loop_apply`，status=0 |
| `POST /loop/admin/list/page/vo` | 管理列表 | 全部 Loop 含已删 |
| `POST /loop/admin/update` | 管理更新 | 改 priority/visibility，清精选缓存 |

字段校验规格：
- `loopName`：必填，1~128 字符
- `workflowJson`：必填，JSON 结构合法，steps 数组至少 1 项，每项 key 非空
- `visibility`：仅 `public` / `private`
- `sourceType`：仅 `created` / `imported`
- `description`：最长 512 字符

### 4.2 App Loop 库

| 端点 | 说明 | 关键逻辑 |
|------|------|----------|
| `POST /app/loop/bind` | 批量绑定 | 批量 insert `app_loop`，删 `app:loop:ids:{appId}` |
| `POST /app/loop/add` | 追加单个 | 唯一索引防重复 |
| `POST /app/loop/list/vo` | 查询应用库 | 含 loop 名称/简介，`@Cacheable` |
| `POST /app/loop/remove` | 移除 | 删 Redis |

扩展 `AppAddRequest` 增加 `List<Long> loopIds`；`AppServiceImpl.createApp` 成功后内联 `appLoopService.bindLoops(appId, loopIds)`，`createApp` 与 `bindLoops` 由 `@Transactional` 包裹保证原子性。

### 4.3 SSE 注入

`ChatToGenCodeController` 仅 `/gen/code` 端点加 `@RequestParam(required=false) Long loopId`（`/gen/workflow` 暂不支持 Loop）。

`LoopInjectService`：
- `validateLoop(userId, appId, loopId)`：查 `app_loop`，不属应用库或非用户自有→跳过（不抛异常）
- `buildInjectBlock(loopId)`：读 Redis `loop:compiled:{id}`，miss 则读 MySQL 回填
- 输出 tagged 块：`[loop_skill name="xxx"]\n{compiledPrompt}\n[/loop_skill]`

`ChatToGenCodeImpl` 接入：
```java
// 注入顺序：personalization → message → loop_skill（后缀拼接）
String enhanced = injectPersonalizationPrompt(message, userId);
enhanced = loopInjectService.injectIfPresent(enhanced, userId, appId, loopId);
```

最终 userMessage 结构（从近到远）：
```
[personalization]...[/personalization]
用户本轮消息内容
[loop_skill name="..."]...[/loop_skill]
(SystemMessage)
```

### 4.4 Redis 缓存策略

Cache-Aside 模式，参考 `UserPersonalizationServiceImpl`：

| Key | 类型 | 值 | TTL |
|-----|------|----|-----|
| `loop:compiled:{loopId}` | String | compiledPrompt 纯文本 | 30min base + random(5min) 抖动 |
| `app:loop:ids:{appId}` | String | loopId 列表 JSON | 1h |
| `loop:app_ids:{loopId}` | Set | 引用了该 Loop 的 appId 集合 | 持久（缓存删除时用） |
| 空值占位 | String | `"{}"` | 60s 防穿透 |

写路径：
- Loop update/delete → 删 `loop:compiled:{id}`
- Loop delete → 读 `loop:app_ids:{loopId}` Set → 逐 key 删 `app:loop:ids:{appId}`，再删该 Set
- app_loop bind/add → 写 `loop:app_ids:{loopId}`（SADD）+ 删 `app:loop:ids:{appId}`
- app_loop remove → 删 `loop:app_ids:{loopId}`（SREM）+ 删 `app:loop:ids:{appId}`

### 4.5 LoopWorkflowCompiler

遍历 `steps[]`，过滤空 content，按顺序拼接：
```
## 角色设定
{content}

## 背景上下文
{content}

## 约束与边界
{content}

## 执行步骤
{content}

## 输出格式
{content}
```

导入 `.md` 时 body 直写 `compiledPrompt`，steps 自动生成为单步。

## 5. 前端

### 5.1 路由

| path | 组件 | 说明 |
|------|------|------|
| `/loop` | `page/Loop/LoopMarketView.vue` | 精选/探索市场 |
| `/user/loops` | `page/Loop/MyLoopView.vue` | 我的 Loop 栅格 |
| `/loop/create` | `page/Loop/LoopCreateView.vue` | 5步模板创作 |
| `/loop/:id/edit` | `page/Loop/LoopEditView.vue` | 编辑（可复用 Create） |

### 5.2 GlobalHeader 改动

- `baseMenuItems` 在「代码生成」后插 `{ key: 'loop', path: '/loop', label: 'Loop' }`
- 用户下拉在「个人设置」后加「我的 Loop」→ `/user/loops`

### 5.3 LoopMarketView

- 分区：精选 Loop（priority>=99）+ 探索（public 分页）
- 卡片：SVG 图标区 + 名称 + 描述 + 作者 + 「加入我的应用」/「预览详情」
- 动效：hover 微光边框、stagger 入场、`prefers-reduced-motion` 降级
- CSS 变量复用 `--bg-card`、`--text-base`，兼容亮暗主题

### 5.4 MyLoopView

- 顶部 CTA：创建 Loop / 导入 Skill
- 响应式 grid（2~4 列）
- 每卡：名称、简介、步骤数、public/私有标识、申请精选按钮
- 点击进编辑页

### 5.5 LoopCreateView

- 固定 5 步流程示意图（只读 SVG 节点+连线）
- 每步 textarea + label + placeholder
- 底部：名称、简介、可见性
- 保存调 `POST /loop/add`
- 导入 `.md`：拖拽/选择 `.md`，预览后确认
- **自动解析**：新增「解析导入」按钮，粘贴下列格式自动拆解填入各字段：
  ```text
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
  解析 frontmatter → `loopName/description/visibility`，body 按 `## ` 标题拆步骤→填入 `workflowJson.steps`

### 5.6 LoopPickerTrigger（HomeView）

- 位置：`.prompt-actions` 内 `?` 与工作流按钮之间
- N=0 时不隐藏按钮，显示「Loop」，点击后 Popover 内展示引导 **「暂无 Loop，前往市场添加」**，带跳转 `/loop` 链接
- N>0 时显示「Loop · N」，点击后 Popover
- Checkbox 多选：我的 Loop + 公开市场简要列表
- 创建应用时传 `loopIds`

### 5.7 AppLoopInjectBar（AppChatView）

- 位置：`.chat-input-bar` 上方
- 加载 `app/loop/list/vo`
- Radio 单选（含「无」选项）
- 20+ Loop 时 Radio 列表上方加搜索 input，动态过滤选项列表
- 可追加 Loop 市场到本应用
- `sendMessage` 构建 SSE URL 时附加 `loopId`
- `selectedLoopId` 本地 state，切换不影响库

### 5.8 管理端

- `AdminLoopManage.vue` 新页或 AdminAppManage Tab
- `AdminApplyManage.vue` 扩展 `user_loop_apply` Tab
- 审批通过→`loop.priority=99`→清 `good_loop_page` 缓存
- **审批提示**：approve 成功后响应明确提示「已通过，Loop 已上架精选」

### 5.9 API 客户端生成

- 后端接口改完后执行 `npm run openapi2ts` 生成 `src/api/loopController.ts` 等
- 勿手改 `src/api/` 下生成文件

## 6. 实施顺序

Batch 1（并行无依赖）：
- A：DDL + Loop Entity/Mapper + LoopWorkflowCompiler
- B：前端路由注册 + GlobalHeader + 页面骨架

Batch 2（依赖 A）：
- C：LoopService + LoopController（CRUD + 导入 + 精选 + 审批 API）
- D：AppLoopService + AppLoopController + AppAddRequest + createApp 内联绑定

Batch 3（依赖 C+D）：
- E：LoopInjectService + ChatToGenCodeImpl + Controller loopId + Redis
- F：LoopMarketView + MyLoopView + LoopCreateView/LoopEditView

Batch 4（依赖 E+F）：
- G：HomeView LoopPickerTrigger + AppChatView AppLoopInjectBar
- H：AdminLoopManage + AdminApplyManage 扩展

Batch 5：openapi2ts + 编译验证 + 全链路走查

## 7. 验收标准

### 功能验收

| # | 核验项 | 期望 |
|---|--------|------|
| 1 | 未登录访问 `/loop` | 重定向到登录页或显示登录提示 |
| 2 | 创建 Loop 时不填名称 | 前端阻止提交 + 后端 `ThrowUtils.throwIf` |
| 3 | 导入非法 `.md` | 前端预览报错 + 后端返回 `PARAMS_ERROR` |
| 4 | 首页不选 Loop 创建应用 | 「无 Loop」照常创建，不传 `loopIds` 不报错 |
| 5 | 聊天不选 Loop 直接发送 | SSE URL 无 `loopId` 参数，后端不注入不报错 |
| 6 | 传入不属于本应用的 loopId | 后端跳过注入（非 4xx/5xx） |
| 7 | 精选列表缓存 5min | 管理员更新 `priority` 后，清缓存，下次请求新数据 |
| 8 | 空值占位 | Redis 未命中时写入空值占位 `"{}"` + TTL 60s |
| 9 | 并发创建应用 + 绑定 Loop | `@Transactional` 确保 `createApp` 和 `bindLoops` 原子性 |
| 10 | 亮/暗主题切换 | 卡片栅格使用 `--bg-card`、`--text-base` 等 CSS 变量 |
| 11 | 375px 宽度 | 无横向滚动，Picker/Radio 列表可操作 |

### 全流程验收

- 用户可在「我的 Loop」用标准 5 步模板创建 Loop，或导入 `.md`／自动解析 skill
- 顶栏「Loop」可浏览精选与他人公开 Loop；可申请精选，管理员可审批
- 首页创建应用时可多选 Loop 写入应用库
- 聊天页底部展示应用 Loop 库；每轮仅选 1 个注入；SSE 请求带 `loopId`
- 未选 Loop / loopId 无效 / 非本应用库成员 → 不注入、不报错
- 亮/暗主题、移动端 375px 无横向滚动；Picker 可键盘操作

## 8. 不在本阶段

- 拖拽式工作流编辑器
- Loop 作为 LangChain4j Tool
- 每轮多 Loop 同时注入
- LangGraph 新节点
