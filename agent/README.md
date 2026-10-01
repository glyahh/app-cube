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

## 模型出口

仓库根目录 `.env` 只保留直连/指定入口二选一：

```dotenv
AGENT_MODEL_PROXY_ENABLED=true
AGENT_MODEL_PROXY_URL=http://127.0.0.1:17897
```

关闭开关时明确直连；开启时主模型、摘要模型和窗口探测都走指定入口，且不读取系统代理。
示例端口不保证本机当前可用，也不保证其路由为 DIRECT；使用前核对实际代理配置。
修改后需重启后端和 Agent worker，并检查“模型 httpx 客户端”日志确认实际路由。

## Docker Session 沙箱（可选）

默认仍使用 `local_shell`。启用 WSL Ubuntu 中的 Docker Engine 后，可使用一个
Session 复用一个容器：

```bash
export AGENT_SANDBOX_BACKEND=docker
export AGENT_SANDBOX_IMAGE=ubuntu:24.04
uv run python app.py
```

`AGENT_SANDBOX_TYPE=docker` 也被兼容支持；`AGENT_SANDBOX_BACKEND` 优先。
Windows 进程找不到 `docker.exe` 时，会自动通过 `wsl.exe -d Ubuntu -- docker`
连接 WSL Docker Engine。

容器只把请求中的 workspace 挂载到 `/workspace`，不挂载 Docker socket。Run
结束只释放 Session 锁；后台回收器会在空闲 10 分钟降低 CPU、30 分钟停止、
120 分钟销毁容器。代码工作区仍由宿主机路径持久化。Docker 不可用时返回
`SANDBOX_UNAVAILABLE`，不会静默回退到宿主机 shell。

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
prompt.py              系统提示词
server.py              AgentFactory 装配
core/streaming_runtime.py  LangGraph 事件适配
core/compaction.py     四层压缩
core/persistence.py    PostgreSQL checkpointer / store 与内存降级
backends/              local_shell / docker_sandbox / 只读及敏感路径防护
retrieval/             Glob/Grep/SearchTrace/Projection
```

这些模块已有源码，不代表本次文档同步完成了运行验收。当前架构、待开发边界及第一阶段验收见 [当前项目状态与开发主线](../docs/CURRENT_PROJECT_STATUS.md)。

生成沙箱和 Java 管理的应用运行容器是两条执行链路。Docker 直接读写挂载的工作区，不等于保护原项目的独立修复副本；桥接网络不阻断出网。导入与修复流程仍需补齐副本、运行基线和验证证据绑定，不应直接在原项目执行未知脚本。

## 两条不能破的约束

1. **stdout 只许出协议帧。** `app.py` 在任何其他 import 之前把 `sys.stdout`
   指向 stderr，真 stdout 留给协议。任何 `print` 都会破坏协议。
2. **所有与上下文占用相关的阈值必须是窗口的函数。** 见 `core/settings.py` 的
   `clamp_to_window` —— 写成纯绝对值时，小窗口上会算出大于窗口的阈值，
   那一层永远不触发，最后以供应商上限错误暴露。
