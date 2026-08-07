# Case 01: iframe visual editor fallback

## Phenomenon

子 iframe 内原计划注入 hover 框，但真实预览中视觉层不稳定，编辑器无法持续显示目标元素。

## Root cause

iframe 内的样式、事件边界、层叠上下文和 SPA 路由变化共同影响 overlay；仅依赖子页面注入无法保证父编辑器可观察到结果。

## Decision

保留子 iframe 注入路径，同时增加父页面透明 shield。父页面通过 `contentDocument.elementFromPoint()` 获取目标元素，完成坐标转换并绘制 `parentHoverBox` 和 `parentSelectedBox`，必要时转发 wheel 和双击事件。

## Verification

- 源码：`ai-generate-code-frontend/src/utils/visualWebsiteEditor.ts`
- 证据：提交 `5b7a5f8`
- 关键路径：`ensureInjectedApi`、`parentShield`、`parentHoverBox`、`elementFromPoint()`

## Residual risk

依赖同源预览；跨域 iframe 需要改为显式通信协议，不能直接读取 `contentDocument`。

