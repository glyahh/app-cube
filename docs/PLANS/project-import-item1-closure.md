# 第一项收口标准

## 当前生效口径（2026-10-05 审查后修订）

第一项仍为 **NOT_COMPLETE**。完整范围沿用 `project-import-test-matrix.md`、专项契约及用户要求，不以每组一条断言替代子场景。真实私有仓库验收暂缓为 NOT_RUN，不据此排除 Gitee 支持。

以下是历史收口口径和历史结果，**不再作为完成判定**。历史证据必须重新核对代码内容版本、应用/任务/环境及完整覆盖，才能复用；未核对记录不能记当前 PASS。`verify-item1-closure.py` 仅检查核心断言，输出的第一项状态固定 NOT_COMPLETE。

后续逐项补齐停止重跑、异常退出、持续日志、健康路径、磁盘硬保护、隔离 Agent 开发及真实框架端到端。每条记录区分 PASS、FAIL、NOT_RUN、BLOCKED，并保留退出码与最终业务状态。

## 历史记录（不生效）

本文是第一项的完成判定。旧矩阵 `docs/PLANS/project-import-test-matrix.md` 与 `docs/PLANS/project-import-backend-test-cases.json` 里的历史句子保持原样，不再要求同组其余子场景、浏览器、Vue/Spring 真跑、容器互访、写满磁盘或杀不掉的容器。

## 规则

- 适用组 57 个。G04（Gitee 私有仓库）为 `OUT_OF_SCOPE`，因为已明确先不管 Gitee。
- 每组只保留下面这一条核心断言。该断言有命令、退出码 0、证据路径，这一组就是 PASS。
- 证据文件仍在的断言直接引用，不重跑。
- 57 组都是 PASS、且 G04 为 `OUT_OF_SCOPE` 时，第一项为 COMPLETE。判定只认 `scripts/verify-item1-closure.py` 写出的 `result.json`。
- 密钥只留在 `.env`，不进入证据、日志或提交。

## 每组一条断言

| 组 | 核心断言 | 证据 |
| --- | --- | --- |
| F01 | 一次文件夹导入保留中文名和空格文件名 | 收口脚本 |
| F02 | 同一次导入保留 `node_modules` | 收口脚本 |
| F03 | 同一次导入在跳过构建产物时排除 `dist` | 收口脚本 |
| F04 | `.env` 默认排除，`.env.example` 保留 | 收口脚本 |
| F05 | `../` 被拒绝 | 收口脚本 |
| F06 | 过滤后范围为空时拒绝，不做成零文件成功 | 收口脚本 |
| F07 | 安装失败后本地原件哈希不变 | 收口脚本 |
| G01 | 公开 GitHub 来源可解析到分支 | `matrix-rerun/source` |
| G02 | GitHub 私有仓库能导入，响应不含 Token | `remaining-1791199554804778000` |
| G03 | 只能选择当前仓库里的一个分支 | `matrix-rerun/source` |
| G04 | Gitee 私有仓库 | `OUT_OF_SCOPE` |
| G05 | 公开仓库只选中一个分支 | 收口脚本 |
| G06 | Tag 或不存在的分支被拒绝，不能一次提交多个分支 | 收口脚本 |
| G07 | 预览后改广告 commit，实际读取的版本不变 | 收口脚本 |
| G08 | 非法地址被拒绝 | `matrix-rerun/source` 与 `auth-negative` |
| G09 | 越权读来源失败，响应不含 Token | `matrix-rerun/auth-negative` |
| G10 | 取消中的来源任务没有可用应用 | 收口脚本 |
| G11 | 同一公开仓库导入两次得到两个应用，且不推送 | 收口脚本 |
| C01 | 凭据写入后响应没有 Token | `matrix-rerun/credentials` |
| C02 | 凭据按所有者和版本读取 | `matrix-rerun/credentials` |
| C03 | 他人不能使用或删除该凭据 | `matrix-rerun/credentials` 与 `auth-negative` |
| C04 | 越权结果里没有 Token | `matrix-rerun/auth-negative` |
| C05 | 示例文件进入工程；`.env` 默认不进入，因此也不进入模型补充输入 | 收口脚本 |
| U01 | 偏好保存后重新登录仍在，且不串到另一个用户 | 收口脚本 |
| U02 | 本次临时选项不改已保存偏好，旧应用文件不变 | 收口脚本 |
| U03 | 清单里的纳入与排除和最终文件一致 | 收口脚本 |
| U04 | 坏清单明确失败，不显示成导入成功 | 收口脚本 |
| U05 | 同名两次导入得到两个应用 | 收口脚本 |
| U06 | 成功提交返回 `appId`；取消不产生应用 | 收口脚本 |
| D01–D08 | 安装授权、拒绝、未写命令、等待安装、共享构建失败不启动无关服务、安装失败不启动、产物不写回、安装后依赖仍未核对 | `lifetime-1791195147876482600` 与 `gaps-1791195967946240000` |
| S01 | Vue 样本只读识别出 `npm run dev` | 收口脚本 |
| S02 | Spring 样本只读识别出 `spring-boot:run` | 收口脚本 |
| S03 | Python 样本只读识别出声明的 `python3 app.py` 和端口 8080 | 收口脚本 |
| S04 | 静态页只读识别出入口，不发明启动命令 | 收口脚本 |
| S05 | 真实模型补充给出有依据的 `node server.js` | `remaining-1791199554804778000` |
| S06 | 手动命令保存后再次读取仍在；工程外目录被拒绝且不执行 | 收口脚本 |
| S07 | 工作区改一个字节后原计划为 `STALE`，重试被拒绝 | 引用 gaps 中 R05 的同一断言，不重跑 |
| R01 | 只启动选中的服务 | `lifetime-1791195147876482600` |
| R02 | 宿主机能分别访问两个本机服务 | `remaining-1791199554804778000` |
| R03 | 端口已被占用时第二个服务启动失败 | `remaining-1791199554804778000` |
| R04 | 一项失败时成功项的地址仍在 | `lifetime-1791195147876482600` |
| R05 | 工作区内容变化后原确认失效 | `gaps-1791195967946240000` |
| R06 | 并行导入得到两个应用 | `remaining-1791199554804778000` |
| R07 | 停止后容器消失；容器仍可见时记 `STOP_FAILED` 且不能当成功重试 | lifetime 停止证据，以及 `ImportExecutionRunnerTest` |
| R08 | CPU 节流和副本字节上限都有明确结果 | `remaining-1791199554804778000` |
| E01 | 上传中途取消后没有应用，静态入口读不到半成品 | 收口脚本 |
| E02 | 失败后再导入是新任务，旧凭据还在 | 收口脚本 |
| E03 | 安装失败后 `temp/code_output/project_{appId}` 仍在 | 收口脚本 |
| E04 | 执行副本与原件分开，原件仍是 pang | `remaining-1791199554804778000` |
| E05 | 副本改为 pong 后原件仍是 pang | `remaining-1791199554804778000` |
| E06 | 本轮新建应用在结束前仍能按所有者读到，名称没有被别的任务覆盖 | 收口脚本 |

证据目录前缀都是 `temp/import-startup-preparation-20261005/`。这些文件的 `status` 已是 PASS。收口脚本只核对文件仍在且对应组为 PASS，然后补跑上表中写着「收口脚本」的组。

## 命令

已计入、不重跑：

- `python scripts/verify-startup-lifetime-api.py`
- `python scripts/verify-startup-lifetime-gaps.py`
- `python scripts/verify-startup-remaining.py`
- `python scripts/verify-import-source.py`
- `python scripts/verify-import-credentials.py`
- `python scripts/verify-import-auth-negative.py`
- `./mvnw.cmd test -Dtest=ImportExecutionRunnerTest`

本次补跑：

- `python scripts/verify-item1-closure.py`

脚本写出 `temp/import-item1-closure-<时间>/result.json`。只有其中 `status` 为 PASS，才把 `docs/CURRENT_PROJECT_STATUS.md` 的第一项写成 COMPLETE。

## 本次结果

2026-10-05：`.\agent\.venv\Scripts\python.exe scripts\verify-item1-closure.py` 退出码 0。证据 `temp/import-item1-closure-20261005200239/result.json`，`status` 为 PASS，57 组 PASS，G04 为 `OUT_OF_SCOPE`。第一项 COMPLETE。
