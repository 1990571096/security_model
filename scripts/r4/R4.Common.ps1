$ErrorActionPreference = 'Stop'

$script:R4RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:R4Tmp = Join-Path $script:R4RepoRoot 'tmp'
$script:R4Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$script:R4RabbitHome = Join-Path $script:R4Tmp 'rabbitmq-4.1.8\rabbitmq_server-4.1.8'
$script:R4ErlangHome = 'C:\Program Files\Erlang OTP'

function Assert-R4SchemaName([string]$SchemaName) {
    if ($SchemaName -notmatch '^security_model_r4_[a-z0-9_]+$') {
        throw "拒绝操作：schema 必须匹配 ^security_model_r4_[a-z0-9_]+$，当前=$SchemaName"
    }
    if ($SchemaName -eq 'eladmin') {
        throw '拒绝操作：R4 验收不得连接或修改 eladmin'
    }
}

function Assert-R4Vhost([string]$Vhost) {
    if ($Vhost -notmatch '^security_model_r4_[a-z0-9_]+$') {
        throw "拒绝操作：vhost 必须匹配 ^security_model_r4_[a-z0-9_]+$，当前=$Vhost"
    }
}

function Invoke-R4Mysql([string]$SchemaName, [string]$Sql, [string]$RootPassword) {
    Assert-R4SchemaName $SchemaName
    $args = @('-uroot', "-p$RootPassword", '--default-character-set=utf8mb4', '-N', '-B', '-e', $Sql, $SchemaName)
    & $script:R4Mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败，schema=$SchemaName，exit=$LASTEXITCODE" }
}

function Invoke-R4MysqlFile([string]$SchemaName, [string]$Path, [string]$RootPassword) {
    Assert-R4SchemaName $SchemaName
    if (!(Test-Path -LiteralPath $Path)) { throw "SQL 文件不存在: $Path" }
    $asciiDir = Join-Path $env:TEMP 'r4-sql'
    New-Item -ItemType Directory -Force -Path $asciiDir | Out-Null
    $asciiPath = Join-Path $asciiDir (Split-Path -Leaf $Path)
    Copy-Item -LiteralPath $Path -Destination $asciiPath -Force
    $source = $asciiPath.Replace('\', '/')
    $args = @('-uroot', "-p$RootPassword", '--default-character-set=utf8mb4', '-D', $SchemaName,
        '-e', "SOURCE $source")
    & $script:R4Mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql SOURCE 失败，file=$Path，exit=$LASTEXITCODE" }
}

function Get-R4Secret {
    param([string]$Path)
    if (!(Test-Path -LiteralPath $Path)) { throw "凭据文件不存在: $Path" }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function New-R4Secret {
    param([string]$Path)
    $alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $chars = for ($i = 0; $i -lt 32; $i++) { $alphabet[$bytes[$i] % $alphabet.Length] }
    $password = -join $chars
    $object = [ordered]@{
        schema = 'security_model_r4_' + (Get-Date -Format 'yyyyMMddHHmmss')
        vhost = 'security_model_r4_' + (Get-Date -Format 'yyyyMMddHHmmss')
        rabbitUser = 'r4_test_' + (Get-Date -Format 'HHmmss')
        rabbitPassword = $password
        mysqlRootPassword = $env:MYSQL_IT_PASSWORD
    }
    $object | ConvertTo-Json | Set-Content -LiteralPath $Path -Encoding utf8
    return [pscustomobject]$object
}
