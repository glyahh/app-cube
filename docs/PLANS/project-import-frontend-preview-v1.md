# 第一项：文件夹导入前端第一版评审

日期：2026-10-03。最新状态：**用户已确认文件夹界面及成功进入 Agent 工作台的前端衔接，授权正式前端开发；本轮已接入正式文件夹表单，见[正式前端接入记录](./project-import-frontend-integration-v1.md)。业务后端仍未开发。**

最新成功进入 Agent 工作台的衔接与验证见第 11 节；前版验证记录保留为历史，不冒充本轮重新执行。第 8、9 节保留开发标识的方式已被第 10 节用户要求纠正。

## 1. 范围、版本与隔离

- 分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，加本轮未提交预览文件；未暂存、提交或推送。原有文档改动保留。
- 当前只推进第一项的第一个小步骤：文件夹来源、名称、范围清单、过滤、模拟结果与错误/取消交互。Git 导入、凭据、启动识别、依赖安装、多服务运行及另外三项未实现。
- 独立入口 `ai-generate-code-frontend/import-preview.html`，独立 Vite 配置；不修改生产首页、路由、API、登录或 Agent。开发服务器根路径也重定向到该预览。
- 复用现有 `theme.css`、`base.css`、`resolveThemeConfig` 和 Ant Design Vue；新增范围计算与模拟状态仅服务本预览，未提前建设其他项公共设施。
- 本地目录选择只取 `webkitRelativePath` 与 `size`，不读内容、不上传、不保留可上传 File 对象、不执行脚本。样例仅供测试；页面不放样例按钮和来源标签。
- `preview-*` 仅为模拟任务标识；冻结名称、数量与大小，取消阻断旧回调，重试新建模拟标识。**不代表服务器任务、应用 ID、内容快照或后端取消资格。**
- 模拟记录与偏好仅在当前页面内存，刷新清除；无服务器账户持久化。独立安装入口已有正常产品文案的确认、进度、失败/重试与完成交互；全部为内存演示，无真实安装或运行结果。测试参数和记录不展示在用户界面。
- 原有未提交文档修改前副本在 `temp/import-frontend-preview/documentation-baseline/`，原始哈希在 `documentation-baseline-hashes.json`；用于按差异回退，不能直接覆盖后续用户改动。

## 2. 用户已确认的规则

Token 替换：终止使用旧值的未完成任务，提供以新凭据重新导入的入口；不自动续跑，不在原任务中途换身份。仅修改备注不触发替换取消；已完成应用和其他凭据不受影响。已同步主设计、凭据契约、共同契约与 C04，**尚无凭据业务实现或真实验证**。

## 3. 可交互入口与复跑

打开 [文件夹导入前端预览](http://127.0.0.1:5175/import-preview.html)。本轮已启动独立 Vite，监听 `127.0.0.1:5175`；无业务代理，不启动后端、数据库或容器。

以下命令在仓库的 `ai-generate-code-frontend` 目录执行，复用已有依赖：

```powershell
.\node_modules\.bin\vite.cmd --config vite.import-preview.config.ts --host 127.0.0.1 --port 5175 --strictPort
```

人工评审：

1. 点击“选择文件夹”，选择非空工程目录；本页只核对相对路径与大小，不读文件内容。也可选择最新浏览器证据下 `fixtures/客户管理台/`，得到 10 拟导入、2 敏感排除。
2. 核对新应用名称和范围；分别勾选“跳过依赖目录”“跳过构建产物”，观察摘要与清单；显式包含敏感配置时保留警告。
3. 点击“确认导入”，操作锁定并显示阶段，可“取消导入”；取消后等待超过演示时长，不应出现旧成功。重新导入使用新内部任务标识。
4. 成功后自动进入该工程的 Agent 工作台，不停留在成功页；在工作台内询问“是否安装依赖？”。选择“暂不安装”保留安装入口，点击“安装依赖”再次确认并展示状态。全部是隔离页面状态，不执行安装脚本或自动生成。
5. “导入另一个工程”重置当前应用与安装状态；“保存为默认偏好”只在本页内存有效，刷新重置。切换深浅主题和窄窗口核对布局。故障注入仅由自动检查装配，不在页面放测试工具。

浏览器自动检查可从全新隔离会话复跑，不复用用户登录配置：

```powershell
node scripts/verify-import-preview.mjs
```

默认使用本机 Edge：`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`。可用 `IMPORT_PREVIEW_BROWSER` 指定已有浏览器；不自动下载。每次生成独立 `temp/import-frontend-preview/browser-{timestamp}/`，防止覆盖此前浏览器证据。

## 4. 本轮实际命令与结果

环境：Windows、PowerShell、Node `v22.21.0`、Vue `3.5.27`、Ant Design Vue `4.2.6`、Vite `7.3.1`、Vitest `3.2.7`、Playwright `1.62.1`、Edge `154.0.4258.37`。这是现有安装版本，不是按 package.json 范围猜测；见 `environment.json`。

证据根目录：`D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\import-frontend-preview\`。

`source-hashes.json` 绑定本轮预览源码、脚本、配置与原 package-lock 的 SHA-256；`verification-record.json` 汇总最终退出码、范围和结果路径。Git HEAD 不包含这些未提交源码，不能仅凭 HEAD 重放本版。

| 实际命令（前端目录） | 退出码/状态 | 当前结果与证据 |
| --- | --- | --- |
| `.\node_modules\.bin\vitest.cmd run src/preview/project-import/__tests__/manifest.test.ts` | 0 | 新增 12 项通过，`unit.log`；只有元数据规则，不是上传验收 |
| `.\node_modules\.bin\vue-tsc.cmd --build` | 0（复查） | 前端类型检查通过，`type-check.log`；首次 2，`Array.at` 不被当前 TS lib 支持，已改 `pop`，失败保留 `type-check-first.log` |
| `.\node_modules\.bin\tsc.cmd --noEmit --module esnext --moduleResolution bundler --target es2022 --types node --skipLibCheck vite.import-preview.config.ts` | 0 | 独立预览配置类型检查，`preview-config-type-check.log`；不修改生产 tsconfig |
| `.\node_modules\.bin\vite.cmd build --config vite.import-preview.config.ts` | 0 | 独立预览构建，`preview-build.log`，产物 `build/import-preview.html`；不覆盖生产 dist |
| `.\node_modules\.bin\vitest.cmd run` | 0 | 22 文件、167 项通过（含新增 12 项），`frontend-regression.log`；已有测试路径输出 Vue/stub 警告，未隐瞒，不等于真实生成主线验收 |
| `node scripts/verify-import-preview.mjs` | 0 | 最终 9 组真实 Edge 交互通过；`browser-final-empty.log` 和 `browser-1791018006531/browser-results.json` |
| `.\node_modules\.bin\vite.cmd --config vite.import-preview.config.ts --host 127.0.0.1 --port 5175 --strictPort` | RUNNING | `vite.log`；端口监听仅说明入口可用，不作为业务通过 |

最终浏览器检查涵盖：名称/空范围、摘要与过滤、单次与预览偏好、取消/重复提交/旧回调、失败记录/新任务重试、成功归属/安装待实现、真实文件夹选择（中文/空格/空文件/空目录）、375px/1440px 布局、深浅主题与键盘交互。空目录明确报错并清空旧清单，不能沿用上一来源。确认生产 API/外部请求为 0、页面/console error 为 0、localStorage/sessionStorage 未变、7 个虚构来源文件前后 SHA-256 相同。该隔离会话的原工程未被改写；**不证明真实后端的副本/权限保护**。

最终截图位于上述浏览器目录：`desktop-range.png`、`failure-state.png`、`success-state.png`、`mobile-light.png`、`mobile-dark.png`。截图仅辅助布局评审，可交互入口和断言才是本轮依据。

### 已发生的失败与限制

- 首次日志输出目录写错，Tee-Object 提示路径不存在，该次不能作为单元通过证据；修正目录后重新运行并记录退出码 0。
- MCP 浏览器入口缺少其指定 Chromium 可执行文件；没有安装或下载，改用现有 Edge 隔离会话。
- 浏览器脚本前三次退出 1：分别为加载按钮可访问名称含 `loading`、弹窗关闭动画期间两个同名安装按钮、Ant Design 的双字按钮自动插空格。调整定位与关闭等待后重新从全新会话执行；未删除失败结果，也未用减少断言制造通过。失败保留 `browser-first.log`、`browser-second.log`、`browser-third.log` 及对应 JSON、失败截图。
- 前端按文件名识别敏感配置属于提示规则，不是内容扫描或最终保护。浏览器提供的元数据不证明隐藏文件、空目录、符号链接、权限和内容完整性。
- 10,000 文件、名称 80 字符是明确显示的预览保护值，未冻结为后端上传/命名契约；超限拒绝，不截断成功。大工程性能和浏览器完整支持清单尚未验证。
- 未安装依赖、执行迁移、启动/重启业务后端、访问远端仓库、调用模型、运行导入工程或做无关压测。

## 5. 待实现接口契约，不是已注册路由

| 操作职责 | 后端必须承担的边界 |
| --- | --- |
| 读取/保存长期过滤偏好 | 会话确定用户；长期设置与单次覆盖分开；不将当前预览内存冒充账户持久化 |
| 新建文件夹导入任务、接收内容 | 新 taskId、非公开 staging、资源限额与归属；校验路径/实际字节/读取失败，不接受覆盖目标 appId；输入原工程不执行 |
| 查询/确认范围 | 权威文件清单、数量/大小/排除原因、内容与选项版本；确认过期明确失败，不靠浏览器 size 证明内容版本 |
| 确认提交独立新应用 | 任务/版本/取消资格/幂等、文件与 DB journal、最终可读资格；不得复用 `/app/add` Prompt 假装导入或误触发 autoSend |
| 查询/取消/重新导入 | 权威阶段、脱敏错误、实际资源停止和清理状态；取消意图不等于已停止，新任务不使用旧成功事件 |
| 导入后独立安装提示与按钮 | 在后续前端评审和后端实现步骤接入；拒绝保留入口；单独安装不自动构建/启动 |

具体 URL/DTO 需在前端确认后的窄后端步骤结合现有代码定稿。此处不建立空业务路由或假后端。

## 6. 对应矩阵的真实状态

- F01–F06：只验证部分元数据/UI 规则与真实浏览器目录选择；内容、磁盘、上传、符号链接、异常资源与后端状态未验证，整组仍 `NOT_RUN`。
- U 类：名称/摘要/选项/拒绝安装入口的局部 UI 有证据；服务器用户长期偏好、真实安装等仍 `NOT_RUN`。
- E01/E02/R06：模拟失败/新任务/取消旧回调仅为前端局部证据；真实 journal、清理、资源停止与跨应用竞态仍 `NOT_RUN`。
- E06：现有前端单元回归已执行；真实新项目生成、编辑/运行端到端未执行，整组未通过。
- G/C/D/S/R 的真实平台、凭据、安装/识别/多服务，跨功能 X01–X20，以及另外三项：`NOT_RUN`，相关业务尚未实现；不伪造联动通过。

## 7. 评审门禁与回退

用户明确确认本版前端之前，只调整前端；不提前开发业务后端、迁移或联调。确认之后再推进文件夹隔离接入窄链路；后续 Git、识别、安装、多服务涉及新 UI 时仍各自先前端评审。

停止预览：在启动 Vite 的终端按 Ctrl+C；只停止本轮独立预览进程，不重启/停止业务后端。代码回退只撤除本轮新增预览入口、配置、目录和验证脚本，并按文档副本差异撤回本轮记录；不要 `git reset`、清工作区或整文件覆盖后续修改。无数据库变更、真实应用、容器或用户 Git 历史需要恢复。

## 8. 文案精简评审（2026-10-03）

按用户指定 `ux-writing` 技能，只精简当前文件夹前端预览，不改业务功能：

- 删除重复副标题、版本副标、未实现 Git 的介绍、选择区重复文字、默认名称解释、空范围介绍、按钮旁重复说明、重复结果标语。
- 文件处理边界、过滤目录/示例、单次与预览偏好规则移至默认收起的“导入说明”，支持键盘展开；不新增替代性功能介绍。
- Mock/来源标识、当前结果、错误与空范围提示、80 字名称限制、超限拒绝、敏感配置警告、安装待实现提示保留。模拟流程、过滤、名称校验、取消、重试和安装入口不变。

基线仍为 `code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，加本轮未提交改动。修改前的组件和浏览器脚本副本、源码 SHA-256、本轮日志位于 `temp/import-frontend-preview/copy-review-20261003-171030/`。`behavior-comparison.json` 记录：组件 script 除两条通知文字外逐字一致；事件、v-model、禁用/加载及名称限制绑定完全一致，比对命令退出码 0。

本轮实际执行（前端目录）：

| 命令 | 退出码 | 本轮结果与证据 |
| --- | --- | --- |
| `.\node_modules\.bin\vue-tsc.cmd --build` | 0 | 类型检查；上述修订目录 `type-check.log` |
| `.\node_modules\.bin\vitest.cmd run src/preview/project-import/__tests__/manifest.test.ts` | 0 | 12 项通过；上述目录 `unit.log` |
| `node scripts/verify-import-preview.mjs` | 0 | 10 组真实 Edge 交互通过；上述目录 `browser.log`，详情 `temp/import-frontend-preview/browser-1791018683689/browser-results.json` |

新增文案断言验证：重复内容已删除、帮助默认不可见、键盘可展开必要规则、Mock 标识保留。原 9 组交互继续执行，名称与敏感警告、取消旧回调、失败重试、实际文件夹选择、主题/移动布局、无 API/存储写入与来源文件哈希保护均通过。最新桌面/移动截图在该浏览器证据目录，`desktop-minimal.png` 为精简后的初始页。

本轮未重跑全套 167 项或独立构建，不把第 4 节历史数字称作本轮通过；没有业务后端、数据库、Git 或导入执行改动，仍等待前端评审。回退本次文案时，依据上述修改前副本逐处撤回，不覆盖后续用户改动。

## 9. 结果页技术元数据移除与项目规范（2026-10-03）

按用户截图红框，直接移除结果页文件数量、总大小和任务 ID 的两个渲染段，不用其他说明替换。确认范围时的必要摘要不删；内部冻结结果、任务标识、诊断记录和业务操作均保留。

已新增项目级[界面文案规范](../UI_COPY_GUIDELINES.md)，并由根目录 `AGENTS.md` 引用：后续新增/修改界面遵守最小文案、按需帮助、结果页不堆技术元数据；保留错误、输入限制、不可逆风险及 Mock/未实现边界。`AGENTS.md` 属本地忽略文件，规范文档本身位于可纳入版本管理的 `docs/`，本轮未提交或推送。

本轮基线仍为 `code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，加未提交改动。修改前组件、脚本与 AGENTS 副本、最终 SHA-256、结果清单在 `temp/import-frontend-preview/result-copy-20261003-172118/`。源码比对退出码 0：组件 script 完全未变，事件/输入/禁用绑定一致，内部数量、大小和 taskId 保留；不是用隐藏样式或删数据冒充删文案。

- 前端目录执行 `node scripts/verify-import-preview.mjs`，退出码 0，10 组真实 Edge 检查通过。新增断言确认结果区域无文件数/大小/`preview-*` 标识；新任务与失败任务的标识对比改从按需诊断记录读取，未削减任务隔离断言。日志 `result-copy-20261003-172118/browser.log`，详情 `temp/import-frontend-preview/browser-1791019280441/browser-results.json`，结果截图为同目录 `success-state.png`。
- 前端目录执行 `.\node_modules\.bin\vue-tsc.cmd --build`，退出码 0，日志 `result-copy-20261003-172118/type-check.log`。
- 本轮未重跑单元、全套回归或构建；既有数字保留历史。未接业务后端或迁移，仍停在前端评审。

回退仅按本轮副本差异恢复被删的两个显示段、对应断言和规范引用；内部数据无需恢复，不覆盖用户后续改动，不回滚 Git 历史。

## 10. 真实使用场景的前端演示与布局修正（2026-10-03）

用户指出上一版是调试演示页，不是正常用户使用的界面。本轮按最新要求纠正，不再仅删除说明文字：

- 页面与浏览器标题使用正常产品文案；移除 Mock/模拟/待实现标识、内置样例按钮、来源标签、故障开关和模拟记录。没有用另一段介绍替换。
- 文件夹选择、命名、范围确认、导入阶段、取消、失败/重试和结果使用正常用户反馈。错误、空范围、名称/文件数限制、敏感配置警告保留；过滤规则按需展开。
- 安装入口不再报“待实现”，提供独立确认、进行中锁定、失败重试及“已安装”状态；拒绝安装保留入口。完成按钮已表达状态，不再重复放一段成功说明。
- 数据和任务仍是隔离页面内存演示；不创建真实应用、不复制或上传文件、不执行安装/构建/运行脚本、不连接生产接口。模拟能力只满足本次前端评审，不表示依赖安装业务已实现。
- 测试从独立入口装配前注入 `__IMPORT_PREVIEW_SCENARIO__`，记录以事件回调留在检查脚本内；无 URL 调试参数、用户可见测试控件或生产路由改动。另用全新页面验证无测试注入的默认成功流程。
- 布局统一：标题与卡片间距 24px、桌面模块内边距 24px、选项间距 16px；页头品牌与正文左边界对齐，操作按钮置于卡片底部右侧。窄屏上下排列、水平内边距 20px，步骤保持一行，避免步骤栏占用整块高度。
- 真实缩窗检查发现全局主题过渡同时动画宽度/内边距，短时产生横向溢出；本预览非 Ant 元素只过渡颜色/边框/阴影，不删溢出断言、不靠延时隐藏问题。

### 环境、输入和权威状态

分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，加未提交预览源码。Node `v22.21.0`、Vite `7.3.1`、Vitest `3.2.7`，本机 Edge `154.0.4258.37`，Windows，独立地址 `127.0.0.1:5175/import-preview.html`。无依赖安装和业务服务重启。

最新浏览器证据为 `temp/import-frontend-preview/browser-1791021222996/`，包括 12 个合成文件的 `fixtures/客户管理台/`、仅敏感配置目录、空目录、`browser-results.json` 和截图。合成文件包含中文/空格路径、空文件、依赖/产物/配置示例。默认 10 拟导入、2 排除，大小 15.1 KB；分别过滤依赖/产物变为 8/6，显式包含敏感配置变为 8。

事件记录核对取消、失败、重试的不同任务标识和最终名称；取消旧任务不出现成功事件。结果页无文件数/大小/任务 ID。真实最终业务状态为：服务器任务、应用 ID、内容快照、安装任务均不存在；偏好未持久化到账户。不能用演示页面的“导入完成”“已安装”证明业务落库或执行成功。

### 实际命令与结果

以下命令在 `ai-generate-code-frontend` 目录执行。日志与修改前副本目录为 `temp/import-frontend-preview/product-review-20261003-172836/`，最终源码 SHA-256 见 `source-hashes-final.json`，Git/范围状态见 `final-state.json`。

| 命令 | 退出码 | 当前结果和证据 |
| --- | --- | --- |
| `.\node_modules\.bin\vue-tsc.cmd --build` | 0 | 最终类型检查；`type-check-final.log` |
| `.\node_modules\.bin\vite.cmd build --config vite.import-preview.config.ts` | 0 | 最终独立构建，不变更生产入口；`build-final.log` |
| `.\node_modules\.bin\vitest.cmd run` | 1 | 首轮 164 通过、3 项 5 秒超时；`unit-regression.log`，保留失败，不称首轮通过 |
| `.\node_modules\.bin\vitest.cmd run --maxWorkers=2` | 0 | 相同 22 文件、167 项全部通过，包含 12 项清单检查；不改测试代码或超时值；`unit-regression-bounded.log` |
| `node scripts/verify-import-preview.mjs` | 0 | 最终 13 组真实 Edge 检查通过；`browser.log`，详情上述 `browser-results.json` |
| `git diff --check`（仓库根） | 0 | 已跟踪差异空白检查；不代替新增源码/功能验证 |

首轮单测默认多 worker 并与构建并行时，登录初始化/访问守卫/图谱物理各一项超时；限制 worker 为 2 后相同用例全部通过。只说明本次受限并行运行结果，不宣称已根治所有环境不稳定。既有组件单测仍有 Vue 桩相关警告，日志保留；本页真实浏览器无脚本/控制台错误。

浏览器首次检查失败来自加载图标改变按钮可访问名称，定位器改为允许加载前缀，仍保留禁用与取消断言；证据 `browser-1791020824473/`、`browser-first-failure.log`。第二次失败是真实缩窗溢出，修正布局过渡后通过；证据 `browser-1791021003478/`、`browser-layout-failure.log`。中间 12 组通过为 `browser-1791021105185/`，最终增加干净默认入口后为 13 组，未覆盖历史记录。

最新截图：`desktop-empty.png`、`layout-1440.png`、`layout-1024.png`、`layout-768.png`、`layout-375.png`、`failure-state.png`、`success-state.png`、`installed-state.png`、`mobile-result.png`、`mobile-light.png`、`mobile-dark.png`、`default-installed.png`。实际测量四种宽度：标题/卡片间距均 24px、选项间距均 16px、无横向溢出；桌面面板顶边对齐，375px 上下边界相接。已实际查看桌面、窄屏及结果截图，不用截图代替可交互入口。

### 边界、后续门禁和回退

无生产 `/api`、`/static` 或外部请求；浏览器 localStorage/sessionStorage 前后未变，测试源文件 SHA-256 前后未变。测试与修复保持独立，检查脚本不修改业务源码。用户已确认的 Token 替换行为不变；凭据业务尚未开发。

本轮通过只覆盖文件夹前端演示和已有前端单元回归。整项导入矩阵、真实文件/数据库/取消资格/资源保护、Git/Token/识别/安装运行、跨功能 X01–X20 及另外三项均仍 `NOT_RUN`。没有扩大功能范围、执行无关压测或宣称整项交付。

项目文案规范已按最新要求更新：正常产品模拟界面不展示开发标识，隔离和真实业务边界留在工程文档/报告中。仍停在前端评审；用户未明确确认前，不开发业务后端或迁移。回退按上述修改前副本和具体差异逐处撤回当前组件/入口/脚本/规范及文档，不覆盖用户后续改动；停止本轮 Vite 即可，不影响业务后端、数据库或 Git 历史。

## 11. 导入成功直接进入 Agent 工作台（2026-10-03）

用户确认上一版界面，并要求导入成功重定向到 Agent 平台。本轮仅推进第一项的前端衔接：

- 复用现有 `AppChatView.vue`，不是新建另一套聊天平台。生产入口为 `/app/:id/chat`；预览采用隔离 hash 路由 `/import-preview.html#/app/{previewAppId}/chat`，不将假 ID 发送到生产 API。
- 只有本次导入成功才跳转；应用 ID 与导入任务 ID 分开生成，名称/范围冻结绑定当前输入。不携带 `autoSend`、初始生成提示或 Loop，进入工作台不会自动发送消息、生成、修改文件或运行项目。
- 安装提示移到工作台内。拒绝保留安装按钮，独立安装仍只演示本页状态。失败或取消不跳转；返回导入页中止旧安装，新工程不接收旧回调。
- 预览项目只在当前页面内存。刷新、无效/其他应用地址明确提示“工程会话已失效，请重新导入”，不假装恢复真实应用。真实生产刷新恢复仍须由后端权威任务与应用状态验证。
- 现有工作台新增可选 `importedProject` UI 属性，仅调整导入上下文的标语、空态和输入占位；默认不传时保留原生成入口行为。没有修改实际消息、鉴权、生成、运行或保存业务。
- 工作台样式、分栏、聊天组件、外观参数沿用现有实现。预览层补齐长名称换行、按钮换行和窄屏面板高度；375/768px 的聊天消息区与输入区不重叠，面板间距 20px，无横向溢出。

### 隔离方式与未实现能力

独立 `main.ts` 装配 Pinia、仅本页的路由和开发者身份；不装配生产鉴权入口。`workbenchTransport.ts` 仅在此入口安装内存 Axios/fetch 响应，Agent 流替身仅由 `vite.import-preview.config.ts` 的精确 alias 使用，生产 Vite 配置与生产路由未变。

已知读取严格核对当前应用标识，未知操作和异源服务明确失败，不向真实后端转发或自动替换成功结果。聊天演示仅根据已选择文件元数据返回固定响应，不读用户代码、不调用模型、不做代码修改。文件内容、真实 Agent、编辑/下载/发布/看板设置/运行等业务未在本步联调；未映射的操作保持错误，不虚构实际执行成功。没有提前开发测试验收、回档或图谱功能。

原工作台临时写入 `sessionStorage` 的 `genMode:{previewAppId}`，离开该工程时仅移除该演示键。此次检查确认离开后无残留存储，不能把它写成完全没有发生过临时存储写入。无账户偏好持久化或生产身份，原工程文件哈希未变。

Vite 首次按需加载 `marked` 时曾因依赖优化重载丢失内存工程；预览配置明确提前优化这项已有依赖，未安装新依赖。启动错误有明确页面提示，不以白页或静默兜底作为正常结果。

### 本轮实际验证与证据

基线仍为 `code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d` 加未提交文件。环境同第 10 节，真实 Edge `154.0.4258.37`，仅独立 Vite `127.0.0.1:5175`；业务后端、DB、容器和真实模型均未启动或重启。

本轮副本、日志及最终源码 SHA-256：`temp/import-frontend-preview/agent-redirect-20261003-191900/`，包含 `AppChatView.before.vue`、`AppChatThinking.before.test.ts`、导入组件/入口/脚本/配置副本和 `source-hashes-final.json`。最新浏览器证据：`temp/import-frontend-preview/browser-1791027198611/browser-results.json`，以及同目录截图与合成文件。

| 实际命令（前端目录） | 退出码 | 本轮结果 |
| --- | --- | --- |
| `.\node_modules\.bin\vue-tsc.cmd --build` | 0 | `type-check-final.log`，包括可选工作台属性与隔离传输 |
| `.\node_modules\.bin\vite.cmd build --config vite.import-preview.config.ts` | 0 | 最新 `build-final.log`；工作台按需分块；保留入口 chunk 超过 500 KB 的警告，不调高警告阈值掩盖 |
| `.\node_modules\.bin\vitest.cmd run --maxWorkers=2` | 0 | 最新 `unit-regression-final.log`：23 文件、173 项通过，包括 5 项隔离传输和 1 项导入上下文回归；首轮 172 项结果为增加上下文测试前的历史，不称当前完整结果 |
| `.\node_modules\.bin\vitest.cmd run src/page/App/__tests__/AppChatThinking.test.ts src/preview/project-import/__tests__/workbenchTransport.test.ts src/preview/project-import/__tests__/manifest.test.ts --maxWorkers=2` | 0 | `unit-final-targeted.log`：33 项通过 |
| `node scripts/verify-import-preview.mjs` | 0 | `browser-final.log`：15 组真实浏览器交互通过；截图实际查看，不能替代可交互页面 |

浏览器检查覆盖：前版来源/过滤/名称/取消/重试；成功 hash 跳转与名称绑定、无自动消息；工作台内拒绝安装/失败重试/完成；当前工程消息演示；返回中止旧安装与新工程隔离；80 字名称窄屏换行；刷新/伪造地址失败；375/768px 面板间距与消息输入区边界。页面脚本/控制台错误、生产 `/api`/`/static` 请求、外部请求均为 0；合成来源文件 SHA-256 前后相同。

可看 `success-state.png`、`agent-chat.png`、`mobile-result.png`、`default-installed.png`。真实项目文件和 Agent 结果没有被这些截图证明。原组件单测的 Vue 桩警告仍在日志中，未在本步改动无关测试以消除它们。

最初浏览器检查发现普通 `<template>` 标签导致表单不可见，移除无指令包装后复测；`smoke.log`、`smoke-second.log` 和 `before.png` 保留。按需优化重载白页的 `smoke-third.log`、`smoke-workbench.png` 保留；修正后 `smoke-fourth.log` 和最新 15 组检查通过。窄屏初版聊天输入压缩空态的旧截图在 `browser-1791026926413/`，修正后新截图/边界测量不重叠，未删验收断言。

### 矩阵状态、评审和回退

设计和导入矩阵已补充成功进入工作台的 U06，六份矩阵目前共 258 项，导入 58 项；只是目标用例数量。U06、D02/D03 和相关任务/跨功能隔离仅有本轮前端局部证据，真实权威应用、文件、权限、任务状态、安装和运行仍 `NOT_RUN`，不标整组通过。另外三项与跨功能 X01–X20 不伪造联动验收。

最新入口仍为 `http://127.0.0.1:5175/import-preview.html`，选文件夹并确认导入即可体验自动跳转。等用户确认这一步前端衔接后再按已批准顺序进入窄后端步骤，不提前迁移或联调业务。

回退只按本轮副本差异撤回隔离路由/传输/组件跳转，以及 `AppChatView` 可选 UI 属性和对应测试、文档增量；不整文件覆盖后续用户修改、不修改 Git 历史。停止本轮 Vite 即结束演示，无真实应用、容器、数据库或文件写入需要恢复。
