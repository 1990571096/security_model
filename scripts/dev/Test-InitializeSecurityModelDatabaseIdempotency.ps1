param(
    [string]$RootPassword = $env:MYSQL_IT_PASSWORD,
    [string]$MysqlExe = $(if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    } else {
        'mysql'
    }),
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 3306,
    [string]$VabDatasetRoot = $env:SECURITY_MODEL_VAB_DATASET_ROOT
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RootPassword)) {
    throw '需要 MYSQL_IT_PASSWORD 或 -RootPassword'
}

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$schema = "security_model_initidem_$suffix"
$copyPath = Join-Path $PSScriptRoot "tmp-initidem-$suffix.ps1"
$tempRoot = [IO.Path]::Combine([IO.Path]::GetTempPath(), "security-model-initidem-$suffix")
$initScript = Join-Path $PSScriptRoot 'InitializeSecurityModelDatabase.ps1'

function Invoke-Mysql([string]$sql, [string]$database = '') {
    $args = @('-h', $DbHost, '-P', "$Port", '-uroot', "-p$RootPassword",
        '--default-character-set=utf8mb4', '-N', '-B', '-e', $sql)
    if ($database) { $args += $database }
    & $MysqlExe @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 执行失败: $sql" }
}

function Query([string]$sql) {
    $args = @('-h', $DbHost, '-P', "$Port", '-uroot', "-p$RootPassword",
        '--default-character-set=utf8mb4', '-N', '-B', '-D', $schema, '-e', $sql)
    $value = & $MysqlExe @args
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败: $sql" }
    return (($value | Out-String).TrimEnd())
}

function RunInit([string]$root) {
    $output = & pwsh -NoProfile -File $copyPath -RootPassword $RootPassword `
        -DbHost $DbHost -Port $Port -MysqlExe $MysqlExe -VabDatasetRoot $root 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "初始化脚本失败，exit=$exitCode。输出:`n$text"
    }
    return $text
}

function Snapshot {
    $tables = @(
        @{ Name = 'sys_dataset'; Order = 'dataset_id' },
        @{ Name = 'sys_algorithm'; Order = 'algorithm_id' },
        @{ Name = 'sys_hyper'; Order = 'template_id' },
        @{ Name = 'sys_pre_defense'; Order = 'task_id' },
        @{ Name = 'sys_model'; Order = 'model_id' },
        @{ Name = 'sys_menu'; Order = 'menu_id' },
        @{ Name = 'sys_dev_init_marker'; Order = 'initialized_at, schema_name' }
    )
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($table in $tables) {
        $parts.Add("### $($table.Name)")
        $parts.Add((Query "SELECT * FROM ``$($table.Name)`` ORDER BY $($table.Order)"))
    }
    return ($parts -join "`n")
}

function Assert-Equal([string]$expected, [string]$actual, [string]$label) {
    if ($expected -ne $actual) {
        throw "$label 不一致。expectedSha=$((Get-FileHashText $expected)) actualSha=$((Get-FileHashText $actual))"
    }
}

function Get-FileHashText([string]$text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

try {
    $datasetRoot = $VabDatasetRoot
    if ([string]::IsNullOrWhiteSpace($datasetRoot)) {
        New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
        $datasetRoot = Join-Path $tempRoot 'dataset'
        $cifarDir = Join-Path $datasetRoot 'cifar-10-batches-py'
        New-Item -ItemType Directory -Force -Path $cifarDir | Out-Null
        foreach ($name in @('batches.meta', 'data_batch_1', 'data_batch_2', 'data_batch_3',
                'data_batch_4', 'data_batch_5', 'test_batch')) {
            [IO.File]::WriteAllBytes((Join-Path $cifarDir $name), [byte[]](1, 2, 3, 4))
        }
        Write-Host "using synthetic CIFAR-10 structure at $datasetRoot (structure check only)"
    }

    $content = [IO.File]::ReadAllText($initScript).Replace("'security_model'", "'$schema'")
    [IO.File]::WriteAllText($copyPath, $content, (New-Object Text.UTF8Encoding($false)))

    RunInit $datasetRoot | Out-Null
    $first = Snapshot
    $firstTables = Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema'"

    RunInit $datasetRoot | Out-Null
    $second = Snapshot
    $secondTables = Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema'"
    Assert-Equal $first $second '第二次完整初始化后的业务行/更新时间'
    Assert-Equal $firstTables $secondTables '第二次完整初始化后的表数量'

    $datasetId = Query "SELECT dataset_id FROM sys_dataset WHERE dataset_name='VaB CIFAR-10 Runtime Root'"
    $algorithmId = Query "SELECT algorithm_id FROM sys_algorithm WHERE implementation_path='vab://pre-defense/ldd'"
    $templateId = Query "SELECT template_id FROM sys_hyper WHERE template_name='LDD (VaB) - CIFAR10 SIG'"
    if (-not $datasetId -or -not $algorithmId -or -not $templateId) {
        throw "种子资源缺失：datasetId=$datasetId algorithmId=$algorithmId templateId=$templateId"
    }

    Query "UPDATE sys_dataset SET file_path='/operator/vab-path', parse_status='pending', sample_count=12345, parse_message='operator edited', update_by='operator', update_time='2025-01-02 03:04:05' WHERE dataset_id=$datasetId" | Out-Null
    $algorithmJson = '{"dataset":"cifar10","operator_marker":true}'
    $hyperJson = '{"operator_marker":true}'
    Query ("UPDATE sys_algorithm SET enabled=b'0', default_config='" + $algorithmJson + "', update_by='operator', update_time='2025-01-02 03:04:05' WHERE algorithm_id=$algorithmId") | Out-Null
    Query ("UPDATE sys_hyper SET enabled=b'0', parameter_config='" + $hyperJson + "', update_by='operator', update_time='2025-01-02 03:04:05' WHERE template_id=$templateId") | Out-Null
    Query "UPDATE sys_algorithm SET enabled=b'0', update_by='operator', update_time='2025-01-02 03:04:05' WHERE implementation_path='builtin://baseline/statistical-cleaning'" | Out-Null
    Query "UPDATE sys_menu SET hidden=b'0', update_by='operator', update_time='2025-01-02 03:04:05' WHERE menu_id=122" | Out-Null

    $unknownName = "未知历史任务-$suffix"
    Query "INSERT INTO sys_pre_defense (task_name, description, status, dataset_id, algorithm_id, progress, create_by, update_by, create_time, update_time) VALUES ('$unknownName', 'operator fixture', 'running', $datasetId, $algorithmId, 50, 'operator', 'operator', '2025-01-02 03:04:05', '2025-01-02 03:04:05')" | Out-Null

    $modified = Snapshot
    $thirdText = RunInit $datasetRoot
    $third = Snapshot
    Assert-Equal $modified $third '修改资源/启用状态后第三次完整初始化的业务行/更新时间'

    $dataset = Query "SELECT CONCAT(file_path, '|', parse_status, '|', sample_count, '|', DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s')) FROM sys_dataset WHERE dataset_id=$datasetId"
    if ($dataset -ne '/operator/vab-path|pending|12345|2025-01-02 03:04:05') {
        throw "dataset 运维修改被覆盖: $dataset"
    }
    $algorithm = Query "SELECT CONCAT(enabled+0, '|', default_config, '|', DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s')) FROM sys_algorithm WHERE algorithm_id=$algorithmId"
    if ($algorithm -notmatch '^0\|.*"operator_marker":\s*true.*\|2025-01-02 03:04:05$') {
        throw "algorithm 运维修改被覆盖: $algorithm"
    }
    $hyper = Query "SELECT CONCAT(enabled+0, '|', parameter_config, '|', DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s')) FROM sys_hyper WHERE template_id=$templateId"
    if ($hyper -notmatch '^0\|.*"operator_marker":\s*true.*\|2025-01-02 03:04:05$') {
        throw "hyper 运维修改被覆盖: $hyper"
    }
    $builtin = Query "SELECT CONCAT(enabled+0, '|', DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s')) FROM sys_algorithm WHERE implementation_path='builtin://baseline/statistical-cleaning'"
    if ($builtin -ne '0|2025-01-02 03:04:05') {
        throw "builtin 启用状态被初始化覆盖: $builtin"
    }

    $seedTasks = Query "SELECT COUNT(*) FROM sys_pre_defense WHERE task_id IN (3,4) AND status='failed'"
    if ($seedTasks -ne '2') {
        throw "种子历史任务没有按精确 ID 收敛: failed=$seedTasks"
    }
    $unknown = Query "SELECT CONCAT(status, '|', COALESCE(current_run_id, 'NULL'), '|', progress) FROM sys_pre_defense WHERE task_name='$unknownName'"
    if ($unknown -ne 'running|NULL|50') {
        throw "未知无上下文 running 行被改动: $unknown"
    }
    if ($thirdText -notmatch [regex]::Escape($unknownName)) {
        throw "第三次初始化没有输出未知历史任务审核清单: $unknownName"
    }

    Write-Host "init idempotency passed: schema=$schema tables=$secondTables twice-identical; operator edits preserved; unknown running row untouched and audited"
} finally {
    if (Test-Path -LiteralPath $copyPath) {
        Remove-Item -LiteralPath $copyPath -Force
    }
    try {
        Invoke-Mysql "DROP DATABASE IF EXISTS ``$schema``"
    } catch {
        Write-Warning "清理测试库失败: $schema ($($_.Exception.Message))"
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}