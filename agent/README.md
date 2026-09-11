# agent — Harness 架构的 Agent 运行时

Python 侧的 Coding Agent 执行体。由 Spring Boot 以**常驻子进程**方式拉起，
通过 stdin/stdout 的 NDJSON 协议通信。

**接口契约见 [`docs/AGENT_CONTRACT.md`](../docs/AGENT_CONTRACT.md)** —— 那是唯一的权威定义。

## 跑起来

```bash
uv sync                                              # 首次
echo '{"runId":"r-1","workspace":{"root":"'"$PWD"'/../temp/code_output","name":"html_1"},"input":{"message":"hi"}}' \
  | uv run python app.py
```

stdout 是协议通道（只有 NDJSON 帧），日志全部在 stderr。调试时分开看：

```bash
echo '<请求>' | uv run python app.py 2>/tmp/agent.log | jq -c .
```

## 测试

```bash
uv run pytest -q
```

`tests/test_main_loop.py` 跑真实子进程，顺带盯住两件事：协议通道没被 `print`
污染、以及**终止帧一定发得出去**（漏发会让 Java 侧永久阻塞在读上）。

## 目录

```
app.py                 入口：stdio 主循环 + stdout 保护
env_utils.py           .env 加载（空值不覆盖）
core/
  events.py            事件名枚举 + 信封 + Emitter
  runtime.py           任务分类、run 生命周期、请求校验
  settings.py          配置；含 clamp_to_window（阈值折算）
  model_window.py      动态窗口解析（不硬编码窗口）
prompt.py              系统提示词            ← Phase 1
server.py              AgentFactory 装配     ← Phase 1
core/streaming_runtime.py  LangGraph 事件适配 ← Phase 1
core/compaction.py     四层压缩              ← Phase 2/3
backends/              local_shell / docker_sandbox ← Phase 3
retrieval/             Glob/Grep/SearchTrace/Projection ← Phase 2
```

带 ← 的是尚未实现的位子，目录按契约先立住。

## 两条不能破的约束

1. **stdout 只许出协议帧。** `app.py` 在任何其他 import 之前把 `sys.stdout`
   指向 stderr，真 stdout 留给协议。任何 `print` 都会破坏协议。
2. **所有与上下文占用相关的阈值必须是窗口的函数。** 见 `core/settings.py` 的
   `clamp_to_window` —— 写成纯绝对值时，小窗口上会算出大于窗口的阈值，
   那一层永远不触发，最后以供应商上限错误暴露。
