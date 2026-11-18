param(
    [string]$BaseUrl = 'http://127.0.0.1:18300',
    [string]$TokenPath = 'tmp\deploy-stack\admin-deploy.token',
    [string]$BearerToken = '',
    [long]$TaskId = 0,
    [string]$TaskName = '',
    [long]$DatasetId = 6,
    [long]$AlgorithmId = 14,
    [int]$Epochs = 0,
    [string]$OutputRoot = 'tmp\ldd-short-run',
    [int]$PollSeconds = 10,
    [int]$TimeoutMinutes = 90,
    [switch]$CreateOnly,
    [switch]$NoStart,
    [switch]$Start,
    [switch]$SkipArtifactFileCheck
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

function Write-Summary([object]$summary) {
    $summary | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $summaryPath -Encoding utf8
}

function Invoke-ApiJson {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers,
        [object]$Body = $null,
        [int]$TimeoutSec = 30
    )
    $args = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $Headers
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $args.ContentType = 'application/json'
        $args.Body = $Body
    }
    return Invoke-RestMethod @args
}

function Get-AcceptanceParams([int]$numEpochs) {
    return [ordered]@{
        dataset = 'cifar10'
        trigger_type = 'SIG'
        trigger_label = 0
        poisoned_mode = 'all2one'
        poisoned_portion = 0.1
        batch_size = 128
        num_epochs = $numEpochs
        lr_poison = 0.001
        r = 0.75
        alpha1 = 0.1
        alpha2 = 0.5
        seed = 123
    }
}

function Test-ParameterSet {
    param([object]$Actual, [object]$Expected)
    $mismatches = New-Object System.Collections.Generic.List[string]
    foreach ($key in $Expected.Keys) {
        $actualValue = $Actual.$key
        if ($null -eq $actualValue) {
            $mismatches.Add("$key 缺失")
            continue
        }
        $expectedValue = $Expected[$key]
        if ($expectedValue -is [double] -or $expectedValue -is [single] -or $expectedValue -is [decimal]) {
            if ([math]::Abs([double]$actualValue - [double]$expectedValue) -gt 1e-9) {
                $mismatches.Add("$key=$actualValue，期望 $expectedValue")
            }
        } elseif ($expectedValue -is [int] -or $expectedValue -is [long]) {
            if ([long]$actualValue -ne [long]$expectedValue) {
                $mismatches.Add("$key=$actualValue，期望 $expectedValue")
            }
        } else {
            if ([string]$actualValue -ne [string]$expectedValue) {
                $mismatches.Add("$key=$actualValue，期望 $expectedValue")
            }
        }
    }
    return [ordered]@{
        ok = ($mismatches.Count -eq 0)
        mismatches = @($mismatches)
    }
}

function Parse-TaskParams([object]$detail) {
    if ([string]::IsNullOrWhiteSpace([string]$detail.customParams)) {
        return $null
    }
    try {
        return ([string]$detail.customParams | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Add-AcceptanceCheck {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Ok,
        [string]$Detail
    )
    $Checks.Add([ordered]@{
        name = $Name
        ok = $Ok
        detail = $Detail
    })
}

function Get-TaskDetail([long]$id) {
    return Invoke-ApiJson -Method Get -Path "/api/task-progress/pre_defense/$id" -Headers $headers -TimeoutSec 20
}

function Test-TaskPreStart {
    param([object]$Detail, [int]$ExpectedEpochs)
    $checks = New-Object System.Collections.Generic.List[object]
    $status = [string]$Detail.status
    Add-AcceptanceCheck $checks 'task pending before start' ($status -eq 'pending') "status=$status"
    Add-AcceptanceCheck $checks 'dataset id' ([string]$Detail.datasetId -eq [string]$DatasetId) "datasetId=$($Detail.datasetId)，期望 $DatasetId"
    Add-AcceptanceCheck $checks 'algorithm id' ([string]$Detail.algorithmId -eq [string]$AlgorithmId) "algorithmId=$($Detail.algorithmId)，期望 $AlgorithmId"
    $params = Parse-TaskParams $Detail
    if ($null -eq $params) {
        Add-AcceptanceCheck $checks 'server parameters parse' $false 'customParams 缺失或不是合法 JSON'
    } else {
        $actualEpochs = [int]$params.num_epochs
        if ($ExpectedEpochs -le 0) {
            $ExpectedEpochs = $actualEpochs
        }
        Add-AcceptanceCheck $checks 'server epochs' ($actualEpochs -eq $ExpectedEpochs) "num_epochs=$actualEpochs，期望 $ExpectedEpochs"
        $expected = Get-AcceptanceParams $ExpectedEpochs
        $match = Test-ParameterSet -Actual $params -Expected $expected
        Add-AcceptanceCheck $checks 'server final parameters' $match.ok ($(if ($match.ok) { '与服务端解析参数一致' } else { $match.mismatches -join '; ' }))
        $checks.Add([ordered]@{ name = 'resolved_epochs'; ok = $true; detail = [string]$ExpectedEpochs })
    }
    return $checks
}

function Test-AllChecks([System.Collections.Generic.List[object]]$Checks) {
    return (@($Checks | Where-Object { -not $_.ok }).Count -eq 0)
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
    $health = Invoke-ApiJson -Method Get -Path '/actuator/health/readiness' -Headers @{} -TimeoutSec 20
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
$finalLogs = $null
$reportError = $null

if ($TaskId -eq 0) {
    if ($Epochs -le 0) {
        Write-RunLog '创建新任务时必须显式提供 -Epochs（例如 -Epochs 5）。'
        exit 2
    }
    if ([string]::IsNullOrWhiteSpace($TaskName)) {
        $TaskName = 'LDD short-cycle 20261118 ' + (Get-Date -Format 'HHmmss')
    }
    $params = Get-AcceptanceParams $Epochs
    $body = [ordered]@{
        taskName = $TaskName
        description = "Real ${Epochs}-epoch GPU acceptance; not a paper experiment result"
        datasetId = $DatasetId
        algorithmId = $AlgorithmId
        customParams = ($params | ConvertTo-Json -Compress)
    } | ConvertTo-Json -Depth 5
    try {
        $createdTask = Invoke-ApiJson -Method Post -Path '/api/pre-defense' `
            -Headers ($headers + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }) `
            -Body $body -TimeoutSec 30
        $TaskId = [long]$createdTask.taskId
        Write-RunLog "task created id=$TaskId name=$($createdTask.taskName) status=$($createdTask.status)"
    } catch {
        Write-RunLog "创建任务失败: $($_.Exception.Message)"
        if ($_.ErrorDetails) { Write-RunLog $_.ErrorDetails.Message }
        exit 2
    }
} else {
    Write-RunLog "复用任务 id=$TaskId；开始前核对 pending 与服务端最终参数"
}

$taskDetail = Get-TaskDetail $TaskId
$preStartChecks = Test-TaskPreStart -Detail $taskDetail -ExpectedEpochs $Epochs
$epochsCheck = $preStartChecks | Where-Object { $_.name -eq 'resolved_epochs' } | Select-Object -First 1
if ($epochsCheck) { $Epochs = [int]$epochsCheck.detail }

if (-not (Test-AllChecks $preStartChecks)) {
    $failed = @($preStartChecks | Where-Object { -not $_.ok } | ForEach-Object { "$($_.name): $($_.detail)" })
    Write-RunLog "启动前校验失败，未调用 start：$($failed -join ' | ')"
    Write-Summary ([ordered]@{
        generated_at = (Get-Date).ToString('o')
        base_url = $BaseUrl
        task_id = $TaskId
        task_name = [string]$taskDetail.taskName
        epochs = $Epochs
        task_detail = $taskDetail
        task_status = [string]$taskDetail.status
        acceptance = [ordered]@{
            state = 'preflight_failed'
            passed = $false
            checks = $preStartChecks
            failed_checks = $failed
        }
        evidence_dir = (Resolve-Path $OutputRoot).Path
    })
    exit 2
}

$noStartRequested = $CreateOnly -or $NoStart -or (-not $Start)
if ($noStartRequested) {
    $reason = if ($CreateOnly) { 'CreateOnly' } elseif ($NoStart) { 'NoStart' } else { '未提供 -Start' }
    Write-RunLog "$reason：只做启动前校验，不调用 start。"
    Write-Summary ([ordered]@{
        generated_at = (Get-Date).ToString('o')
        base_url = $BaseUrl
        task_id = $TaskId
        task_name = [string]$taskDetail.taskName
        epochs = $Epochs
        created = $createdTask
        task_detail = $taskDetail
        task_status = [string]$taskDetail.status
        started = $false
        acceptance = [ordered]@{
            state = 'not_started'
            passed = $false
            checks = $preStartChecks
            failed_checks = @()
        }
        evidence_dir = (Resolve-Path $OutputRoot).Path
    })
    exit 0
}

try {
    $startResponse = Invoke-ApiJson -Method Post -Path "/api/pre-defense/start/$TaskId" `
        -Headers ($headers + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }) -TimeoutSec 30
    Write-RunLog "start accepted taskId=$TaskId"
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
        $detail = Get-TaskDetail $TaskId
        $status = [string]$detail.status
        $progress = [int]$detail.progress
        if ($status -ne $lastStatus -or $progress -ne $lastProgress) {
            Write-RunLog "status=$status progress=$progress"
            $transitions.Add([ordered]@{ at = (Get-Date).ToString('o'); status = $status; progress = $progress })
            $lastStatus = $status
            $lastProgress = $progress
            $logs = Join-Path $OutputRoot ("logs-{0}.json" -f (Get-Date -Format 'HHmmss'))
            try {
                Invoke-ApiJson -Method Get -Path "/api/pre-defense/logs/$TaskId" -Headers $headers -TimeoutSec 20 |
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
        $finalDetail = Get-TaskDetail $TaskId
    } catch {}
}
$terminalStatus = if ($finalDetail) { [string]$finalDetail.status } else { 'unknown' }

try {
    $executor = Invoke-ApiJson -Method Get -Path '/api/task-progress/executor-status' -Headers $headers -TimeoutSec 20
} catch {
    $executor = $null
}
try {
    $dispatch = Invoke-ApiJson -Method Get -Path '/api/observability/dispatch' -Headers $headers -TimeoutSec 20
} catch {
    $dispatch = $null
}

if ($terminalStatus -eq 'success') {
    try {
        $finalReport = Invoke-ApiJson -Method Get -Path "/api/pre-defense/report/$TaskId" -Headers $headers -TimeoutSec 30
    } catch {
        $reportError = $_.Exception.Message
        Write-RunLog "报告读取失败: $reportError"
    }
}

try {
    $finalLogs = Invoke-ApiJson -Method Get -Path "/api/pre-defense/logs/$TaskId" -Headers $headers -TimeoutSec 20
} catch {
    $finalLogs = $null
    Write-RunLog "日志读取失败: $($_.Exception.Message)"
}

$acceptanceChecks = New-Object System.Collections.Generic.List[object]
Add-AcceptanceCheck $acceptanceChecks 'task result success' ($terminalStatus -eq 'success') "status=$terminalStatus"
Add-AcceptanceCheck $acceptanceChecks 'report readable' ($null -ne $finalReport) $(if ($null -ne $finalReport) { '报告已返回' } else { "报告缺失: $reportError" })
Add-AcceptanceCheck $acceptanceChecks 'logs readable' ($null -ne $finalLogs -and [bool]$finalLogs.available) '日志接口 available=true'

$runId = $null
$attempt = $null
if ($finalLogs -and $finalLogs.progress) {
    $runId = [string]$finalLogs.progress.run_id
    $attempt = [int]$finalLogs.progress.attempt
    Add-AcceptanceCheck $acceptanceChecks 'progress run id' (-not [string]::IsNullOrWhiteSpace($runId)) "run_id=$runId"
} else {
    Add-AcceptanceCheck $acceptanceChecks 'progress run id' $false '日志缺少 progress.run_id'
}

if ($finalReport) {
    Add-AcceptanceCheck $acceptanceChecks 'report task id' ([string]$finalReport.taskInfo.taskId -eq [string]$TaskId) "report.taskInfo.taskId=$($finalReport.taskInfo.taskId)"
    Add-AcceptanceCheck $acceptanceChecks 'report task status' ([string]$finalReport.taskInfo.status -eq 'success') "report.taskInfo.status=$($finalReport.taskInfo.status)"
    Add-AcceptanceCheck $acceptanceChecks 'report run id' ([string]$finalReport.cleaningResult.run_id -eq $runId) "report run_id=$($finalReport.cleaningResult.run_id)，progress run_id=$runId"
    Add-AcceptanceCheck $acceptanceChecks 'report attempt' ([int]$finalReport.cleaningResult.attempt -eq $attempt) "report attempt=$($finalReport.cleaningResult.attempt)，progress attempt=$attempt"
    Add-AcceptanceCheck $acceptanceChecks 'cleaning result success' ([string]$finalReport.cleaningResult.status -eq 'success') "cleaningResult.status=$($finalReport.cleaningResult.status)"
    Add-AcceptanceCheck $acceptanceChecks 'effective epochs' ([int]$finalReport.cleaningResult.effective_config.num_epochs -eq $Epochs) "effective num_epochs=$($finalReport.cleaningResult.effective_config.num_epochs)，期望 $Epochs"

    $artifacts = $finalReport.cleaningResult.artifacts
    Add-AcceptanceCheck $acceptanceChecks 'artifacts available' ([bool]$artifacts.available) "available=$($artifacts.available)"
    $artifactPath = [string]$artifacts.path
    Add-AcceptanceCheck $acceptanceChecks 'artifact path recorded' (-not [string]::IsNullOrWhiteSpace($artifactPath)) "path=$artifactPath"
    $modelFiles = @($artifacts.model_files)
    Add-AcceptanceCheck $acceptanceChecks 'model files recorded' ($modelFiles.Count -gt 0) "count=$($modelFiles.Count)"
    $logFiles = @($artifacts.logs)
    Add-AcceptanceCheck $acceptanceChecks 'log files recorded' ($logFiles.Count -gt 0) "count=$($logFiles.Count)"

    if (-not $SkipArtifactFileCheck) {
        if (-not [string]::IsNullOrWhiteSpace($artifactPath)) {
            Add-AcceptanceCheck $acceptanceChecks 'artifact path exists' ([bool](Test-Path -LiteralPath $artifactPath)) "path=$artifactPath"
        } else {
            Add-AcceptanceCheck $acceptanceChecks 'artifact path exists' $false '报告未记录产物路径'
        }
        foreach ($file in $modelFiles) {
            if (-not [string]::IsNullOrWhiteSpace([string]$file)) {
                Add-AcceptanceCheck $acceptanceChecks 'model file exists' ([bool](Test-Path -LiteralPath ([string]$file))) "path=$file"
            }
        }
        foreach ($file in $logFiles) {
            if (-not [string]::IsNullOrWhiteSpace([string]$file)) {
                Add-AcceptanceCheck $acceptanceChecks 'log file exists' ([bool](Test-Path -LiteralPath ([string]$file))) "path=$file"
            }
        }
    }
} else {
    Add-AcceptanceCheck $acceptanceChecks 'report details' $false '报告不可用，无法核对 taskId/runId/attempt/产物'
}

Add-AcceptanceCheck $acceptanceChecks 'executor status readable' ($null -ne $executor) 'executor-status 已返回'
if ($executor) {
    Add-AcceptanceCheck $acceptanceChecks 'gpu slot idle' ([string]$executor.gpuSlot.state -eq 'idle') "gpuSlot.state=$($executor.gpuSlot.state)"
    Add-AcceptanceCheck $acceptanceChecks 'running task count zero' ([int]$executor.runningTaskCount -eq 0) "runningTaskCount=$($executor.runningTaskCount)"
}
Add-AcceptanceCheck $acceptanceChecks 'dispatch readable' ($null -ne $dispatch) 'dispatch 已返回'
if ($dispatch) {
    Add-AcceptanceCheck $acceptanceChecks 'dispatch ready' ([bool]$dispatch.dispatchReady) "dispatchReady=$($dispatch.dispatchReady)"
    Add-AcceptanceCheck $acceptanceChecks 'dispatch running zero' ([int]$dispatch.runningTasks -eq 0) "runningTasks=$($dispatch.runningTasks)"
}

$acceptancePassed = Test-AllChecks $acceptanceChecks
$failedChecks = @($acceptanceChecks | Where-Object { -not $_.ok } | ForEach-Object { "$($_.name): $($_.detail)" })
$acceptanceState = if ($terminalStatus -eq 'success' -and $acceptancePassed) {
    'passed'
} elseif ($terminalStatus -eq 'success') {
    'evidence_incomplete'
} elseif ($terminalStatus -in @('failed', 'canceled')) {
    'task_failed'
} else {
    'not_terminal'
}

$summary = [ordered]@{
    generated_at = (Get-Date).ToString('o')
    base_url = $BaseUrl
    task_id = $TaskId
    task_name = if ($finalDetail -and $finalDetail.taskName) { [string]$finalDetail.taskName } else { [string]$taskDetail.taskName }
    epochs = $Epochs
    created = $createdTask
    task_detail = $taskDetail
    task_result = [ordered]@{
        status = $terminalStatus
        detail = $finalDetail
        start = $startResponse
        transitions = $transitions
    }
    task_status = $terminalStatus
    final_status = $terminalStatus
    final_detail = $finalDetail
    report = $finalReport
    logs = $finalLogs
    executor_status = $executor
    dispatch = $dispatch
    acceptance = [ordered]@{
        state = $acceptanceState
        passed = $acceptancePassed
        checks = $acceptanceChecks
        failed_checks = $failedChecks
    }
    evidence_dir = (Resolve-Path $OutputRoot).Path
}
Write-Summary $summary
Write-RunLog "完成 task_status=$terminalStatus acceptance=$acceptanceState passed=$acceptancePassed summary=$summaryPath"
if ($failedChecks.Count -gt 0) {
    Write-RunLog "验收未通过项：$($failedChecks -join ' | ')"
}

if ($terminalStatus -eq 'success') {
    if ($acceptancePassed) { exit 0 }
    exit 4
}
if ($terminalStatus -eq 'failed' -or $terminalStatus -eq 'canceled') { exit 1 }
exit 3
