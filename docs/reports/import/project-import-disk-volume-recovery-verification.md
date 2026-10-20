# 受限卷预留空间与暂停/恢复：局部验收

日期：2026-10-06。第一项 **NOT_COMPLETE**。本轮补齐生产接入前置条件；**Java 安装/构建/运行接口仍未使用此卷**，不宣称正式执行已受磁盘硬保护。

## 核对后调整范围

原卷核心是无 journal 的稀疏镜像，不能直接拿去承担生产持久产物和宿主空间预留。先沿用既有 helper 补齐 formatVersion=2、真实预分配、文件系统校验和受控卸载/重挂载；原工程冻结复制、执行/资源账本、owner/task/plan/context 资格仍须后续业务接入。

无新增 UI、依赖、数据库迁移或其他三项能力；没有提交、推送或部署。旧核心记录保留其时点结果，不代表当前 v2 已覆盖完整业务。

## 实现与权限边界

- v2 镜像通过 posix_fallocate 预留实际块，并核对 st_blocks；创建前保留至少 256 MiB 宿主余量，与现有 ImportProperties 默认 minFreeBytes 数值一致，但尚未接配置传递。空间不足或预分配失败明确失败，不退回稀疏镜像。
- 使用有 journal 的 ext4，最低 16 MiB；更小额度明确拒绝，不去掉 journal 降低标准。mkfs 指定 nodiscard 和同步初始化，避免格式化将预分配块打洞；检查时同时核对 journal、镜像/loop 真实容量与块预留。
- formatVersion=2 参与身份匹配，旧格式不自动升级或覆盖。其旧产物不能以新格式已验证冒充。
- suspend 必须先确认应用容器清理，记录 SUSPENDING，再卸载并核对 loop 解绑，最后才记 SUSPENDED；不删除产物。
- resume 只接受同身份 SUSPENDED 卷且没有同应用容器/残余 loop。先记 RESUMING，再 e2fsck；退出码 0 或 1才允许挂载，实际检查退出码保留。其他退出码、超时或未知资源不能变 READY，不重建卷解除阻塞。
- 完成挂载后重新核对大小、来源、权限与 journal，才更新 READY。部分阶段保留未解决身份；已有 release 仍不强制卸载，不跟链接删除，不清理他人资源。

256 MiB 只是本 helper 的准入余量，不代表宿主总容量预算或能控制其他进程写入。生产接入仍需按实际配置和并发资源策略验证。

## 当前版本与实际命令

分支 code_board，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，保留用户已有改动。当前 helper、测试与夹具 SHA-256、退出码、专用身份和证据路径见 `D:\codex\WorkDocument\app-cube-review-20261005\disk-recovery-version.json`。

| 验证 | 退出码 | 结果 |
|---|---:|---|
| 新恢复测试先行 | 1 | 新能力尚未实现，`disk-recovery-before.log` |
| 首次实现后的回归 | 1 | 局部变量与 backing 方法重名，触发 UnboundLocalError；保留 `disk-recovery-unit.log` |
| 修复后的原生回归 | 0 | 15 项通过，`disk-recovery-unit-final.log` |
| 补宿主余量边界后的最终回归 | 0 | **16 PASS、0 SKIP**，`disk-recovery-unit-admission.log` |
| 完整受控卷脚本 | 0 | **17 条真实检查 PASS**，`disk-recovery-real.log` |

原生单元命令：`wsl.exe -d Ubuntu -u root --exec python3 -m unittest discover -s '/mnt/d/mainJava/all Code/program/glyahh-ai-generate-code/scripts/tests' -p test_import_disk_volume.py`。

真实命令：`agent\.venv\Scripts\python.exe scripts/checks/verify-import-disk-volume.py`。

最终真实证据：`temp/import-disk-volume-1791218388454389100/result.json`，包含每步 argv、退出码、stdout/stderr。仅使用两个合成测试 ID `9000000388455386700`、`9000000388455386701`，不是业务库项目/任务，没有 owner/session 验收结论。

实测两卷实际块设备大小均为 67108864 字节，宿主预留各 67112960 字节（含宿主文件元数据开销）；journal=true。累计写入在 56623104 字节收到 ENOSPC，既有安装文件保留。首卷真实卸载、loop 解绑，随后文件系统校验退出码 0、重新挂载并更换容器，安装文件仍完整；未挂载时 inspect 明确拒绝。双卷隔离、身份冲突、容器未清理拒绝释放和最终清理均通过。两个测试卷及容器已经释放。

最终新增单元覆盖：旧格式拒绝、更小 journal 额度拒绝、预分配失败、宿主余量不足、缺 journal、活动容器阻止恢复，以及 e2fsck 退出 4 时不挂载、不变 READY。首次变量重名失败是本次开发问题，已修正后重跑，不删除失败用例或沿用旧通过数字。

按仓库修复规则执行标准后端重启：`powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory D:\codex\WorkDocument\app-cube-review-20261005\restart-disk-recovery`。脚本报告完成，8124 新监听 PID 44040；外层会话退出码未捕获，记录 NOT_CAPTURED，不填 0。日志 `disk-recovery-restart.log`。Java/业务接口未改，本轮没有重新执行其 35 条历史 API 检查；监听不替代业务验收。

## 未完成与运行/回退

本轮只证明正常卸载/重挂载及明确失败门禁，不证明掉电、WSL/平台崩溃或任意损坏后的数据完整。没有自动恢复未知中间态、没有演练 e2fsck 真正修复损坏卷；其失败分支目前为单元验证。

正式业务仍需冻结副本复制、权限/租约/资源记录、共享安装/构建/服务卷、版本变化/删除应用/平台重启对账、迟到结果隔离以及宿主总预算。R08 整组与跨项联动保持 NOT_RUN；第一项其余缺口和暂缓私有仓库验收仍保留。

复现仅运行上述专用夹具。未知挂载或检查失败时保留记录，不能手改 READY、删除镜像或自动重新安装。回退先停止本次专用容器并用当前 v2 helper 释放对应卷，再撤销本轮代码增量；不将 v2 活跃资源交给旧版本强行接管，不清理全局 Docker/WSL，不修改原工程或用户 Git 历史。
