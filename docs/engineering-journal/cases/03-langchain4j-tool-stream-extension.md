# Case 03: LangChain4j tool stream extension

## Phenomenon

项目需要把工具请求、工具执行和 AI 响应统一发送给前端，但现有 `AiServiceTokenStream` 没有完整的 partial tool execution 回调。

## Root cause

上游流接口覆盖普通 token，却没有满足项目交互需要的结构化工具事件。

## Decision

在项目使用的 LangChain4j 实现层补充工具流事件，并在应用层将 Tool Request、Tool Executed 和 AI Response 统一编码。

## Verification

- 证据：提交 `02d1053`
- 观察点：工具请求和执行事件能够进入同一 SSE 流

## Residual risk

上游版本升级可能改变接口，需要固定依赖版本并增加事件回放测试。

