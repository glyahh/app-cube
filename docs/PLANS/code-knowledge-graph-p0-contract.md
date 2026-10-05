# 图谱 P0：统一结果契约与首轮样本

日期：2026-10-03。
状态：实现前设计草案，字段名和适配工作量待验证；没有安装候选、创建可执行 fixture 或运行模型。
范围：只细化 P0 的输入、归一化输出、候选补全与结果对照，不一次设计全部语言或后端功能。
关联：[主设计](./code-knowledge-graph-design.md)、[验收清单](./code-knowledge-graph-test-matrix.md)。

## 1. 为什么先定契约

2026-10-03 核对现有 `appKnowledgeApi.ts` 与 `V9__knowledge_system.sql`：

- 节点/边主要使用 key、name、kind、relation、evidence 与文本 sourceRef。
- 旧接口没有结构化调用点、解析策略、候选目标、工具版本或上下文代次。
- 图查询没有分页与覆盖信息，当前页面完整性不能从节点数量推断。
- 旧 `CODE_EVIDENCE` 可能由模型声明；不能只因字符串相同就升级为新协议的静态事实。

新协议作为工具输出与归一化边界，不直接改写现有 API；后续用映射层投影到原画布，保留证据详情与版本元数据。
事实与推断仍统一入 PostgreSQL，但服务端按证据类型过滤，不只靠前端虚线区分。

## 2. 输入与身份边界

每轮使用不可变源码快照，输入包含：

| 字段 | 要求 |
| --- | --- |
| `schema_version` | 明确结果协议版本，未知版本不能猜测解析 |
| `project_id` | 映射当前 appId；传输为 string，不另建项目身份 |
| `snapshot_id` / `snapshot_version` | 当前允许访问的快照及其内容身份，不用分支名代替 |
| `branch_id` / `context_epoch` | 当前会话边界，回档后的旧请求不能继续读写 |
| `task_id` / `task_generation` | 任务与发布资格；取消、回档或替代后不可恢复资格 |
| `manifest` | 文件路径、语言、编码、内容 hash、纳入/排除/跳过原因 |
| `toolchain_fingerprint` | 候选工具、grammar、query、resolver 与相关配置版本 |

任务身份由可信调度层指定，Worker 和 Agent 不能自行切换项目或上下文。
Agent 仅可按需检索上述快照的合法源码；全仓库可检索不意味着全仓一次入模。

## 3. 静态结果归一化

### 3.1 结构

```text
StaticResult
  identity
  files[]
  symbols[]
  import_sites[]
  call_sites[]
  relations[]
  coverage
  diagnostics[]
```

每个对象的 ID 在当前索引版本内唯一，不使用短方法名；所有引用必须归属于同一 project/snapshot。

### 3.2 关键对象

| 对象 | 必需信息 |
| --- | --- |
| 文件 | 相对路径、内容 hash、语言、是否解析及原因 |
| 符号 | 类型、限定名、签名/作用域、所属文件、源码范围、来源 |
| 导入点 | 声明位置、模块/名称/别名、目标或外部目标、解析状态 |
| 调用点 | 调用方、原表达式及位置、receiver、静态目标/候选、解析状态 |
| 关系 | 有向起终点、关系类型、关联调用/导入点、来源、策略、条件与引用 |

源码位置保留路径、内容 hash、半开字节范围和显示行列；行列从 1 开始、字节从 0 开始。嵌入语言映射回宿主文件；跨工具位置单位不能混用。

### 3.3 证据规则

- `syntax_fact` 可证明某个定义或调用表达式存在，不自动证明其目标。
- `resolved_static` 要求解析策略和条件可追溯；不能把唯一名称或路径近似当确定目标。
- `heuristic_static` 保持推断；不能只按候选工具的 confidence 数值升级。
- 未知调用保留 call_site，没有 target 时不生成伪造的确定调用边。
- 只有事实层的目标解析成功才生成 `calls_static`；推断建议使用 `calls_candidate`，并保留证据类别。
- 推断候选出现后，静态调用点的 unresolved 状态仍保留；不能偷偷改成已静态解析。
- 归一化不得从 gold 拷贝答案修补候选输出，否则候选比较失去意义。

## 4. 首轮样本蓝图

以下路径是未来独立验证工作区内的 fixture 名称，不是仓库现有文件。代码块是人工样例，不在本轮落成 .py 或执行。

### helpers.py

```python
def normalize(value):
    return value
```

### shadow.py

```python
def normalize(value):
    return value
```

### entry.py

```python
from helpers import normalize as clean


def register(callback):
    return callback


def direct(value):
    return clean(value)


def reference_only():
    return clean


def callback_reference():
    return register(clean)


def dynamic(obj, method_name, value):
    return getattr(obj, method_name)(value)
```

执行前固定静态条件：以给定模块为分析范围，无额外名称注入/monkey patch，模块查找配置明确。此条件不保证任意真实 Python 工程也有同样目标。

### 4.1 人工预期，尚非实测结果

| 检查项 | 预期 |
| --- | --- |
| 定义 | 3 个文件、7 个函数定义；两个 normalize 身份不同 |
| 导入 | entry 导入 helpers.normalize 并绑定别名 clean，不依赖 shadow.normalize |
| direct | 存在 clean(value) 调用点；若候选具备该静态解析能力，目标应为 helpers.normalize |
| reference_only | clean 是函数引用，不是本处调用 normalize |
| callback_reference | 本处直接调用 register；clean 为参数中的函数引用，register 当前只是返回 callback，不调用它 |
| dynamic | 有内层 getattr 调用和外层结果调用；外层目标由运行参数决定，不能唯一确定 |
| 外部目标 | getattr 的内置归属可记录为外部符号；不能绑成本仓库同名函数 |
| 错误边 | direct/callback_reference 不得静默绑到 shadow.normalize；reference_only 不得生成确定调用 normalize 的边 |

P0 只证明契约可用与小样本能力，不代表全语言覆盖。某候选不支持导入语义时标记缺口，不根据它的行为改 gold。
后续独立加入同作用域遮蔽、Java 重载、Vue 嵌入语言和跨语言契约；不在第一轮混在一起导致无法定位原因。

## 5. Agent 候选提交契约

候选提交与静态结果使用不同入口，身份仍受同一快照与上下文校验。

每条提交包含原 call_site/实体、decision、目标候选、源码引用、理由与限制。
`decision` 允许 `candidate / unknown`；找不到证据的目标保持 unknown，不强制输出一条边。
Agent 不得提交可升级的事实标签，不得直接写数据库或修改源码。

下面是人工编写的 unknown 输出示例，不是实际模型响应；示例身份没有运行意义：

```json
{
  "schema_version": "1",
  "project_id": "example-project",
  "snapshot_id": "example-snapshot",
  "context_epoch": "example-epoch",
  "call_site_id": "example-dynamic-result-call",
  "decision": "unknown",
  "candidate_target_ids": [],
  "evidence_type": "agent_inferred",
  "citations": [],
  "reason": "目标由 obj 和 method_name 的运行时值决定，当前源码不能唯一确定。",
  "limitations": ["缺少运行时对象和方法名"]
}
```

真正候选需要 source path/hash/span 引用。unknown 无引用可作为无法判断的记录，不生成关系；不是免除带目标候选的证据要求。

### 5.1 拒绝场景

| 提交 | 结果 |
| --- | --- |
| 错误项目、旧快照、旧 epoch、无效任务资格 | 拒绝，不能靠修改输出 ID 绕过 |
| 目标 ID 不存在且未提交合法推断实体 | 拒绝或保持待验证，不创建伪事实节点 |
| 源码引用越界、内容 hash 错误、路径逃逸 | 拒绝，记录理由 |
| Agent 声称自己的边是 resolved_static | 拒绝事实声明，不按字符串相信模型 |
| 原因只来自旧图、保护备份或另一推断 | 不能升级为事实，不允许访问旧上下文 |
| 合法候选且引用有效 | 仅证明候选格式与引用合法，仍标记推断 |

## 6. 两个视图与现有接口的映射

- 已确认新版可用时机采用 B：本轮静态与补全阶段都结束后统一发布，普通查询不先返回静态半成品。补全任务访问暂存静态结果须通过专用任务权限，不能用它恢复独立问答或绕过主查询边界。
- 文件视图聚合已分类关系；不能把引用/调用显示为 imports。
- 方法视图投影调用方、目标及调用点；未知目标在详情中可见，不能因没有边而消失。
- 推断默认显示虚线和标签，可一键隐藏；服务端事实查询仍排除推断。
- 现有 key/name/kind 等画布字段可继续使用，但服务层保留结构化证据、调用点和覆盖，不把新证据压回一句 sourceRef 后丢弃。
- 原 evidence 字段只能作为展示兼容映射；历史 CODE_EVIDENCE 缺少可验证解析来源时保留 legacy 未验证标识，不凭标签直接加入新事实层。
- 独立知识问答已取消。这里只增加主 Agent 只读工具和图谱视图，不恢复 QA 输入框或专用接口。
- 以上是待实施映射策略，没有修改现有 SQL、TypeScript 类型或后端返回值。

## 7. 三个检查点与交付

1. 静态比较：同一输入分别导出候选原始结果，映射后核对人工预期，记录缺口/错目标/错误方向/引用误判。
2. Agent 补全：静态通过后，只对未知/受影响项运行，检查候选引用与拒绝场景；模型不可用时标为未验证。
3. 结果对照：分别查询事实和含推断结果，验证未知保留、版本隔离、源码 hash 未改和产物可回放。

各检查点独立记录实际命令、退出码、版本、原始输出和判定。前一检查点不通过先定位，不能用后续 Agent 结果掩盖。
本轮仅核对文档内 JSON 和预期结构的一致性；这不证明候选解析、模型补全或 PostgreSQL 接入通过。
后续执行需明确授权使用独立工作区和测试环境，不在原工程创建 fixture、安装候选或执行未知构建。
