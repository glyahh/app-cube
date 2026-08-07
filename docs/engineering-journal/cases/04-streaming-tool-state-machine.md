# Case 04: streaming tool state machine

## Phenomenon

partial/complete 回调顺序不固定，tool-only、空流重试和重复回调会让前端状态错乱。

## Root cause

把模型回调当作最终状态，而不是把它们归并成可恢复的事件状态。

## Decision

使用 `toolCallId` 去重，complete 补发，设置 exit 门禁，并通过磁盘快照回显已确认状态；空流才进入有限重试。

## Verification

- 证据：提交 `96ed098`
- 关注：partial、complete、tool-only、重试和快照回显路径

## Residual risk

仍需固定事件样本和断线重连测试，当前不宣称已完成高并发验证。

