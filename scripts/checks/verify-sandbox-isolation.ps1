param(
    [ValidateSet('generate', 'import')][string]$Mode = 'generate',
    [string]$ContainerId = '',
    [string]$Image = 'glyahh-sandbox-runtime:24.04',
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\_common.ps1') -AsLibrary

function Write-Skip([string]$Reason) {
    $date = Get-Date -Format 'yyyyMMdd'
    $directory = Join-Path $ProjectRoot "docs\reports\evidence\$date-p2-sandbox"
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $file = Join-Path $directory "sandbox-$Mode-skip.json"
    @{
        mode        = $Mode
        status      = 'skip'
        reason      = $Reason
        generatedAt = (Get-Date).ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 $file
    Write-Host "SKIP: $Reason"
    exit 3
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Write-Skip 'docker CLI 不可用' }
& docker version --format '{{.Server.Version}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Skip 'Docker 守护进程不可用' }

$createdName = ''
if ($Mode -eq 'generate') {
    $createdName = 'glyahh-isolation-check-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $workspace = Join-Path $ProjectRoot 'temp\sandbox-check'
    New-Item -ItemType Directory -Force -Path $workspace | Out-Null
    & docker create --name $createdName --workdir /workspace --network bridge `
        --pids-limit 512 --cpus 2 --memory 4g --memory-swap 4g `
        --cap-drop ALL --security-opt no-new-privileges `
        -v "${workspace}:/workspace:rw" $Image sleep infinity
    if ($LASTEXITCODE -ne 0) { throw '创建沙箱核对容器失败' }
    & docker start $createdName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '启动沙箱核对容器失败' }
    $target = $createdName
} else {
    if (-not $ContainerId) { Write-Skip '未提供 -ContainerId，无法核对导入容器' }
    $target = $ContainerId
}

$date = Get-Date -Format 'yyyyMMdd'
$directory = Join-Path $ProjectRoot "docs\reports\evidence\$date-p2-sandbox"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$inspectFile = Join-Path $ProjectRoot 'temp\baseline\sandbox-inspect.json'
New-Item -ItemType Directory -Force -Path (Split-Path $inspectFile) | Out-Null

& docker inspect $target | Set-Content -Encoding UTF8 $inspectFile
if ($LASTEXITCODE -ne 0) { throw "读取容器 inspect 失败：$target" }

$uid = $null
$uidText = (& docker exec $target id -u 2>$null)
if ($LASTEXITCODE -eq 0 -and "$uidText".Trim() -match '^\d+$') { $uid = [int]"$uidText".Trim() }

$output = Join-Path $directory "sandbox-$Mode-$stamp.json"
$arguments = @('--inspect', $inspectFile, '--mode', $Mode, '--output', $output)
if ($null -ne $uid) { $arguments += @('--uid', $uid) }

& (Join-Path $ProjectRoot 'agent\.venv\Scripts\python.exe') `
    (Join-Path $ProjectRoot 'scripts\checks\assert-sandbox-inspect.py') @arguments
$code = $LASTEXITCODE

if ($createdName -and -not $Keep) {
    & docker rm -f $createdName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "容器清理未确认：$createdName" }
}

exit $code
