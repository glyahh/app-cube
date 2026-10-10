param(
    [int]$Port = 8124,
    [int]$TimeoutSeconds = 180,
    [string]$LogDirectory = ''
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

# 1. 保留后台启动会话，避免开发执行器回收子进程；启动失败则显式退出。
$escapedBackendScript = $backendScript.Replace("'", "''")
$argumentList = '-NoExit -NoProfile -ExecutionPolicy Bypass -Command "try { & ''' + $escapedBackendScript + ''' } catch { Write-Error $_; exit 1 }"'
$redirect = @{}
if ($LogDirectory) {
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    $redirect.RedirectStandardOutput = Join-Path $LogDirectory 'backend.stdout.log'
    $redirect.RedirectStandardError = Join-Path $LogDirectory 'backend.stderr.log'
}
$launcher = Start-Process -FilePath $shell -ArgumentList $argumentList -WindowStyle Hidden -PassThru @redirect
$null = $launcher.Handle

Write-Host "后端启动中，等待端口 $Port ..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$ready = $false
do {
    Start-Sleep -Seconds 2
    $launcher.Refresh()
    if ($launcher.HasExited) {
        throw "后端启动进程已退出，退出码 $($launcher.ExitCode)，请查看启动日志。"
    }
    # 2. 端口监听后还须确认 Spring 返回本项目的结构化响应，不把监听当业务验收。
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        try {
            $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/user/get/login" -TimeoutSec 3
            $ready = $response.code -in @(20000, 40100)
        } catch { $ready = $false }
    }
} while (-not $ready -and (Get-Date) -lt $deadline)

if (-not $ready) {
    throw "后端在 $TimeoutSeconds 秒内未就绪；启动进程 PID=$($launcher.Id) 仍在运行，未自动重启或终止，请查看日志。"
}

Write-Host "后端已重启：http://localhost:$Port/api" -ForegroundColor Green
