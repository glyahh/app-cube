# Git 导入：当前接入步骤与验证门禁

2026-10-04 当前增量：[来源请求恢复证据](./project-import-source-recovery-verification.md)记录 owner 绑定的只读找回、刷新恢复与 Windows Git 只读对象清理；Java 225 项中 224 通过、1 未执行，前端 208 项通过。真实请求丢失/刷新/取消及已有 GitHub、文件夹回归通过；旧清理失败样本保留，完整进程树/硬资源限额及第一项整体验收仍未通过。识别/安装/服务选择下一步仅交互预览，后端待评审。

2026-10-04 最新增量：[凭据读取](./project-import-git-private-verification.md)与[固定范围重建](./project-import-git-range-verification.md)后端已接入，双平台公开固定内容重建/最终应用各 40 项局部检查通过；正式 Git/凭据前端联调、来源故障恢复和识别/授权/运行继续待开发。有效私有仓库真实成功验收暂缓；一例文件夹提交失败原始原因未确认。下方未接入描述仅属于早期时点，不作为当前现状。

2026-10-04 后续增量：公开来源持久业务入口、同 UUID 内容读取与最终 App 提交已实现，GitHub/Gitee 各完成一次实际固定内容导入。当前以[公开来源业务证据](./project-import-git-public-verification.md)为准；下方“无 HTTP 入口/未创建 App”仅记录早期时点。正式 Git 前端、私有认证、过滤重建、执行隔离与第一项整体验收仍未完成。

2026-10-04。第一项内部继续开发，不扩展其他三项。Git/凭据界面已批准；凭据阶段见[当前证据](./project-import-credential-verification.md)。本页是实现步骤，不是业务完成证明。

## 已证明的最小原语

- `ImportRepositorySource`：只接受 GitHub/Gitee 规范 HTTPS 仓库、一个合法分支；秘密 URL、跨平台、query/fragment、任意协议/端口和 Git 修订表达式拒绝。
- `ImportGitMetadata`：全 heads 解析、NUL/严格 UTF-8 树清单、路径/限额复用、执行标志记录；链接/子模块/LFS 指针明确阻断，不冒充资源已获取。
- `ImportCredentialBroker` + `scripts/import-git-askpass.py`：私有回环能力通道，仅给精确仓库的可信 helper；错误能力、越仓库、撤销及解密失败不供给秘密。真实 Python helper 测试只用虚构标记；还未接 Git 私有认证。
- `ImportGitNetworkPolicy`：直连拒绝私网/合成/保留地址并使用 `http.curloptResolve` 固定已验证 DNS；显式本机代理单独记录 `EXPLICIT_PROXY_TLS_HOST`，不能冒充直连 DNS 通过。两种方式均开启 TLS 验证、拒绝重定向、关闭宿主 helper/持久 cookie，限定 HTTPS。
- 公开分支实测：`git-public-branches-system-proxy/result.json` 退出 0，GitHub 3 个 heads、Gitee 2 个 heads；仅只读公开样本，未创建导入 App。独立 bare 元数据、HOME/配置/空 template，未 checkout 或执行工程。

证据根：`temp/import-backend-publication-20261004/`。`git-public-branches/result.json` 保留 17897 上 Schannel TLS 握手失败；相同 TLS 检查由 curl 复现。只改为现有 Mihomo 的 7897 后，curl TLS 与 Git 两平台 heads 均通过，未关闭验证或调整系统代理。主机 DNS 返回 198.18 合成地址，直连仍未验收。7897 由当前 `verge-mihomo` PID 18368 监听；系统代理开关为 0，不把已保存端口称作当前启用系统代理。

[Git 官方配置](https://git-scm.com/docs/git-config)定义了 curloptResolve、sslVerify 和 followRedirects；[官方凭据协议](https://git-scm.com/docs/gitcredentials)说明清空 helper 与 useHttpPath。实际安装 Git 为 2.45.1.windows.1，curl 为 8.21.0；当前选择复用 CLI/JDK/Python，不安装 Git 库或第三方服务。

## 下一小步

### 受管读取增量（仍非 Git 应用业务完成）

- `ImportProcessRunner` 为发现的子进程追加 PID/启动时间记录；后台资格/记录异常显式停止。正常退出后仍有已观察子进程存活时不返回成功。新增真实子进程停止、中途撤销、stderr 越限及未消费输出测试；50ms 观察不等于 OS 级完整进程容器隔离，尚需持久来源协调器和重启停止核验。
- `ImportGitReader` 使用独立 empty HOME/config/template 和显式 bare `--git-dir`，不向上发现开发仓库配置。取得全 heads 后按选中 SHA 拉取，核对实际 commit、严格完整树；原始 blob 流校验 Git SHA-1（含对象头）与长度，拒绝 LFS 指针、部分消费和错误长度。不 checkout、执行 filter 或工程脚本。
- GitHub 公开样本固定 `7fd1a60b01f91b314f59955a4e4d4e80d8edf11d`，实际读取 1 个 blob；两平台分支仍为 3/2。`git-managed-content-source-qualified-tests.log` 退出 0，reader 4、process 8、network 3，共 15 个测试无跳过。本地实际 Git 对象覆盖空、短、大文件、错误长度、未读完和 LFS；登记进程最后均核实退出。
- `git-managed-content-tests.log` 保留第一次 blob 流读失败；修正组合流 EOF/关闭及短文件部分消费校验后重新实测，不把失败隐藏。直连 DNS 固定配置必须对应当前来源 host，不能复用 GitHub DNS 资格访问 Gitee。
- fetch 有运行时磁盘额度/剩余空间检查，但这是周期检查，非文件系统硬配额；尚未接共享来源空间预留与任务准入。Gitee 此轮只读分支，未拉取其完整工程；私有认证仍 NOT_RUN。

上述读取器尚未注册成业务 HTTP 入口；尚未创建 Git 导入 App。下面的持久来源、同 UUID 主任务、资格门禁、凭据撤销、正式前端联调均待实现，不把 CLI/测试成功冒充整项验收。

1. 持久来源准备状态先于网络：归属、规范仓库、凭据版本、选项、branch/commit、实际资源与 executor。复用一个用户可见 UUID；内容清单未取得前不伪造文件、不创建 App。
2. 受管进程必须隔离宿主配置并登记 PID/启动时间，使用结构化参数；有界 stdout/stderr、时间/磁盘/实际停止检查。stderr 不直接进入通用事件或日志；askpass 秘密协议不与 Git 输出合并。
3. 所有分支取得后仅选择一个；受控 fetch 固定实际 commit，校验对象/模式/完整文件，直接读取 blob，不 checkout/filter/submodule/hook。原始 Git 元数据与真实来源快照分开，导入文件不包含 `.git`。
4. 完整清单取得后复用现有上传/封存/提交链路，但必须标记服务端 Git 来源与准备资格。现有任务定义要求完整清单，因此准备阶段须有独立持久记录；不以占位文件绕过校验，也不新增用户可见的第二套任务界面。
5. 来源准备与最终提交共享凭据/来源资格条件。不能仅依赖前端不知道内部 taskId；来源未完成或已撤销时，直接调用文件夹 commit 路由也不能发布。凭据行锁 → 来源资格 → 任务条件统一顺序，取消不持 DB 锁等待进程退出。
6. 替换/删除同时覆盖尚未生成完整清单的来源准备及已关联任务；真正停止核实后才结束清理，已成功 App 不误删。失败/重启保留资源记录和脱敏错误，不自动匿名重试、不轮试其他 Token。
7. 再接正式前端的真实分支/范围/凭据操作，移除生产中的占位适配；隔离预览仍走独立适配器。重新核对登录代次、来源版本、迟到响应、取消、桌面/窄屏与最终 App。

尚待核实：Gitee 私有 Git 的用户名取得与 PAT 认证必须采用官方支持路径。官方 Swagger 标注 access_token query，不按该标注把秘密塞 URL；Bearer/用户名取得尚未获得真实私有样本证据。不新增密码/SSH 输入，不猜身份，不宣称两个平台私有认证已通过。按用户要求真实私有验收暂缓，但实现和离线故障/泄漏测试继续。

然后推进第一项的识别/安装/多服务，涉及新界面仍先评审。当前旧 Agent/运行/发布执行门禁不解除；完整 58 组和跨项矩阵仍未通过。
