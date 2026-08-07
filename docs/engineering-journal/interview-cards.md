# Interview cards

每张卡控制在 90 秒。不要背实现细节清单，要背“现象—判断—选择—验证”。

## Card 01: iframe hover fallback

**一句话：** 子 iframe 内的 hover overlay 在真实预览中不稳定，因此父页面接管绘制，保证编辑器的确定性。

**90 秒：** 我先尝试在 iframe 内注入样式和 overlay，但遇到渲染层级、事件边界和 SPA 路由变化后状态残留的问题。后来把交互分成“子页面提供目标元素，父页面负责绘制”。父页面通过透明 shield 捕获事件，再用 `contentDocument.elementFromPoint()` 和坐标换算绘制 hover/selected box，同时转发 wheel 和双击事件。这样牺牲了一部分实现优雅性，但换来了可预测的编辑体验。边界是需要同源预览。

## Card 02: personalization and Memory consistency

**一句话：** 个性化配置不是一次 Prompt 替换，而是 MySQL、Redis、Memory Version 和 AiService 生命周期的一致性问题。

**90 秒：** 用户修改回答风格后，MySQL 作为持久化来源，Redis 中的风格缓存被清理并回填；每轮请求读取最新风格并注入 Memory。当 Redis Memory 或版本变化时，失效并重建绑定了 ChatMemory 的 AiService，同时同步 JVM 内存窗口。Caffeine 仍有装配复用价值，但不能替代一致性设计。我的验证重点是切换风格、TTL 过期、Redis 为空和 Service 重建后的行为。

## Card 03: stream and tool state

**一句话：** 流式输出的难点不是把 token 转发出去，而是把 partial、complete、tool-only 和重试统一成可恢复状态机。

**90 秒：** 我为工具请求增加结构化事件，并用 `toolCallId` 去重，处理 complete 补发、空流重试、exit 门禁和磁盘快照回显。这样前端不依赖某一次模型回调的顺序，断线后也能从已保存状态恢复。下一步是用固定事件样本补回放测试和指标。

## Card 04: build validation

**一句话：** 生成成功不等于制品可交付，必须把 SFC 解析和构建结果纳入验收。

**90 秒：** 生成代码先进行 Vue SFC 预解析，发现结构性问题时进入修复流程，再进行二次校验和 `npm run build`。只有构建产物进入发布链路。这样把“模型认为正确”转化为可观察的工程证据，失败时也能定位是生成、修复还是构建阶段。

