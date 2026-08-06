# Case 02: personalization and Memory consistency

## Phenomenon

用户切换回答风格后，后续 AI Service、Redis Memory 和 JVM 内存窗口必须使用新配置；只改数据库会导致旧 Service 继续回答。

## Root cause

`AiService` 捕获了可变的 ChatMemory 和会话风格，MySQL、Redis、Caffeine 与 Memory 的生命周期并不天然一致。

## Decision

MySQL 保存配置；修改时清理 Redis 风格 Key；每轮读取最新 `appStyle` 和 `answerStyle` 并注入 Memory；检测 Redis Memory/版本变化后失效并重建 Service；同步 `ChatMemoryStore` 与 `MessageWindowChatMemory`。

## Verification

- `UserPersonalizationServiceImpl.saveOrUpdate`
- `MemorySessionInjectSupport`
- `aiCodeGeneratorServiceFactory`
- 证据：`58b827f`、`e4c9f1b`、`2700f85`

## Residual risk

Caffeine 仍有装配复用价值，但 TTL、版本和 Service 生命周期耦合，需补并发切换、Redis 故障和过期重建测试。

