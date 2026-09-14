[CmdletBinding()]
param(
    [ValidateRange(10, 300)]
    [int]$BackendTimeoutSeconds = 120,
    [ValidateRange(10, 120)]
    [int]$FrontendTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary

$stopScript = Join-Path $PSScriptRoot 'stop-all.ps1'
$backendScript = Join-Path $PSScriptRoot 'start-backend.ps1'
$frontendScript = Join-Path $PSScriptRoot 'start-frontend.ps1'

foreach ($script in @($stopScript, $backendScript, $frontendScript)) {
    if (-not (Test-Path $script)) {
        throw "未找到脚本：$script"
    }
}

function Test-HttpReady([string]$Url) {
    try {
        Invoke-WebRequest -Uri $Url -Method Get -UseBasicParsing -TimeoutSec 3 | Out-Null
        return $true
    } catch {
        # 连接失败或 HTTP 4xx/5xx 都不能代表服务已经就绪。
        return $false
    }
}

function Wait-HttpReady([string]$Url, [int]$TimeoutSeconds, [string]$ServiceName) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpReady $Url) {
            Write-Host "$ServiceName 已就绪：$Url" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "$ServiceName 在 $TimeoutSeconds 秒内未就绪，请查看对应启动终端日志。"
}

Write-Host '=== 停止项目服务 ===' -ForegroundColor Yellow
& $stopScript

# 给端口和 Windows 服务留出时间，避免新进程抢占旧连接。
Start-Sleep -Seconds 2

Write-Host '=== 启动依赖服务 ===' -ForegroundColor Cyan
Start-RequiredService 'Redis'
Start-RequiredService 'postgresql-x64-18'
Assert-Command 'npm'

$shell = (Get-Process -Id $PID).Path
$backendArgs = '-NoExit -ExecutionPolicy Bypass -File "' + $backendScript + '"'
$frontendArgs = '-NoExit -ExecutionPolicy Bypass -File "' + $frontendScript + '"'

Write-Host '=== 启动后端（前端将在后端就绪后启动） ===' -ForegroundColor Cyan
# 不隐藏窗口，保留 Spring Boot 实时日志，方便定位启动失败原因。
Start-Process -FilePath $shell -ArgumentList $backendArgs -WorkingDirectory $ProjectRoot
Wait-HttpReady 'http://127.0.0.1:8124/api/actuator/health' $BackendTimeoutSeconds '后端'

Write-Host '=== 启动前端 ===' -ForegroundColor Cyan
# 只有后端 API 可访问后才打开前端，避免 Vite 代理先报 ECONNREFUSED。
Start-Process -FilePath $shell -ArgumentList $frontendArgs -WorkingDirectory $ProjectRoot
Wait-HttpReady 'http://127.0.0.1:5173/' $FrontendTimeoutSeconds '前端'

Write-Host '=== 一键重启完成 ===' -ForegroundColor Green
Write-Host '后端：http://localhost:8124/api  前端：http://localhost:5173' -ForegroundColor Cyan
