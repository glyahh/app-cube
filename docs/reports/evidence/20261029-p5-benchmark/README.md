# P5 固定任务基准自检

执行日期：2026-10-29。

## 命令

```powershell
./agent/.venv/Scripts/python.exe scripts/checks/run-benchmark.py --skip-agent
./agent/.venv/Scripts/python.exe scripts/checks/run-benchmark.py --skip-agent --apply-solution
```

## 结果

- seed 原样：5 个任务全部失败，退出码 1。评分能识别未修复状态。
- 应用 `solution/`：5 个任务全部通过，退出码 0。

对应 JSON 为本目录内的 `benchmark-*.json`。

## 未验证

- 真实模型执行。本机没有可用模型密钥，`--skip-agent` 只校验 seed 与评分。
- `agent_driver.py` 对真实 `agent/app.py` 的驱动。当前只用假子进程验证了协议解析、超时与错误帧。
- 两次同代码同模型结果是否一致。

## 重新执行

配置 `.env` 中的模型后运行不带 `--skip-agent` 的命令。首次结果作为基线，连续两次通过集合应一致；不一致的任务记为 `unstable`。
