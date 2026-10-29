param(
    [string[]]$Task = @(),
    [switch]$SkipAgent,
    [string]$WorkRoot = '',
    [string]$Output = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\_common.ps1') -AsLibrary

$arguments = @(Join-Path $ProjectRoot 'scripts\checks\run-benchmark.py')
foreach ($id in $Task) { $arguments += @('--task', $id) }
if ($SkipAgent) { $arguments += '--skip-agent' }
if ($WorkRoot) { $arguments += @('--work-root', $WorkRoot) }
if ($Output) { $arguments += @('--output', $Output) }

& (Join-Path $ProjectRoot 'agent\.venv\Scripts\python.exe') @arguments
exit $LASTEXITCODE
