$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_common.ps1') -AsLibrary
Resolve-Java21
if (-not (Test-Path (Join-Path $ProjectRoot 'mvnw.cmd'))) { throw '未找到 mvnw.cmd' }
# OSS 客户端在 Spring 启动阶段创建，缺少凭证会导致应用启动后立即退出。
# 提前检查，避免启动窗口只显示一闪而过的 Spring 异常。
$envFile = Join-Path $ProjectRoot '.env'
if (-not (Test-Path $envFile)) { throw "未找到本地配置文件：$envFile。请先复制 .env.example 为 .env 并填写真实配置。" }
$envText = Get-Content $envFile -Raw
foreach ($key in @('ALIYUN_OSS_ENDPOINT','ALIYUN_OSS_ACCESS_KEY_ID','ALIYUN_OSS_ACCESS_KEY_SECRET','ALIYUN_OSS_BUCKET_NAME')) {
    if ($envText -notmatch "(?m)^\s*$key\s*=\s*[^\r\n#]+") {
        throw "本地 .env 缺少 $key。OSS 是后端启动必需配置，请填写后重新运行。"
    }
}
# PostgreSQL 使用 SCRAM 认证时密码不能为空；否则 Spring 会在 Flyway 初始化阶段退出。
if ($envText -notmatch '(?m)^\s*POSTGRES_PASSWORD\s*=\s*[^\r\n#\s]+\s*(?:#.*)?$') {
    throw '本地 .env 的 POSTGRES_PASSWORD 为空或缺失。请填写 PostgreSQL 用户 glyahh_biz 的密码后重新运行。'
}
Write-Host "使用 JDK：$env:JAVA_HOME" -ForegroundColor Cyan
Push-Location $ProjectRoot
try { & .\mvnw.cmd spring-boot:run }
finally { Pop-Location }
