# 工具调用卡片折叠 + Hover-Copy 交互设计

## 概述

为 AI 聊天界面中的工具执行结果卡片（写入文件、修改文件）增加可折叠能力和 hover 复制交互，提升长对话中的信息浏览效率。

## 设计动机

- 当前工具执行结果卡片中的代码块始终展开，占据大量纵向空间
- 用户无需在每次滚动时都看到全部代码，需要在需要时主动展开查看
- 复制代码和路径是最频繁的操作，需要让触发路径足够短

## 实现方案

### 组件架构

新增 **`CollapsibleCodeBlock.vue`** 组件，封装 ToolExecCardHeader + 可折叠的 CodeBlock 区域。

### 交互细节

#### 1. 折叠系统

| 属性 | 值 |
|------|-----|
| 目标卡片 | `tool_executed_write_file`、`tool_executed_modify_file` |
| 折叠范围 | 整个 CodeBlock 内容区（含"替换前"/"替换后"标题） |
| 触发区域 | 卡片头部右上角的眼睛图标按钮 |
| 默认状态 | **全部折叠**（SSE 流式 + 历史回放均折叠） |
| 动画 | CSS `max-height` + `opacity` 过渡，250ms ease |

#### 2. 展开/收起图标（streamline-mcp Core Line - Free 系列）

| 状态 | 图标名 | 说明 |
|------|--------|------|
| 折叠态（点击展开） | **Visible**（睁眼） | 提示"点击查看代码" |
| 展开态（点击收起） | **Invisible 1**（闭眼斜线） | 提示"点击收起代码" |

两个图标均来自同一免费系列，14×14 viewBox，线条风格统一。使用 `currentColor` 渲染。

#### 3. Hover 复制系统

在 ToolExecCardHeader 内部，为以下两个元素添加 hover 交互：

**① "写入文件" / "修改文件" 标签（`.tool-exec-action-label`）：**
- Hover 时，文本右侧淡入「复制」字样（inline）
- 点击「复制」，将**代码内容**写入剪贴板
- 写入文件 → 复制 `segment.content`
- 修改文件 → 复制 `segment.afterContent`
- 点击后文字变为「✓已复制」，1.5s 后自动恢复为「复制」

**② 文件路径（`.tool-exec-target-path`）：**
- Hover 时，路径文本右侧淡入「复制路径」字样（inline）
- 点击「复制路径」，将路径字符串写入剪贴板
- 点击后文字变为「✓已复制」，1.5s 后自动恢复为「复制路径」

两个触发区**独立**互不干扰（各自的 hover 状态、点击状态、定时器独立）。

#### 4. 无障碍

- 眼睛图标按钮添加 `aria-label`（展开/收起代码内容）
- 复制触发区使用 `<button>` 或 `role="button"` + `tabindex`，支持键盘操作
- `prefers-reduced-motion` 时跳过动画

### 对 ToolExecCardHeader 的修改

在 ToolExecCardHeader 的 `<span class="tool-exec-action-label">` 和 `<span class="tool-exec-target-path">` 内部各新增一个 slot：

- `<slot name="action-label-after" />` — 允许 CollapsibleCodeBlock 在标签名后注入复制按钮
- `<slot name="target-path-after" />` — 允许在路径后注入复制路径按钮

这两个 slot 是极简入侵，保留 ToolExecCardHeader 的完整向后兼容性（不传 slot 时行为不变）。

### 对 AppChatView 的修改

替换模板中两处渲染分支：

```diff
<!-- 写入文件 -->
- <ToolExecCardHeader ...><template #actions>...</template></ToolExecCardHeader>
- <CodeBlock ... />
+ <CollapsibleCodeBlock :code="..." :language="..." :file-path="..." />

<!-- 修改文件 -->
- <ToolExecCardHeader ...><template #actions>...</template></ToolExecCardHeader>
- <div><div>替换前</div><CodeBlock /><div>替换后</div><CodeBlock /></div>
+ <CollapsibleCodeBlock :before-code="..." :after-code="..." :file-path="..." />
```

## 涉及文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `src/components/CollapsibleCodeBlock.vue` | **新增** | 核心组件：折叠 + hover 复制 + 图标切换 |
| `src/components/ToolExecCardHeader.vue` | **小改** | 新增 2 个 named slot（label 和 path 后方） |
| `src/page/App/AppChatView.vue` | **修改** | 两处渲染分支替换为 CollapsibleCodeBlock |

## 不涉及的变更

- 不修改 `CodeBlock.vue`
- 不修改已有的复制按钮（CodeBlock 自己的 📋 按钮保留，互不冲突）
- 不修改 `ToolExecCardHeader` 的原有 props/emits 接口

## 测试策略

1. 折叠功能：默认折叠、点击展开、再次点击收起、动画执行
2. 复制功能：hover 显示复制项、点击复制内容正确、点击复制路径正确、成功后状态恢复
3. 流式场景：流式过程中折叠/展开正常，不报错
4. 边界情况：空代码、无路径、修改文件只有替换前或替换后
