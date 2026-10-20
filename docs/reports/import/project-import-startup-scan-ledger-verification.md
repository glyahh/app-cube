# 持久扫描任务：状态与资源账本预检

2026-10-04；`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。仅第一项的内部增量，执行流程和 HTTP 尚未接入；不是可用扫描任务交付。

## 实际新增

- `ImportStartupScan` 冻结 request/scan/app/owner/import task、scope、配置/epoch、执行者 UUID/PID/启动时间与期限。资源 PID/时间不可缺失，集合只读；公开 View 不暴露执行者或资源账本。
- queued/scanning/cancelling/stop_failed/recovery_required 保留准入占用。取消中不能变成成功，终态不能重跑，未确认停止/清理不能伪装 CANCELLED/FAILED/STALE/SUCCEEDED；状态值和调用者传入 DONE 本身仍不是实际停止证明。
- `ImportStartupScanRepository` 保存请求幂等身份、短事务准入锁、owner 读取、当前配置/实际 App owner/READY/期限资格、资源追加和租约 CAS。扫描数量准入只覆盖本扫描表，不冒称导入来源、上传、Docker、全磁盘/CPU/内存统一硬额度。
- 同 request 不重复创建；不同 frozen input 不复用。开始/成功提交 SQL 再核对当前业务与 epoch，不只依赖此前查询。取消改变 revision，旧租约不能追加资源或提交成功。
- V21 草案包含外键、owner/request 唯一、版本/状态/终态清理约束。目前 **尚未应用**，没有新任务 API、执行 worker、真实取消、停止核验或重启恢复。

## 实际验证

```powershell
./mvnw.cmd -q test '-Dtest=ImportStartupScanTest'
./mvnw.cmd -q test '-Dtest=ImportStartupScanTest,ImportStartupScanRepositoryTest'
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-scan-schema.py
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
```

证据根 `temp/import-startup-scan-20261004/`：

- `state-repository-unit.log`、`combined-regression.log` 退出 0。新增 13 项，完整组合 **36 类 336 项，335 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**；最终 XML 在 `junit-final/`。原 Git 网络条件测试未启用；警告/预期异常不抹除。Repository 单元使用明确 JdbcTemplate Mock，不冒充任务端到端。
- `schema-preflight-qualified.log` 退出 0、`schema-preflight.json` **5 组 PASS**：真实 Postgres 独立 schema 执行 V21 DDL和从 Repository 当前源文件提取的 SQL，验证插入/唯一、真实资格/外用户/旧 rev、成功提交时重新核对 epoch、取消撤销、终态清理约束。仅临时 scratch 行变化；全部事务回滚，既有业务配置不修改。
- `database-boundary.json`：仍为 Flyway20，正式扫描表不存在，预检 schema 残留数0。未重启后端，不把当前进程当作已加载新扫描流程。下一次标准启动会自动执行 V21，接业务流程前仍须继续检查 schema 与实际行为；一旦应用不得改写该迁移。
- `source-manifest.json` 固定本步源/脚本 SHA256。当前 PID23432 的 V20 完成证据重启找回属于[上一节点](project-import-startup-record-verification.md)，没有在本步重新执行该 API 流程或前端检查。

## 下一步及回退边界

继续接入：创建/执行和只读找回，统一 snapshot/helper 身份、实际资源登记、取消/迟到门禁、STOP_FAILED 与重启资源核对、单应用/全局硬额度，再接只读 Agent 与正式工作台。不能让同步旧检查绕过新任务准入，也不能把未停止资源自动释放或改为 CANCELLED。

本步仅增加内部类和未应用迁移，没有打开导入工程执行权限。没有改原工程/保护字节、清理用户工作区或提交 Git。未应用的草案可继续依据集成事实调整；已应用 V12–V20 不改写或回滚，已有数据保留。

第一项 **NOT_COMPLETE**；完整任务取消/恢复、安装/构建/多服务、真实私有成功和四功能整体联动仍未验收，其余三项不展开。
