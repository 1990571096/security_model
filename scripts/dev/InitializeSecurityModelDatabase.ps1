param(
    [string]$RootPassword = $env:MYSQL_IT_PASSWORD,
    [string]$SchemaName = 'security_model'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RootPassword)) {
    throw '需要 MYSQL_IT_PASSWORD 或 -RootPassword（不要写入仓库）'
}
if ($SchemaName -ne 'security_model') {
    throw "拒绝操作：本脚本只允许初始化 security_model，当前=$SchemaName"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mysql = if ($env:MYSQL_EXE) { $env:MYSQL_EXE } else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' }
$mysqlDir = Join-Path $env:TEMP 'security-model-sql'
New-Item -ItemType Directory -Force -Path $mysqlDir | Out-Null

function Invoke-Sql([string]$sql, [string]$database = '') {
    $args = @('-uroot', "-p$RootPassword", '--default-character-set=utf8mb4', '-N', '-B', '-e', $sql)
    if ($database) { $args += $database }
    & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败: $sql" }
}

function Invoke-SqlFile([string]$relativePath) {
    $path = Join-Path $repoRoot $relativePath
    if (!(Test-Path -LiteralPath $path)) { throw "SQL 文件不存在: $path" }
    $asciiPath = Join-Path $mysqlDir (Split-Path -Leaf $path)
    Copy-Item -LiteralPath $path -Destination $asciiPath -Force
    $source = $asciiPath.Replace('\', '/')
    $args = @('-uroot', "-p$RootPassword", '--default-character-set=utf8mb4', '-D', $SchemaName,
        '-e', "SOURCE $source")
    & $mysql @args
    if ($LASTEXITCODE -ne 0) { throw "mysql SOURCE 失败: $relativePath" }
}

function Get-TableCount([string]$where = '') {
    $sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SchemaName'$where"
    return (Invoke-Sql $sql).Trim()
}

Invoke-Sql "CREATE DATABASE IF NOT EXISTS $SchemaName CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"

# sql/eladmin.sql 开头是 DROP TABLE IF EXISTS，不是幂等建表脚本。
# 只有在本脚本留下 marker 之后才允许重跑增量；否则要求目标库为空，防止清空已有业务数据。
$markerExists = (Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SchemaName' AND table_name='sys_dev_init_marker'" $SchemaName).Trim()
$baseExists = (Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SchemaName' AND table_name='sys_pre_defense'" $SchemaName).Trim()

if ($markerExists -eq '0' -and $baseExists -ne '0') {
    throw "拒绝执行：$SchemaName 已有业务表但没有初始化标记。sql/eladmin.sql 会 DROP 并重建业务表，" +
          "不能对已有数据的库执行。请先确认该库可清空，或改用新库名。"
}

if ($markerExists -eq '0') {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-SqlFile 'sql\eladmin.sql'
    $watch.Stop()
    Write-Host ("applied sql\eladmin.sql (base schema, {0:n0} ms)" -f $watch.ElapsedMilliseconds)
    # 基础 schema 一旦成功就立刻落 marker：后续增量失败时允许重跑增量，
    # 不会因为“有业务表但无 marker”而把已完成的基础 schema 判成不可信。
    Invoke-Sql "CREATE TABLE IF NOT EXISTS sys_dev_init_marker (initialized_at DATETIME NOT NULL, schema_name VARCHAR(64) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" $SchemaName
    Invoke-Sql "INSERT INTO sys_dev_init_marker (initialized_at, schema_name) VALUES (NOW(), '$SchemaName')" $SchemaName
} else {
    Write-Host 'skip sql\eladmin.sql (init marker present; base schema already applied)'
}

$files = @(
    'sql\security_model_execution_snapshot.sql',
    'sql\security_model_task_run_context.sql',
    'sql\security_model_clean_subset_export.sql',
    'sql\security_model_operation_audit.sql',
    'sql\security_model_ownership.sql',
    'sql\security_model_gpu_lease.sql',
    'sql\security_model_task_dispatch.sql',
    'sql\security_model_task_outbox.sql',
    'sql\security_model_request_idempotency.sql',
    'sql\security_model_knowledge.sql',
    'sql\security_model_knowledge_vector_reconcile.sql',
    'sql\security_model_assistant.sql',
    'sql\security_model_mvp_seed.sql'
)

foreach ($relative in $files) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-SqlFile $relative
    $watch.Stop()
    Write-Host ("applied {0} ({1:n0} ms)" -f $relative, $watch.ElapsedMilliseconds)
}

# security_model_gpu_lease_block.sql 只用于“已有 sys_gpu_lease 但缺 blocked 列”的老库。
# 新库由 security_model_gpu_lease.sql 直接建出这两列，重复 ALTER 会报 Duplicate column。
$blockedColumn = (Invoke-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$SchemaName' AND table_name='sys_gpu_lease' AND column_name='blocked_at'" $SchemaName).Trim()
if ($blockedColumn -eq '0') {
    Invoke-SqlFile 'sql\security_model_gpu_lease_block.sql'
    Write-Host 'applied sql\security_model_gpu_lease_block.sql (legacy upgrade path)'
} else {
    Write-Host 'skip sql\security_model_gpu_lease_block.sql (blocked columns already present)'
}

Invoke-Sql "CREATE TABLE IF NOT EXISTS sys_dev_init_marker (initialized_at DATETIME NOT NULL, schema_name VARCHAR(64) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" $SchemaName

$tableCount = Get-TableCount
$required = @(
    'sys_pre_defense', 'sys_gpu_lease', 'sys_task_dispatch_intent',
    'sys_dispatch_outbox', 'sys_dispatch_consumption',
    'sys_request_idempotency', 'sys_knowledge_document', 'sys_assistant_session'
)
$missing = @()
foreach ($table in $required) {
    $exists = (Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SchemaName' AND table_name='$table'" $SchemaName).Trim()
    if ($exists -eq '0') { $missing += $table }
}
if ($missing.Count -gt 0) {
    throw "缺少关键表: $($missing -join ', ')"
}

Write-Host "security_model initialized: tables=$tableCount"
Invoke-Sql "SELECT user_id, username, enabled, is_admin FROM sys_user ORDER BY user_id LIMIT 10" $SchemaName
