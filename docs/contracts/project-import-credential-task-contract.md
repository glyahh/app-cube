# 项目导入：凭据版本与任务协调契约

> 类型：领域契约与接口设计；实现和验收状态须另行核对。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

日期：2026-10-03。源码：`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`。
状态：第一项凭据业务仍未编码、迁移、连接用户仓库或执行认证请求；在途替换行为于 2026-10-03 经用户明确确认。当前只有隔离的文件夹[前端预览](../reviews/project-import-frontend-preview-v1.md)，不包含凭据实现。

关联：[导入设计](../plans/project-import-design.md)、[全面矩阵](../tests/project-import-test-matrix.md)、[共同契约](project-task-version-contract.md)、[跨功能矩阵](../tests/cross-feature-consistency-test-matrix.md)。

## 1. 已确认规则与研发建议分开

已确认：GitHub/Gitee 公私仓库、手动填写并加密保存多份 Token；单次单分支、独立新应用；替换后新导入只用新值；删除 Token 取消关联的未完成导入，不影响已完成应用和其他凭据。只读远端，不增加推送、SSH 或平台 OAuth 入口。

在途替换已确认：终止使用旧值的未完成任务，提供用新凭据重新导入的入口。不在原任务中途换身份，也不自动重新导入或自动确认变化后的文件范围。仅改备注不取消任务。不可混用的凭据版本、撤销资格与实际停止是该规则的工程方案，尚未实现验证。

本文将已确认规则与待验证工程机制分开；确认在途行为不等于批准所有底层选型。若改变原要求再单项对齐。

## 2. 当前源码与复用位置

| 源码 | 本轮只读观察 | 接入边界 |
| --- | --- | --- |
| [AppController](../../src/main/java/com/dbts/glyahhaigeneratecode/controller/AppController.java) | `/app/add` 要求 initPrompt，取当前会话用户 | 不把仓库地址塞进 Prompt 模拟导入，也不接受客户端 ownerId |
| [AppServiceImpl](../../src/main/java/com/dbts/glyahhaigeneratecode/service/impl/AppServiceImpl.java) | createApp 保存 AUTO app，可绑定 Loop；没有导入文件提交状态机 | 复用实体/鉴权，但单独编排导入提交，不能 save(app) 后假称接入完成 |
| [App 实体](../../src/main/java/com/dbts/glyahhaigeneratecode/model/Entity/App.java) | 有用户归属、名称、类型和看板字段 | AppOrigin/ImportTask 独立记录来源与可用资格，不让 initPrompt 承载密钥或旧任务材料 |
| [UserPreferenceController](../../src/main/java/com/dbts/glyahhaigeneratecode/controller/UserPreferenceController.java) | 偏好取当前登录用户，PUT 仅改非 null 项 | 可复用鉴权/更新语义，凭据密文不塞偏好 DTO，也不返回给前端 |
| [WorkspacePathResolver](../../src/main/java/com/dbts/glyahhaigeneratecode/service/support/WorkspacePathResolver.java) | output/deploy 路径构造与范围校验 | 复用路径职责，增加非公开 staging；路径合法不代表内容或任务已提交 |
| [StaticResourceController](../../src/main/java/com/dbts/glyahhaigeneratecode/controller/StaticResourceController.java) | 方法按目录读取 output 文件，未在方法内检查导入成功/用户归属 | 需核对全局过滤器及预览隔离；发布前入口也必须受可用资格约束，不仅隐藏列表 |

相关范围检索未发现已有 Git Token CRUD/版本与加密业务实现；密码摘要不是可解密 Token 存储。上述不是运行时漏洞复现或全仓穷尽结论。

## 3. 最小逻辑对象

| 对象 | 最小职责 | 不存什么 |
| --- | --- | --- |
| RepositoryCredential | credentialId、ownerId、provider、备注、当前版本、状态与更新时间 | 不回显 Token，不按平台只允许一份 |
| CredentialVersion | 不可变语义版本、密文、keyId、nonce/tag、状态；旧版不可再签发租约 | 不存浏览器缓存、明文或可复制到仓库的凭据 URL |
| CredentialLease | 特定 owner/task/凭据版本/服务端允许的远端范围、租约状态及执行归属 | 不给主 Agent 一个可随意解密 Token 的权限 |
| ImportTask | 来源/分支、固定实际 commit、凭据版本引用、选项/清单版本、任务阶段、取消原因、提交及清理状态 | 不把分支名当内容版本，不在新任务复用旧成功事件 |
| ImportResource | 本任务的进程、Git 元数据目录、staging、网络执行与清理记录 | 不把资源归属只绑定 PID 或目录名字 |
| ImportCommitRecord | operationId、目标 appId、已校验 manifest、文件发布阶段与最终结果 | 不假设文件系统和 PostgreSQL 天然原子 |

这是逻辑边界，不要求每行对象各造一个服务。ID 跨 Java/Python/浏览器为 string；owner/provider/可用版本由服务端决定。

凭据语义版本变化与密文维护分开：替换 Token 改版本并撤销旧资格；仅备注修改不变版本；同一秘密的密钥轮换不是用户替换 Token，不应无理由取消导入。轮换仍要记录 keyId 并校验解密一致性。

## 4. 密文与最小读取面

建议复用 Java 21 JCA 的 `AES/GCM/NoPadding`，使用认证加密，独立 nonce，AAD 绑定 owner/provider/credentialId/语义版本/编码版本，防止密文跨用户或平台错配。JDK 官方说明 AEAD 校验与 GCM 同密钥 IV 不可重复；具体密钥规格与 nonce 生成/冲突保护须实现测试。[JDK Cipher](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)

- 主密钥由平台独立部署配置管理，不与凭据密文同表存放，不提交 Git、不进入工作区、模型请求或沙箱挂载。部署与恢复说明须覆盖密钥丢失、轮换和权限。
- 密钥缺失、版本未知、tag/AAD 校验失败时阻断凭据使用并报告，不退回明文、默认密钥、全局缓存或其他 Token。其他不依赖该凭据的功能不因它被误删。
- 控制器响应只返回备注、平台、credentialId/version、状态，不返回完整秘密或密文。输入 Token 不进入 localStorage/sessionStorage、历史 Prompt、URL、埋点或 DTO 自动日志。
- Java 的受管导入执行器按短生命周期租约取秘密；主 Python 聊天 Worker、模型、知识索引与运行容器不接收它。明文暂存尽量限于必要缓冲区，不声称 JVM String 或进程内存能被彻底擦除。
- 删除时建议移除秘密材料，保留最小无秘密审计/任务状态，停止相关执行；不将“保留失败证据”解释为保存可恢复 Token。删除平台记录不等于远端令牌撤销。
- 日志脱敏只是末端保护，不能依靠正则保证任意回显安全。凭据通道不接通用日志、模型事件或用户下载包；泄漏验证使用专用虚构标记/测试 Token。

不引入强制付费 KMS 或 OAuth 服务；部署密钥保护仍是实际运维责任，不把“加密入库”当成整个系统不会泄漏。

## 5. Git 执行适配与平台差异

优先评估受管 Git CLI，避免先引入另一套 Git 库；版本及镜像待实测锁定。Git 官方提供 credential helper/askpass 机制，helper 使用远端上下文，不需要把密码写到 clone URL。可据此设计受管、无持久秘密文件的凭据适配器。[Git credential 机制](https://git-scm.com/docs/gitcredentials)

建议：任务外部的私有 Git 元数据目录与只读内容获取，凭据经专用 helper/私有 IPC 交付；只向已校验 HTTPS 远端、特定任务/版本提供，不通过命令参数、源码文件或通用 stdout 事件。helper 自己的协议输出与导入日志必须是两条独立通道；能力令牌同样不应暴露到参数、日志或沙箱。

- 独立 HOME/配置与 helper 链；不复用宿主个人 Git Credential Manager、凭据缓存、SSH key 或交互 OAuth，防止跨用户身份混用。禁终端交互，无凭据立即明确失败。
- 原始用户 URL 只用于解析规范来源；拒绝嵌入秘密、任意协议/外部地址，校验 DNS、代理、重定向及实际目的地。仓库名/分支名以结构化参数传递，不拼接 Shell。
- 不执行仓库 hooks、任意 filter/smudge、子模块脚本或项目命令。候选方案为受控对象读取与内容导出；其压缩包、链接、文件模式及路径必须独立校验，不认为使用 Git 就天然安全。
- 子模块、LFS、符号链接及外部对象若影响完整工程内容，先登记实际缺口与所需读取/权限。无法按范围完成时明确阻断，不自动扩大跨仓库 Token 使用，也不把指针文件冒充实际资源。具体支持路径待样本验证，不借本文件删除承诺文件。
- GitHub 官方确认 PAT 可用于 HTTPS Git，权限受 Token 与账号限制。没有证明一个平台成功就等于 Gitee 成功。[GitHub PAT](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
- Gitee 的 Token 类型、用户名要求、私有仓库读取与 API/分支查询差异仍须官方资料与实际样本验证。本轮旧帮助文章跳转首页，API 页面只取得导航，未形成完整认证证据；不据此新增密码/SSH 输入，也不声称 Token 接入已验证。[Gitee 帮助入口](https://help.gitee.com/)、[API 入口](https://gitee.com/api/v5/swagger)

两个平台适配器先分别证明凭据、分支与固定 commit 读取，再评估复用。不得盲试同一用户所有 Token，失败不换账号、不静默匿名重试伪装私有导入成功。

## 6. 凭据变更与任务准入

### 6.1 创建、预览和确认

1. 登录用户选择自己的一个凭据；公开仓库可不选，不能因有多份就自动轮试。服务端核对 provider 与规范仓库来源。
2. 新任务绑定当前有效语义版本。预览、分支查询和导入读取都核验租约；备注不是身份，客户端提供旧 credentialVersion 不获得旧秘密。
3. 以实际读取 commit 与过滤选项形成确认清单，独立记录 manifest revision。首期 commit 是固定读取结果，不提供用户输入历史 commit 的新入口。
4. 确认时同时校验任务归属、凭据有效版本、commit/清单及选项版本。Token/分支/过滤项变化导致资格失效时，不能带旧摘要静默成功；重新读取与确认所需状态明确返回。

### 6.2 替换与删除：建议串行化点

凭据记录变更与最终导入提交需要共享数据库条件/短临界区；先撤销使用资格，再异步停止实际执行，不能等进程退出才阻止迟到提交。

| 发生时点 | 已确认替换行为的实现建议 | 删除已确认行为的实现建议 |
| --- | --- | --- |
| 尚未运行/确认 | 旧版本任务失效，不能启动；提供新任务入口 | 取消关联未完成任务，不再签发租约 |
| 拉取/扫描进行中 | 旧版本不再供给；停止旧执行，收到晚到文件/事件不提交 | 同左，记录真实停止状态；平台记录删除不等于远端已撤销 |
| 待最终提交 | 以有效版本与 task revision 的条件提交；撤销先成立则拒绝 | 已取消则不产生可用 app，不能只按文件已复制判成功 |
| SUCCEEDED 已持久化且文件资格有效 | 已完成 app 保留，新导入用新值 | 已完成 app 保留，不删项目，不取消无关任务 |
| 停止失败 | 显示资格已撤销、执行尚未停止；锁住旧资源，不显示已取消完成 | 同左，持续可恢复的停止/清理记录，不允许旧任务发布 |

竞态按共享提交条件决定先后，不用浏览器到达时间。只标了取消意图但最终成功先提交的情况，要核对两者是否经过同一条件/锁；不能各服务自行声称先成功。未完成语义以权威终态与最终文件资格为准。

在途秘密可能已进入网络客户端缓冲区，撤销租约不能宣称“已经没有旧请求”。必须停止并核验资源；其他凭据的新任务可独立执行，但不能复用旧进程或未清理 staging。

## 7. 状态机与资源停止

```text
REQUESTED -> RESOLVING_SOURCE -> READING_CONTENT -> SCANNING_RANGE
SCANNING_RANGE -> WAITING_CONFIRMATION -> VALIDATING -> READY_TO_COMMIT
READY_TO_COMMIT -> COMMITTING -> SUCCEEDED

未提交阶段 -> CANCELLING -> CANCELLED
未提交阶段 -> FAILED
CANCELLING -> STOP_FAILED -> 重试停止 -> CANCELLED
文件/数据库提交不一致 -> RECOVERY_REQUIRED
```

实际 commit 在读取前确定并核对内容；READING_CONTENT 不能混读随后移动的分支。改过滤选项只重建已固定输入的范围；改凭据/来源则使旧确认失效，不在原任务里换身份。

任务状态、提交 journal 和资源/清理状态分开：CANCELLED 需实际执行停止确认；清理失败可另标 CLEANUP_FAILED 并保留失败记录，但不得误报已清理。COMMITTING 不能盲杀，先按 journal 及共享凭据/任务条件判断是否已有合法最终提交。

FAILED 同样立即撤销后续执行/发布资格，并请求停止登记资源；任务失败不代表进程已退出，停止失败仍单独可见。不得将未停止资源或旧 staging 借给新任务。

Git 子进程、helper、网络客户端、私有元数据与暂存文件都登记。关闭浏览器/失去流连接不代表任务已停止；主 Agent 停止接口也不能替代独立导入执行确认。

## 8. 最终提交与事故恢复

不在长网络操作期间持数据库行锁。staging 初始不在静态可服务目录，完成内容校验后封存；发布阶段用 journal、独占目录/资源与应用可用资格协调，不能仅依赖 App 列表隐藏。

建议最终短事务将 credential 版本/状态、task revision/取消资格、固定 manifest、唯一目标 appId 作为同一提交条件，写应用来源并转换权威终态。文件发布前后都核对实际清单；事务仅成功或 App 行存在不够，最终文件和读入口资格也必须一致。

所有文件树、预览、静态资源、下载、运行、图谱及 Agent 启动入口都要阻止读取未提交/恢复中的导入内容。当前 static 方法没有上述检查，实施前须证明新增闸门覆盖所有入口；不直接复用面向公开部署的 deploy 目录存储私有暂存内容。

导入成功后的合法编辑属于新的 workspaceRevision，不能因当前工作区不再等于原始导入 manifest 而撤销历史导入成功；来源清单与当前代码版本分开。提交恢复检查只对对应导入发布阶段和其封存输入进行，不覆盖后续用户代码。

| 故障 | 必须结果 |
| --- | --- |
| 密钥/tag/AAD/租约失效 | 不尝试明文或别的 Token，不发起未授权远端读取 |
| 同时替换/删除与提交 | 共享条件保证唯一结果，迟到任务不能产生可用 app |
| 文件完成但 DB 提交失败 | 不显示可用，入口闭合；保留 journal，按归属恢复或清理 |
| DB 记录已有但内容不完整 | 标 RECOVERY_REQUIRED，禁可用入口，不能沿用成功布尔 |
| 停止/清理失败或服务重启 | 从持久记录核对真实资源，失败可见，不清其他用户/已完成目录 |
| 导入成功后安装/构建/运行失败 | 不走导入取消清理，不删除用户已接入工程 |

需要先验证文件发布策略、Windows 句柄、重启恢复与静态入口后才能锁定提交实现。本文件不伪造“文件系统+SQL 原子事务”。

## 9. 操作契约：建议接口职责，不是已注册路由

| 操作 | 输入重点 | 输出/限制 |
| --- | --- | --- |
| 新建/列举凭据 | provider、备注、仅写秘密；会话确定 owner | 新建/列表均无秘密；同平台多份独立 |
| 改备注/替换秘密 | credentialId、预期记录版本；两种动作分开 | 条件冲突明确，改备注不撤销导入；替换给新语义版本 |
| 删除凭据 | credentialId、预期版本 | 返回记录/相关任务停止状态，不把“请求接受”当执行停止 |
| 查询仓库/分支 | 规范来源、选定凭据引用 | 标注来源/版本/分页完整性；无权不泄漏其他用户仓库 |
| 创建导入/确认范围 | 来源、一个分支、过滤选项；确认携 task/manifest revision | taskId、阶段、清单与脱敏错误；不接受宿主路径或任意 app 覆盖目标 |
| 查询/取消/重新导入 | 服务端归属核验，taskId 与条件版本 | 独立新任务，新读取与确认；不自动复用旧文件或换秘密 |

状态事件不携密文/秘密；业务版本、事件序号与 operation 幂等结合，断线重连从权威记录恢复。DTO 校验、会话/CSRF 防护、日志序列化和前端旧响应覆盖需一起验收。

## 10. 对应验收与进入实现前门禁

扩展原矩阵断言，不新增“已通过”数字：

- C01/C02：多份独立、备注与替换区分、越权与跨 provider 错配。
- C03/C05：密文篡改、错误密钥、轮换、helper/URL/进程/日志/模型/下载包泄漏；只用测试秘密。
- C04/R06：替换/删除在准备、拉取、确认、提交各阶段相遇，停止失败与晚到结果；两个串行化顺序都测。
- G02/G04/G05/G07/G08/G09：两平台真实私有认证、全分支、不混 commit、不向未批准远端交付秘密，不轮试其他凭据。
- E01/E02/E03：文件/DB 两阶段断电点、静态暂存访问、重启/清理失败与重新导入；已完成项目不误删。
- X15/X17/X18：凭据与其他功能取消/回档/清理隔离，不把导入失败现场注入当前 AI。

实施前记录 Git/平台适配版本、执行镜像、密钥管理、测试账号/仓库范围、状态机与静态入口覆盖。无上述证据不宣布双平台私有导入完成。本轮仅补契约，所有断言仍待执行。
