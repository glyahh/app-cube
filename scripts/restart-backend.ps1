param(
    [int]$Port = 8124,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary

function Stop-PortProcess([int]$TargetPort) {
    $connections = @(Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $process.Id -Force
            Write-Host "已停止端口 $TargetPort 的后端进程 PID=$($process.Id) ($($process.ProcessName))" -ForegroundColor Yellow
        }
    }
}

Stop-PortProcess $Port

$shell = (Get-Process -Id $PID).Path
$backendScript = Join-Path $PSScriptRoot 'start-backend.ps1'
if (-not (Test-Path $backendScript)) {
    throw "未找到后端启动脚本：$backendScript"
}

# Run the backend in a separate PowerShell window so this script can wait for readiness.
$argumentList = '-NoExit -ExecutionPolicy Bypass -File "' + $backendScript + '"'
Start-Process -FilePath $shell -ArgumentList $argumentList

Write-Host "后端启动中，等待端口 $Port ..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$ready = $false
do {
    Start-Sleep -Seconds 2
    $ready = [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
} while (-not $ready -and (Get-Date) -lt $deadline)

if (-not $ready) {
    throw "后端在 $TimeoutSeconds 秒内未监听端口 $Port，请查看后端窗口日志。"
}

Write-Host "后端已重启：http://localhost:$Port/api" -ForegroundColor Green
