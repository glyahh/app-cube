# P0 基线冻结

执行日期：2026-10-29。

## 命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-baseline.ps1
```

## 结果

见本目录 `baseline-*.json`：Java 901/901、Python 682/689（7 跳过）、前端 295/295，失败 0，`unstable` 为空。`dirty=true` 表示执行时工作区包含未提交改动；本次基线在 P0–P5 实施后的工作区运行，作为后续对比起点，不是改动前的历史快照。

第一次生成的基线对比了一份缺少 Java 报告的旧文件，导致 `unstable` 被错误放大到 904。删除旧文件后用同一批 surefire、pytest、vitest 报告重新汇总，`unstable` 归零；没有重跑测试。

## 未验证

- 两次执行之间的不稳定用例。`unstable` 字段需要传入 `--previous` 才会计算；本次单次执行，该字段为空，不代表用例确定。
- 无 Java、Python 或前端报告时汇总器会以非零退出，不把缺失当通过。

## 重新执行

同一代码状态连续执行两次，第二次会自动读取同目录最近一份基线并填充 `unstable`。报告缺失时先看命令退出码和 `report: missing`，不要直接引用计数。
