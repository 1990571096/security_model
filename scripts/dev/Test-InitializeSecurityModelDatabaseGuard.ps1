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

function Invoke-MysqlQuery([string]$sql) {
    $args = @('-h', $DbHost, '-P', "$Port", '-uroot', "-p$RootPassword",
        '--default-character-set=utf8mb4', '-N', '-B', '-e', $sql)
    $value = & $MysqlExe @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败: $sql" }
    return (($value | Out-String).Trim())
}

try {
    Invoke-Mysql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
    # Deliberately no sys_pre_defense and no sys_dev_init_marker: exactly the P1 case.
    # Two tables plus sentinel rows prove the guard refuses before touching any business data.
    Invoke-Mysql "CREATE TABLE ``$schema``.``sys_user`` (``user_id`` BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB"
    Invoke-Mysql "INSERT INTO ``$schema``.``sys_user`` (``user_id``) VALUES (42)"
    Invoke-Mysql "CREATE TABLE ``$schema``.``sys_sentinel`` (``sentinel_id`` BIGINT NOT NULL PRIMARY KEY, ``payload`` VARCHAR(32) NOT NULL) ENGINE=InnoDB"
    Invoke-Mysql "INSERT INTO ``$schema``.``sys_sentinel`` (``sentinel_id``, ``payload``) VALUES (7, 'keep-me')"

    $beforeTables = Invoke-MysqlQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema'"
    $beforeUser = Invoke-MysqlQuery "SELECT user_id FROM ``$schema``.``sys_user``"
    $beforeSentinel = Invoke-MysqlQuery "SELECT CONCAT(sentinel_id, ':', payload) FROM ``$schema``.``sys_sentinel``"

    # Exercise the real script with only the schema name changed. The replacement targets
    # the quoted default/allowlist literal, not the seed filenames.
    $content = [IO.File]::ReadAllText($scriptPath).Replace("'security_model'", "'$schema'")
    [IO.File]::WriteAllText($copyPath, $content, (New-Object Text.UTF8Encoding($false)))

    $env:MYSQL_EXE = $MysqlExe
    $output = & pwsh -NoProfile -File $copyPath -RootPassword $RootPassword `
        -DbHost $DbHost -Port $Port -MysqlExe $MysqlExe 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "保护测试失败：非空库($schema)未被拒绝。输出:`n$text"
    }
    $compact = ($text -replace '\s', '') -replace '\|', ''
    if ($compact -notmatch '没有可信初始化标记') {
        throw "保护测试失败：拒绝原因不是缺少可信标记。输出:`n$text"
    }
    if ($compact -notmatch "已有$beforeTables个表或视图") {
        throw "保护测试失败：没有报告拒绝前的表数量 $beforeTables。输出:`n$text"
    }

    $afterTables = Invoke-MysqlQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema'"
    $afterUser = Invoke-MysqlQuery "SELECT user_id FROM ``$schema``.``sys_user``"
    $afterSentinel = Invoke-MysqlQuery "SELECT CONCAT(sentinel_id, ':', payload) FROM ``$schema``.``sys_sentinel``"
    $markerCount = Invoke-MysqlQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='sys_dev_init_marker'"
    if ($afterTables -ne $beforeTables) {
        throw "保护测试失败：表数量变化 $beforeTables -> $afterTables"
    }
    if ($afterUser -ne $beforeUser) {
        throw "保护测试失败：sys_user 哨兵数据变化 '$beforeUser' -> '$afterUser'"
    }
    if ($afterSentinel -ne $beforeSentinel) {
        throw "保护测试失败：sys_sentinel 哨兵数据变化 '$beforeSentinel' -> '$afterSentinel'"
    }
    if ($markerCount -ne '0') {
        throw "保护测试失败：拒绝路径写入了 sys_dev_init_marker，count=$markerCount"
    }
    Write-Host "guard test passed: schema=$schema refused; tables=$beforeTables unchanged; sentinels intact"
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