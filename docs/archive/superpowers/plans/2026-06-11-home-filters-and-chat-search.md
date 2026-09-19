# 首页筛选项 + 查看历史搜索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页「我的应用」新增三个下拉筛选项，查看历史页支持应用ID/名称联合搜索

**Architecture:** 后端 DTO 新增字段→查询 Wrapper 追加条件→前端表单联动→类型同步

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis-Flex / Vue 3 + Ant Design Vue

---

### Task 1: 后端 AppQueryRequest 新增 isWorkflow / isDeployed 字段

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/AppQueryRequest.java`

- [ ] **Step 1: 在 AppQueryRequest 追加两个字段**

```java
    /**
     * 是否工作流生成：1-是（isBeta=1），0-否（isBeta=0），null-全部
     */
    private Integer isWorkflow;

    /**
     * 是否已部署：1-已部署（deployKey 非空），0-未部署（deployKey 为空），null-全部
     */
    private Integer isDeployed;
```

插在 `private Integer isDelete;` 之后。

- [ ] **Step 2: 编译验证**

```bash
cd d:/mainJava/all\ Code/program/glyahh-ai-generate-code
./mvnw.cmd -q -DskipTests compile
```
预期：BUILD SUCCESS

---

### Task 2: 后端 AppServiceImpl.buildMyAppQueryWrapper 追加筛选条件

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/AppServiceImpl.java`

- [ ] **Step 1: 在 buildMyAppQueryWrapper 的 appName like 条件之后追加三个条件**

定位到 `appName` 条件之后（约第 245 行），追加：

```java
        // 工作流/普通生成筛选：isWorkflow=1 对应 isBeta=1，isWorkflow=0 对应 isBeta=0
        if (appQueryRequest.getIsWorkflow() != null) {
            queryWrapper.eq(App::getIsBeta, appQueryRequest.getIsWorkflow());
        }
        // 部署状态筛选：isDeployed=1 要求 deployKey 非空，isDeployed=0 要求 deployKey 为空
        if (appQueryRequest.getIsDeployed() != null) {
            if (appQueryRequest.getIsDeployed() == 1) {
                queryWrapper.isNotNull(App::getDeployKey);
                queryWrapper.ne(App::getDeployKey, "");
            } else {
                queryWrapper.isNull(App::getDeployKey);
            }
        }
        // 代码类型筛选
        if (StrUtil.isNotBlank(appQueryRequest.getCodeGenType())) {
            queryWrapper.eq(App::getCodeGenType, appQueryRequest.getCodeGenType());
        }
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw.cmd -q -DskipTests compile
```
预期：BUILD SUCCESS

---

### Task 3: 后端 ChatHistoryQueryRequest 新增 appName 字段

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/ChatHistoryQueryRequest.java`

- [ ] **Step 1: 在 ChatHistoryQueryRequest 追加 appName 字段**

```java
    /**
     * 应用名称关键字（模糊搜索，通过 app 表关联）
     */
    private String appName;
```

插在 `private Long appId;` 之后。

- [ ] **Step 2: 编译验证**

```bash
./mvnw.cmd -q -DskipTests compile
```
预期：BUILD SUCCESS

---

### Task 4: 后端 ChatHistoryServiceImpl 追加 appName EXISTS 子查询

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java`

- [ ] **Step 1: 在 buildQueryWrapper 的 appId 条件之后追加 appName 子查询**

定位到 `buildQueryWrapper` 方法中 `appId` 条件之后（约第 456 行），追加：

```java
        if (StrUtil.isNotBlank(queryRequest.getAppName())) {
            queryWrapper.exists(
                "SELECT 1 FROM app WHERE app.id = chat_history.app_id AND app.app_name LIKE CONCAT('%', ?, '%')",
                queryRequest.getAppName()
            );
        }
```

- [ ] **Step 2: 在 listMyChatHistoryByPage 的 safeRequest 中同步传入 appName**

定位到 safeRequest 构建处（约第 346 行），在 `safeRequest.setAppId(queryRequest.getAppId());` 之后追加：

```java
        safeRequest.setAppName(queryRequest.getAppName());
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw.cmd -q -DskipTests compile
```
预期：BUILD SUCCESS

---

### Task 5: 同步前端 API 类型

**Files:**
- Modify: `ai-generate-code-frontend/src/api/types.ts`

- [ ] **Step 1: AppQueryRequest 追加 isWorkflow / isDeployed**

```typescript
export type AppQueryRequest = {
  // ... 原有字段 ...
  isDelete?: number;
  isWorkflow?: number;
  isDeployed?: number;
};
```

- [ ] **Step 2: ChatHistoryQueryRequest 追加 appName**

```typescript
export type ChatHistoryQueryRequest = {
  // ... 原有字段 ...
  appId?: number;
  appName?: string;
  // ... 后续字段 ...
};
```

- [ ] **Step 3: 验证前端 build**

```bash
cd d:/mainJava/all\ Code/program/glyahh-ai-generate-code/ai-generate-code-frontend
npm run build
```
预期：无类型错误，build 成功

---

### Task 6: 前端 HomeView.vue 新增三个下拉筛选

**Files:**
- Modify: `ai-generate-code-frontend/src/page/HomeView.vue`

- [ ] **Step 1: myApps 状态新增三个筛选字段**

找到 `const myApps = ref<AppListState>({` 处（约第 197 行），在 `keyword: '',` 之后追加：

```typescript
      isWorkflow: undefined as number | undefined,
      isDeployed: undefined as number | undefined,
      codeType: undefined as string | undefined,
```

同时更新 `AppListState` 类型（约第 189 行 type 定义处）追加三个可选字段。

- [ ] **Step 2: loadMyApps 函数追加筛选参数**

定位到 `loadMyApps` 函数（约第 344 行），在 body 中追加：

```typescript
        isWorkflow: myApps.value.isWorkflow,
        isDeployed: myApps.value.isDeployed,
        codeGenType: myApps.value.codeType,
```

要求：在 `...keywordToQuery(myApps.value.keyword),` 之后。

- [ ] **Step 3: 在 template 的 list-toolbar 中插入三个 ASelect**

替换搜索框所在的 `.list-toolbar-search` div 内容（约第 753-765 行）。需要从 ant-design-vue 导入 Select。

在 script 开头补充导入 `Select`（约第 9 行 `Button as AButton` 所在行那块）。

新增模板替换：

```vue
        <div class="list-toolbar-search">
          <ASelect
            v-model:value="myApps.isWorkflow"
            placeholder="生成类型"
            allow-clear
            style="width: 130px"
            @change="loadMyApps"
          >
            <ASelectOption value="">全部</ASelectOption>
            <ASelectOption :value="0">普通生成</ASelectOption>
            <ASelectOption :value="1">工作流生成</ASelectOption>
          </ASelect>
          <ASelect
            v-model:value="myApps.isDeployed"
            placeholder="部署状态"
            allow-clear
            style="width: 130px"
            @change="loadMyApps"
          >
            <ASelectOption value="">全部</ASelectOption>
            <ASelectOption :value="0">未部署</ASelectOption>
            <ASelectOption :value="1">已部署</ASelectOption>
          </ASelect>
          <ASelect
            v-model:value="myApps.codeType"
            placeholder="代码类型"
            allow-clear
            style="width: 130px"
            @change="loadMyApps"
          >
            <ASelectOption value="">全部</ASelectOption>
            <ASelectOption value="html">HTML</ASelectOption>
            <ASelectOption value="multi_file">多文件</ASelectOption>
            <ASelectOption value="vue">Vue</ASelectOption>
          </ASelect>
          <AInput
            v-model:value="myApps.keyword"
            placeholder="按名称或 App ID 搜索我的应用"
            allow-clear
            style="width: 200px"
            @press-enter="loadMyApps"
          />
          <AButton type="primary" ghost @click="loadMyApps">
            搜索
          </AButton>
        </div>
```

注意：需要从 ant-design-vue 解构 `Select as ASelect`, `SelectOption as ASelectOption`。在顶部 import 中追加：

```typescript
import {
  message,
  Modal as AModal,
  Input as AInput,
  Select as ASelect,
  Button as AButton,
  Card as ACard,
  Pagination as APagination,
  Empty as AEmpty,
  Tag as ATag,
  Tooltip as ATooltip,
} from 'ant-design-vue'
```

并在 import 后追加 `const ASelectOption = ASelect.Option` 或直接从 ant-design-vue 导入 SelectOption。

实际上 Ant Design Vue 4 中，`Select` 自带 `Option` 组件，可以直接用 `ASelect.Option`。或者从 ant-design-vue 解构 `SelectOption`。

最佳方式是在 import 中追加 `Select as ASelect`，然后模板中用 `ASelect.Option` 代替 `ASelectOption`。更简洁——直接在 import 中写：

```typescript
import {
  ...
  Select as ASelect,
  ...
} from 'ant-design-vue'
```

模板中用 `<ASelect.Option value="...">...</ASelect.Option>`。

或者从 ant-design-vue 同时解构 `SelectOption`：

```typescript
import { ..., Select as ASelect, SelectOption as ASelectOption, ... } from 'ant-design-vue'
```

Ant Design Vue 4 是从 `ant-design-vue` 直接导出的，所以可以同时解构。

- [ ] **Step 4: 重置函数清空三个下拉字段**

在 `resetSearch` 逻辑对应的位置（首页没有独立重置按钮，但搜索按钮会重新查询），确保在用户清空 Select 时（allow-clear 点清除）恢复为 `undefined`。每次 `loadMyApps` 时直接取当前 `myApps.value.*` 的值，不需要额外重置逻辑。

- [ ] **Step 5: 验证**

```bash
npm run build
```
预期：build 成功

---

### Task 7: 前端 UserChatHistory.vue 搜索栏支持应用名称

**Files:**
- Modify: `ai-generate-code-frontend/src/page/User/UserChatHistory.vue`

- [ ] **Step 1: searchForm 追加 appName 字段，修改 appId 为可选**

```typescript
const searchForm = ref<{
  messageType?: string
  appId?: number
  appName?: string
}>({
  messageType: '',
  appId: undefined,
  appName: '',
})
```

- [ ] **Step 2: 替换 InputNumber 为 Input，标签改为「应用 ID/名称」**

将 template 中的：

```vue
          <Form.Item label="应用 ID">
            <InputNumber v-model:value="searchForm.appId" :min="1" style="width: 140px" />
          </Form.Item>
```

替换为：

```vue
          <Form.Item label="应用 ID/名称">
            <Input v-model:value="searchForm.appName" placeholder="数字 ID 或名称关键字" allow-clear style="width: 160px" />
          </Form.Item>
```

- [ ] **Step 3: loadData 函数适配双字段**

将 API 请求 body 修改为：

```typescript
        messageType: searchForm.value.messageType || undefined,
        appId: searchForm.value.appId,
        appName: searchForm.value.appName || undefined,
```

其中 `appId` 的解析逻辑：在 `handleSearch` 中解析 `searchForm.value.appName`，如果为纯数字则设 `appId` 否则设 `appName`。

更好的方案——在 handleSearch 中动态解析：

```typescript
function handleSearch() {
  pagination.value.current = 1
  const raw = searchForm.value.appName?.trim() || ''
  if (/^\d+$/.test(raw)) {
    searchForm.value.appId = parseInt(raw, 10)
    searchForm.value.appName = undefined
  } else {
    searchForm.value.appId = undefined
    searchForm.value.appName = raw || undefined
  }
  void loadData()
}
```

同时重置函数也需要清：

```typescript
function resetSearch() {
  searchForm.value = {
    messageType: '',
    appId: undefined,
    appName: '',
  }
  pagination.value.current = 1
  void loadData()
}
```

同时 `loadData` 中 body 要同步传入 `appName`：

```typescript
        appId: searchForm.value.appId,
        appName: searchForm.value.appName || undefined,
```

- [ ] **Step 4: 清理 InputNumber 导入**

顶部 import 可以移除 `InputNumber`（如果其他地方没用到）。检查 `UserChatHistory.vue` 中只有筛选表单用了 InputNumber，可以安全移除。

```typescript
import { Button, Form, Input, message, Space, Table, Tag } from 'ant-design-vue'
```

- [ ] **Step 5: 验证 build**

```bash
npm run build
```
预期：build 成功

---

### Task 8: 全量构建验证

- [ ] **Step 1: 后端编译**

```bash
cd d:/mainJava/all\ Code/program/glyahh-ai-generate-code
./mvnw.cmd -q -DskipTests compile
```
预期：BUILD SUCCESS

- [ ] **Step 2: 前端构建**

```bash
cd ai-generate-code-frontend
npm run build
```
预期：无错误，构建成功

- [ ] **Step 3: 复查改动文件完整性**

确认四个后端文件、两个前端文件、一个类型文件变更合理，无遗漏。
