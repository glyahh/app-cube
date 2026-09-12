$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary
Start-RequiredService 'Redis'
Write-Host 'Redis 已启动（默认端口 6379）。' -ForegroundColor Green
