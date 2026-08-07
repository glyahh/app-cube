# Case 05: layered Memory lifecycle

## Phenomenon

长对话不能无限把完整历史塞给模型，同时 Redis TTL 过期后需要恢复可用上下文。

## Root cause

完整历史、模型窗口、摘要和项目快照承担不同职责，单一存储无法同时满足成本、恢复和审计要求。

## Decision

MySQL 保存完整历史，Redis 保存窗口和摘要，超过轮次后异步压缩并回写；TTL 或版本变化时重建窗口，SSE 告知用户“回忆分析中”。

## Verification

- 证据：提交 `615eecc`、`2700f85`
- 关注：`boundedElastic`、Memory Version 和 TTL 重建

## Residual risk

异步压缩失败、并发读写和摘要质量需要专项指标与重试策略。

