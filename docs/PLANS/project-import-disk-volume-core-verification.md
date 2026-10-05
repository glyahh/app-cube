# 受限持久磁盘卷核心：局部验收

日期：2026-10-06。第一项 **NOT_COMPLETE**。本轮仅实现独立卷核心，**没有接 Java 执行、发布、删除应用或资源恢复业务**。当前正式执行接口仍使用原 Windows 持久副本，不得宣称已获得本轮硬配额。

## 依据与复用边界

现有 `ImportExecutionRunner` 只在执行前后统计持久目录；生产 `ImportExecutionSandbox` 将目录绑定为可写 `/work`，运行中没有累计写入硬边界。

本机 Docker 使用 overlayfs/containerd，副本在 Windows 挂载目录；不假定宿主支持 XFS project quota。只读核对确认 WSL root、mkfs.ext4、mount、losetup 可用，无安装依赖、修改 Docker daemon、关闭 WSL 或改用户磁盘分区。

调研参考 [Moby loop 挂载议题](https://github.com/moby/moby/issues/37893)，不将历史议题当作当前环境验证。选择宿主原生固定大小 ext4 镜像/loop 挂载；实际可用性和容量边界以本轮容器行为为准，不使用简单计数、单文件 ulimit 或 tmpfs 冒充可持久的累计磁盘硬保护。

## 本次实现

- `scripts/import_disk_volume.py`：平台 Linux root 专用 create/inspect/release 核心。没有用户 HTTP 入口，不执行工程脚本。应用容器仍非 root、无额外设备或能力。
- 私有资源根固定 `/var/lib/glyahh/import-disks`，应用 ID 必须为合法正 Long，内容身份为 64 位十六进制摘要，额度为对齐的 1 MiB–8 GiB。路径不接受链接；不接管没有身份记录的旧文件。
- 先登记 PREPARING，再创建固定总块数的镜像与挂载；核对 ext4、loop 对应镜像、块设备真实大小与 nosuid/nodev。成功才记 READY。同应用内容身份/额度变化明确冲突，不覆盖旧产物。
- 复用或检查遇到 PREPARING/RELEASING、不确定挂载或不匹配容量时明确失败，不伪装可用。命令失败/超时保留未解决身份。
- 释放先确认同应用容器全部清理；不使用 force/lazy 卸载。卸载成功且 loop 实际解绑后才删除该私有应用目录，挂载/引擎状态未知则保留资源。
- 本轮使用全局文件锁串行化卷操作。只复用第一项所需能力，不展开其他三项；没有 UI、Java 业务或数据库修改，不提交、推送或发布。

内容摘要在本核心仅是调用者提供的冻结身份，不代表卷内文件已经与原工程复核。原工程复制、owner/task/plan/execution/context 资格复核和资源账本均须在后续业务接入时实现。

## 实际验证与版本

分支 code_board，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，保留现有脏工作区。三个文件 SHA-256、实际退出码和最终证据路径见 `D:\codex\WorkDocument\app-cube-review-20261005\disk-core-version.json`。

| 命令/检查 | 退出码 | 结果 |
|---|---:|---|
| 初次 `agent\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -p test_import_disk_volume.py` | 1 | 先编写测试，核心尚未存在；`disk-core-before.log` |
| 实现后同 Windows 命令（早期四项） | 0 | 3 PASS/1 SKIP，Windows 无符号链接条件；不是最终九项结果 |
| `wsl.exe -d Ubuntu -u root --exec python3 -m unittest discover -s '/mnt/d/mainJava/all Code/program/glyahh-ai-generate-code/scripts/tests' -p test_import_disk_volume.py` | 0 | 最终 **9 PASS、0 SKIP**，`disk-core-linux-unit.log` |
| `agent\.venv\Scripts\python.exe scripts/verify-import-disk-volume.py` | 0 | 最终 **11 条真实容器/文件系统局部检查 PASS**，`disk-core-real-final.log` |

最终真实证据：`temp/import-disk-volume-1791217681897915000/result.json`，包括每步完整 argv、退出码、stdout/stderr、容量、资源身份与清理结果。仅使用两个专用合成应用 ID `9000000681900909700`、`9000000681900909701`；它们不是业务库项目/任务，也没有模拟业务 owner 或会话验收通过。

真实环境：WSL Ubuntu、现有 Python 3.14.4、Docker 与已有 `glyahh-sandbox-runtime:24.04` 镜像。夹具容器无网络、只读 rootfs、uid/gid 65534、cap-drop ALL、no-new-privileges，并限制内存/CPU/进程。没有导入或执行用户原工程。

核心真实证明：

1. 两个 loop 块设备实际大小均为 **67108864 字节**。
2. 容器尝试累计写入 100 MiB，在 **60817408 字节**时收到 **ENOSPC（errno 28）**，而不是程序写完后由平台统计判失败。文件系统元数据也计入 64 MiB 总额度，可用数据容量小于总容量。
3. 写满不破坏之前的 `installed.txt`；删除旧容器、换新容器后仍能读取产物，不重新初始化卷。
4. 第二个应用卷不能看到首卷文件，仍可以写入；改变内容身份被拒绝且不覆盖旧资源。
5. 尚有同应用容器时拒绝释放；最后容器、挂载、loop 与两个私有目录实际清理完成，仅保留平台根目录及锁文件。

故障单元还覆盖创建中断保留 PREPARING、未知挂载/引擎失败不删除资源、容量不匹配不接管、非法动作不转为释放。

## 后续必做与未执行

后续前置增量见 [预留空间与暂停/恢复](project-import-disk-volume-recovery-verification.md)：当前核心已改为 v2 journal、实际预分配与受控重挂载。以下保留 v1 时点的未完成描述；生产业务未接的边界仍未解除。

- **生产未接**：冻结副本复制、卷资源持久登记与租约、安装/构建/服务共享卷、原工程保护、应用删除/版本变化/重启对账和迟到结果资格必须接入后独立验收。
- 尚无全局容量预留或卷数量/宿主空间预算。镜像按需占用宿主空间；单卷硬边界不能保证宿主不会被多个卷耗尽。
- 本核心创建无 journal 的 ext4 以兼容小额度；没有执行掉电、WSL 重启、文件系统损坏与数据恢复验收。不能宣称崩溃后产物完整；生产接入前须明确持久性方案并验证。
- 不自动重建丢失挂载，不清掉未知资源来解除阻塞。资源恢复、同应用并发与正在使用中的释放仍需进一步真实验证。
- R08 整组保持 NOT_RUN；既有 Java/HTTP/浏览器矩阵未重跑，本轮不沿用其数字。Java 后端未改，不做无关重启或迁移。私有仓库验收继续暂缓。

## 使用与回退

只运行专用验收脚本重现核心行为。脚本先核对专用资源命名空间不存在，再创建两个卷，最后只清理自己分配的容器名/卷；命令、清理失败明确标 FAIL。

排查残留时使用证据中的 appId/contentHash/bytes 调用 helper inspect/release。先停止并删除对应容器，再 release；不要对全局 Docker、WSL 或用户目录批量清理，不直接删除仍挂载的镜像。

当前 Java/前端业务不调用此核心；回退可仅撤销三个新增脚本/测试及文档增量。先按记录释放专用资源，保留用户原工程、历史改动和验收证据；不执行 git reset。
