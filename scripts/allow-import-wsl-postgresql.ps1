#Requires -RunAsAdministrator
param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$name = 'glyahh-item1-wsl-postgresql'
$description = 'AppCube item1 WSL-only PostgreSQL rule, managed 20261007'
$existing = Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
if ($Remove) {
    if ($existing) {
        if ($existing.Description -ne $description) { throw '同名规则不属于本脚本，不删除。' }
        Remove-NetFirewallRule -Name $name
    }
    Write-Output '专用规则已移除；未更改其他规则。'
    return
}

# 1. 只接受当前WSL默认网关对应的Windows接口，不开放全部网卡或数据库端口。
$routeText = & wsl.exe -d Ubuntu -u root --exec ip -4 route show default
if ($LASTEXITCODE -ne 0 -or $routeText -notmatch '^default via ([0-9.]+) ') { throw '未确认WSL网关。' }
$gateway = $Matches[1]
$addresses = @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -eq $gateway -and $_.InterfaceAlias -like 'vEthernet (WSL*' })
if ($addresses.Count -ne 1) { throw 'WSL接口身份不唯一，不添加规则。' }
$address = $addresses[0]
$prefix = [int]$address.PrefixLength
if ($prefix -lt 16 -or $prefix -gt 32) { throw 'WSL网段范围异常，不添加规则。' }
$bytes = [Net.IPAddress]::Parse($gateway).GetAddressBytes()
for ($i = 0; $i -lt 4; $i++) {
    $bits = [Math]::Min(8, [Math]::Max(0, $prefix - $i * 8))
    $mask = if ($bits -eq 0) { 0 } else { (255 -shl (8 - $bits)) -band 255 }
    $bytes[$i] = $bytes[$i] -band $mask
}
$network = ([Net.IPAddress]::new($bytes)).ToString() + '/' + $prefix
$owners = @(Get-NetTCPConnection -LocalPort 5432 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
if ($owners.Count -ne 1) { throw '数据库监听身份不唯一，不添加规则。' }
$process = Get-Process -Id $owners[0]
if ($process.ProcessName -ne 'postgres' -or [IO.Path]::GetFileName($process.Path) -ne 'postgres.exe') { throw '5432不是当前PostgreSQL进程，不添加规则。' }

# 2. 已有同名规则必须逐字段相同；不修改用户规则，不关闭防火墙。
if ($existing) {
    $port = $existing | Get-NetFirewallPortFilter
    $scope = $existing | Get-NetFirewallAddressFilter
    $app = $existing | Get-NetFirewallApplicationFilter
    if ($existing.Description -ne $description -or $existing.Action -ne 'Allow' -or $existing.Direction -ne 'Inbound' -or
        $port.Protocol -ne 'TCP' -or $port.LocalPort -ne '5432' -or $scope.LocalAddress -ne $gateway -or
        $scope.RemoteAddress -ne $network -or $app.Program -ne $process.Path) { throw '同名规则与冻结范围不同，不覆盖。' }
} else {
    New-NetFirewallRule -Name $name -DisplayName $name -Description $description -Direction Inbound -Action Allow -Enabled True `
        -Profile Any -Protocol TCP -LocalPort 5432 -LocalAddress $gateway -RemoteAddress $network -Program $process.Path | Out-Null
}
Write-Output "已核对专用规则：仅 $network -> ${gateway}:5432，限定当前postgres.exe。仍须实际连接复验。"
