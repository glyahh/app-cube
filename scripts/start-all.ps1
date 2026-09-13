$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary
Resolve-Java21
Start-RequiredService 'Redis'
Start-RequiredService 'postgresql-x64-18'
Assert-Command 'npm'
$frontend = Join-Path $ProjectRoot 'ai-generate-code-frontend'
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Push-Location $frontend; npm install; Pop-Location
}
$shell = (Get-Process -Id $PID).Path
$backendScript = (Join-Path $PSScriptRoot 'start-backend.ps1')
$frontendScript = (Join-Path $PSScriptRoot 'start-frontend.ps1')
# 路径包含空格时，-File 参数必须显式加引号，否则子窗口会立即退出。
# `Start-Process -ArgumentList` 会把数组元素重新拼接；路径含空格时，
# 仅给 -File 参数单独加引号在部分 Windows PowerShell 版本会被再次剥离。
# 传入完整参数字符串，确保脚本路径作为一个参数交给子进程。
$backendArgs = '-NoExit -ExecutionPolicy Bypass -File "' + $backendScript + '"'
$frontendArgs = '-NoExit -ExecutionPolicy Bypass -File "' + $frontendScript + '"'
Start-Process -FilePath $shell -ArgumentList $backendArgs -WorkingDirectory $ProjectRoot
Start-Process -FilePath $shell -ArgumentList $frontendArgs -WorkingDirectory $ProjectRoot
Write-Host '已启动后端窗口和前端窗口，等待服务监听...' -ForegroundColor Yellow
$deadline = (Get-Date).AddSeconds(60)
do {
    Start-Sleep -Seconds 2
    $frontendReady = [bool](Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue)
    $backendReady = [bool](Get-NetTCPConnection -LocalPort 8124 -State Listen -ErrorAction SilentlyContinue)
} while ((-not ($frontendReady -and $backendReady)) -and (Get-Date) -lt $deadline)
if (-not $frontendReady) { Write-Warning '前端尚未监听 5173，请查看前端窗口日志。' }
if (-not $backendReady) { Write-Warning '后端尚未监听 8124，请查看后端窗口日志。' }
if ($frontendReady -and $backendReady) { Write-Host '前后端均已监听。Python Agent 由 Java AgentWorkerPool 自动拉起。' -ForegroundColor Green }
Write-Host '后端：http://localhost:8124/api  前端：http://localhost:5173' -ForegroundColor Cyan
