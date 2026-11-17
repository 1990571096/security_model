param(
    [string]$RootPassword = $env:MYSQL_IT_PASSWORD,
    [string]$MysqlExe = $(if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    } else {
        'mysql'
    }),
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 3306
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RootPassword)) {
    throw '需要 MYSQL_IT_PASSWORD 或 -RootPassword'
}

$scriptPath = Join-Path $PSScriptRoot 'InitializeSecurityModelDatabase.ps1'
if (!(Test-Path -LiteralPath $scriptPath)) {
    throw "初始化脚本不存在: $scriptPath"
}

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$schema = "security_model_guardtest_$suffix"
$copyPath = Join-Path $PSScriptRoot "tmp-guard-$suffix.ps1"

function Invoke-Mysql([string]$sql) {
    $args = @('-h', $DbHost, '-P', "$Port", '-uroot', "-p$RootPassword",
        '--default-character-set=utf8mb4', '-N', '-B', '-e', $sql)
    & $MysqlExe @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败: $sql" }
}

try {
    Invoke-Mysql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
    # Deliberately no sys_pre_defense and no sys_dev_init_marker: exactly the P1 case.
    Invoke-Mysql "CREATE TABLE ``$schema``.``sys_user`` (``user_id`` BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB"

    # Exercise the real script with only the schema name changed. The replacement targets
    # the quoted default/allowlist literal, not the seed filenames.
    $content = [IO.File]::ReadAllText($scriptPath).Replace("'security_model'", "'$schema'")
    [IO.File]::WriteAllText($copyPath, $content, (New-Object Text.UTF8Encoding($false)))

    $env:MYSQL_EXE = $MysqlExe
    $output = & pwsh -NoProfile -File $copyPath -RootPassword $RootPassword 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "保护测试失败：非空库($schema)未被拒绝。输出:`n$text"
    }
    if ($text -notmatch '没有可信初始化标记') {
        throw "保护测试失败：拒绝原因不是缺少可信标记。输出:`n$text"
    }
    if ($text -notmatch '已有 1 个表或视图') {
        throw "保护测试失败：没有报告实际的表数量。输出:`n$text"
    }
    Write-Host "guard test passed: schema=$schema refused without running sql/eladmin.sql"
} finally {
    if (Test-Path -LiteralPath $copyPath) {
        Remove-Item -LiteralPath $copyPath -Force
    }
    try {
        Invoke-Mysql "DROP DATABASE IF EXISTS ``$schema``"
    } catch {
        Write-Warning "清理测试库失败: $schema ($($_.Exception.Message))"
    }
}
