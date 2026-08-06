# Case 06: code artifact RAG

## Phenomenon

后续生成需要理解已有代码制品，但整库塞入 Prompt 会导致上下文膨胀、成本升高和无关内容干扰。

## Root cause

代码需要按结构切块、增量更新和混合召回，单一向量检索无法稳定覆盖精确符号匹配。

## Decision

使用 ES 保存切块，结合 KNN + BM25，使用 Hash 去重和增量索引，并在检索失败时降级到可用上下文。

## Verification

- 证据：提交 `ca73b4f`，当前 HEAD `c225ee0`
- 关注：切块、Hash、KNN、BM25 和失败降级路径

## Residual risk

还没有基于真实问题集评测 Recall、引用准确率、过期内容识别和成本收益。

