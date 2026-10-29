# 固定任务基准

用于回答「改动之后整体是否还正常」。每个任务由固定 seed、固定任务描述和确定性评分组成。

## 结构

```text
benchmarks/tasks/{id}/
  task.json    任务定义：seed、prompt、grade、超时与模型
  seed/        初始工程；评分前原样复制到临时目录
  solution/    参考修复；仅用于自检评分能从红变绿，不参与 Agent 运行
```

## 运行

```powershell
# 只校验 seed 与评分：预期 seed 失败
./agent/.venv/Scripts/python.exe scripts/checks/run-benchmark.py --skip-agent

# 应用参考修复后校验评分：预期全部通过
./agent/.venv/Scripts/python.exe scripts/checks/run-benchmark.py --skip-agent --apply-solution

# 真实执行：调用模型，需要 .env 中的模型配置
./agent/.venv/Scripts/python.exe scripts/checks/run-benchmark.py
```

结果写入 `docs/reports/evidence/{date}-p5-benchmark/`。

## 评分口径

只认编译退出码、测试退出码和声明产物。模型自评、日志中的完成声明、失败后的人工解释都不算通过。

## 边界

驱动脚本 `scripts/checks/agent_driver.py` 直接按 Java ↔ Python 契约驱动 `agent/app.py`，不经过 Java。它只衡量 Python 执行面，不覆盖鉴权、权限门禁、候选审阅与持久化。要覆盖整条业务链需要另建 HTTP 加登录的驱动。

同一任务必须固定 `model` 与 `backend`。Docker 与 local_shell 的文件权限和网络条件不同，结果不可混比。
