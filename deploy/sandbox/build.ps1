# 构建运行时沙箱镜像。
#
# 用法（在仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File deploy/sandbox/build.ps1
#
# 镜像名与 agent 侧 AGENT_SANDBOX_RUNTIME_IMAGE 约定一致；
# docker_sandbox.py 没有校验运行时镜像，这个名字只在本仓库内部流转。

param(
    [string]$ImageName = 'glyahh-sandbox-runtime:24.04'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Get-DockerCmd {
    if (Get-Command docker -ErrorAction SilentlyContinue) { return @('docker') }
    # 与 agent/backends/docker_sandbox.py 的探测逻辑一致：本机没有 CLI 就走 WSL
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if ($wsl) { return @('wsl.exe', '-d', $env:AGENT_WSL_DISTRO ?? 'Ubuntu', '--', 'docker') }
    throw '未找到 docker CLI，也未检测到 WSL'
}

$docker = Get-DockerCmd

Write-Host "构建运行时沙箱镜像 $ImageName ..." -ForegroundColor Cyan
& $docker[0] @($docker[1..($docker.Length - 1)]) build `
    -t $ImageName `
    -f (Join-Path $PSScriptRoot 'Dockerfile') `
    (Join-Path $repoRoot 'deploy/sandbox')

if ($LASTEXITCODE -ne 0) { throw "镜像构建失败 (exit $LASTEXITCODE)" }

Write-Host "自检运行容器 ..." -ForegroundColor Cyan
& $docker[0] @($docker[1..($docker.Length - 1)]) run --rm $ImageName bash -lc `
    'node --version && python3 --version && java -version 2>&1 | head -1 && sqlite3 --version'

if ($LASTEXITCODE -ne 0) { throw '镜像自检失败' }

Write-Host "完成：$ImageName" -ForegroundColor Green
