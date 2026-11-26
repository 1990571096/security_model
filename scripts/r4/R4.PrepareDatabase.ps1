param(
    [Parameter(Mandatory = $true)][string]$SchemaName,
    [string]$RootPassword = $env:MYSQL_IT_PASSWORD
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'R4.Common.ps1')
Assert-R4SchemaName $SchemaName
if ([string]::IsNullOrWhiteSpace($RootPassword)) { throw '需要 MYSQL_IT_PASSWORD 或 -RootPassword' }

$createArgs = @('-uroot', "-p$RootPassword", '--default-character-set=utf8mb4', '-e',
    "CREATE DATABASE IF NOT EXISTS $SchemaName CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
& $script:R4Mysql @createArgs
if ($LASTEXITCODE -ne 0) { throw "创建隔离 schema 失败: $SchemaName" }

$files = @(
    'sql\eladmin.sql',
    'sql\security_model_account_deletion.sql',
    'sql\security_model_task_run_context.sql',
    'sql\security_model_ownership.sql',
    'sql\security_model_task_dispatch.sql',
    'sql\security_model_task_outbox.sql',
    'sql\security_model_gpu_lease.sql',
    'sql\security_model_operation_audit.sql',
    'sql\security_model_clean_subset_export.sql',
    'sql\security_model_execution_snapshot.sql',
    'sql\security_model_knowledge.sql',
    'sql\security_model_knowledge_vector_reconcile.sql',
    'sql\security_model_assistant.sql',
    'sql\security_model_experiment_note.sql',
    'sql\security_model_request_idempotency.sql',
    'sql\security_model_mvp_seed.sql'
)
foreach ($relative in $files) {
    $path = Join-Path $script:R4RepoRoot $relative
    if (Test-Path -LiteralPath $path) {
        Invoke-R4MysqlFile $SchemaName $path $RootPassword
        Write-Host "applied $relative"
    } else {
        Write-Host "skip missing $relative"
    }
}

$tables = Invoke-R4Mysql $SchemaName "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SchemaName'" $RootPassword
Write-Host "R4 schema ready: $SchemaName tables=$tables"
