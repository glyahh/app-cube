$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary
$service = Get-Service -Name 'postgresql-x64-18' -ErrorAction SilentlyContinue
if (-not $service) { throw '未找到 PostgreSQL 服务 postgresql-x64-18' }
Start-RequiredService 'postgresql-x64-18'
Write-Host 'PostgreSQL 已启动（默认端口 5432）。' -ForegroundColor Green
