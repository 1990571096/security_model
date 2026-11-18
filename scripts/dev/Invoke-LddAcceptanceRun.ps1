param(
    [string]$BaseUrl = 'http://127.0.0.1:18300',
    [string]$TokenPath = 'tmp\deploy-stack\admin-deploy.token',
    [string]$BearerToken = '',
    [long]$TaskId = 0,
    [string]$TaskName = '',
    [long]$DatasetId = 6,
    [long]$AlgorithmId = 14,
    [int]$Epochs = 1,
    [string]$OutputRoot = 'tmp\ldd-short-run',
    [int]$PollSeconds = 10,
    [int]$TimeoutMinutes = 90,
    [switch]$CreateOnly,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$runLogPath = Join-Path $OutputRoot 'run.log'
$summaryPath = Join-Path $OutputRoot 'run-summary.json'

function Write-RunLog([string]$message) {
    $line = '[{0}] {1}' -f (Get-Date).ToString('HH:mm:ss'), $message
    Write-Host $line
    Add-Content -LiteralPath $runLogPath -Value $line -Encoding utf8
}

if ([string]::IsNullOrWhiteSpace($BearerToken)) {
    if (-not (Test-Path -LiteralPath $TokenPath)) {
        Write-RunLog "缺乏 Bearer token：$TokenPath 不存在。先通过 /auth/code 登录或提供 -BearerToken。"
        exit 2
    }
    $BearerToken = (Get-Content -Raw $TokenPath).Trim()
}
if ([string]::IsNullOrWhiteSpace($BearerToken)) {
    Write-RunLog 'Bearer token 为空。'
    exit 2
}
$headers = @{ Authorization = $BearerToken }

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 20
    Write-RunLog "readiness=$($health.status)"
} catch {
    Write-RunLog "后端不可达: $($_.Exception.Message)"
    exit 2
}

$transitions = New-Object System.Collections.Generic.List[object]
$createdTask = $null
$startResponse = $null
$finalDetail = $null
$finalReport = $null

if ($TaskId -eq 0) {
    if ([string]::IsNullOrWhiteSpace($TaskName)) {
        $TaskName = 'LDD short-cycle 20261118 ' + (Get-Date -Format 'HHmmss')
    }
    $params = [ordered]@{
        dataset = 'cifar10'
        trigger_type = 'SIG'
        trigger_label = 0
        poisoned_mode = 'all2one'
        poisoned_portion = 0.1
        batch_size = 128
        num_epochs = $Epochs
        lr_poison = 0.001
        r = 0.75
        alpha1 = 0.1
        alpha2 = 0.5
        seed = 123
    }
    $body = [ordered]@{
        taskName = $TaskName
        description = "Real ${Epochs}-epoch GPU acceptance; not a paper experiment result"
        datasetId = $DatasetId
        algorithmId = $AlgorithmId
        customParams = ($params | ConvertTo-Json -Compress)
    } | ConvertTo-Json -Depth 5
    try {
        $createdTask = Invoke-RestMethod -Uri "$BaseUrl/api/pre-defense" -Method Post `
            -Headers ($headers + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }) `
            -ContentType 'application/json' -Body $body -TimeoutSec 30
        $TaskId = [long]$createdTask.taskId
        Write-RunLog "task created id=$TaskId name=$($createdTask.taskName) status=$($createdTask.status)"
    } catch {
        Write-RunLog "创建任务失败: $($_.Exception.Message)"
        if ($_.ErrorDetails) { Write-RunLog $_.ErrorDetails.Message }
        exit 2
    }
} else {
    Write-RunLog "复用任务 id=$TaskId"
}

if ($CreateOnly) {
    Write-RunLog 'CreateOnly：不启动任务。'
    [ordered]@{ task = $createdTask; taskId = $TaskId; started = $false } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    exit 0
}
if ($NoStart) {
    Write-RunLog 'NoStart：不启动任务。'
    [ordered]@{ task = $createdTask; taskId = $TaskId; started = $false } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    exit 0
}

try {
    $startResponse = Invoke-RestMethod -Uri "$BaseUrl/api/pre-defense/start/$TaskId" -Method Post `
        -Headers ($headers + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }) -TimeoutSec 30
    Write-RunLog "start accepted status=$($startResponse.status)"
} catch {
    Write-RunLog "启动任务失败: $($_.Exception.Message)"
    if ($_.ErrorDetails) { Write-RunLog $_.ErrorDetails.Message }
    exit 2
}

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$lastStatus = ''
$lastProgress = -1
while ((Get-Date) -lt $deadline) {
    try {
        $detail = Invoke-RestMethod -Uri "$BaseUrl/api/task-progress/pre_defense/$TaskId" -Headers $headers -TimeoutSec 20
        $status = [string]$detail.status
        $progress = [int]$detail.progress
        if ($status -ne $lastStatus -or $progress -ne $lastProgress) {
            Write-RunLog "status=$status progress=$progress"
            $transitions.Add([ordered]@{ at = (Get-Date).ToString('o'); status = $status; progress = $progress })
            $lastStatus = $status
            $lastProgress = $progress
            $logs = Join-Path $OutputRoot ("logs-{0}.json" -f (Get-Date -Format 'HHmmss'))
            try {
                Invoke-RestMethod -Uri "$BaseUrl/api/pre-defense/logs/$TaskId" -Headers $headers -TimeoutSec 20 |
                    ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $logs -Encoding utf8
            } catch {
                # Logs may not exist in the first polling interval; keep polling.
            }
        }
        if ($status -in @('success', 'failed', 'canceled')) {
            $finalDetail = $detail
            break
        }
    } catch {
        Write-RunLog "轮询失败: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $PollSeconds
}

if (-not $finalDetail) {
    Write-RunLog "超时：$TimeoutMinutes 分钟内未到终态；任务保持当前状态，未自动取消。"
    try {
        $finalDetail = Invoke-RestMethod -Uri "$BaseUrl/api/task-progress/pre_defense/$TaskId" -Headers $headers -TimeoutSec 20
    } catch {}
}
$terminalStatus = if ($finalDetail) { [string]$finalDetail.status } else { 'unknown' }

try {
    $executor = Invoke-RestMethod -Uri "$BaseUrl/api/task-progress/executor-status" -Headers $headers -TimeoutSec 20
    $dispatch = Invoke-RestMethod -Uri "$BaseUrl/api/observability/dispatch" -Headers $headers -TimeoutSec 20
} catch {
    $executor = $null
    $dispatch = $null
}

if ($terminalStatus -eq 'success') {
    try {
        $finalReport = Invoke-RestMethod -Uri "$BaseUrl/api/pre-defense/report/$TaskId" -Headers $headers -TimeoutSec 30
    } catch {
        Write-RunLog "报告读取失败: $($_.Exception.Message)"
    }
}

try {
    $finalLogs = Invoke-RestMethod -Uri "$BaseUrl/api/pre-defense/logs/$TaskId" -Headers $headers -TimeoutSec 20
} catch {
    $finalLogs = $null
}

$summary = [ordered]@{
    started_at = (Get-Date).ToString('o')
    base_url = $BaseUrl
    task_id = $TaskId
    task_name = if ($createdTask) { $createdTask.taskName } else { $TaskName }
    epochs = $Epochs
    created = $createdTask
    start = $startResponse
    transitions = $transitions
    final_status = $terminalStatus
    final_detail = $finalDetail
    report = $finalReport
    logs = $finalLogs
    executor_status = $executor
    dispatch = $dispatch
    evidence_dir = (Resolve-Path $OutputRoot).Path
}
$summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-RunLog "完成 status=$terminalStatus summary=$summaryPath"

if ($terminalStatus -eq 'success') { exit 0 }
if ($terminalStatus -eq 'failed' -or $terminalStatus -eq 'canceled') { exit 1 }
exit 3
