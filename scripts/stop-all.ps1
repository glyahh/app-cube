$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary

function Stop-PortProcess([int]$Port) {
    # 查询和停止分别容错：某个端口由管理员进程占用时，不能让整个脚本直接退出。
    if (-not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
        Write-Warning '当前 PowerShell 未提供 Get-NetTCPConnection，跳过端口进程停止。'
        return
    }
    $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)

    foreach ($connection in $connections) {
        try {
            $process = Get-Process -Id $connection.OwningProcess -ErrorAction Stop
            Stop-Process -Id $process.Id -Force -ErrorAction Stop
            Write-Host "已停止端口 $Port 的进程 PID=$($process.Id) ($($process.ProcessName))" -ForegroundColor Yellow
        } catch {
            # 非管理员窗口无法终止管理员进程时，保留告警并继续处理其他端口/服务。
            Write-Warning "无法停止端口 $Port 的进程 PID=$($connection.OwningProcess)：$($_.Exception.Message)"
        }
    }
}

# 只关闭本项目约定的服务端口，不影响其他项目进程。
Stop-PortProcess 8124
Stop-PortProcess 5173

foreach ($name in @('Redis', 'postgresql-x64-18')) {
    $service = Get-Service -Name $name -ErrorAction SilentlyContinue
    if ($service -and $service.Status -eq 'Running') {
        try {
            Stop-Service -Name $name
            Write-Host "已停止服务：$name" -ForegroundColor Yellow
        } catch {
            Write-Warning "无法停止服务 $name（可能需要管理员权限）：$($_.Exception.Message)"
        }
    }
}

Write-Host '项目后端、前端及依赖服务已关闭。' -ForegroundColor Green
