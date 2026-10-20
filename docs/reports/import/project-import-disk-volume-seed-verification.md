# 受限卷冻结文件复制：2026-10-06 局部验证

## 范围与当前状态

第一项仍为 **NOT_COMPLETE**。本次实现 Linux helper 的 `seed` 操作，未接 Java 安装/构建/运行入口，未迁移数据库。根目录资源记录只是平台文件账本，不代替业务 owner/task/plan/context 资格。

`contentHash` 由调用者提供，绑定既有冻结版本。Java `ImportStartupSnapshotStore` 的摘要包含属性；本 helper **不会用文件摘要重新冒充 Java 内容版本**。正式接入须从已封存 `Snapshot.files` 导出清单，复核资格和版本后调用，不能信任工程自己提交的清单。

## 已实现

- 清单绑定 appId/contentHash/diskBytes/formatVersion；每文件包含 path/size/sha256/executable。
- 拒绝绝对路径、父级路径、重复和文件/目录冲突、超额、非法类型、链接和未列文件。
- 复制前确认真实受限挂载、目标为空且同应用无遗留容器；源与受限卷必须隔离。
- 先持久登记 SEEDING，再逐文件校验大小、SHA-256 和读取期间属性，落盘同步后登记 READY/SEALED。
- 每级源目录通过 NOFOLLOW 描述符打开，普通文件要求单链接；不执行来源中的任何脚本。
- 原身份同清单的重复请求只复用，不覆盖已安装变化；不同清单明确拒绝。
- 复制中断/摘要错误/磁盘异常不恢复成 READY，保留 SEEDING 和部分文件；需停止资源、核对并释放，不能直接重试覆盖。

## 当前版本证据

- 分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`；工作区有既存改动，HEAD 不代表本轮源码。文件 SHA-256 另记于外部 `disk-seed-version.json`。
- 原生 Linux 单元 **21 PASS / 0 SKIP**，退出码 0。
- 真实 WSL/ext4/Docker 专用夹具 **21 条局部检查 PASS**，退出码 0：包含复制二进制、非 root 读取/写产物、复用不覆盖，以及原有 ENOSPC/跨应用隔离/正常重挂载/容器阻止释放。
- 真实结果：`temp/import-disk-volume-1791225217698260100/result.json`。
- 外部日志：`D:\codex\WorkDocument\app-cube-review-20261005\disk-seed-unit-final.log`、`disk-seed-real-final.log`、`disk-seed-restart.log`。
- 专用卷、loop 与容器已清理；测试源目录另有 finally 清理。未修改原工程或其他应用。
- 测试先执行时因缺少 seed 实现退出 1；追加硬链接测试时测试代码误放状态断言导致 NameError，修正归属后全部通过。未减少断言。

重放（仓库根目录）：

```powershell
wsl.exe -d Ubuntu -u root --exec python3 -m unittest discover -s '/mnt/d/mainJava/all Code/program/glyahh-ai-generate-code/scripts/tests' -p test_import_disk_volume.py
python scripts/checks/verify-import-disk-volume.py
```

需要现有 Ubuntu、Linux root 执行入口、Docker 和 `glyahh-sandbox-runtime:24.04`；脚本不安装依赖、不拉镜像。

## 未完成与回退边界

1. Java 封存快照清单导出与受限卷客户端尚未接入；当前生产仍是 Windows 持久执行目录，无磁盘硬保护。
2. 清单目前只复制文件及执行位，不恢复原 ACL/POSIX 权限，也未保留空目录；不能称完整工程语义复制已交付。
3. owner/task/plan/context、执行租约和资源登记尚未接业务库；根操作依赖可信平台调用，不能对用户开放任意 source/manifest 参数。
4. 复制失败、掉电、WSL 重启失挂载的自动恢复仍待业务接入和真实验收；当前只保证未决不冒充可用。
5. 完整 R08、58 组业务矩阵、真实私库及其他三项均不计通过。

本次没有启用生产路径，不需要数据库回退。若有本轮专用卷残留，只对结果文件记录的 appId/contentHash/diskBytes 调用 helper `release`，确认容器已清理、真实卸载与 loop 消失；不手动递归删除挂载点，不全局清理 Docker/WSL。
