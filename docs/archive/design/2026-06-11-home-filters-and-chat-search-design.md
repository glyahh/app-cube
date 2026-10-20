# 我的应用筛选项 + 查看历史应用名称搜索 设计文档

## 概述

首页「我的应用」区块新增三个下拉筛选项；「查看历史」页应用 ID 搜索改为支持 ID/名称联合搜索。

## 改动范围

### 一、首页「我的应用」新增三个下拉筛选

#### 后端

1. **`AppQueryRequest.java`** 新增两个字段：
   - `isWorkflow` — Integer，1=工作流生成(isBeta=1)，0=普通生成(isBeta=0)，null=全部
   - `isDeployed` — Integer，1=已部署(deployKey非空)，0=未部署(deployKey为空)，null=全部

2. **`AppServiceImpl.buildMyAppQueryWrapper`** 追加条件：
   - `isWorkflow != null` → `eq(App::getIsBeta, isWorkflow)`
   - `isDeployed != null` → `isDeployed==1 ? isNotNull(App::getDeployKey) : isNull(App::getDeployKey)`
   - `codeGenType` 已存在，追加 `eq` 条件

#### 前端（HomeView.vue）

- `myApps` 状态新增 `isWorkflow`、`isDeployed`、`codeType` 三个筛选字段
- 在 `.list-toolbar-search` 内搜索框旁插入三个 `ASelect` 下拉：
  - 生成类型：全部 / 普通生成 / 工作流生成
  - 部署状态：全部 / 已部署 / 未部署
  - 代码类型：全部 / HTML / 多文件 / Vue（后端 value: html/multi_file/vue）
- `loadMyApps()` body 中代入三个字段
- `keywordToQuery` 逻辑保留，与三个下拉组合使用
- 搜索按钮触发时合并所有条件发送
- 重置时清空三个下拉 + keyword

### 二、查看历史支持应用名称搜索

#### 后端

1. **`ChatHistoryQueryRequest.java`** 新增字段：
   - `appName` — String，应用名称关键字

2. **`ChatHistoryServiceImpl.buildQueryWrapper`** 追加逻辑：
   - 若 `appName` 不为空 → 使用子查询：`queryWrapper.exists("SELECT 1 FROM app WHERE app.id = chat_history.appId AND app.appName LIKE concat('%', ?, '%')", appName)`
   - `appId` 保留原有精确匹配逻辑（若同时传 appId 和 appName，取交集）

3. **`ChatHistoryServiceImpl.listMyChatHistoryByPage`** 在构造 `safeRequest` 时同步传入 `appName`

#### 前端（UserChatHistory.vue）

- `searchForm` 中 `appId?: number` 改为 `appId?: number` + `appName?: string`
- 输入框从 `InputNumber` 改为 `Input`，标签从「应用 ID」改为「应用 ID/名称」
- 用户输入时：若为纯数字，填 `appId`；否则填 `appName`
- API 请求 body 中同时代入对应字段

### 三、类型同步

- 手动更新 `ai-generate-code-frontend/src/api/types.ts`：
  - `AppQueryRequest` 加 `isWorkflow`、`isDeployed`
  - `ChatHistoryQueryRequest` 加 `appName`

### 不变的部分

- 查看历史表格列「应用名称」已有，不做改动
- 「消息类型」筛选保持原样
- 精选应用筛选区不做改动
- `AppVO`、`UserChatHistoryItemVO` 等展示 VO 不变

## 验收要点

1. 首页三个下拉可单选/组合，搜索后卡片正确过滤
2. 重置恢复全量列表
3. 查看历史输入数字按 appId 精准匹配，输入文字按名称模糊匹配
4. 表格应用名称列正确显示
5. `npm run build` + `mvnw compile` 通过
