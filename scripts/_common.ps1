param([switch]$AsLibrary)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-Java21 {
    $candidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
        (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -match '^jdk-21' | Select-Object -First 1 -ExpandProperty FullName)
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } | Select-Object -Unique

    $javaHome = $null
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate 'bin\java.exe'
        $version = & cmd.exe /d /c ('"' + $java + '" -version 2>&1') 2>$null | Out-String
        if ($version -match 'version "21') {
            $javaHome = $candidate
            break
        }
    }
    if (-not $javaHome) { throw '未找到 JDK 21。请设置 JAVA_HOME 或安装 Eclipse Temurin 21。' }
    $env:JAVA_HOME = $javaHome
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
}

function Start-RequiredService([string]$Name) {
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if (-not $service) { throw "未找到 Windows 服务：$Name" }
    if ($service.Status -ne 'Running') { Start-Service -Name $Name }
    Write-Host "[$Name] $((Get-Service $Name).Status)" -ForegroundColor Green
}

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw "未找到命令：$Name" }
}

if (-not $AsLibrary) { Write-Host "ProjectRoot=$ProjectRoot" }
