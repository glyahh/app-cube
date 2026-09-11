# 上下文压缩机制调研

**Claude Code · OpenCode · Codex · Hermes，以及另外六条路**

> 调研时点：2026-09-12。所有标了「源码」的结论都核到了具体文件；Claude Code 那部分我另外做了独立验证（从 npm 真实发行包里反混淆出常量），见附录 B。
> 写给你的版本：比喻打头，数字在后面。不要求你先懂 transformer。

---

## 怎么读这份文档

**只有 10 分钟**：读第 0 章 + 第 1 章。那两章不含任何技术细节，讲的是"为什么这事必须做"和八条路各自的取舍。

**想知道 Hermes 怎么做**（你的主攻方向）：第 2 章是重点，9 个小节，从阈值数学到失败清单都有。

**想横向对比**：第 3–5 章是另外三家，第 6 章是其他八家的对照（含 MemGPT/Letta 这一族的源头、provider 原生压缩这个新家族）。

**想直接拿结论用**：第 9 章，9 条可执行的建议，末尾两条专门对着你现在的 V4 系统说。

**想核实/引用**：附录 A 是数字速查表，附录 B 是可信度分级（**哪些数我敢担保、哪些不敢**），附录 C 是出处清单。

一句话导航：

| 章 | 讲什么 |
|---|---|
| 0 | token / 桌子有硬上限 / 每次都得重发全部 |
| 1 | 八种解法总览 + 四个最容易想错的地方 |
| 2 | **Hermes**（重点） |
| 3 | Claude Code |
| 4 | OpenCode |
| 5 | Codex |
| 6 | Aider · Cline/Roo · Gemini CLI · Cursor/Amp · Goose · **MemGPT/Letta** · **provider 原生** · Copilot · prompt 源流判定 |
| 7 | 为什么"摘要压缩"成了默认，以及它的四样代价 |
| 8 | 六个真实翻车场面（都有 issue 号） |
| 9 | 对你做 harness 有什么可抄的 |
| 附录 A/B/C | 数字表 · 可信度分级 · 出处 |

---

## 第 0 章 三件小事

不懂这三件小事，后面全是天书。

### 0.1 token 不是字

模型不按「字」算账，按 token。一个 token 大约是英文 4 个字符，中文 1 到 2 个字。你说一句「帮我改一下登录页」，大概 10 个 token。

这个单位很关键，因为后面所有的阈值——13K、20K、150K——都是 token 数，不是字数。

### 0.2 模型面前有一张桌子，桌子是硬的

每个模型都有一个「上下文窗口」，就是它一次能同时看多少 token。常见的 200K，贵一点的有 1M。

这不是软件限制，是模型结构决定的。打个比方：**模型面前有一张桌子，能摊开的纸是有限的。** 写满了就是写满了，再多一个字它就看不见——不是"看不清楚"，是根本看不见。撞上这个上限，请求直接报错。

### 0.3 每问一次，它都要把桌上所有纸从头读一遍

这条最反直觉，但最关键。

你可能会以为：模型记得上一轮聊了什么，所以第二轮只发新内容就行。

**不是。模型没有记忆。**

每一轮请求，客户端都要把**迄今为止的全部对话**打包重新发过去。上一轮的工具输出、读过的文件、跑过的命令，全部重发。

这带来两个后果：

1. **每一轮的钱和时间，都正比于桌上纸的总量。** 你说一句"嗯，继续"，如果桌上已经有 30 万 token，这一句"嗯"就要按 30 万 token 计费。
2. **桌子只会越来越满。** 一个 agent 跑几十轮工具调用，读几个文件、跑几次测试，几十万 token 是常态。

所以：**管理上下文不是优化，是必需品。** 不管理，必然撞墙。

于是整件事的问题就变成一句人话：

> **怎么让桌上的纸变少，但又别把"我们聊到哪儿了"弄丢？**

后面所有人都在回答这一句，只是答案不同。

---

## 第 1 章 八种答案，先看全貌

主流的做法一共八种。先扫一眼，再细看。

| 解法 | 用大白话说 | 省不省钱 | 丢不丢信息 | 要多守纪律吗 |
|---|---|---|---|---|
| **摘要压缩** | 把前面几摞纸抄成一张小纸条夹在最上面 | 中等（抄那次很贵） | 丢，且拿不回来 | 不用 |
| **硬切窗口** | 桌子整个清空，另开一个新本子 | 省（不用抄） | 丢，靠模型自觉补 | 要，模型得主动记笔记 |
| **外部记忆** | 旁边放个小本子，随手记要点 | 省 | 不丢，但可能想不起来写 | 要 |
| **检索** | 后面有个大抽屉，需要什么去翻 | 省 | 不丢，但可能翻不到 | 要 |
| **子代理隔离** | 派个小孩去查，回来只说一句结论 | **更贵** | 主桌不丢，小孩那边丢 | 不用 |
| **只擦草稿纸** | 把算草纸扔了，正文留着 | 最省（不花钱） | 丢旧工具输出 | 不用 |
| **超长窗口硬吃** | 换一张特大号桌子 | 单次还行，多轮平方级涨 | 不丢 | 不用 |
| **provider 原生压缩** | 让供应商自己帮你收拾桌子 | 你付摘要那趟采样费 | 看供应商（有的你连摘要都看不到） | 不用 |

（最后一行是这两年新长出来的一族，而且**所有家都在往这个方向缩**——详见 6.7 和 6.8。）

### 1.1 四个容易想错的地方

在往下走之前，先把四个最容易建立的错误印象掐掉。它们是这份调研里最值钱的部分——**几乎所有二手文章都在这里说错**。

**想错一：小纸条 = 忠实的回忆。**

不是。摘要是有损的，而且不只是"丢细节"这么简单。真实案例里出现过：

- 摘要在**关键的约束上写错话**——用户写"不要用 X 库"，摘要写成"用户提到过 X 库"（见 4.3 节）。
- 摘要把**没验证的猜测洗成确定的事实**。有用户报告，原本标注着 `[fee assumption — unverified]` 的猜测，压缩后变成了语气确定的事实陈述，provenance 直接消失。
- 摘要**干脆是空的**。OpenCode 有一个 issue：推理模型只产出 reasoning 部件、没有文本部件，系统照样把这条空壳标记成 `summary:true` 落库，原始历史被替换掉，而且不报错。

所以小纸条的正确理解是：**一张别人凭印象写的、语气还比较肯定的转述**。不是原件。

**想错二：抄纸条是免费的。**

不是。抄纸条这个动作本身是一次完整的模型调用，而且**输入就是被压缩的那一整摞纸**。

这意味着一个很坑的成本结构：**最贵的一次请求，恰好发生在上下文最满的时候。** Claude Code 官方文档原话是「compacting a large context is itself a large request」。

有些二手文章用"妈妈读一遍抄成卡片"来比喻，会让人以为这是顺手做的事。它不是。

**想错三：纸条越压缩越短。**

不是。反复压缩会让摘要**膨胀**。

因为它是在摘要的基础上再摘要——上一次的摘要成了这一次的输入。有用户统计过：24 个经历多次压缩的会话，摘要从第一次到最大一次的中位增长是 **2.30 倍**，最大 **7.13 倍**，单个注入的摘要达到 35,958 token。还有一个会话被压缩了 **103 次**。

正确的比喻是**"纸条越抄越长"**，不是"纸条去抄自己"。后者是一种流传很广但没人能证实的说法——调研里专门找过"压缩器递归调用自身"的纯正案例，**没有找到**。

**想错四：这就像电脑的虚拟内存，把不用的页换到硬盘上。**

这是流传最广的类比，而且它出自 MemGPT 论文本身，不是网友瞎编的。它在**结构**上说得通——但**三个关键性质上全反了**，给外行讲的时候最容易出事。详见 6.6 节。简版：

| | 真实虚拟内存 | LLM 的"换页" |
|---|---|---|
| 换出的是什么 | 精确字节副本 | **有损摘要，且误差跨轮累积** |
| 缺页谁负责 | **硬件强制**（不可能忘） | **模型自觉**（不查就没有） |
| 对语义的影响 | 无（只是变慢） | **改变模型能力**——这正是目的 |

最要命的是第二行：**模型没意识到自己缺信息的时候，什么都不会发生，信息静默丢失。** 真实内存里没有这种情况。

---

## 第 2 章 Hermes：把便宜的先做，贵的最后做

**这一家是重点。** 原因是：它是四家里把"分层"做得最明确的，而且文档和源码对得上——读懂它，其它三家就都只是变体。

Hermes 是 NousResearch 的开源 agent harness（Python）。

### 2.1 一句话

> 先做所有不花钱的清理，只有真撑不住了，才花钱请模型写一张结构化交接条。

### 2.2 比喻

你的桌子满了。Hermes 的处理顺序是：

1. **先把草稿纸挑出来扔了**——那些用过就不太会再看第二眼的（旧命令输出、旧文件内容）。这一步不动脑子，不花钱。
2. 还是满？**把最早的那几页抽出来，请人读一遍写成一张交接条**，夹在最上面。
3. 最近写的几页**原样留着**，因为下一步最可能要用到。
4. 第一页（你的原话、当前任务）**永远留着**。

### 2.3 真实机制

它把这件事拆成四个阶段（源码 `agent/context_compressor.py`）：

**Phase 1：确定性剪枝（零 LLM 调用）**

不花钱的那些活，包括：

- 内容完全相同的工具结果，去重
- 超过 **200 字符**的旧工具结果，降级成一行摘要，占位符原文是 `[Old tool output cleared to save context space]`
- 超过 **500 字符**的工具调用参数，截断（而且是改写解析后的 JSON，保证它还是合法 JSON）
- 旧图片降级成占位符，**最新 3 张保留**
- 尾部压力过大时，还有一轮 `_pressure_demote_tail`

注意这里有保护：**小于等于 200 字符的工具结果是豁免的**（`_PRUNE_MIN_CHARS = 200`），不剪。因为小结果占不了多少地方，剪了反而丢信息。

**Phase 2：切三段**

把消息切成 head / middle / tail：

- **head** = system prompt + 前 `protect_first_n` 条（默认 **3**）
- **tail** = 从末尾往前累计，直到用完 tail 预算
- **middle** = 中间那块，送去摘要

tail 预算是动态算的。lean 模式（默认）：

```
tail = max(10_000, min(25_000, 上下文窗口 × 0.025))
```

也就是**窗口的 2.5%，夹在 1 万到 2.5 万 token 之间**。

另外两个硬性保护：**最新的一条"要干活的"用户消息**和**最新的一条助手消息**，无论预算够不够，永远拉进 tail。

**head 保护会衰减。** 这是个很妙的细节：`protect_first_n` 那 3 条在**第一次压缩之后就变成 0**。为什么？因为如果不衰减，最早那几条用户消息会被反复保护、反复留在桌上，变成一个"化石"——永远在场，永远挤占空间，而它可能早就无关了。源码注释点名了 issue #11996 就是这个毛病。

**Phase 3：结构化摘要**

请模型写一张交接条，字段是硬的（不能自由发挥）：

```
## Historical Task Snapshot
## Goal
## Constraints & Preferences      ← 用户的约束和偏好
## Completed Actions               ← 编号列表，格式 N. ACTION target — outcome [tool: name]
## Active State                    ← 工作目录/分支、改动文件、测试状态、运行中的进程
## Blocked                         ← 要求带精确报错
## Key Decisions                   ← 要求带 why
## Errors & Fixes                  ← 要求引用用户纠正的原话
## Resolved Questions
## Relevant Files
## Critical Context                ← 凭据一律 [REDACTED]
## Pruned Skills
```

两个细节值得注意：

- `Errors & Fixes` 明确要求**引用用户给出的纠正原话**。这是在对抗"转述会失真"这件事。
- 摘要本身有预算：`max(2_000, min(被压缩内容的 20%, min(窗口的 5%, 10_000)))`。**摘要不能比它压缩掉的内容还大**，否则这一步白做。

**Phase 4：组装 + 修 tool 配对**

出来的形状是 `[head][summary][tail]`。摘要是单独一条消息，带前缀 `[CONTEXT COMPACTION — REFERENCE ONLY]` 和结束标记。

如果摘要行的角色跟 tail 第一行冲突、翻转也不合法，就不新增独立行，而是**把摘要合并进 tail 第一行的内容里**。

**多次压缩怎么办？** 不是再生成一条新摘要，而是把上一次的摘要当作「PREVIOUS SUMMARY」喂回去，要求**结构不变地增量更新**。这是它对抗"摘要膨胀"的办法。

### 2.4 阈值：一个文档和源码打架的地方

这块值得单独讲，因为**网上流传的数字是错的**。

Hermes 用的是**相对比例**，不是绝对 token 数。配置默认 `compression.threshold = 0.50`。

但源码里还有一层：窗口小于 **512,000** 时，阈值会被强行抬高到 **0.75**（`_SMALL_CTX_THRESHOLD_PERCENT`，且是 raise-only，只升不降）。另外还有个 **64,000** 的下限（`MINIMUM_CONTEXT_LENGTH`）。

所以：

| 窗口 | 网上流传的触发点 | 源码实际触发点 |
|---|---|---|
| 200K | 100,000 | **150,000**（0.75 生效） |
| 1M | 500,000 | **500,000**（0.50 生效） |

官方文档自己的 "Computed Values (200K at defaults)" 那一段写的是 100,000——**它跟同一份文档 261-263 行的 floor 规则冲突**。这不是"文档写的是旧版本行为"，而是**同一份文档内部没算对**。

我还发现另一处：文档正文写摘要上限 12,000，源码常量是 `_SUMMARY_TOKENS_CEILING = 10_000`。（不过在 200K 这个例子上两者算出来一样，都是 10,000——要窗口超过 240K 才会有差别。）

> **给你的教训**：这个领域的官方文档普遍落后于源码，把文档当权威会踩坑。

**为什么小窗口要抬到 0.75？**

官方给了理由，而且是个很实在的理由：有些东西**压不掉**——system prompt、几十个工具的 schema，这些是固定的，加起来 20-30K token。在 200K 窗口上按 0.50 触发，压完回收的空间还不够抵消这个固定开销，结果就是**每压一两次又满了，再压**。所以宁可早压。

### 2.5 压缩之后，会话还是同一个会话

这里也有个流传错的说法。**Hermes 默认不换 session id。**

配置默认 `compression.in_place: True`，意思是**原地重写同一个 session**：旧的消息行被软归档（`active=0, compacted=1`），还能被 `session_search` 搜到，**从不删除**。

只有把 `in_place` 设成 `false`，才走老路：新建一个子 session、轮换 id、记录父子血缘。

官方说改成 in_place 是为了消除"session 旋转 bug 簇"——原话列了三种：丢 `/goal` 状态、孤儿 session、跨边界搜索缺口。

### 2.6 它为了缓存改过设计——而且改的方向是"少压缩"

这是 Hermes 最有意思的地方。

**prompt cache** 是供应商给的折扣：如果你的请求前缀跟上次一字不差，那部分按 0.1 倍计价。但**压缩的定义就是重写前缀**——两者天生打架。

Hermes 的应对不是"想办法压得更好"，而是**主动少压**：

- **micro-compaction 默认关掉**。文档原话的理由：「每次 pass 都会重写已经发出去的历史，每一轮都在破坏 provider 的 prompt-cache 前缀……在某些配置下这个代价超过它带来的好处。」
- **proactive prune 加了个门槛**：要回收至少 **4,096 token** 才允许动手。配置注释说得很直白——要让破坏变成「一次大的偶发中断，而不是每个工具循环都来一下小的」。

还有一条更细的：它把 system prompt 内部分成三层，**稳定 / 上下文 / 易变**，并且在文档里解释了为什么：

> 项目共享文件排在"当前工作目录"之前。这样同一个项目在不同 git worktree 下的会话，能共享一段覆盖整个 context block 的前缀——而这段前缀正是最长前缀缓存会复用的东西。

`agent/prompt_cache_boundary.py` 这个文件更极端：skill / webhook / cron 的构造器会拼一个"大段静态脚手架 + 小段易变尾巴"的消息，**只有构造器知道易变尾巴从哪个字节开始**，所以它把这个边界登记下来，让缓存规划器在正确的位置打断点，而不是把整条消息当一个原子块缓存。

### 2.7 记忆是另一条轴，不是压缩的替代品

Hermes 有两套文件式长期记忆，放在 `~/.hermes/memories/`：

- `MEMORY.md`——上限 2200 字符（约 800 token）
- `USER.md`——上限 1375 字符（约 500 token）

它们是**会话启动时的冻结快照**，注入到 system prompt 的 volatile 段。跟压缩的关系有三处：

1. 摘要 prompt 里插一段 `MEMORY PROVIDER CONTEXT`
2. 摘要前缀和压缩提示词里**反复声明**「persistent memory (MEMORY.md, USER.md) 永远权威」——在文本层面显式保护
3. memory provider 有个 `on_pre_compress` 检查点钩子，可以配置成「没检查点就不许压缩」（fail closed）

另外有个项目文件读取上限的公式也值得记：

```
单文件上限 = max(20_000, min(窗口token × 4 × 0.06, 500_000))   字符
```

截断的时候，提示语会**明确写出文件路径**并让 agent 用 `read_file` 去取完整内容。这比"内容被截断"这种含糊提示有用得多。

### 2.8 LCM：一个必须澄清的误会

网上有不少文章说 Hermes 用了一套叫 LCM（Lossless Context Management）的无损上下文管理——不可变存储 + 摘要 DAG + 七个工具（expand/pin/forget/search/focus/budget/toc）+ 三级升级 + DAM 神经检索。

**这套东西没有进主干。**

核实结果：

- PR **#4033**（正是这个设计）状态是 **closed、draft、merged=false**，2026-04-09 关闭，+14036/−143，72 个文件。
- 主干里 `plugins/context_engine/` 目录**只有一个 `__init__.py`**（发现和加载器），没有打包的 lcm engine。
- 官方文档确实在讲 LCM，但那是作为**「怎么给 Hermes 写一个 context engine 插件」的举例**——是范式声明和设计意图，不是已交付物。
- 真正能用的是第三方插件 `stephenschoettler/hermes-lcm`。而且它的工具集跟 PR #4033 **基本不相交**——PR 那七个 `lcm_expand/lcm_pin/lcm_forget/lcm_search/lcm_focus/lcm_budget/lcm_toc`，插件里一个都没有；插件的 15 个工具是 `lcm_grep/lcm_recall/lcm_expand/lcm_describe/...` 这一套。

第三方插件自带的参数也跟内置压缩器完全不同，是两套：

| | 内置 ContextCompressor | 第三方 hermes-lcm |
|---|---|---|
| 阈值 | 0.50 / 0.75 | **0.35** |
| 保护尾部 | protect_last_n=20 | **32 条消息** |
| 摘要熔断 | 阶梯 (60,300,900) 秒 | **连续失败 2 次 / 冷却 300 秒** |
| 摘要结构 | 12 段 | DAG，最大深度 **3** |
| 三级升级 | 无 | **有**（detailed → bullets → 确定性截断） |

注意：**第三方插件是有三级升级的**（它 README 的 "How it works" 第 4 步原文就是 Escalate），缺的只是 PR 里那套 DAM / HRR 神经检索层。这一点第一轮调研搞错了，第二轮核验纠正过来了。

还有一层耦合：插件在"无状态会话"上会**回退调用内置的 ContextCompressor**——所以一次部署里可能两条压缩路径并存。

### 2.9 它的失败清单（说明这不是简单问题）

Hermes 源码注释里带 issue 号，列出来很有教育意义：

| issue | 症状 |
|---|---|
| #14690 | 64K 小模型上，阈值算出来等于整个窗口 → **压缩永不触发**，供应商先拒请求 |
| #14695 | system prompt + 工具 schema 的固定开销本身就超阈值 → **每轮压了又压的死循环** |
| #40803 | 整段 transcript 恰好塞进 soft ceiling → 切点算在 head 之前，反复无进展 |
| #10896 | 最新一条用户消息被切在压缩外（已修） |
| #11996 | 早期 turn 被反复保护成"化石"（已用 protect_first_n 衰减修） |
| #11978 / #11914 | **摘要返回空内容** → 模型忘掉正在做的任务 |
| #92699 | 尾部图片无法降级，每次请求都重发 → 触发 anti-thrash 关掉压缩 |
| #100611 | 原生 checkpoint 是约 500 万字符的密文，被按 bytes/4 估算成 **约 129 万 token 的幻影**，对着阈值反复触发本地压缩 |
| #70328 | 每张图固定按 1500 token 计价，实测 4000+ → 估算 20K 而真实 prompt 过 80K，压缩永不触发 |

对策是几层熔断：摘要失败冷却阶梯 (60, 300, 900) 秒；anti-thrash 在"无效压缩连续 2 次"或"退化成 fallback 连续 2 次"时跳闸，跳闸后 300 秒只允许一次探针；`compression.max_attempts` 默认 3（上限 10）。

**一个最实在的教训**：文档说「摘要模型的上下文必须 ≥ 主模型的窗口，否则摘要调用会吃 context-length 错误、返回 None，中间那段就被**无摘要丢弃**」——而官方把这条称为**压缩质量下降最常见的原因**。

这条直接推翻了一个很自然的优化想法：**"摘要这种活随便找个小模型干就行"是陷阱。**

---

## 第 3 章 Claude Code：前台收银，后台整理

### 3.1 一句话

> 主线程只干一件事——在快满的时候请一个"分身"把前面写成 9 段交接条，写完之后连逐字的历史都不留，靠重新读盘把关键东西捞回来。

### 3.2 触发：一个减法

```
有效窗口 = 模型窗口 − min(模型的输出上限, 20,000)
触发点   = 有效窗口 − 13,000
```

- 200K 窗口 → `200,000 − 20,000 − 13,000` = **167,000** 触发
- 1M 窗口 → `1,000,000 − 20,000 − 13,000` = **967,000** 触发

那个 20,000 是有注释的，而且注释写得很可爱：**"基于压缩摘要输出的 p99.99 是 17,387 token"**。也就是说这个数是拿真实分位数定出来的，不是拍的。

1M 那档跟官方文档对得上——文档写「at about 967K tokens by default」，**精确吻合**，这也是"13K 缓冲仍是现值"的最强交叉证据。

> **存疑点**：200K 那档有个矛盾。源码算式给 167,000，但官方文档对 200K 模型写的是「compact at the 200K boundary」。如果阈值真的在 167,000（明显在边界之前），文档不该这么写。这一条我标为**源码值、未经文档交叉验证**。
> 另外那个 13,000 到底是干什么用的，**官方从未说明**。只有 20,000 有注释。别信任何说得斩钉截铁的二手解释。

### 3.3 三层，但默认只有一层对你生效

Claude Code 名义上有好几层压缩。但**对普通外部用户，实际上只有最重的那一层会触发**：

| 层 | 是什么 | 默认状态 |
|---|---|---|
| 时间触发的微压缩 | 距最后一条助手消息超过 60 分钟后，把除最近 5 个以外的可压缩工具结果内容换成 `[Old tool result content cleared]` | **关**（`enabled: false`） |
| 会话记忆压缩 | 用后台 agent 预先维护的"会话记忆"当摘要，省掉一次 LLM 调用 | **关**（两个开关都默认 false） |
| 完整 LLM 摘要 | fork 一个分身写 9 段摘要 | **这就是你实际经历的** |

只有 8 类工具（Read / Bash / PowerShell / Grep / Glob / WebSearch / WebFetch / Edit / Write）属于"可压缩工具"。

### 3.4 摘要 prompt：9 段 + 一个草稿块

摘要要求固定 9 段，而且开头有一段很凶的指令：

```
CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.
- Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
- Tool calls will be REJECTED and will waste your only turn — you will fail the task.
```

（这段是从 npm 发行包里直接抠出来的原文，不是转述。）

结构是先写 `<analysis>` 草稿块（大声思考），再写 `<summary>`。**草稿块在注入前会被整段剥掉**——用思维链换摘要质量，但不占压缩后的 token。这个设计挺聪明的。

9 段是：Primary Request and Intent / Key Technical Concepts / Files and Code Sections / Errors and fixes / Problem Solving / All user messages / Pending Tasks / Current Work / Optional Next Step。

其中第 6 段特别点明：**「列出所有非工具结果的用户消息」**。第 9 段要求带最近对话的**逐字引用**。

### 3.5 压缩之后形状是 `[boundary][summary][附件][hooks]`——没有 tail

**这一点必须讲清楚，因为很多二手文章讲错了。**

Claude Code 的完整压缩路径**不留逐字的历史尾巴**。`messagesToKeep` 在这个路径上是空的。

压缩后的消息数组是：

```
[compact_boundary 标记] [摘要消息] [重新注入的附件] [SessionStart hooks 输出]
```

它靠"重新读盘"把这些东西捞回来：

| 捞回什么 | 限制 |
|---|---|
| 项目根 `CLAUDE.md`、rules、auto memory | 从磁盘重新读 |
| 最近读过/改过的文件 | 最多 **5 个**，按修改时间倒序，总预算 **50,000 token**，单文件 **5,000 token**（超了就只留路径） |
| 已调用的 skill 正文 | 单 skill 上限 5,000，总上限 25,000，超额丢最老的 |
| plan 文件、plan_mode 提示 | 重新追加 |

**不在保护清单里的东西就真的没了**——比如 hooks 之前加进上下文的内容、带路径限定的 rules、子目录里的嵌套 CLAUDE.md。

所以那句"只留最近写的几行原话"**对 Claude Code 不成立**。它只对应"会话记忆压缩"那条默认关闭的路径。

### 3.6 它为一个技术细节付出了很大代价：缓存前缀

摘要请求不是单独起一个请求，而是**fork 主会话**——用同样的 system prompt、同样的工具、同样的历史，只在最后追加一条摘要指令。

为什么？**为了复用主会话的 prompt cache 前缀。** 源码里有个开关 `tengu_compact_cache_prefix` 默认开着，注释说：关掉的路径**98% 缓存 miss**。

fork 的时候还有个讲究：**故意不设 `maxOutputTokens`**，因为设了会改动 thinking 配置，进而改变 cache key。

另有一个很细的 bug 修复也值得记：压缩成功或失败之后都要调 `notifyCompaction` 重置 cache-read 基线，否则压缩后 cache read 骤降会被缓存断裂检测**误报成异常**——注释说某次缺了这一步，导致 **20% 的 `tengu_prompt_cache_break` 是假阳性**。

### 3.7 熔断器的来历：拿生产数据换的

`MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3`，连续失败 3 次就本会话不再自动压缩。

为什么是 3？源码注释给了一份生产数据：某日有 **1,279 个会话连续失败 50+ 次**（最多 3,272 次），全球每天浪费约 **25 万次 API 调用**。

> 但要诚实：注释只给了这份统计，**没说"所以定成 3"**。因果是推测。

### 3.8 用户抱怨最集中的三件事

从 issue 里看，主要是：

1. **plan 被忘**（#24686 / #13955 / #26532）
2. **CLAUDE.md / MEMORY 的关键信息压缩后丢失**（#28469 / #29890）
3. **整段历史只剩一张摘要**（#19888）

源码里对 plan 和 CLAUDE.md 的重注，其实是**事后打补丁**——发现丢了，才加"从磁盘重新注入"。

还有一条很硬的数据：有用户测了 354 次裸 `/compact`，发现写进 CLAUDE.md 的 "Compact Instructions" **一次都没生效**（354/354 全部回落到内置模板）；改成把指令内联写成 `/compact <内容>` 之后 7/7 生效。这直接说明"用户的自定义约束到底有没有进压缩器提示词"是个真实存在的问题。

---

## 第 4 章 OpenCode：先擦草稿，再考虑抄纸条

### 4.1 一句话

> 两层：一层完全不花钱，把旧工具输出擦成占位符；另一层才花钱，用 LLM 写摘要，然后按切点把消息**重排**成 `[触发][摘要][尾巴]`。

### 4.2 触发：绝对 token 数，不是比例

```
预留 = min(20,000, 模型输出上限)           // COMPACTION_BUFFER = 20_000
可用 = 窗口 − 模型输出上限(最多 32,000)      // 没有 limit.input 时
     或 limit.input − 20,000               // 有 limit.input 时
溢出 = 已用 >= 可用
```

代表值（按公式推算）：

- 200K 窗口 → `200,000 − 32,000` = **约 168,000** 触发
- 1M 窗口 → `1,000,000 − 32,000` = **约 968,000** 触发

**这里有个很典型的 bug 值得看**（issue #45168）：某个模型声明 context 256,000 / output 64,000，但真实输入上限是 196,608。按公式算出来的阈值是 224,000，而实际用量封顶在 198,100——**永远够不到阈值，自动压缩永不触发**，每次全额重发约 196K 的原始输入。修法 PR 到现在还开着。

> 这是"用绝对阈值 + 固定 buffer"这套做法的固有问题：**换个模型，行为就漂了。**

### 4.3 第一层：prune（擦草稿纸，零 LLM 调用）

```
PRUNE_MINIMUM   = 20,000   // 攒够这么多才值得动手
PRUNE_PROTECT   = 40,000   // 最近这么多 token 的工具输出受保护
PRUNE_PROTECTED_TOOLS = ['skill']   // 这个工具的输出永不擦
```

从最新往回扫，累计工具输出 token。**最近 40,000 token 内的受保护**，再往前的就被"擦掉"——具体做法是给这个 part 打一个 `state.time.compacted` 时间戳，模型看到的是 `[Old tool result content cleared]`。

只有被擦的总量超过 20,000 token 才真正提交。<br>
`skill` 工具的输出来自白名单，永不裁。

**全程没有 LLM 调用。**

注意配置默认 `compaction.prune` 是 **false**（关着的）。作者没解释为什么——config schema 上只有一个 `(default: false)`，源码注释只讲机制不讲动机。**任何说"因为 X 所以默认关"的说法都是推测。**

### 4.4 第二层：切 head / tail，存一个切点 ID

tail 预算：

```
preserveRecentBudget = min(15,000, max(2,000, 可用窗口 × 0.25))
```

切法是**从最新往回按"轮"累加**，装得下就整轮进 tail，装不下就调 `splitTurn` 在一轮内部按消息下标再切——**所以确实可能从一轮中间切开**（单位是消息下标，不是 part）。

切点存在**那条 user 消息的 `CompactionPart.tail_start_id`** 上。这是 OpenCode 一个很有辨识度的设计：**切点是个持久化的 ID，不是内存里的索引。**

重排的形状（`filterCompacted()`）：

```
[compaction-user("What did we do so far?")] [summary-assistant] [...保留的尾巴...] [continue-user]
```

注意注释里那句：**"filterCompacted reorders messages for model consumption"，数组位置不是时间顺序。** 压缩标记和摘要被整体搬到最前面，成了一个稳定的头部。

### 4.5 摘要写什么

```
## Objective
## Important Details
## Work State          (### Completed / ### Active / ### Blocked)
## Next Move
## Relevant Files
```

硬性要求：section 顺序不可变、空也要保留、用短 bullet、**必须原样保留精确的文件路径 / 符号 / 命令 / 错误串 / URL / 标识符**，而且**不得提及"摘要"或"上下文被压缩"**。

有上一份摘要时，额外注入 `<prior-summary>` 和合并指令（新的优先、把 Active 里完成的挪到 Completed）。而且模板里有一句很直白的警告：

> **"The `<prior-summary>` is discarded after this: anything you do not carry into the new summary is lost."**

### 4.6 一手设计动机（这份文件第一轮调研漏了）

OpenCode 仓库里有份设计文档 `specs/v2/session.md`，把动机写得非常清楚。这是第一轮调研完全漏掉的、最该引的一手出处：

> Compaction keeps the full transcript durable while replacing its active model representation with one hidden checkpoint containing a structured rolling summary and token-bounded serialized recent context. **Provider-native assistant, reasoning, and tool messages never survive across the boundary, avoiding signature and encrypted-reasoning failures when the earlier prefix changes.**

这段话解释了一个之前看起来"没道理"的设计：**为什么 tail 只保留序列化后的文本、不原生重放历史消息**——因为前缀一变，供应商签名和加密推理状态就失效了。

### 4.7 仓库里其实有两套，跑哪套是个坑

OpenCode 同时存在两套压缩实现：

- `packages/opencode/src/session/compaction.ts`（v1）
- `packages/core/src/session/compaction.ts`（v2）

v2 有自己的常量：`DEFAULT_BUFFER = 20_000`、`DEFAULT_KEEP_TOKENS = 8_000`、`SUMMARY_OUTPUT_TOKENS = 4_096`。

我核实到的情况是：

- 仓库自己的 `AGENTS.md` 写 **"Do not bridge through legacy `SessionPrompt.loop(...)`"**，`specs/v2/todo.md` 也称 v1 为 legacy → **v1 是遗留路径，v2 是新主线**。
- v2 的 `compactIfNeeded` 已经接进了 core 的 session runner。
- 但 V1→V2 的整体替换**还没完成**——`specs/v2/session.md` 里那张 parity 表的用途本身就写着「V2 runner 替换 V1 之前仍需检查的清单」。
- 两者**共享摘要 prompt 构建器**（v1 直接 `import { buildPrompt } from "@opencode-ai/core/session/compaction"`）。

所以：**准确说法是「v1 仍是 `opencode` 包（v1.18.30）的主循环路径，v2 已接入 core runner 且压缩部分 parity 标 complete，但整体替换未完成」。** 任何断言"现在跑的一定是某一套"的说法都过头了。

### 4.8 它的缓存问题比别家严重

OpenCode 的压缩请求复用了主对话的 `prompt_cache_key`（这个 key 只由 session id 决定），但它发出去的 payload 是 `tools: []`、无 system、而且用了另一套序列化格式。

结果：**prompt cache 是前缀匹配，而工具块排在最前面，所以第一个字节就分叉了。**

后果（issue #43249）：

- 每次压缩都是**全额 cache write**，**永远 0 命中**
- 还会把主对话即将要读的缓存条目挤掉
- 实测 3,245 个会话里有 52 个发生压缩，压缩花费 $36.95（约总量的 1.4%），`cache.read` 恒为 0

而且 `buildPrompt` 把易变的 `<prior-summary>` 排在固定的 `SUMMARY_TEMPLATE` **之前**，让唯一跨压缩相同的模板段也不可缓存。

修法（`#46369` cache-preserving compaction）**还开着，没合入**。

### 4.9 它的失败清单

| issue | 症状 |
|---|---|
| #44080 | 推理模型只产出 reasoning 部件、没有文本部件，系统**仍把空壳标为 `summary:true` 落库**并替换 epoch，原始历史不可逆丢失、不报错 |
| #41571 | 摘要为空（Kimi K3-256K）。结构前提是 v1 落库时 tokens 全零并显式 `cost: 0` |
| #27924 | runLoop 的两条压缩触发路径**都没有重试计数/熔断**，压缩不减少上下文就无限循环烧钱。issue 仍 open，但它挂的 5 个修复 PR **全部 CLOSED** |
| #48065 | 大上下文会话因 tail 预算太小算出 `keep=undefined` → head=全部、压缩比仅 56-60% → 约 10 分钟重新涨满 → **单会话一夜压缩 56 次** |
| #40235 | 中断的 tool run 之后，压缩永久报 `Duplicate value for tool_call_id` |
| #39291 | 压缩发送被改动的 thinking 块 → 永久 400 重试循环 |
| #45168 | 阈值够不到，自动压缩永不触发，静默成本爆炸 |
| #42282 | 压缩失败时**静默丢掉** pre-tail 历史 |

---

## 第 5 章 Codex：两条路，一条写交接条，一条直接换新本子

### 5.1 一句话

> 默认还是"写摘要"；但另有一条实验路径，**跳过摘要、直接把桌子清空**，靠模型自己记的笔记和一个只读的历史抽屉来恢复连续性。

### 5.2 默认路径：摘要

触发数学：

```
输出预留 = 95%              // effective_context_window_percent 默认 95
软阈值   = min(配置值, 窗口 × 9/10)
硬上限   = 窗口 × 95/100
触发     = 软阈值够到  或  硬上限够到      // 两项是「或」
```

≥ 90% 那一档是 `model_auto_compact_token_limit`，≥ 95% 那一档是硬顶。还有个 `auto_compact_fallback_buffer_tokens`，仓库里给某些模型配的是 16,384。

另有一个配置项 `model_auto_compact_token_limit_scope`，语义是「**算哪一段**占用」：

- `total`（默认）：整个活跃上下文都算
- `body_after_prefix`（可选）：只算"进入本轮压缩窗口之后的增长"

引入它的 PR 原话解释了动机：`total` 语义下，**保留的大前缀会吃掉整个预算，导致刚压缩完立刻又要压缩**。

### 5.3 摘要 prompt 很短，和别家很不一样

Codex 的本地摘要 prompt 只有 **4 个字段**：

```
Current progress and key decisions made
Important context, constraints, or user preferences
What remains to be done (clear next steps)
Any critical data, examples, or references needed to continue
```

开头有一句固定前缀：**"Another language model started to solve this problem..."** 代码里靠这个前缀识别摘要消息，避免二次压缩。

对比一下：Claude Code 9 段、Roo 9 段、Gemini 是 XML 快照、OpenCode 5 段。**Codex 是最短的。** 网上流传的"Codex 8 段式"是错的。

### 5.4 保留的是用户消息，不是 tail

它**不做 head/tail 分割**，整段历史一次性发给摘要器。保留规则是：

> 从历史末尾**倒着取 user 消息**，累计到 `COMPACT_USER_MESSAGE_MAX_TOKENS = 20,000`，最后一条放不下就截断。

**保留单位是整条 user 消息**，所以不会从一轮中间切开。**助手消息和所有工具调用/结果一律丢弃**——因此也不会产生半个 tool 对。

回放形状有个坑：**manual / pre-turn 和 mid-turn 的顺序不一样**。

- mid-turn：`[初始上下文][最后一条真实 user 消息][<COMPACTION_SUMMARY>]`
- manual / pre-turn：`[保留的旧 user 消息][<COMPACTION_SUMMARY>][PERMISSIONS 指令][ENVIRONMENT_CONTEXT][新 user 消息]`

原因在 `InitialContextInjection::{DoNotInject, BeforeLastUserMessage}` 两种模式。把 mid-turn 的顺序当成统一形状是常见错误。

**远端 v2 压缩的保留规则是另一个东西**，而且网上（包括这份文档的第一版）普遍说错。经独立核对源码（`compact_remote_v2.rs`），实际保留的是**四类**：

| 保留什么 | 条件 |
|---|---|
| user 消息 | 但**不是所有 user 角色消息**——必须能被 `parse_turn_item` 判为 `UserMessage` 或 `HookPrompt`。所以 `environment_context` 这类上下文型 user 消息**被排除** |
| hook prompt | 同上 |
| AgentMessage | 单条 ≤ 10,000 token，**且**不是 descendant progress、**且**不是 completion（final answer） |
| client-authored developer 消息 | 仅当 `Feature::RetainClientDeveloperMessages` 打开（**默认关**，Stage: UnderDevelopment） |

四类**共享同一个 64,000 token 预算**，倒序（新→旧）累加。

截断逻辑有个细节值得说准：**它不是一个严格的"放不下就停"**。当某条要超预算、而剩余额度又还够触发它的 attached notice 时，会截断这条、把剩余额度清零（后面就不收更旧的了）；但如果剩余额度已经小到连那条的 notice 都不够、而且这条又不触发图片计费，那么额度**不清零**，循环会继续去找更旧的、能塞得下的小条目。

（这套保留规则所在的 commit 是 `bc5957e`。要说清"某版本怎么保留"，得带上版本号——`developer` 那一支和图片计费那一支都还在演进。）

还有一层容易漏：**图片是否计入这 64k，由一个独立的开关决定**（`Feature::CompactionImageBudget`，已 Stable、**默认开**）。

单元测试印证：输入 developer / system / user / hook / assistant / FunctionCall / Compaction 一大串，断言结果只剩 `[user, hook, output]`。

> 注意"只保留 client-authored developer 消息"这个说法其实描述的是**硬切窗口那条路**（`start_new_context_window`），不是远端 v2 摘要。两条路被很多人混成一条。

### 5.5 实验路径：TokenBudget 硬切（这是重点方向）

`codex-rs/core/src/compact_token_budget.rs` 的注释说得很直接：

> Token-budget compaction **skips model/server summarization and installs a fresh context window** instead.

真正的切换发生在 `start_new_context_window()`：它**不总结历史**，而是重新生成一份初始上下文，把旧消息**整体丢弃**，`window_number` 加一。

配套的是两样东西：

**notes（小本本）**——模型在窗口快耗尽前自己写进度笔记。注入给模型的提醒原文（阈值 `reminder_threshold_tokens: 6144`）：

> Your current context window is nearly exhausted; only {n_remaining} tokens remain. Before starting a new context window, save concise progress notes with the `notes` tool with the goal, decisions, progress, learnings, next steps, and the window ID and item ID of every relevant user request still being solved... **Future context windows will not automatically include the current conversation.**

**history（大抽屉）**——一个**后端服务**，不是本地库。接口是 `alpha/history/v2/{list_windows, list_items, read_item, search_contents}`。所以原文在后端是可查的，这就是这条路"没有信息损失"的来源。

模型看到的"窗口身份"是一段 `<context_window>` 消息：Agent name / First context window id / Current context window id / (Previous context window id) + 一段 thread_hint（上限 4,000 字节）。

### 5.6 这条路的门禁很严，而且是实验状态

同时满足这些才激活：

- `Feature::TokenBudget` / `ContextManagement`（**Stage: UnderDevelopment，default_enabled: false**）
- OpenAI provider + codex backend
- **ChatGPT Plus / Pro / ProLite**（API key 不算）
- 模型 `supports_experimental_context`（仓库 catalog 里只有 gpt-6-astra）

另外 notes / history 是**"私有、模型专用"**能力——工具描述里明确要求模型**不要向用户披露它的存在或内容**。

**门禁的时间线**（核实过的一手事实）：

- 2026-09-02（#42385）：加"订阅 + 后端"限制，**当时不检查模型**
- 2026-09-06（#43147）：补上模型检查，`supports_experimental_context` 默认 `false`，**只对 `gpt-6-astra` 置 true**
- 2026-09-08（#43912）：把 Guardian 审查会话钉回摘要压缩
- 2026-09-09（#44281）：Guardian 的恢复逻辑里明确写 **"Avoid history-resetting token-budget compaction for this recovery."**

也就是说：**订阅限制是设计上就有的，模型限制是三天后补的收紧。** 而且连 OpenAI 自己最敏感的审计路径（Guardian reviewer）的兜底恢复都在**显式绕开硬切压缩**。

### 5.6.1 一个负面结论：这套东西的"为什么"根本不存在

我专门去读完了引入 TokenBudget 的那批 PR 的正文，包括 #27438 / #27488 / #27518 / #29743 / #29762 / #42385。结论是：

**OpenAI 没有任何一处说明"为什么用硬切而不是摘要"。**

这些 PR 的正文格式统一是 `## Why / ## What changed / ## Testing`，但 "Why" 段写的是**行为规定**或**能力缺口**，不是设计论证。比如：

- #27438 的 Why：「模型应该能看到窗口预算元数据」——这是"消息长什么样"，不是"为什么选这条路"
- #27518 的 Why：「模型没有直接办法按需索取剩余 token」——纯能力缺口
- #29743 的 Why：「TokenBudget 启用时，压缩应该表现得像 `new_context`……不去要求服务端摘要旧历史」——**这是行为规定，不是理由**

整批里唯一沾到动机的一句，是 #27488：

> When the model decides the current window is no longer useful, it needs a way to ask Codex to start over with a fresh context window **without spending tokens on a compaction summary**.

——也就是**省掉摘要那一次 token 开销**。

我还搜了整个仓库的文档：`docs/` 目录只有 CLA / config / install 这些，**没有任何 context management 的设计文档**，`extension:md "context management"` 搜索 **0 命中**。

**所以"OpenAI 认为硬切更好"这个结论，没有证据。** 有一条更强的反面证据：v0.153.0 的 release notes 官方措辞是「Adds the **disabled-by-default** `features.context_management.experimental_mode` configuration」——**disabli-by-default、experimental**。

一句话：**它是一条针对特定场景的实验路径，不是"更好的方案"。**

### 5.7 一个反证：摘要路径没被弃用

`#43912`（2026-09-08）专门把 Guardian 审查会话**钉回摘要压缩**。源码注释给了理由：

> **"Only summarizing compaction preserves the action and evidence; token-budget resets must fail closed."**

也就是说：对需要保留"证据链"的审查场景，硬切窗口会丢证据，所以必须用摘要。

这条很重要——它说明 OpenAI 内部并没有把硬切当成"更好的方案"，而是**针对特定场景的实验**。

### 5.8 它自己承认的问题

代码里有一条明确的警告要发给用户：

> **"Long threads and multiple compactions can cause the model to be less accurate. Start a new thread when possible."**

防循环也没有硬计数器，靠的是一个假设（`turn.rs` 注释原文）：

> "as long as compaction works well in getting us way below the token limit, we shouldn't worry about being in an infinite loop"

用户侧的实测（issue #14347）给了一个质量衰减分档：

| 压缩次数 | 质量 | 现象 |
|---|---|---|
| 0-2 | Excellent | 几乎无损失 |
| 3-5 | Good | 细节消退，决策保留 |
| 6-8 | Acceptable | 高层路线还在，细节没了 |
| 9+ | Degraded | 连累积的摘要本身也开始被压缩 |

另外两个用户报的问题：

- **#19111 标题就是 "System prompt does NOT survive compaction"** —— 用户放在 `~/.codex/CODEX.md` 的内容压缩后模型就不知道了。对照 Claude Code 会把 CLAUDE.md 从磁盘重注，说明"压缩后什么能活"**是实现选择，不是必然**。
- **#26656** "CONTEXT COMPACT AFTER CONTEXT COMPACT EATING TOKEN"。

### 5.9 关于那段"内存 / 工作集 / 冷存储"的类比

网上流传一个说法：Codex 把上下文窗口类比成内存、notes 类比成工作集、history 类比成冷存储。

**我没找到这个类比的官方表述。** 最接近的只有两处：

1. 源码里注入给模型的文案：`"Once the token budget is exhausted, you will lose access to the current window ... recover through notes and history tools"`
2. 社区帖的三段式描述

**这是观察者的归纳，不是 OpenAI 的说法。**

而且这个类比在给外行讲的时候**该弃用**——真实的内存换出换的是精确副本，page fault 由硬件强制；而这里的"换出"是有损摘要，还要模型自己意识到缺信息才去查。两条关键差别全反了。

---

## 第 6 章 别的公司在干什么

（这一章覆盖面较广，只列最值得对照的）

### 6.1 Aider：不压缩对话，先把该看的挑出来

Aider 的思路完全不同：**它不主要靠压缩，靠"永远只把相关文件放进上下文"。**

- 仓库地图（repo map）用 tree-sitter 抽符号 + PageRank 排序，**永不压缩、每次重算**
- 对话压缩只针对 `done` 块（已完成的部分），阈值 `max_chat_history_tokens = min(max(窗口/16, 1024), 8192)`

注意这个公式：**不论窗口是 200K 还是 1M，触发点都是 8192**（因为被 clamp 住了）。这等于押注"对话历史不值得留太多"。

它的消息被分成 8 块（system / examples / readonly_files / repo / done / chat_files / cur / reminder），**只有 `done` 会被摘要替换**。system 提示、仓库地图、只读文件、当前 chat 文件、reminder 都不进摘要器。

缓存上它是**主动排序**：`add_cache_control_headers()` 在 examples（否则 system）、repo（否则 readonly_files）、chat_files **三处**打缓存断点——把易变的 `done` / `cur` 排在可缓存块之后。

（注意：8 块是**重放顺序**，不是打缓存的顺序。）

### 6.2 Cline / Roo Code：把压缩做成"任务级"

这两家（Roo 出自 Cline）的共同点：**压缩单位是任务，不是会话。**

**Cline** 有两套策略可选：

- `basic`：纯确定性裁剪（不调 LLM）
- `agentic`（默认）：LLM 摘要

常量：`CONTEXT_WINDOW_INPUT_RATIO = 0.9`、`COMPACTION_TRIGGER_RATIO = 0.9`、`DEFAULT_PRESERVE_RECENT_TOKENS = 20,000`、`DEFAULT_SUMMARY_MAX_OUTPUT_TOKENS = 4096`。触发：`请求输入 >= maxInputTokens × 0.9`。

切点用 `isSafeCutBoundary` 保证不切断 tool 对——**tool_result-only 的 user 消息永远不作为切点**，而且永不摘掉最新一条 typed user prompt。这是**从源头避免**孤儿 tool_result，而不是事后再修。

摘要只有 5 段（Goal / State / Highlights / Next / Files），输出上限 4096，且**注释特别说明"这是上限不是目标"**——因为预算给太小的话，推理模型会把预算全花在思考上，摘要正文根本出不来。

（注意：这 5 段是 Cline **新 SDK 版**的。它的**经典扩展版**用的是另一套——`<thinking>` 草稿 + 9 段，和 Claude Code 那套同源。详见 6.9。）

**Roo Code** 用的是"**fresh start**"模型，非破坏式：

- 原消息全部打上 `condenseParent` 标记（永久保留，可以 rewind）
- 摘要作为末尾一条 `role=user` 消息
- 实际发给 API 的是 `[summary, ...摘要之后的消息]`
- 摘要 9 段（**是照抄 Claude Code 的**，这条已经判定清楚了，见 6.9）

它的 tool 对处理最完整，**双向都管**：

- 摘要前：给没有配对的 tool_call **补一条合成 tool_result**，内容 `'Context condensation triggered. Tool execution deferred.'`——注释原文说这是因为 **"OpenAI's Responses API rejects conversations with orphan tool_calls"**
- 重放时：过滤掉被摘掉区间里 tool_use 对应的 orphan tool_result

Roo 的代价写在源码注释里，很实在：

> 删/合并摘要消息会破坏 `isSummary` / `condenseId` 元数据，**导致所有被 condense 的消息复活**。

而且它有防呆：`messagesToSummarize.length <= 1` 时直接返回 `condense_not_enough_messages`；最近已有摘要且消息 <= 2 时返回 `condensed_recently`。

### 6.3 Gemini CLI：唯一一家给"压缩失败"做了熔断状态机的

阈值是**窗口比例**：`DEFAULT_COMPRESSION_TOKEN_THRESHOLD = 0.5`。

但注意一个实现细节：`tokenLimit()` 对列出的 Gemini 模型**一律返回 1,048,576**，代码里**没有 200K 分支**。所以 0.5 阈值在 200K 和 1M 窗口上都按 524,288 算——**两者触发点相同**。

摘要产物是 XML `<state_snapshot>`：

```
<overall_goal> <active_constraints> <key_knowledge> <artifact_trail>
<file_system_state> <recent_actions> <task_state>
```

`task_state` 用 `[DONE]` / `[IN PROGRESS]` / `[TODO]` 标进度。先写私有 `<scratchpad>`，然后**做第二轮 probe 自检**——让模型批判自己的快照，给出更精确的 FINAL 快照。

它的失败处理是六态状态机：

```
COMPRESSED
COMPRESSION_FAILED_INFLATED_TOKEN_COUNT    ← 摘要后 token 反而变多
COMPRESSION_FAILED_TOKEN_COUNT_ERROR
COMPRESSION_FAILED_EMPTY_SUMMARY
NOOP
CONTENT_TRUNCATED                          ← 降级成只截断
```

关键逻辑：一旦摘要把 token 数弄大了，**非强制压缩就不再调 LLM，只做截断**。注释理由是避免重复失败与重复花钱。

这是一家把"压缩是优化而非必需"**写进设计**的例子。

它也有个公开事故值得记：**压缩时进程 OOM 崩溃**（issue #19590）——529,479 token 想压到 131,437 token，客户端直接 `FATAL ERROR: Ineffective mark-compacts near heap limit`，彻底不可用需重启。被关为 not_planned。

### 6.4 Cursor / Amp：一个往训练走，一个干脆不用了

**Cursor** 方向最明确：把压缩**训练进模型**。

基线做法是"数千 token 的 prompt + 十几个分节，输出平均 >5000 token"。他们改成让 Composer 自己学会写摘要——prompt 短到接近 `'Please summarize the conversation'`，**输出约 1000 token**，且摘要里含对话状态（plan state、剩余任务、此前摘要次数）。官方说这样**压缩误差降低 50%，只用五分之一 token，并且复用 KV cache**。

另外它把"把东西挡在上下文外"当主手段：长工具输出写文件、MCP 工具描述外置到文件夹、终端会话当文件。官方一个 A/B 里，MCP 工具描述外置让调用 MCP 的运行总 token **降 46.9%**。

**Amp** 走得更远：**2025-10-23 直接移除了 compaction。**

官方给的理由就是失败模式本身：压缩是有损的，而且**"鼓励又长又绕的线程，一旦窗口满就压一次，摘要叠摘要"**。他们主张 200k token 足够了、短线程更好。

替代方案是 **Handoff**——用户主动换新线程，由第二个模型分析当前线程、起草新线程的 prompt 和文件清单，**发送前由用户审阅**。还有 `@@` / thread ID 引用，让第二个模型按当前提问从别的线程抽信息。

> **时效提醒**：Amp 那篇 `guides/context-management` **页面顶部自带 Archived 横幅**（2025-11 撰写，为更早的模型和上下文管理时代而写）。把它当现行文档引用是个常见的坑。

### 6.5 Goose：把摘要做成"结构化 JSON"，字段缺了也不崩

**先修正一个事实**：Goose 已经不在 `block/goose` 了。Block 在 2025-12 把它捐给了 Linux Foundation 旗下的 Agentic AI Foundation，现在是 **`aaif-goose/goose`**（GitHub 会重定向）。

它的设计很有辨识度：

- 阈值 `DEFAULT_COMPACTION_THRESHOLD = 0.8`（80%），可用 `GOOSE_AUTO_COMPACT_THRESHOLD` 调
- **摘要请求把历史放在 system prompt 里**，请求体只是一条固定文案 `"Please summarize the conversation history provided in the system prompt."`
- 摘要先写 `<analysis>` 草稿（丢弃），再输出**唯一一个 JSON 块**，9 个字段：`user_intent / technical_concepts / files[{path,summary,key_code}] / errors_and_fixes / problem_solving / user_messages / pending_tasks / current_work / next_step`
- prompt 里明确要求错误信息和测试输出**逐字引用**，还说了一句很实在的话：「这份摘要只会被你读，可以远长于给人看的摘要，**把全部篇幅预算花在 JSON 上**」
- **解析用宽松反序列化**：字段缺失默认空，非字符串会被 stringify 而不是拒绝；**JSON 解析失败就保留原始文本当摘要**（无损兜底）。这个"失败也要能降级"的设计值得抄
- 渲染用模板，带一个 `code_fence` 过滤器**动态加长反引号**，防止嵌入的代码块逃逸出 markdown——这个细节能看出作者踩过坑

它的 prune 层有一招很新颖：**`filter_tool_responses()` 从中间向两侧删工具响应**，按 `[0, 10, 20, 50, 100]%` 逐级重试。

为什么是"中间向外"？因为中间的最不影响续写。如果连工具响应都没得删了，就 fail fast 并给**可操作建议**（换大窗口模型 / 关扩展减小 tool schema / 开新会话）——而不是干耗。

熔断是 `compaction_attempts >= 2` 就放弃。

### 6.6 MemGPT / Letta："外部记忆"这一族的源头

**这一节要专门讲，因为那个最流行的类比（虚拟内存/分页）就是从这里来的。**

#### 6.6.1 论文说了什么

MemGPT（arXiv 2310.08560，Packer 等，UC Berkeley，2023-10）。它摘要里用的词是 **"virtual context management"**，明确说灵感来自「传统操作系统里的分层内存系统，通过物理内存和磁盘之间的分页提供扩展虚拟内存的假象」。

结构（论文 §2）：

- **main context（类比 RAM）** = LLM 的 prompt token，由三段拼成：system instructions（只读）、working context（固定大小的读写块）、FIFO 队列
- **external context（类比磁盘）** = 落在窗口外的信息，必须**显式移进来**才能被推理看到。两个库：recall storage（消息库）+ archival storage（任意长度文本对象）
- 分页**靠 function call 驱动**，用 `request_heartbeat=true` 实现函数链式调用

它的队列管理器有一层"预警"机制，和 Codex 的 `reminder_threshold_tokens` 神似：

- 超过 **"warning token count"（论文举例：窗口的 70%）** 时，往队列里插一条系统消息，警告模型即将换出（**"memory pressure" warning**），目的是让模型**赶紧把重要信息写进 working context / archival storage**
- 超过 **"flush token count"（举例：100%）** 时，换出约 **50%** 的消息，并用"已有递归摘要 + 被换出的消息"生成新的递归摘要
- FIFO 队列的第 0 位存的就是"被换出消息的递归摘要"

（70% / 100% / 50% 论文都写着 "e.g."，是举例值不是硬指标。）

**实验数字**——这张表是整份调研里最有说服力的一张：

| 模型 | Deep Memory Retrieval 准确率 |
|---|---|
| GPT-4 | 32.1% |
| GPT-4 **+ MemGPT** | **92.5%** |
| GPT-4 Turbo | 35.3% |
| GPT-4 Turbo **+ MemGPT** | **93.4%** |

**为什么这张表重要**：基线之所以这么低，是因为固定窗口的基线拿到的是**"过去五段对话的有损摘要"**，而 MemGPT 拿到的是全量历史、只能通过分页搜索召回。

**这就是"有损摘要 vs 可检索原文"的差距，60 个百分点。**

#### 6.6.2 Letta 现在是什么样

**两个必须先说的发现：**

1. **`letta-ai/letta` 的 `main` 分支已经没有源码了。** 2026-08-16 的 commit `chore: archive the legacy server repository (#3430)` 把源码从 main 拿掉了，只剩 19 个根级文件（README / LICENSE / AGENTS.md 等）。我能读的最后一份完整开源服务端在 `archive` 分支。
2. **官方 compaction 文档页顶部标着 "V1 SDK (legacy)"**，文档站现在主推 Agent SDK。所以下面这些属于**遗留服务端**的机制。

它的 compaction 有四种模式：

```
sliding_window（默认） / all / self_compact_all / self_compact_sliding_window
```

这里有个很直接的证据，说明这个领域的"最佳实践"是怎么流动的——`self_summarizer.py` 的文件头注释原文：

```python
"""Claude Code-style summarization where agent self-summarizes using its own LLM."""
```

**也就是说 self_compact 这一支是照着 Claude Code 做的，不是 MemGPT 原教旨。**

而 `self_compact_*` 存在的理由是缓存：它让摘要请求带上 agent 自己的 system prompt 和工具定义，摘要指令作为 user message 追加进原有上下文——文档原话是 "keeps the prompt prefix identical to normal agent requests, **improving cache hit rates**"。

还有一句旁证同一个趋势的注释，在 `thresholds.py` 的文档串里：

> "this aligns GPT-5 behavior with **the codex harness' proactive 90% compaction policy**."

**Letta 在自己的源码里明确把 90% 主动压缩对标 Codex harness。**

切点对齐做得很细：切点**必须落在 assistant 消息上**，否则工具调用/结果对会被劈开。找不到合法切点就抛错，然后**降级到 `all` 模式**。循环是 `eviction_percentage` 从 0.3 起步、每次 +0.10。

护栏 prompt 里有一条值得单说——第 7 节 **"Lookup hints"**，明确要求模型记下"塞不进摘要的细节"的主题词，以便日后回查。

**这是对"摘要有损"的显式补偿。** 换句话说：作者承认摘要有损，所以额外要求模型留下"挖掘指引"。

另外 `memory_replace` 这条工具比 `core_memory_replace` 更严：`old_string` 必须唯一，出现多次直接报错并把行号列出来——因为不唯一的替换会把记忆改错。

#### 6.6.3 一个重要的区分：self-editing memory ≠ notes + 检索

很多人把这两个当一回事，其实它们是**同一架构里的两个不同层**。

| 层 | 内容 | 进上下文的方式 | 代价 |
|---|---|---|---|
| **memory blocks**（self-editing memory） | 放在 system prompt 里 | **永远可见，不需要检索** | 每一轮都付 token |
| **recall**（对话历史） | 消息库 | 按需检索 | 可能想不起来查 |
| **archival**（笔记） | 向量库 | 按需检索 | 同上 |

官方文档对 memory blocks 的措辞是：「persist across all interactions. **They are always visible - no retrieval needed.**」

真正的分野只有一条轴：**内容进入上下文是"钉住"还是"按需取"**。

- 钉住（push）：每轮都付 token，换"必然可见"
- 按需（pull）：只在用到时付一次，换"可能漏"

而保真度上还有一层差别：memory block 里装的是**模型自己组织的转述**（会漂移、会自相矛盾，所以才有 `memory_replace` / `memory_rethink` 这种"整块重写"的工具）；archival 里可以塞近乎原文的内容。**一个是有损的自述，一个是较保真的外存。**

有意思的是**这两个东西正在融合**：Letta 后来给 block 加了 `memory_apply_patch`，描述里直接写 "Extended, **codex-style** behavior"，用 `+`/`-`/`@@` 的 unified diff。反过来看，一个每轮都被模型主动重写的 notes 文件，功能上就是一个 memory block。

#### 6.6.4 分页这个类比，能用到什么程度

**站得住的：**

1. 两层结构 + 容量约束 + 强制换出——这个结构是真的
2. "用比物理内存更大的内存"这个 illusion **确实达成了**。DMR 那张表不是隐喻，是可测的行为
3. 内存操作是**显式调用**（syscall 式），不是透明的
4. 控制流与内存管理耦合（`request_heartbeat` 让模型能把"查—再查—再答"串起来）

**会误导的：**

1. **换出是有损的，而且是用同一个模型来做这件有损的事。** 真实 VM 换出的是精确字节副本，换回来一模一样，程序语义不变、只变慢。这里换出的是摘要，换回来的是**同一族模型生成的转述**——而且论文 flush 那一步是"已有递归摘要 + 新换出的消息再生成摘要"，**误差会跨轮累积**。DMR 表里基线拿到"有损摘要"只考了 32–35%，就是这件事的直接证据。
2. **缺页靠模型自觉，不是靠硬件。** 真实 VM 里程序**不可能忘记**缺页——访问不存在的地址一定触发。这里"缺页"是一次 tool call，模型不去查就没有 fault。**没有 MMU。** 论文自己承认了这一点（原话："Awareness of context limits is a key aspect in making the self-editing mechanism work effectively"）。
3. **换出会改变语义，而不是保持语义。** 真实 VM 里换出对程序可观测行为无影响（只有延迟变）。这里换出**恰恰改变模型的能力**，这正是它想要的效果。所以它根本不是"透明的"虚拟内存，而是一次**有意的、有副作用的上下文干预**。
4. **没有替换策略，只有静态分区 + 阈值。** 论文是 system instructions / working context / FIFO 三段**手工切分**，没有 LRU、没有访问计数、没有"热页"概念。这叫"分段回收"，不叫"页面置换算法"。
5. **地址空间不是均匀的。** "取出"走的是 embedding 语义检索，是**近似排序**，不是精确寻址。真 page fault 是精确命中、代价可预期；语义检索可以就是查不到。论文自己承认文档 QA 上「golden document 常在前十几个结果之外」。

**还有一层源流的事实值得单说**：论文里 OS 味道最重的那部分——队列管理器 + memory pressure 预警 + flush + 递归摘要——在今天的 Letta 线上实现里**基本退场了**：

- v3 里 `summarize_conversation_history` 那条主动预警路径**整段被注释掉**
- 模型可见的 "memory pressure" 提醒被降级成一个**默认关闭**的开关
- 实际触发改成两种：每步后 `token 估算 > 0.9 × 窗口`，或超窗报错后重试
- 换出改成主流的 harness 式滑窗摘要

**活下来的是"分层 + 自我编辑块"，退场的是"OS 调度器"。** 所以现在真正继承 MemGPT 血统的是前者，不是"分页"这个隐喻本身。

**一句话结论**：分页可以当**设计词汇**用（分层、容量驱动换出、显式记忆操作），但只要落到**保真度 / 强制性 / 透明性**这三点就必须撤掉类比。它最会误导人的地方，恰好就是前面说的那个失败模式：**模型没意识到缺信息时，什么都不发生，静默丢失，而且摘要还会一轮轮漂。**

### 6.7 provider 原生压缩：摘要归谁所有

**这是一个之前被散落在各家的独立家族，值得单列。** 因为它决定了"前缀和摘要归谁"，而这正是缓存故事的关键。

这个家族里其实是**两类不同的机制**，别混：

- **clear（挖洞）**：把历史里某些块换成占位符，**不产生摘要**
- **compact（重写）**：让模型把历史重写成摘要，**替换掉原文**

#### Anthropic：`compact_20260112`（beta）

```python
context_management={"edits": [{"type": "compact_20260112"}]}
```

（beta header `compact-2026-01-12`，在 `client.beta.messages.create` 里传。）

| 参数 | 默认 | 说明 |
|---|---|---|
| `trigger` | `{"type":"input_tokens","value":150000}` | `input_tokens` 是唯一支持的触发类型，**最低 50,000** |
| `pause_after_compaction` | `false` | 生成摘要后停下 |
| `instructions` | `null` | 自定义摘要 prompt，**完全替换**默认 prompt，不是追加 |

**几个关键行为，都影响你的架构选择：**

- **compaction block 出现在 assistant response 的开头。** API 收到它时，**它之前的全部 content block 被忽略**——注意是"之前"不是"之后"。所以客户端可以留着旧消息不管。
- **`pause_after_compaction` 是 provider 侧唯一能做"摘要 + 保留最近几轮原文"混合策略的钩子。** 置 true 时 API 在生成 compaction block 后停下，返回 `stop_reason == "compaction"`，此时 response **只含 compaction block**。你可以在这时插入想原样保留的消息，再发一次请求继续。
- **摘要那趟要额外计费、吃 rate limit。** 官方原话 "requires an additional sampling step, which contributes to rate limits and billing"。usage 里多出一个 `iterations` 数组，里面有一笔 `{"type":"compaction", ...}` 记录这次额外采样的 token。
- **两条硬限制**：① 摘要用的是**请求里的那个模型**，不能换更便宜的；② 请求带 `tools` 时，模型**偶尔会在摘要步骤里去调工具**而不是写摘要，结果是 compaction block 的 `content: null`。官方建议用 `instructions` 明确写"不要调用任何工具，只回文本"。

**缓存怎么配合（官方明确说了，而且说得直白）：**

1. 直接在 compaction block 上加 `cache_control`，缓存这段摘要；
2. **在 system prompt 末尾再放一个 breakpoint**。原因是：compaction 发生时摘要属于新内容要写缓存，如果没有独立的 system breakpoint，**会连带把缓存的 system prompt 一起冲掉**。加了之后 system prompt 继续命中，只有摘要写新缓存。

#### Anthropic：clear 家族

`clear_tool_uses_20250919`：

| 配置 | 默认 | 说明 |
|---|---|---|
| `trigger` | 100,000 input tokens | 可按 `input_tokens` 或 `tool_uses` 计 |
| `keep` | 3 tool uses | 保留最近 N 个 tool use/result 对 |
| `clear_at_least` | None | **每次至少清出这么多 token，清不够就不清** |
| `exclude_tools` | None | 白名单，这些工具永不清 |
| `clear_tool_inputs` | `false` | false 只清 result；true 连参数一起清 |

**`clear_at_least` 是个很值得抄的设计**：它是一个显式的"这次重写值不值得破坏缓存"的闸门。官方文档原话是 "**This helps determine if context clearing is worth breaking your prompt cache.**"

`clear_thinking_20251015` 只有一个 `keep`，而且**默认按模型不同**——Opus 4.5+ / Sonnet 4.6+ / Fable / Mythos 是"保留全部轮次"，更早的和所有 Haiku 只保留最近一轮。

thinking block 的缓存关系和 tool result 相反：**thinking 保留时缓存保持命中；一旦清理，就在清理点失效。**

#### OpenAI：`context_management:[{type:"compaction"}]`

```python
client.responses.create(
    model="gpt-5.3-codex",
    input=conversation,
    store=False,
    context_management=[{"type": "compaction", "compact_threshold": 200000}],
)
```

- 谁做摘要：**服务端**。渲染 token 数越过 `compact_threshold` 就触发
- `compact_threshold` 的**默认值官方没有文档化**，所有示例都显式传 200,000
- **返回的是 opaque、加密、不可人读的东西**。官方原话 "It is opaque and not intended to be human-interpretable"
- 另有独立端点 `POST /responses/compact`：**没有 threshold 参数**，你给什么就压什么。返回 `output` = 你之前的 user 消息 + 一个 `compaction` item。官方忠告：**不要裁它的输出**，返回的就是"规范的下一窗"
- **你对摘要写什么、保留什么，完全没有发言权**——`ResponseCompactParams` 里没有摘要指令这个字段

OpenAI 的缓存文档里有一节标题直接就叫 **"Compaction can reduce cache reuse"**，settings 表把 `context_management` 归为"会从第一个变化的 token 起阻止复用"。

#### 什么时候用 provider 原生，什么时候自己压

| 维度 | Anthropic compact | OpenAI compact | 自己压 |
|---|---|---|---|
| 前缀构造权 | 服务端重写 | 服务端重写 | 你自己 |
| 摘要透明度 | **明文可读** | **加密不可读** | 你自己定义 |
| 可控性 | trigger / pause / instructions | 仅 `compact_threshold` | 全可控 |
| 可移植性 | 绑 Anthropic | 绑 OpenAI | 任何供应商 |

实用判据：**如果"摘要归谁所有"这件事你无所谓**（任务导向、历史可丢、单供应商），用 provider 原生；**如果摘要要能被人看、被人改、被人搬走**，自己压。

**一个必须点名的引用陷阱**：Anthropic 官方公布的那三个效果数字——**memory tool + context editing 提升 39%、context editing 单独提升 29%、100 轮搜索任务 token 降 84%**——属于 **context editing（clear 家族）**，**不是 server-side compaction**。把 84% 安到压缩头上是错的，网上这么干的文章不少。

### 6.8 GitHub Copilot：公开信息稀薄，但有个共同点很说明问题

Copilot 分三面，公开程度差别很大：

| 面 | 公开程度 |
|---|---|
| VS Code agent mode | **源码级** |
| Copilot CLI | 只有官方文档（`github/copilot-cli` 仓库**不发源码**——探测 `src/main.ts` / `package.json` / `CHANGELOG.md` 全部 404，只有 `README.md` 和 `install.sh`） |
| GitHub cloud / coding agent | 只有文档，**且没有任何 compaction 机制的描述** |

**VS Code 那面的源码现在在 `microsoft/vscode`**——独立的 `vscode-copilot-chat` 仓库已归档并入主仓（README 明说）。压缩逻辑在一个 1209 行的文件里（`extensions/copilot/src/extension/prompts/node/agent/summarizedConversationHistory.tsx`）。

几个值得一提的设计：

**① 阈值解析器会主动报错，防止你把百分比当 token 数。**

设置项 `summarizeAgentConversationHistoryThreshold`，官方 description 原文：

> Specify either a ratio of the model's context window (a value greater than `0` and at most `1`, e.g. `0.8` to compact at 80%) or an absolute token count (a value of `100` or greater, e.g. `60000`). **Leave unset to use the model's full context window.**

解析规则：`≤1` 当比例（`0.8` = 80%）、`≥100` 当绝对 token 数、**`(1, 100)` 区间直接抛错**。

那个抛错是有意设计的——防止你把 `80`（想表示 80%）当成 80 个 token 传进去。**这个细节值得抄**：一个"两种语义共用一个参数"的配置，不做这个检查就会静默地按错的方式工作。

**② 摘要分两档，Full 失败降级 Simple。** `enum SummaryMode { Simple, Full }`。Full 模式会把工具 schema 一起发过去（用 `tool_choice: 'none'` 禁止调用）并为其预留预算；Full 失败或强制 simple 时**降级**。

**③ 摘要有 7 个字段**：Chronological Review / Intent Mapping / Technical Inventory / Code Archaeology / Progress Assessment / Context Validation / Recent Commands Analysis。输出包在 `<analysis>` + `<summary>` 标签里，`unwrapSummary()` 会剥掉标签——**模型不吐标签时回退用原始文本**（和 Goose 的无损兜底是同一个思路）。

**④ 大工具输出外置**：有个设置项是"超过多少字节就把工具结果写到磁盘"，另有 `maxToolResultLength` 截断和 `maxSummaryTokens` 硬顶摘要长度。

**⑤ 有 PreCompact hook**，可以在压缩前归档 transcript。

**⑥ 把 compaction 委托给 provider**：走 OpenAI Responses API 时，如果 `isResponsesCompactionContextManagementEnabled` 为真，就用服务端的 compaction/截断，**同时禁用自家的 summarization**。

**最有意思的是第 ⑥ 点和 Goose 的重合**：两家的源码里都有同一句短路——「如果 provider 自己管上下文，就不做本地压缩」：

```rust
// Goose: crates/goose/src/context_mgmt/mod.rs:232
if provider.manages_own_context() { return Ok(false); }
```

**两家都在做同一件事，这是 6.7 节那个趋势的又一个证据——上下文管理正在往 provider 侧移动。**

### 6.9 Roo Code 那份 9 段 prompt 是从哪来的——判定结果

**先给结论：Roo Code 的 9 段 CONDENSE prompt 是照抄 Claude Code 的压缩摘要 prompt。Roo 的祖先 Cline 不是来源。源就在 Claude Code 自己。**

#### 怎么判定的

**证据一：时间线。**

| 时间 | 事件 |
|---|---|
| 2025-04-08 | Claude Code 压缩 prompt（**7 段**版，已含 `<analysis>` 和 `## Compact Instructions`）首次公开在 GitHub |
| 2025-05-14 | Roo 加 LLM condense（**6 段** v1，PR #3582） |
| 2025-08-13 | Cline 加 `summarize_task`（PR #5520） |
| 2025-11-19 | Claude Code **9 段**版公开（对应 cc 2.0.14） |
| **2026-01-23** | **Roo PR #10873 逐字抄入 9 段** |
| 2026-03-31 | Claude Code v2.1.88 source map 泄漏 |

Claude Code 先，Roo 后，**差约 2 个月**。

**证据二（这条是决定性的）：一个重复编号的笔误在两家同步出现。**

Roo PR #10873 的机器人评审评论第一条待办原文：

> Fix CONDENSE support prompt section numbering: previously **"6." appeared twice** (All user messages vs Pending Tasks).

也就是说 Roo 那版草案里，"All user messages" 和 "Pending Tasks" **都编号成 6**。而 Claude Code 公开发布的对应文本**也**是同样的 `6. All user messages / 6. Pending Tasks` 重复编号 bug。

**一个罕见的编号笔误在两家逐字同步出现，基本排除独立撰写。** Roo 后来在评审里把它改成了 1–9。

**证据三：过程是透明的。** Roo 的 PR #10873 正文只写「Restructured CONDENSE Prompt — Completely rewritten with a highly structured XML format」，**全程没提任何出处**。Roo 在 9 段前面自己加了 4 行前言（说明这是 SYSTEM OPERATION、不要把这段当用户消息），后面正文逐字是 Claude Code 的。

#### 这里要修正一个流传的推理

网上（包括这份文档的第一版）有个推理是：「Roo 是 9 段，它的祖先 Cline 是 5 段，所以 Roo 是从别处换进来的」。

**这个推理是错的。**

Cline 有**两代**压缩 prompt，两代都抄了 Claude Code：

- **经典扩展版**（`src/core/prompts/contextManagement.ts`，2025-08-13 起）：同样是 Claude Code 那套的改编——开头把 Claude 的 `<analysis>` 换成了 `<thinking>`，**1-5 段逐字一致**（含 "include a summary of why this file read or edit is important" 这句），然后加了一个 Claude 没有的 "6. Task Evolution"
- **新 SDK 版**（`sdk/.../compaction-shared.ts`）：才是那个 5 段 Goal / State / Highlights / Next / Files

所以准确说法是：**Cline 和 Roo 都抄过 Claude Code 的压缩 prompt，只是各改各的**——Roo 是"几乎不改照抄"，Cline 是"改成 `<thinking>` 再加一段"。

这个修正过的版本反而**更好地支持了原结论**：源在 Claude Code，而且在这两家之外没有更早的共同出处。

#### 它顺带完成了一件事：独立验证

Roo 的引入 commit 是 **2026-01-23**，比那个著名的 v2.1.88 source map 泄漏（2026-03-31）**早约两个月**，而且它带的是**改编号之前的原文**（含那个重复的 6）。

> 措辞上说准一点：Roo 的文本本身也是从公开镜像抄来的，所以谈不上"独立复现"。它"独立"的对象是**那次 source-map 泄漏**，不是 Claude Code。准确说法是：**泄漏之前的又一份公开副本/见证**。

再加上 7 段前身**在 2025-04-08 就已经公开**——也就是说这段 prompt 文本本身在所谓"泄漏"之前近一年就在公开流通了。

**所以那段 prompt 的真实性有两个独立见证，不是镜像仓库一个人说了算。**（泄漏泄漏的是完整 TS 源码，不是这段 prompt 的新鲜度。）

**一个要注意的边界**：这些文本都还在演进。Roo 后续有 v2.1 的 smart code folding；Claude Code 侧也在加子句——那句 "Note any security-relevant instructions…" **首见于 2.1.139（2026-05-12）左右**（2.1.63 和 2.1.84 都还没有）。所以"逐字同构"是对**特定版本快照**成立，不是永久属性。

另外要说准 Roo 到底加了什么：**Claude 的 9 段正文 + Roo 的 4 行前言 + 1 句 command 说明**（那句 `Note: Any <command> blocks from the original task will be automatically appended…` 是 **Roo 自己插的**，Claude 的五个版本快照里都没有）。

**最后说一句这个现象本身**：一家开源项目把自己最大的竞品的摘要 prompt **原样抄走、连编号 bug 都不改**，而这个 prompt 后来成了 Gemini 之外好几家的参考模板。这说明**"摘要该写哪些字段"这件事，行业里已经收敛了**——不是巧合，是这件事的最优解空间确实很窄。

---

## 第 7 章 为什么"摘要压缩"成了默认选择

现在回到你最初的问题：**为什么不选别的？**

### 7.1 它同时满足三件事

| 要满足 | 硬切窗口 | 外部记忆 | 子代理 | 超长窗口 | **摘要压缩** |
|---|---|---|---|---|---|
| 保住对话流 | ✗ 不保 | ✗ 不保 | ✗ 不保在主桌 | ✓ | **✓** |
| 不要求模型额外守纪律 | ✓ | ✗ 要主动写 | ✓ | ✓ | **✓** |
| 在长上下文里真的更准 | ✓ | ✓ | ✓ | **✗** | **✓** |

第三条是很多人忽略的。**"更短的上下文，模型表现更好"是有实测支持的**——Chroma 的 Context Rot 报告、Liu 等人的 Lost in the Middle（TACL 2023）都指向这个：

> 相关信息出现在输入**开头或结尾**时表现最好，**必须从中间取用时显著退化**——即使是对专门的长上下文模型。

Chroma 的 README 原话更狠：**"Model performance varies significantly as input length changes, even on simple tasks."**

所以压缩的正当性来源不是"省钱"，而是：**同等信息量下，桌子上的纸越少，模型越准。** 问题不在"压不压"，在"压得对不对"。

### 7.2 但"摘要"这件事本身有实测上的争议

这一条必须讲，因为它推翻了一个很自然的假设。

**《The Complexity Trap》**（arXiv 2508.21433，Lindenbauer 等，DL4C @ NeurIPS'25）在 SWE-bench Verified 上、5 个模型配置下，直接对比了"**掩蔽旧观察**" vs "**LLM 摘要**"。摘要原话：

> a simple environment observation masking strategy **halves cost relative to the raw agent while matching, and sometimes slightly exceeding, the solve rate of LLM summarization**.

翻译成人话：**简单地"把旧工具输出涂黑"就能把成本砍一半，解出率跟花大钱做 LLM 摘要是打平、有时还略高。** 论文还说这"raise concerns regarding the trend towards pure LLM summarization"。

**《Context as a Tool》**（arXiv 2512.22087）也说 append-only 和被动触发的压缩启发式"often lead to context explosion, semantic drift, and degraded reasoning"。他们把压缩升级成 agent 可以**主动调用**的工具，在 SWE-bench Verified 上到 57.6%，显著优于 ReAct baseline。

### 7.3 所以真正的答案

> **压缩之所以是默认，不是因为它最优，而是因为它是在"模型注意力随上下文衰减"这个约束下，唯一能同时保住对话流、又不要求模型额外自律的折中。**

而工程界现在的共识，其实是最前面那节 Hermes 讲的事情：

> **把大量工作前移到"不花钱的裁剪 + 外部记忆"这两层，只在真的必要的时候才动用 LLM 摘要。**

Anthropic 自己在那篇 context engineering 文里给了这个原则的一句话版本：

> **"the art of compaction lies in the selection of what to keep versus what to discard"**

还有一条他们的自我警告，值得贴在墙上：

> **"overly aggressive compaction can result in the loss of subtle but critical context whose importance only becomes apparent later"**

### 7.4 压缩的四样代价（都得付）

1. **一次额外的大请求。** 输入就是被压缩的全部历史——**最贵的那次，发生在最满的时候**。
2. **有损且不可逆。** 官方文档自己承认：「Compaction replaces older messages with a summary, so specific instructions from early in the conversation may not be preserved.」
3. **打断 prompt cache。** 这一条在 Claude Code 官方文档里是明说的"by design"。
4. **可能让模型变笨。** 见 7.2。

### 7.5 关于缓存这件事，有个反直觉的结论

很多人以为"不压缩、一路缓存下去更省"。

**成本侧：不是。** 缓存读只是**打折**（0.1 倍），不是免费。Claude Code 官方文档说得很直白：

> Claude Code sends your full conversation with every request... so **a one-line question in a session that has been open all day still draws usage for the whole conversation.**

每一轮的输入成本 ∝ 上下文大小 × 0.1。上下文一直涨，每轮付的钱也一直涨。

而且 TTL 是硬约束：Anthropic 5 分钟（Claude Code 订阅给 1 小时）、OpenAI 默认 30 分钟。闲置超过 TTL，下一次就是冷读。

**所以这不是"压缩 vs 缓存"的二选一**，而是：**把缓存重建的费用在什么时候付、付多少次。**

有个用户自测的成本模型（非官方，只能当量级参考）：

| 场景 | 恢复成本 |
|---|---|
| 放着不管 | `2C` |
| 缓存热时压缩 | `0.1C + 2C'` |
| 缓存冷时压缩 | `2C + 2C'` |

（他回测了 168 个会话、45 天、142 次真实压缩，得出 `C'/C = 0.203`。）

**结论**：缓存热的时候压缩便宜得多。Claude Code 官方给的实操建议就是这句话——**把 `/compact` 放在任务之间的空隙里**。

还有两条更省的替代：`/clear`（零成本，但放弃连续性）和 `/rewind`（截断回已经缓存的前缀，官方原话是 "rather than building a new one as compaction does"）。

---

## 第 8 章 六个真实翻车场面

这一章全部有 issue 编号，可以直接去核。

### 8.1 把"没验证的猜测"洗成"确定的事实"

Claude Code issue #67500 的评论里记录了一个很隐蔽的问题：原本带着 `[fee assumption — unverified]` 这种标记的猜测，**压缩后被改写成语气确定的事实陈述，provenance 直接消失**。

评论者的分析很到位：压缩后的摘要「描述了这些规则**曾经是什么**，但它不带着同样的指令权威……模型现在是在**推理一条它'记得'的规则**，而不是一条它**正在看着的**规则」。

这比"丢信息"更糟——**丢的是一个"我不确定"的标记**。

### 8.2 压缩本身把上下文弄大了

Claude Code #85483（一份很硬的量化）：有人扫了 **47,019 个 transcript、5,180 个压缩边界**，发现：

- **133 次子代理压缩，每次反而增加 >50k token**，最坏 +167,519（30,029 → 197,548）
- **12.9% 的 workflow-subagent 压缩是净负增长**，而主会话只有 0.1%

判定条件写得很清楚："Starting above the floor was sufficient for a safe compaction — zero counter-examples in 1,188 cases."

### 8.3 压缩太频繁，等于每隔几分钟付一次全量重放

**先修正一条**：第一轮调研里出现过「Claude Code 601 次压缩跨 8 个项目、平均间隔 7.0 分钟」这组数字。我专门去核，**在 GitHub 和全网都查不到**（`601 compactions` 搜索 0 命中）。**这条不能用。**

下面是可以核的：

| 数据 | 来源 | 口径 |
|---|---|---|
| 正常约 **2 次/天**（4 个会话合计） | Claude Code #49593 | 用户自测 |
| 某回归版本涨到 **约 2 次/小时/会话**（20-30 倍） | 同上 | 用户自测，有人独立复现 |
| 后台预计算压缩 **3 小时内 3 次** | Claude Code #70459 | 用户自测日志 |
| 单次压缩耗时 **75 秒 ~ 103 秒** | 同上（真实日志） | 用户自测 |
| 压缩后降到原上下文的 **约 20%**（n=142 次真实压缩，中位 `post/pre = 0.203`） | Claude Code #70459 评论 | 用户自测 |
| 单次实测 **54,358 → 2,522 token** | compact-canon 项目实测 | 用户自测 |
| OpenCode 单会话一夜压缩 **56 次**（病态） | OpenCode #48065 | 用户自测，**故障态** |
| OpenCode 无头会话**每约 10 分钟压一次** | 同仓库另一 issue 标题 | 故障态 |
| Hermes 开 micro-compaction 时 **3.5 小时 0 次批量压缩** | 维护者合并的 PR #75345 | 贡献者自测（本地小模型） |

一条很硬的：Claude Code #90509 —— 上下文无声掉了 157,023 token，随后 17 分钟内 **21 次全量重写、19 次连续 cache miss**，约 **2500 万 token 被烧掉**。

**频率为什么会两极分化**（都有量化证据）：

往"很频繁"走：

- **上下文被系统侧撑大。** Claude Code 某个版本把启动占用从 8% 提到 **22%**（约 16k → 45k token 的硬地板），结果每会话约 30 分钟就压一次。用户附了 `/context` 分解：系统提示 6.1k + 系统工具 7.9k = 14k 的硬地板。
- **每轮注入过重。** 同帖实测：`effortLevel: high` 让一次平凡交互多约 8k token，`normal` 也约 6.4k/turn。这直接把"每分钟新增 token"抬高，阈值自然更快被跨过。
- **尾预算塌陷 + 重算**（OpenCode #48065）：大上下文下 tail 预算算出 `total=0 → head=全部`，压缩比只有 56-60%，约 4k token/90 秒回填，**约 10 分钟再跨阈值**——一个慢速自激循环。

往"几乎不触发"走：

- **窗口够大**（1M / 900k 变体）
- **主动摊销**：Hermes 开 micro-compaction，占用从 8.7% 一路走到 22.0% 后**走平**，全程 0 次批量压缩
- **设计上就不做**：Amp 只有手动 handoff
- **用户手动关掉**：`DISABLE_AUTO_COMPACT=1` 之类的开关，社区里"关掉 auto-compact、改用 handoff.md"是常见做法

### 8.3.1 一个关于 Codex 的重要澄清

那份流传很广的"Codex 压缩质量分档"（0-2 次 excellent / 3-5 good / 6-8 acceptable / 9+ degraded）出自 issue #14347，但**报告者自己说那是"生产经验，不是正式基准"**，样本是"日常 10-16 小时会话"。

更重要的是同一 issue 里 **OpenAI 维护者（etraut-openai）的澄清**：

> **OpenAI / Azure 托管模型的压缩发生在服务端**，仓库里那个 `templates/compact/prompt.md` **只用于自定义模型提供方**。

也就是说：如果你用的是官方托管的 Codex 模型，你看到的摘要行为**可能不是**仓库里那个 4 段 prompt 产生的。这条对"照着开源代码推测产品行为"是个提醒。

### 8.4 配对切断，然后整个会话变砖

这是最硬的一类故障——**API 直接 400，而且会一直 400**。

- Claude Code #6836 是个伞形汇总：**150+ 报告**，正文列了 63 个 open + 92 个 closed 关联 issue。
- #84272 有一份逐版本量化："orphaned tool_use" 比例从 2.1.220 的 **0.089%** 跳到 2.1.222 的 **1.406%**，约 **15.8 倍**。
- #88421（v2.1.238）：`Each advisor_tool_result block must have a corresponding server_tool_use block before it`，而且 **"Repeats on every request once it starts"**——会话直接报废。
- #90771 有个很关键的观察：**磁盘上的 transcript 本身配对是一致的（119/119，无 dangling）**，孤儿**只存在于客户端装配的请求里**。也就是说，压缩/重装配那一步丢了一半。

对照一下别家的做法就知道这不该发生：

- **LangChain** 的 `SummarizationMiddleware._find_safe_cutoff_point` 专门保证不切开 AI/Tool 对，注释原文 "Advance past any ToolMessages to avoid splitting AI/Tool pairs"
- **Roo Code** 双向都管（补合成 + 过滤孤儿）
- **Cline** 靠 `isSafeCutBoundary` 从切点源头避免
- **Gemini** 只允许 `role=user` 且不含 `functionResponse` 的位置当切点

### 8.5 小会话被过度压缩

- Claude Code #89738："Compaction fired at **53%** for no apparent reason."
- #23751：**48%** 时就报 context full。
- #43509：约 **5%** 用量时可见历史被静默截断。
- Cline #9748：LiteLLM 下 1M 窗口被内部当成 200K，**约 100K 就开始摘要**。

部分根因是"压缩走的是更小的模型"或"token 计数用错了字段"。

### 8.6 摘要边界没写，UI 显示成功但实际没压

Claude Code #89040（v2.1.237/2.1.241）：summary 生成了，但**边界记录 `compact_boundary` 从没被写入**。用户看到的是"命令成功"，上下文却没缩。那个会话一生有 **103 条 `/compact`、只有 28 条 `compact_boundary`**；最近 47 次手动尝试里 **46 次成了孤儿**。

---

## 第 9 章 对你做 harness 有什么可抄的

把前面所有东西压成可执行的几条。（这一节的判断是我的，不是任何一家的官方说法。）

### 9.1 分层：把不花钱的排在前面

这是最值钱的一条，四家在做、但 Hermes 做得最清楚：

```
第 0 层  结构性排除    —— 根本不让它进上下文（检索、文件外置、工具输出截断）
第 1 层  确定性清理    —— 去重、旧工具输出降级成一行、大参数截断   零 LLM 调用
第 2 层  轻量裁剪      —— 擦旧工具输出，带保护窗口和最小回收门槛    零 LLM 调用
第 3 层  摘要          —— 只在真的超阈值时做，一次，结构化          一次 LLM 调用
```

**回到你的 V4 系统**，你现在的位置是：`compactAiMessageForMemory`（约 2400 字符阈值）在第 1 层，`trySummarizeOldestRoundsIfNeeded`（>3 轮合并最早 2 轮）在第 3 层，`cm:ref` 大文件归档是个第 0 层的雏形。

**缺的是第 2 层**——也就是 OpenCode 那种"擦旧工具输出"的轻量层。你的缺口清单 G2（`cm:ref` 读回未接线）本质上是第 0 层没闭合：存了但取不回来，等于没存。

### 9.2 阈值：相对比例 + 一个绝对上限，别只用一个

这是第一轮调研里最一致的一条教训：**单纯用绝对 token 阈值会出事。**

- OpenCode #45168：绝对阈值 + 固定 buffer，换个模型就够不到，自动压缩永不触发
- Hermes #14690：绝对阈值在小窗口上等于整个窗口，压缩永不触发
- Codex 的 `body_after_prefix` scope 就是为了修"保留的大前缀吃掉整个预算"

Hermes 的解法可以照抄：**比例为主（0.50 / 小窗口 0.75），可选一个绝对 cap，取两者较小者。**

而且**小窗口必须抬高下限**——因为"压不掉的部分"（system prompt + 工具 schema，20-30K）在小窗口上占比太大。这条对你自己也成立：你那个 `@SystemMessage` 加上 tools 定义有多大？如果它在 32K 窗口上的占比超过 50%，任何按比例算的阈值都会失效。

### 9.3 摘要：必须结构化，且要显式保护用户约束

- **字段固定、不能自由发挥**（四家都这么做）
- **必须有"用户的约束/偏好"这一段**（Hermes 是 `Constraints & Preferences`，Codex 是 `Important context, constraints, or user preferences`）。Claude Code #80938 丢的正是 working-mode 指令，代价是"约 10 小时高级工程师时间"
- **必须要求引用用户纠正的原话**（Hermes 的 `Errors & Fixes` 明确要求）
- **必须写"不要提摘要/上下文压缩"**（OpenCode 模板里有这条禁令）

**还有一条最容易被忽略的**：Claude Code #89797 那个 354/354 的实测说明——**用户写在 CLAUDE.md 里的压缩指令，默认不会进压缩器提示词**。如果你要支持"用户自定义压缩指令"，得**显式接进摘要 prompt**，不能指望它自动生效。

### 9.4 摘要模型不能随便选小的

Hermes 那条教训要看两遍：

> **摘要模型的上下文必须 ≥ 主模型窗口**，否则摘要调用会吃 context-length 错误、返回 None，**中间那段被无摘要丢弃**。文档称这是压缩质量下降最常见的原因。

这条直接推翻"摘要随便找个小模型干"这个很自然的省钱想法。

### 9.5 一定要有熔断，而且熔断要能恢复

四家的做法：

| 家 | 熔断 |
|---|---|
| Claude Code | 连续失败 3 次 → 本会话跳过自动压缩 |
| Hermes | 摘要失败冷却阶梯 (60,300,900) 秒；anti-thrash 连续 2 次无效 → 跳闸，300 秒后放一次探针 |
| Codex | 靠"压缩后应该远低于阈值"的假设，**没有硬计数器** |
| OpenCode | **没有**（#27924 至今 open） |
| Gemini | 六态状态机，inflate 后降级为只截断 |

Hermes 的文档还点出一个容易被忽略的坑：**熔断之后如果没有恢复路径，这个会话会永远不再自动压缩，一路撞到供应商硬上限**（#14694）。所以熔断必须有**探针式的恢复**，不能一刀切死。

**另外一个必须抄的**：`no-op 检测`。Hermes 的有效性判定不看"消息条数变少了"，而是**看供应商返回的真实 prompt token 数有没有降下来**。因为消息条数变少但 token 没降的情况太常见了（摘要本身可能很大）。

### 9.6 切点：一定要对齐，而且要考虑"压缩器自己"的边界

- **不切开 tool 对**（四家都在防，LangChain 有专门的 `_find_safe_cutoff_point`）
- **最新一条用户消息永远保留**（Hermes 强制拉进 tail；OpenCode 有 `min_tail_user_messages`；Codex 保留 user 消息；Cline 永不摘最新 typed prompt）
- **要考虑"压缩时结果还没回来"的情况**：Hermes 有一个特判——如果最后一个非 tool 消息是 assistant，就假定它的 tool_calls 还在 pending，**整条逐字保留**

另外，Claude Code 那个 `shouldAutoCompact` 的递归防护值得抄：`querySource` 是 `'session_memory'` 或 `'compact'` 时直接返回 false——**避免 fork 出来的摘要 agent 再触发压缩而死锁**。注意：这是"防护针对的场景"，**不是"实际观察到的递归事故"**（调研专门找过纯正递归案例，没找到）。

### 9.7 缓存：要么把摘要放在稳定前缀，要么别打那个断点

这次调研里最有实操价值的一条。

- **Anthropic 官方给的做法**：给 compaction block 打 `cache_control`，同时**在 system prompt 末尾单独打一个断点**。这样压缩发生时，system prompt 那一段缓存**继续命中**，只有摘要需要作为新的缓存条目写入。
- **Claude Code 的做法**：把易变内容全部追加到对话尾部（plan mode 和 skill 加载都是**作为对话消息追加**的），压缩时只接受 conversation 层失效。
- **OpenCode 的反面教材**：它把 `[compaction-user, summary, ...tail, continue-user]` **重排到最前面**——这本来是个好设计（摘要成了稳定头部），但它**没有为这个位置单独打断点**，加上压缩请求用了 `tools: []`，前缀第一个字节就分叉，结果每次压缩都是全额 cache write、0 命中。
- **Hermes 的细节**：`prompt_cache_boundary.py` 那个"由构造器登记易变尾巴起点"的做法，是解决"大段静态 + 小段易变拼在一条消息里"的通用方案。

**判断标准**：Anthropic 文档里有一句直接的警告——**"Common mistake: Breakpoint on content that changes every request"**。压缩后的对话恰好就是"每次都变"的内容。**如果你压缩完还在同一个位置打断点，那你每次都在付全额写缓存的钱，一次读都拿不到。**

### 9.8 最后：给用户一个"手动挡"

Amp 之所以敢删掉自动压缩，是因为它把控制权交给了人（Handoff 由用户审阅）。Claude Code 的官方建议也是"把 `/compact` 放在任务之间的空隙"。

对你来说：**自动压缩是保底，手动压缩是省钱的正道。** 而且 `/clear` 和截断回已缓存前缀这两条路，在成本上比压缩更优——它们只是放弃了连续性。给人一个选择，比默默自动压更划算。

### 9.9 想清楚"摘要归谁所有"

这是最后一个架构决策，也是 2026 年最新长出来的一个。

**趋势很明确：上下文管理正在往 provider 侧移动。** 证据：

- **Goose 和 Copilot 的源码里都有同一句短路**——「如果 provider 自己管上下文，就不做本地压缩」（Goose 是 `provider.manages_own_context()`，Copilot VS Code 是 `responsesCompactionContextManagementEnabled`）
- Anthropic 官方在 SDK 侧压缩 vs 服务端压缩的对照里直接写 "**server-side compaction is generally preferred**"
- Codex 那边，维护者澄清 OpenAI / Azure 托管模型的压缩**发生在服务端**，仓库里那份开源 prompt **只用于自定义模型提供方**

**判断标准（我自己的归纳，不是任何一家的官方说法）：**

用 provider 原生，如果：任务导向、历史可丢、单供应商、你不想维护压缩逻辑。

自己压，如果：

- 需要**精确保留早期细节**，或维护跨变量的精确状态
- 需要对摘要**审计 / 编辑 / 换模型生成**（OpenAI 侧给的是加密串，做不到；Anthropic 侧只有 `instructions`，且模型不能换）
- 需要**跨供应商迁移**
- 需要做**分层记忆**（把不可丢的写窗外，可丢的才压）
- 需要**"摘要 + 保留最近 N 轮原文"的混合**——Anthropic 能靠 `pause_after_compaction` 在服务端做，OpenAI 没有等价物

**落到你的场景**：你做的是生成端侧制品——制品要落盘、可版本化、可发布。所以"摘要归谁所有"对你**本来就不是可选项**：你的会话状态里还有文件快照、变更清单这些**根本不在对话里**的东西，必须自己掌控。

**所以对你的答案是自己压，但把 provider 那套当参照**——特别是 Anthropic 的 `pause_after_compaction`（服务端可做的混合策略）和 `clear_at_least`（值不值得破坏缓存的显式闸门），这两个是官方给出的答案。

---

## 附录 A 数字对照表

### A.1 触发阈值

| | Claude Code | OpenCode (v1) | Codex (默认) | Hermes |
|---|---|---|---|---|
| 类型 | 绝对（由窗口推导） | 绝对 | 双档（90% / 95%） | **相对比例** |
| 预留输出 | `min(输出上限, 20,000)` | `min(20,000, 输出上限)` 或 `输出上限(≤32,000)` | 5%（`effective_context_window_percent`） | 可选（默认不预留） |
| 缓冲 | 13,000 | 20,000（`COMPACTION_BUFFER`） | 16,384（仅 fallback 时） | 无（用比例代替） |
| 200K 触发点 | 167,000（源码）/ 文档存疑 | 约 168,000 | 180,000（软） | **150,000** |
| 1M 触发点 | 967,000（文档吻合） | 约 968,000 | 900,000（软） | **500,000** |

### A.2 保留与摘要

| | Claude Code | OpenCode | Codex | Hermes |
|---|---|---|---|---|
| 逐字 tail | **无**（完整压缩路径） | 有，`min(15,000, max(2,000, 可用×0.25))` | 无 tail，保留 user 消息 ≤20,000 | 有，`max(10,000, min(25,000, 窗口×0.025))` |
| 头部保护 | 从磁盘重注 CLAUDE.md / rules | 无（system 每次重建） | 重新渲染初始上下文 | system + 前 3 条，**第一次压缩后衰减为 0** |
| 摘要段数 | 9 段 + `<analysis>` 草稿 | 5 段 | **4 段** | 12 段 |
| 摘要上限 | `min(20,000, 模型上限)` | 4,096（v2） | 无硬上限 | `max(2,000, min(内容×20%, min(窗口×5%, 10,000)))` |
| 摘要存放 | 一条 `role=user` 的消息 | 一条 `summary=true` 的 assistant 消息 | 一条 `role=user` 消息 | 一条独立消息（两次压缩走增量更新） |
| 熔断 | 连续失败 3 次 | **无** | 无硬计数器 | 冷却阶梯 + anti-thrash + 探针 |
| 压缩后会话 id | 不变 | 不变 | 不变 | **不变**（`in_place: True`） |

### A.3 关键常量速查

```
Claude Code   AUTOCOMPACT_BUFFER_TOKENS = 13,000        ← 用途官方未说明
              MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20,000    ← 注释：p99.99 = 17,387
              WARNING/ERROR_THRESHOLD_BUFFER = 20,000
              MANUAL_COMPACT_BUFFER = 3,000
              MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3
              CLAUDE_CODE_AUTO_COMPACT_WINDOW 范围 = 100,000 ~ 1,000,000

OpenCode      COMPACTION_BUFFER = 20,000
              OUTPUT_TOKEN_MAX = 32,000
              PRUNE_MINIMUM = 20,000 / PRUNE_PROTECT = 40,000
              TOOL_OUTPUT_MAX_CHARS = 2,000
              MIN/MAX_PRESERVE_RECENT_TOKENS = 2,000 / 15,000
              v2: DEFAULT_KEEP_TOKENS = 8,000 / SUMMARY_OUTPUT_TOKENS = 4,096

Codex         effective_context_window_percent = 95
              软阈值 = min(配置, 窗口 × 9/10)
              COMPACT_USER_MESSAGE_MAX_TOKENS = 20,000
              RETAINED_MESSAGE_TOKEN_BUDGET = 64,000（远端 v2）
              MAX_RETAINED_AGENT_MESSAGE_TOKENS = 10,000
              reminder_threshold_tokens = 6,144（TokenBudget）
              MAX_THREAD_HINT_BYTES = 4,000

Hermes        threshold_percent = 0.50（config）/ 小窗口 raise-only 抬到 0.75
              MINIMUM_CONTEXT_LENGTH = 64,000
              _MIN_CTX_TRIGGER_RATIO = 0.85
              _PRUNE_MIN_CHARS = 200
              lean tail = max(10,000, min(25,000, 窗口 × 0.025))
              _SUMMARY_RATIO = 0.20 / _SUMMARY_TOKENS_CEILING = 10,000
              MEMORY.md = 2,200 字符 / USER.md = 1,375 字符
              项目文件上限 = max(20,000, min(窗口 × 4 × 0.06, 500,000))
```

### A.4 补齐几家的常量

```
Goose         DEFAULT_COMPACTION_THRESHOLD = 0.8
              REMOVAL_PERCENTAGES = [0, 10, 20, 50, 100]   ← 从中间向两侧删工具响应
              TOOLCALL_SUMMARIZATION_BATCH_SIZE = 10
              tool cutoff = (3 × 有效上限 / 20,000).clamp(10, 500)
              摘要 = <analysis> 草稿 + 单块 JSON，9 个字段
              熔断 = compaction_attempts >= 2
              （仓库已从 block/goose 迁到 aaif-goose/goose）

Letta         SUMMARIZATION_TRIGGER_MULTIPLIER = 0.9
              DEFAULT_CONTEXT_WINDOW = 128,000 / MIN = 4,096
              partial_evict_summarizer_percentage = 0.30
              message_buffer_limit = 60 / min = 15
              memory_warning_threshold = 0.75
              send_memory_warning_message = False   ← 默认关
              CORE_MEMORY_PERSONA/HUMAN_CHAR_LIMIT = 20,000
              APPROX_TOKEN_SAFETY_MARGIN = 1.3
              （main 分支源码已归档，引自 archive 分支）

Anthropic     compact_20260112: trigger 默认 150,000（最低 50,000）
  API         clear_tool_uses_20250919: trigger 默认 100,000 / keep 3 / clear_at_least
              clear_thinking_20251015: keep 默认按模型分档

Codex        远端 v2 保留：user(限 parse_turn_item) + hook + AgentMessage(≤10,000)
              + client-authored developer(feature 默认关)，共享 64,000 预算
              CompactionImageBudget 默认开（图是否计入那 64k）
              MAX_REMOTE_COMPACTION_V2_STREAM_RETRIES = 2
```

---

## 附录 B 这份调研的可信度分级

### B.1 Claude Code：我做了独立验证

这份报告第一轮只有**单一来源**（一个社区镜像仓库 `chauncygu/collection-claude-code-source-code`），而"核验"是回同一个仓库重读——也就是说如果镜像在反混淆时出错，整份报告会整体继承错误。

所以我自己去 npm 拉了**真实的发行包**（`@anthropic-ai/claude-code`），从 13MB 的 `cli.js` 里反混淆出代码，做了交叉验证。

结果：

| 断言 | 镜像仓库说 | 我从发行包核到的 |
|---|---|---|
| 缓冲常量 | 13,000 | `Z17 = 13000` ✓ |
| 摘要预留 | 20,000 | `V_Y = 20000` ✓ |
| 告警/错误缓冲 | 20,000 | `N_Y = 20000`, `y_Y = 20000` ✓ |
| 手动压缩缓冲 | 3,000 | `G17 = 3000` ✓ |
| 自动压缩窗口下限 | 100,000 | `D17 = 1e5` ✓ |
| 自动压缩窗口上限 | 1,000,000 | `jfK = 1e6` ✓ |
| 9 段摘要 prompt | 9 个 section | 9 个标题**逐字存在**，出现次数 6/6/6/6/6/6/6/4/4（正好对应 BASE / PARTIAL / PARTIAL_UP_TO 三个变体）✓ |
| 微压缩配置 | `enabled:false, gapThresholdMinutes:60, keepRecent:5` | `Bc_={enabled:!1,gapThresholdMinutes:60,keepRecent:5}` **逐字相同** ✓ |
| 会话记忆开关 | 两个开关都默认 false | `u8("tengu_session_memory",!1) && u8("tengu_sm_compact",!1)` ✓ |
| 占位符文本 | `[Old tool result content cleared]` | `EH4="[Old tool result content cleared]"` ✓ |
| PTL 重试标记 | `[earlier conversation truncated for compaction retry]` | 逐字存在 ✓ |
| 自动压缩尾部追加 | "Continue the conversation..." | 逐字存在 ✓ |
| 元数据字段 | `compactMetadata.preservedSegment` / `isCompactSummary` | 存在（11 / 5 / 14 次）✓ |

**结论：镜像仓库的保真度很高，那份报告的核心断言现在有了两个独立来源。**

两个额外发现：

1. **`/autocompact` 这个命令在 2.1.89 里已经存在了**，而且解析规则是"裸数字 100–1000 按千算"（`z>=100&&z<=1000 ? z*1000 : z`），错误提示是 `Expected 100k–1M tokens (e.g. 500k, 200000, or 200 as shorthand)`。官方文档里那条"env 变量只接受纯整数"的限制，跟 slash 命令的宽松解析是两回事。
2. **npm 上不存在 2.1.88 这个版本**——是 2.1.87 之后直接跳到 2.1.89。那个镜像仓库标称的版本号在公开 registry 里找不到对应。

### B.2 我不能担保的部分

诚实列出来：

- **Claude Code 的 200K 触发点（167,000）**：源码算式如此，但官方文档对 200K 模型写的是"at the 200K boundary"。两者有张力。标为**源码值、未经文档交叉验证**。
- **13,000 缓冲的用途**：官方从未说明。任何解释都是推测。
- **OpenCode 实际跑哪套**：v1 是 `opencode` 包的主循环路径，v2 已接进 core runner 但整体替换未完成。二进制默认行为在这个 HEAD 上无法一句话断言。
- **Codex 的 200K / 1M 触发数字**：那是**算术外推**，不是仓库里的实测值。仓库 catalog 里没有 200K 窗口的模型，1M 那档的 `resolved_context_window()` 实际也只到 272,000。真实取值要按模型元数据算。
- **Codex 引入 TokenBudget 的设计动机**：只有两个一手依据（代码里给用户的退化警告、注入给模型的提示词），都是"承认缺陷"而非"说明动机"。issue #36057 是用户提的请求，不能当 OpenAI 的意图。
- **"内存 / 工作集 / 冷存储"那个类比**：不是官方表述，是观察者归纳的。
- **OpenCode 为什么 prune 默认关、tail 只留 2.5%**：config 上只有 `(default: false)`，没有动机说明。第一轮报告里那些"因为 X 所以 Y"是推测。
- **Aider / Cline / Roo 的阈值动机**：同样没有一手出处，属于推测。
- **各家中文技术文章**：这个话题上互相抄得很厉害。第一轮核验里就抓到过：编造的 issue 号（#104192 在仓库里搜不到）、把 Gemini 的状态机搬到 Cline 名下、把 Roo 的 prompt 搬到 Cline 名下、把另一篇博客的实验参数（80k/40k trigger）说成产品常量。**建议只信一手来源。**

第二轮补充的存疑项：

- **「Claude Code 601 次压缩跨 8 个项目、平均间隔 7.0 分钟」查不到**（`601 compactions` 搜索 0 命中，多组 web 搜索无命中）。这份文档的第一版引用过它，已经删掉了。
- **「Hermes micro-compaction 那次 3.5 小时实测窗口 400K、阈值 320K」也无法在一手来源核实。** 一手来源只给了"批量压缩在约 80% 阈值触发"和"占用走平在约 22%"。320/400 恰好等于 80%，**疑似是把百分比反推成了一个具体窗口数字**。别当官方数字引用。
- **「Claude Code 自动压缩在 ~80% 触发」是社区口径**，而且社区里 83.5% / 92% / 95% 三个数互相矛盾，**没有官方数**。我从 2.1.89 发行包算出来的 167,000 / 200,000 = **83.5%**，恰好对上社区那一档——这算是交叉印证，但仍不是官方确认。
- **Codex 的压缩质量分档**（0-2 excellent … 9+ degraded）**是报告者自称的"生产经验，不是正式基准"**。同一 issue 里维护者还澄清了托管模型的压缩在服务端发生。
- **Anthropic 那三个效果数字（39% / 29% / 84%）属于 context editing（clear 家族），不是 compaction。** 把它们安到压缩头上是常见的引用错误。
- **Letta 的源码现在只在 `archive` 分支上**（main 已归档），而且官方把那一套标为 "V1 SDK (legacy)"。引用时要带这个前提。
- **GitHub Copilot 的 cloud / coding agent 没有任何 compaction 机制描述**，属于"公开信息稀薄"，别把 VS Code 的实现推广到全部 Copilot 产品。
- **Goose 的仓库位置**：已从 `block/goose` 迁到 `aaif-goose/goose`（Block 在 2025-12 捐给 Linux Foundation 旗下的 Agentic AI Foundation）。老链接会重定向。

### B.3 明确"没找到证据"的

- **纯正的"压缩器递归调用自身"**：没找到。只有 summary-of-summary 膨胀、递归 collapse 死循环、摘要器自身陷循环这三种，**不要混为一谈**。
- **Anthropic 官方对任一 Claude Code 压缩失败的回应**：没有。检索到的关闭全是 `github-actions[bot]` 以 "inactive for too long" 自动关的。
- **跨项目统一口径的"压缩频率"数据集**：没有。

---

## 附录 C 主要出处

### 源码（一手）

```
Claude Code    （闭源，通过 npm 发行包独立验证）
               @anthropic-ai/claude-code@2.1.89 cli.js（反混淆）
               社区镜像：chauncygu/collection-claude-code-source-code

OpenCode       anomalyco/opencode @95daf90
               packages/opencode/src/session/{compaction,overflow,message-v2,prompt,processor}.ts
               packages/core/src/session/compaction.ts
               specs/v2/session.md          ← 一手设计动机
               AGENTS.md                    ← v1/v2 定位

Codex          openai/codex @654b0a77
               codex-rs/core/src/{compact,compact_token_budget,compact_remote_v2}.rs
               codex-rs/core/src/session/{context_window,token_budget,mod}.rs
               codex-rs/prompts/templates/compact/prompt.md
               codex-rs/models-manager/models.json
               codex-rs/features/src/lib.rs

Hermes         NousResearch/hermes-agent
               agent/{context_engine,context_compressor,conversation_compression}.py
               agent/{prompt_caching,prompt_cache_boundary,prompt_builder}.py
               agent/{memory_manager,memory_provider,model_metadata}.py
               hermes_cli/config_defaults.py
               website/docs/developer-guide/context-compression-and-caching.md
               PR #4033（closed / draft / unmerged）

其他           Aider-AI/aider、cline/cline、RooCodeInc/Roo-Code、
               google-gemini/gemini-cli、continuedev/continue、
               aaif-goose/goose（原 block/goose）、letta-ai/letta（archive 分支）

prompt 源流     RooCodeInc/Roo-Code:src/shared/support-prompt.ts
               commit cf5d42e（PR #10873，2026-01-23）
               RooCodeInc/Roo-Code:commit 7c1185e（PR #3582，2025-05-14）
               Piebald-AI/claude-code-system-prompts
               commit c64a9d3（2025-11-19，cc 2.0.14）
               dontriskit/awesome-ai-system-prompts
               commit 66da747（2025-04-08，7 段前身）
```

### 文档（一手）

- Anthropic, *Effective context engineering for AI agents*
- Anthropic, *Compaction*（`compact_20260112` 参数表、block 语义、cache_control、pause）
- Anthropic, *Context editing*（clear 两策略默认值、缓存关系、SDK 侧 compaction）
- Anthropic, *Managing context on the Claude Developer Platform*（39% / 29% / 84%，2025-09-29）
- Anthropic, *Prompt caching* / *Pricing*
- Claude Code Docs, *Context window* / *Prompt caching* / *Costs* / *Sub-agents*
- Hermes Agent Docs, *Context compression and caching* / *Context engine plugin* / *Memory*
- Letta Docs, *Compaction*（标 V1 SDK legacy）/ *Memory blocks*
- Goose Docs, *Smart context management*
- OpenAI, *Codex config reference* / *Compaction* / *Prompt caching* / *Conversation state*

### 论文（一手）

- Liu et al., **Lost in the Middle**, arXiv:2307.03172, TACL 2023
- Hong / Troynikov / Huber, **Context Rot**, Chroma, 2025-07
- Lindenbauer et al., **The Complexity Trap**, arXiv:2508.21433, DL4C @ NeurIPS'25
- Liu et al., **Context as a Tool**, arXiv:2512.22087, 2025-12
- Packer et al., **MemGPT**, arXiv:2310.08560

### issue（一手，用户报告）

```
Claude Code   #6836 #75759 #85483 #88679 #89281 #89797 #90509 #91351
              #67500 #80938 #89040 #92849 #84272 #65098 #88421 #90771
              #65746 #18641 #63197 #23751 #43509 #43975 #46663 #19111(Codex)
OpenCode      #43249 #48065 #44080 #41571 #27924 #45168 #40235 #42282
Codex         #43912 #36057 #14347 #26656 #37305
Gemini CLI    #19590 #25595 #19456
Hermes        #14690 #14695 #40803 #11996 #11978 #100611 #70328 #14694
```

---

*author By glyahh · 调研时点 2026-09-12 · 所有带 issue 号的结论均可直接核验*
