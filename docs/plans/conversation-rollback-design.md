# 会话回档：代码、用户修改与纯净上下文

> 类型：设计与实施方案；目标架构、阶段状态不等于当前交付结论。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

日期：2026-10-03。源码基线：`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`。
状态：产品规则已确认，工程实现建议待审阅及运行验证；未编码、未创建快照存储、未执行合并或模型请求。

关联：[开发衔接](project-development-workflow-design.md)、[回档验收矩阵](../tests/conversation-rollback-test-matrix.md)、[共同契约](../contracts/project-task-version-contract.md)。

## 1. 确认规则及两个不同的选择

点击用户消息旁“回档”，代码和 AI 当前上下文一起退到该消息执行前：该节点及之后的消息、摘要、工具结果、项目状态记忆不得进入新请求；原提示词回填输入框，不自动发送。旧分支/保护快照仅供用户只读查看，不供当前 Agent 检索。

所有用户对话消息都是设计目标，不只限写代码的消息；无需代码变化也可退对话。队列、测试、分析消息需要真实执行前锚点，不能临时猜一份旧目录冒充快照。上线前已无历史快照的旧消息如实不可恢复；不能通过强制模式制造不存在的快照。

正常回档提供两种方式：

| 方式 | 代码处理 | 上下文 |
| --- | --- | --- |
| 保留我的修改 | 撤销错误路径 AI 修改；保护 user_manual 和明确 user_approved，恢复旧快照后按来源链重新叠加，冲突由用户解决 | 严格裁剪该节点及之后，基于最终合法代码重新建立事实 |
| 严格恢复 | 用户明确同意恢复目标代码快照，不叠加目标之后的手动/认可修改 | 同样严格裁剪，绝不保留错误路径记忆 |

**异常强制回档**不同于正常“严格恢复”：仅在合并/语义等异常导致常规流程失败时提供备用入口，所有具有合法目标的用户消息都适用。用户需确认丢弃哪些受保护修改，再按严格目标重试。强制不绕过停止确认、保护备份、目标完整性、归属/权限、上下文裁剪或必需验证；这些失败时仍不能成功。

不修改用户 Git 历史、refs、index 或远端。不恢复项目数据库、平台数据库、用户外部环境或整个容器时间点；发现代码与现存数据库不兼容必须报告，不能宣称恢复成功。

后续保留政策选择 A：默认保留全部已建立的回档点，直到用户主动清理；允许压缩与去重，但不按时间或数量静默淘汰。容量不足明确提示处理，不删除早期快照冒充仍可回档，不承诺无限免费存储。此政策针对有效回档点，不补造上线前缺失快照。

用户同时要求本功能整体参考 Claude Code，并在差异核对后确认 A：复用整体交互与检查点机制思路，保留本项目已确认的增强要求，不照搬其保留与覆盖限制，见 3.1。

## 2. 当前源码事实与关键差距

- `AgentRunRequestFactory.threadIdOf` 固定为 `app:{appId}`；Python streaming config 以 thread_id 调图。回档后继续用原 thread 可能重新取旧图状态，需新分支/epoch 的内部 thread。
- `core/persistence.py` 同时提供 checkpointer/store 且有内存回退；换 thread 不等于跨线程 store 已隔离，持久化错误不能静默降级后假称回档可恢复。
- `core/compaction.py` 会把历史段落变成摘要；压缩结果不能按消息长度直接截断来保证纯净，要记录来源范围并重建。
- `retrieval/git_epoch.py` 读取 Git 状态，不是可恢复内容备份。`WorkspaceVersioner` 的 mtime 指纹也不是内容快照。
- `KnowledgeSnapshotService` 的文件上限/排除规则服务知识，不保证完整运行或恢复；不能拿它覆盖用户目录。
- `AppServiceImpl.saveProjectFile` 用文件内容版本和单文件同步锁保护手动保存，但没有证明它与 Agent、Shell、Docker 和回档共用全工作区闸门。
- 宿主 `AgentWorker.destroyTree` 不足以证明 Docker 内进程停止。当前 RW workspace 挂载和 session 容器复用会扩大迟到写入风险。

上面是只读源码核对，未复现实际污染。需要核对所有主聊天历史加载、冷启动 historyTail、重连、自动标题/修复/知识后台消费者，不能认为检查了一个入口就覆盖全部。

## 3. 成熟方案复用及不能照搬的部分

- Claude Code 在 turn 前捕捉 checkpoint，并支持代码/对话恢复和提示词回填。其官方资料同时说明 Shell、外部修改、部分子任务/中途消息的追踪限制。本项目借鉴交互，不继承这些遗漏，也不宣称其现有实现已满足本需求。[官方 checkpoint](https://code.claude.com/docs/en/checkpointing)
- LangGraph checkpointer 是 thread 状态，store 可跨线程；必须同时管两者。原 checkpoint replay 会重新执行后续节点，不能用它自动“回档并继续错误工具”。采用新合法 seed，保留只读旧分支。[持久化说明](https://docs.langchain.com/oss/python/langgraph/persistence)、[time travel](https://docs.langchain.com/oss/python/langgraph/use-time-travel)
- 文本三方合并复用 `git merge-file` 在受控临时文件中执行，不要求用户工作区存在 Git；Git 文档说明其冲突输出，不代表语义正确。[merge-file](https://git-scm.com/docs/git-merge-file)
- `git apply --check` 可作受控候选中的补丁预检；`--3way` 需要 base blob 且可能隐含操作 index，不能在用户仓库随手调用。需要对象仓库时用平台独立私有元数据，不写用户 `.git`。[git apply](https://git-scm.com/docs/git-apply)

本轮未执行工具、测冲突质量或锁定二进制版本。复用成熟 patch/merge 算法，不以另造行号启发式代替来源追踪。

### 3.1 整体参考 Claude Code：官方行为核对与已确认取舍

2026-10-03 再次核对[官方 checkpoint 文档](https://code.claude.com/docs/en/checkpointing)。这证明公开文档所述行为，不是本机安装版本验收，也不证明掌握其闭源存储实现。

可参考会话内检查点、恢复选择与原 Prompt 回填的交互。不直接引入其只回代码、只回对话或摘要操作：当前产品要求代码和上下文同步回档，摘要不是抹除错误上下文。

| 官方所述边界 | 与本项目既定要求的差异 |
| --- | --- |
| 单会话文件快照保留最近 100 个检查点，并有默认约 30 天的会话快照清理 | 本轮选择 A：不自动按数量或时间淘汰有效回档点 |
| Bash、外部编辑及部分子任务修改不在可靠恢复覆盖内 | 本项目需要修改来源追踪、用户修改保护和所有写入任务停止确认 |
| 加入运行中 turn 的消息没有独立检查点 | 本项目目标为每条用户消息建立真实边界，不无声遗漏 |

用户在上述差异说明后确认 A：“整体参考”复用交互与检查点机制思路，但保留长期回档点、用户代码保护、所有用户消息边界及代码/纯净上下文同步恢复要求。不采用“连能力限制也照搬”的 B，不重新引入单独代码/对话恢复或用摘要代替严格回档。

参考不等于宣称直接接入 Claude Code 的内部实现或本项目已具备同等能力。底层独立内容存储、修改来源、三方合并与上下文资格仍按本项目约束设计并实际验证；不修改用户 Git 历史。任何未来缩减这些承诺须重新取得用户明确同意。

## 4. 修改来源：patch 级，而非永远给每行贴标签

### 4.1 最小必需数据

`ChangeSet`：changeId、appId、branchId、contextEpoch、originMessageId、taskId/toolCallId、actor、operation、pathBefore/pathAfter、baseBlob、resultBlob、patch/hunks、baseWorkspaceRevision、时间、状态与依赖。

actor 至少 `agent_generated / user_manual / unknown`。`user_approved` 用独立 `ChangeApproval` 表达用户认可的 changeId/hunkId、具体内容 hash、范围、确认消息/操作与时间；不抹掉最初 Agent 来源，也不将“批准执行修复”自动等同“以后回档保留这段成果”。

一次批准只认可当时具体内容，Agent 后续改同一块须产生新 ChangeSet；通过测试、未反对、最后写入者、后续聊天继续都不自动认可。

需要 patch/hunk 级粒度，支持同一文件保留底部用户修改而撤销顶部 AI 修改。行号只是显示锚点，重构/换行会移动；以 before/after blob 和 base 关系验证。无需首期维护每一行永久作者数据库，不声称自动精确判定混合作者的语义意图。

### 4.2 来源采集位置

- 平台手动编辑保存：Java 在校验 baseVersion、实际落盘前后保存 blobs/diff，并以同一工作区 revision 原子登记 user_manual。新增/删除/重命名也要进入通道。
- Agent 文件工具：Python Backend 的所有写/改/删/上传入口记录 before/after 和 tool/run 身份；Java 校验并接纳。纯事件回显不能替代真实文件采集。
- Agent Shell/生成器/格式化器：在受管写入区执行，采集前后完整变更清单与工具来源；执行期间和手动保存协调。未隔离同时写无法可靠归因时记 unknown，不能猜成 AI 或用户。
- 容器、后台服务、其他会话：纳入任务资源与写租约；不能靠“观察文件 mtime”识别谁写了哪些行。
- 不经平台的外部编辑：可检测差异，但身份不确定；以受保护 unknown 处理，回档前展示并允许用户明确选择，禁止静默删。不得遍历用户个人目录找身份线索。

来源缺失的历史代码不能补造精确 provenance。保留模式发现不可归因部分时进入待确认/冲突，不以最后文件版本差减去所有 AI 内容。

## 5. 用户 Patch 的保存、重放与依赖

先完成停止与锁定，再保存完整当前 `Rollback Backup` 和受保护 ChangeSet；保护区域在 Agent 根目录之外、受用户只读接口管理，不能把 raw backup 放进可被 Shell 搜索的项目。

记录文本 patch、完整 base/result blob、增删/重命名和文件模式；二进制文件保留完整内容和操作关系，不用文本 hunk 处理。秘钥配置须加密/限制读取，不进入模型或可下载的默认诊断日志。

重放时按 ChangeSet 因果顺序：每一步使用该用户修改前的 base、当前回档候选 target、该修改后的 user，不把整份当前文件当“用户版本”一次复制。某 patch 的 base 本身含 AI 修改时，仅重放该 patch 的变更，不能把未认可 AI 内容随整个文件带回。

例如 AI 新增 helper，用户写的调用依赖它；撤销 helper 后文本可合并仍可能编译失败。来源链显示依赖，进入语义待处理，用户可舍弃该修改、手动改兼容或明确认可必要的 AI 部分；不能偷偷恢复全部错误路径来让编译绿。

新建同名文件、删除/重命名、二进制变化、路径大小写、CRLF/编码、权限与 symlink/hardlink 独立处理。无法安全判断时进入冲突，不按模糊名字匹配。不允许 patch 越界到 `.git`、备份、其他 app 或宿主目录。

## 6. 三方合并的层次与语义验证

Java `RollbackService` 负责编排和归属，底层 `PatchMergeAdapter` 在平台 staging 执行成熟合并工具；Python 当前 Agent 不作为冲突裁判，不向模型发送旧错误分支来让它“帮忙恢复”。临时文件输出无写用户 Git index；所有退出码和冲突信息由程序接纳。

单条修改：base 是修改前内容；target 是当前恢复候选；user 是需保留修改后的内容。文本无冲突并且上下文 hash 满足条件可合并；修改同一区块、删除/新增碰撞或依赖不兼容需用户处理。

UI 提供回档版本、用户版本、手动合并，并显示差异和影响；不能用 ours/theirs/union 自动吞冲突。用户解决的是特定候选/revision，随后原工作区变化会使该解决过期，不能继续覆盖。

合并后必须检查最终文件清单、冲突标记/漏文件/权限，再按恢复验证计划检查引用/类型/配置与必要编译/测试。缺环境不能假通过，需明确待验证；不会因验证自动安装依赖或启动未授权服务。

验证计划区别：恢复完整性必需；语义检查按真实改动/运行要求声明。已声明必需的编译、测试、数据库兼容验证失败或无法执行时，不显示成功。严格恢复无需证明旧代码本身优于新代码，但不能隐瞒已发现不兼容；用户明确授权后可另开恢复后的修复任务，仍不把失败回档改成成功。

## 7. 快照与上下文锚点

每个用户消息在实际执行前绑定 `ConversationCheckpoint`：messageId、conversationOrdinal、workspaceSnapshotId、workspaceRevision、contextBoundary、branchId/contextEpoch、允许前缀消息范围和输入 Prompt。只读/无代码变化消息可复用相同内容 blob，但不同上下文锚点不可合并。

排队消息尚未执行时显示准备/排队资格；执行前才发布有效锚点。消息在运行中送入 Agent 时，必须先暂停可写动作、建立新边界，或作为独立排队任务处理；不借鉴 Claude Code 的中途消息无 checkpoint 限制而违反所有消息目标。无法建立边界时不允许该消息继续触发写入，失败明确呈现。

快照是平台独立内容存储，不执行用户 `git reset / checkout / clean / commit`。完整业务源码、必要运行配置与文件属性须可恢复；`.git` 元数据、数据库、依赖缓存、日志、平台备份/历史报告分开。排除项写入 manifest，不能用知识索引采样结果冒充完整恢复。

严格恢复针对声明的项目代码范围：撤销其中目标后新增/删除/修改/重命名；不删除用户 Git 元数据或范围外数据。保护快照在恢复前捕捉当前完整受管代码与保护修改，不因源快照排除规则漏掉用户新代码。

有效回档点默认保留至用户主动清理，不设置自动数量/时间淘汰。底层 blob 可去重，但任何仍被有效回档点引用的源码与合法消息材料不得回收。主动清理须先展示受影响回档点；执行中、唯一事故恢复依据或保存中的引用仍受保护。

容量限制、压缩效率和保护备份的独立管理细节需实施前验证；不能把短期报告清理策略套到回档点，也不把回档点保留政策扩写为所有日志永久保存。不存在的 target/backup 内容不能用“强制”绕过校验。

## 8. 纯净上下文重建：新分支，新代次

1. 锁定恢复目标与合法前缀，撤销旧任务发布/写入资格，旧分支移入人类只读历史。
2. 新建 branchId/contextEpoch 和内部 thread，禁用继承旧 checkpoint 的 pending node/tool state，不 invoke 原 checkpoint 自动 replay。
3. 只从目标之前的合法原始消息构建 seed。摘要必须记录覆盖消息和证据；覆盖撤销节点的摘要整体丢弃，从合法原文重建，不能简单裁掉结尾。
4. 工具结果、检索缓存、向量索引、知识候选、项目 store memory、修复计划/报告、冷启动 historyTail、浏览器重连上下文分别按资格过滤；没有来源/范围证明的派生内容不得默认进入。
5. 原任务后产生、但用户明确选择保留的代码，以最终合法文件重新读取作为当前事实；不恢复其错误过程、旧工具输出或未认可的解释。
6. 允许只与任务无关的稳定用户设置/Token 偏好，须明确白名单；项目“事实记忆”不能放在全局 namespace 绕过隔离。
7. 保护快照、旧分支、原 checkpoint 和旧日志只供人类查看。Agent 文件工具、Shell、检索、SDK store 和自动后台任务均无访问通道；不是仅把 UI 隐藏。
8. 校验真正发给模型的最终 messages、system/project 摘要、tool messages、store 检索与恢复 thread 标识，使用独特哨兵验证旧错误内容零进入。校验不调用模型、不自动发送原 Prompt。

与新代码完全同 hash 的静态缓存可在共同契约规定的重新资格化后复用；旧 AI 推断、摘要或工具结果不能仅凭 hash 相同恢复合法性。全局 AGENTS 中不能夹带被撤销项目状态；用户提供的新独立指令按当前合法输入处理。

“彻底抹除”指当前 AI 的可见/可检索上下文，不宣称抹去历史模型服务商请求或全球物理备份；平台保留人类只读历史本身不等于 AI 可以再次读取。

## 9. 完整恢复状态机建议

```text
REQUESTED -> PREFLIGHT -> STOPPING -> QUIESCED -> WRITE_LOCKED
WRITE_LOCKED -> BACKUP_SAVED -> CHANGES_EXTRACTED -> CANDIDATE_BUILT
CANDIDATE_BUILT -> MERGING -> VERIFYING -> CONTEXT_PREPARED
CONTEXT_PREPARED -> COMMITTING -> COMMITTED -> PROMPT_REFILLED -> SUCCEEDED

MERGING -> CONFLICT -> 用户解决 -> CANDIDATE_BUILT
VERIFYING -> VALIDATION_FAILED -> 用户处理/异常严格重试/安全退出
任一阶段 -> STOP_FAILED / BACKUP_FAILED / TARGET_INVALID / RECOVERY_REQUIRED
COMMITTING 前可退出 -> ABORTING -> ABORTED（不会宣称回档成功）
```

回档请求后先登记意图并阻止新任务准入，然后停止 Agent、工具、Shell、容器、后台任务；查询实际进程/容器状态确认不写入后才恢复。操作从异常重试继续时仍需重新校验资格与工作区 revision，不能复用过期锁令牌。

候选在 staging 建，冲突不半写当前项目。长期等待用户合并时原业务工作区保持安全原态、Agent 冻结；可释放物理互斥但不能解除逻辑恢复闸门。需要继续编辑必须显式退出恢复并使候选/解决记录过期。

COMMITTED 要满足：目标/保留策略对应文件清单、必需验证通过、干净 seed 已准备、旧资源停止、所有资格一致且单次发布。Prompt 回填失败显示“恢复已提交，提示词回填失败”而非整体成功，允许安全重取原文，不自动发送。

## 10. 跨文件系统与 PostgreSQL 的提交/失败恢复

不声称目录替换和数据库事务天然原子。建议使用持久化 `RollbackOperation` journal、workspace generation 与上下文指针；恢复闸门覆盖所有 Agent 读写与任务准入，避免半状态被读取。

1. **预备**：记录旧 generation/epoch、target、backup、保留 ChangeSet、候选 hash 和阶段；先校验落盘完整性及必需验证。
2. **准备提交**：新上下文 seed 可持久化但不可服务；候选目录已验证。若文件策略需目录切换，先确认 Windows 句柄、同文件系统、平台路径引用与 `.git` 元数据保护；不做未经证明的整目录替换。
3. **提交**：通过单个应用发布记录 CAS 决定新 generation/epoch；期间闸门关闭。稳定路径方案采用 journal 驱动的逐文件/目录安全切换，版本指针方案由路径解析服务统一引用，不另造第二套工作区真相源。
4. **完成**：核对文件与上下文指针配对，更新清单、撤销旧任务、回填 Prompt，才解闸并标成功。具体文件发布策略要通过故障注入后选定，不当作已实现原子能力。
5. **崩溃恢复**：无提交决定时回安全旧代码或继续已验证候选；已有提交决定时完成新状态。两者都不能自动重新启用错误旧上下文，必须保持人类只读与 Agent 冻结直至一致性确认。

恢复失败返回备份代码仅是事故保护，不恢复错误路径 AI 记忆。用户需明确选择后续恢复动作，新执行仍用合法前缀与重新检查的实际文件，不从旧摘要盲续跑。

| 失败点 | 必须行为 |
| --- | --- |
| 停止失败/容器仍写入 | 不碰工作区，不备份成稳定成功，不释放新任务准入；用户可重试停止，不可强制越过 |
| backup 写满、内容缺失/校验失败 | 不恢复，保存失败记录；禁止以唯一旧快照直接覆盖当前用户代码 |
| 目标缺失/已过期/权限错 | 拒绝，强制不能造 target；保护当前状态 |
| 来源未知、文本冲突 | staging 等用户选择，不写冲突标记到活动项目；可明确严格恢复或异常强制入口 |
| 文本合并但语义无效/验证缺环境 | 标失败或待验证；不宣称成功，不偷偷带回 AI 依赖或安装环境 |
| seed/store 资格失败 | 不激活当前新 AI；保留已验证候选或安全旧代码，记录 RECOVERY_REQUIRED |
| 切换中断/发布 DB 失败 | journal 恢复并保持闸门，不能混用新代码与旧上下文；重新校验再发布 |
| 回填/通知失败 | 提交状态与交互状态分开，提供原 Prompt 重取；不自动触发发送或重跑 |

## 11. 核心数据结构与最小侵入方式

| 结构建议 | 关键内容 |
| --- | --- |
| `WorkspaceSnapshot` / blobs | 内容、manifest、排除/范围、属性、引用与完整性 |
| `ConversationCheckpoint` | 用户消息、执行前 workspace/context 边界、Prompt、恢复资格 |
| `ChangeSet` / `ChangeApproval` | patch 级来源、base/result、认可内容与依赖；批准执行与保留成果分开 |
| `PreservedPatchSet` | 本次需保护的修改、完整 blobs、顺序、来源未知项、内容 hash |
| `RollbackBackup` | 当前受管状态、保护内容、访问级别、人类可见、引用/清理条件 |
| `RollbackOperation` | restoreMode、target、阶段、journal、generation/epoch、资格令牌、错误与恢复决策 |
| `MergeConflict` / resolution | base/target/user、涉及修改、用户选择、候选 revision 与后续验证 |
| `ContextSeedManifest` | 原始前缀、派生来源、排除项、合法 thread/store namespace 与请求核验 |
| `TaskResource` / write lease | 后台资源归属、状态、停止核验、写入/发布 fencing |

Java 管恢复事务、归属、文件保存、journal 和消息投影；Python 管 tool 来源采集、执行权限、context seed 与检索过滤；前端只提交目标消息/恢复方式/用户选择，不传宿主路径或直接操作文件。

复用当前目录解析、Worker 池、鉴权、NDJSON、PostgreSQL 与图谱/测试的内容清单。新增恢复与 ChangeSet 服务而非重写全架构；但固定 thread、文件锁、store namespace 和全部写入入口必须实际打通，不能仅添加一个回档按钮。

## 12. 大白话与实施门禁

代码像桌上的稿纸，AI 对话像记事本。先让所有正在写的人停笔，把桌面完整留一份保险；取回旧稿，再把用户明确要留的改动逐个放回。放不进去或内容不再兼容，就让用户决定，不能偷偷选一边。

随后给 AI 一本只包含目标之前内容的新记事本；保险副本和错误过程锁进只供用户看的柜子。最后核对桌上的稿纸和新记事本确实对应，原话放回输入框但不替用户按发送。

比直接覆盖目录多了来源、备份、合并、验证和上下文闸门，但避免用户有效修改丢失、错误摘要复活、后台任务重新写坏以及“恢复成功”假象。

未来顺序：前端评审；完整内容快照/消息锚点；纯净 seed 与资源停止；严格恢复窄链路；来源/用户 Patch 合并；语义与故障恢复；全面矩阵。先证明严格方式不等于最终只交付严格方式，保留用户修改和异常强制也是已承诺范围。所有步骤须用户另行授权实施。
