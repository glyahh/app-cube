$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary
Assert-Command 'npm'
$frontend = Join-Path $ProjectRoot 'ai-generate-code-frontend'
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Write-Host '未发现 node_modules，先执行 npm install...' -ForegroundColor Yellow
    Push-Location $frontend; npm install; Pop-Location
}
Push-Location $frontend
try { npm run dev -- --host 0.0.0.0 }
finally { Pop-Location }
