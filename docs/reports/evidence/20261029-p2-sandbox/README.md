# P2 沙箱证据

执行日期：2026-10-29。

## 命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\checks\verify-sandbox-isolation.ps1 -Mode generate
```

## 结果

本机 `docker` 命令不可用，脚本按设计以退出码 3 结束，并写入 `sandbox-generate-skip.json`。这是阻塞，不是通过。

## 未验证

- 生成容器实际的 `NetworkMode`、`CapDrop`、`SecurityOpt`、`PidsLimit`、`Memory` 与运行时 uid。
- 导入编辑容器（`scripts/import_disk_volume.py:116`）实际的 `none` 网络、只读根与 `65534:65534` 用户。
- `scripts/checks/assert-sandbox-inspect.py` 只经过固定样本单测，没有对真实 inspect 输出运行过。

## 重新执行

Docker 可用后分别执行 `-Mode generate`；核对导入容器时传入 `-Mode import -ContainerId <容器 ID>`。退出码 0 才算完成。
