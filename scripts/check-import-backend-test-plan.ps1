param([string]$PlanPath = '')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $PlanPath) { $PlanPath = Join-Path $root 'docs/PLANS/project-import-backend-test-cases.json' }
$plan = Get-Content -LiteralPath $PlanPath -Encoding UTF8 -Raw | ConvertFrom-Json
$rows = @(Get-Content -LiteralPath (Join-Path $root $plan.matrix) -Encoding UTF8 | Where-Object { $_ -match '^\| ([FGCUDSRE]\d{2}) \|' })
$ids = @{}
foreach ($case in $plan.cases) {
    if ($ids.ContainsKey($case.id)) { throw "重复测试 ID：$($case.id)" }
    $ids[$case.id] = $case
    if ($case.status -notin @('PASS','FAIL','NOT_RUN','BLOCKED','NOT_APPLICABLE')) { throw "非法状态：$($case.id)" }
    if ($case.status -eq 'PASS' -and @($case.evidence).Count -eq 0) { throw "PASS 缺少证据：$($case.id)" }
}
if ($ids.Count -ne $rows.Count) { throw '测试清单与原矩阵数量不一致' }
foreach ($row in $rows) {
    $cells = $row.Split('|')
    $id = $cells[1].Trim()
    $case = $ids[$id]
    if ($null -eq $case) { throw "缺少测试：$id" }
    if ($case.scenario -ne $cells[2].Trim() -or $case.expected -ne $cells[3].Trim() -or $case.layers -ne $cells[4].Trim()) {
        throw "测试清单已过期：$id"
    }
}
Write-Output "覆盖索引一致：$($rows.Count) 个矩阵整组；不代表业务通过。"
