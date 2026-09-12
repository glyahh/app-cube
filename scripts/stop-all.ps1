$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary

function Stop-PortProcess([int]$Port) {
    $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $process.Id -Force
            Write-Host "已停止端口 $Port 的进程 PID=$($process.Id) ($($process.ProcessName))" -ForegroundColor Yellow
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
