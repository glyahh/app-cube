# 项目脚本

所有命令从仓库根目录执行。此目录同时包含服务入口、运行辅助程序和检查工具，不能把“不是启动命令”理解为没有用途。

| 位置 | 用途 | 本次处理 |
|---|---|---|
| 根目录 | 服务启动/停止、环境准备、运行辅助与维护 | 保留20个原有文件，路径和内容不变 |
| [checks/](checks/README.md) | 当前可复用的检查、真实验收、性能测量和门禁 | 收拢36个脚本及1份框架夹具锁文件，不属于废弃脚本 |
| tests/ | 脚本的回归测试 | 保留测试，跟随更新被测文件路径 |

原先整理到 `scripts/archive/` 的9个历史脚本已从工作区移除，不再作为当前工具入口；旧报告保留历史命令。

## 常用命令

```powershell
./scripts/start-all.ps1
./scripts/restart-backend.ps1
./scripts/stop-all.ps1
./agent/.venv/Scripts/python.exe scripts/debug-agent-trace.py --minutes 15
./agent/.venv/Scripts/python.exe -m pytest scripts/tests/test_import_framework_mapping.py scripts/tests/test_item1_complete_gate.py scripts/tests/test_item1_core_gate.py -q
```

启动脚本会使用本地配置并可能启动服务；只读或文档任务不运行它们。`checks/` 的真实验收可能创建测试账号、连接数据库、启动Docker或调用真实模型，先阅读对应脚本说明与参数，不批量执行目录内文件。

## 必须保留原路径的运行辅助

| 文件 | 实际调用方 |
|---|---|
| `import-git-askpass.py` | Java受管Git凭据代理 |
| `import-startup-python.py` | Java启动入口只读解析 |
| `import-startup-agent.py` | Java独立启动识别进程 |
| `import_disk_volume.py` | Java磁盘卷/执行管理及Python导入Backend |
| `import_pg_stdio.py` | Python原生Worker的PostgreSQL通道 |
| `import_worker_control.py` | Java原生Worker身份核对与停止 |

服务脚本 `_common.ps1`、`start-*.ps1`、`restart-*.ps1`、`stop-all.ps1`，环境准备 `setup-import-agent-runtime.py`、`allow-import-wsl-postgresql.ps1`，维护入口 `debug-agent-trace.py`、`cleanup-import-test-residue.py` 均保持原位。`test_import_startup_python.py` 继续在原位测试只读解析器。

`cleanup-import-test-residue.py` 默认dry-run，执行清理要求准确夹具报告；不因按目录分类而降低范围校验。环境准备和防火墙脚本虽无业务自动调用，仍有手工运维用途，不按“无引用”删除。

完整整理依据与本轮验证见[脚本整理报告](../docs/reports/workbench/scripts-layout-20261021.md)。当前能力见[项目状态](../docs/status/current.md)。
