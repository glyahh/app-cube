param(
    [switch]$SkipJava,
    [switch]$SkipPython,
    [switch]$SkipFrontend,
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary

$sha = (& git -C $ProjectRoot rev-parse HEAD).Trim()
$dirty = [bool](& git -C $ProjectRoot status --porcelain)
$workDirectory = Join-Path $ProjectRoot 'temp\baseline'
New-Item -ItemType Directory -Force -Path $workDirectory | Out-Null

if (-not $OutputDirectory) {
    $date = Get-Date -Format 'yyyyMMdd'
    $OutputDirectory = Join-Path $ProjectRoot "docs\reports\evidence\$date-p0-baseline"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$junit = Join-Path $workDirectory 'pytest.xml'
$vitest = Join-Path $workDirectory 'vitest.json'
$surefireDirectory = Join-Path $ProjectRoot 'target\surefire-reports'
$javaExit = 0
$pythonExit = 0
$frontendExit = 0

# 清除上次报告，避免编译或收集中断时把旧报告当成本次结果。
if (Test-Path $surefireDirectory) { Remove-Item $surefireDirectory -Recurse -Force }
foreach ($file in @($junit, $vitest)) { if (Test-Path $file) { Remove-Item $file -Force } }

Push-Location $ProjectRoot
try {
    if (-not $SkipJava) {
        & (Join-Path $ProjectRoot 'mvnw.cmd') -q test
        $javaExit = $LASTEXITCODE
    }
    if (-not $SkipPython) {
        & (Join-Path $ProjectRoot 'agent\.venv\Scripts\python.exe') -m pytest agent/tests -q "--junitxml=$junit"
        $pythonExit = $LASTEXITCODE
    }
    if (-not $SkipFrontend) {
        Push-Location (Join-Path $ProjectRoot 'ai-generate-code-frontend')
        try {
            & npm run test:unit -- --reporter=json "--outputFile=$vitest"
            $frontendExit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
    }

    $previous = Get-ChildItem $OutputDirectory -Filter 'baseline-*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $arguments = @(
        '--surefire', $surefireDirectory,
        '--junit', $junit,
        '--vitest', $vitest,
        '--java-exit', $javaExit,
        '--python-exit', $pythonExit,
        '--frontend-exit', $frontendExit,
        '--sha', $sha,
        '--output', (Join-Path $OutputDirectory "baseline-$sha.json")
    )
    if ($dirty) { $arguments += '--dirty' }
    if ($previous) { $arguments += @('--previous', $previous.FullName) }

    & (Join-Path $ProjectRoot 'agent\.venv\Scripts\python.exe') (Join-Path $ProjectRoot 'scripts\checks\summarize-test-results.py') @arguments
    $summaryExit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($summaryExit -ne 0) { exit 1 }
