param(
    [string]$PythonExe = 'python'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runScript = Join-Path $PSScriptRoot 'Invoke-LddAcceptanceRun.ps1'
$fakeServer = Join-Path $PSScriptRoot 'fake_ldd_acceptance_api.py'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("ldd-acceptance-run-test-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

$failures = New-Object System.Collections.Generic.List[string]
function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { $script:failures.Add($message) }
}

function New-FakeScenario {
    param(
        [string]$Name,
        [bool]$ReportAvailable = $true,
        [string]$InitialStatus = 'pending',
        [int]$Epochs = 5,
        [int]$LoggedEpochs = 5,
        [bool]$IncludeModel = $true,
        [bool]$IncludeLog = $true,
        [bool]$EmptyModel = $false,
        [bool]$EmptyLog = $false,
        [bool]$OldArtifact = $false,
        [switch]$BlankModelEntry
    )
    $dir = Join-Path $tempRoot $Name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $artifactPath = Join-Path $dir 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
    $modelFile = Join-Path $artifactPath '0.1.pth'
    $logFile = Join-Path $artifactPath '0.1.txt'
    $generatedScript = Join-Path $artifactPath 'generated_LDD.py'
    $selectionFile = Join-Path $artifactPath 'selected_indices_10.npy'

    if ($IncludeModel) {
        if ($EmptyModel) { Set-Content -LiteralPath $modelFile -Value '' -NoNewline -Encoding utf8 }
        else { Set-Content -LiteralPath $modelFile -Value 'fake-pth' -Encoding utf8 }
    }
    $lines = @('Namespace(num_epochs=5)', 'First Warmup Net2')
    for ($i = 0; $i -lt $LoggedEpochs; $i++) {
        $lines += "`nEpoch: $i`nclean_purity:100.00%"
    }
    $lines += '程序运行了 0.01 分钟。'
    $logContent = $lines -join "`n"
    if ($IncludeLog) {
        if ($EmptyLog) { Set-Content -LiteralPath $logFile -Value '' -NoNewline -Encoding utf8; $logContent = '' }
        else { Set-Content -LiteralPath $logFile -Value $logContent -Encoding utf8 }
    }
    Set-Content -LiteralPath $generatedScript -Value 'print("fake LDD")' -Encoding utf8
    Set-Content -LiteralPath $selectionFile -Value 'fake-npy' -Encoding utf8

    if ($OldArtifact) {
        $old = [datetime]'2020-01-01T00:00:00Z'
        foreach ($path in @($modelFile, $logFile, $generatedScript, $selectionFile)) {
            if (Test-Path -LiteralPath $path) { (Get-Item -LiteralPath $path).LastWriteTimeUtc = $old }
        }
    }

    $modelFiles = if ($BlankModelEntry) { @('') } elseif ($IncludeModel) { @($modelFile) } else { @() }
    $logFiles = if ($IncludeLog) { @($logFile) } else { @() }
    $selectionFiles = @($selectionFile)
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
    $scenario = [ordered]@{
        task_id = 2122901116713205761
        task_name = "fake acceptance $Name"
        dataset_id = 6
        algorithm_id = 14
        run_id = "fake-run-$Name"
        params = $params
        report_available = $ReportAvailable
        initial_status = $InitialStatus
        artifact_path = $artifactPath
        model_file = $modelFile
        log_file = $logFile
        model_files = @($modelFiles)
        logs = @($logFiles)
        log_content = $logContent
        logged_epochs = $LoggedEpochs
        include_model = $IncludeModel
        include_log = $IncludeLog
        selection_files = $selectionFiles
        generated_script = $generatedScript
        started_at = (Get-Date).AddMinutes(-5).ToUniversalTime().ToString('o')
        finished_at = (Get-Date).AddMinutes(1).ToUniversalTime().ToString('o')
    }
    $scenarioPath = Join-Path $dir 'scenario.json'
    $scenario | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $scenarioPath -Encoding utf8
    return $scenarioPath
}

function Start-FakeApi([string]$ScenarioPath) {
    $portFile = Join-Path $tempRoot ("port-{0}.txt" -f [guid]::NewGuid().ToString('N'))
    $stdout = Join-Path $tempRoot ("fake-{0}.out.log" -f [guid]::NewGuid().ToString('N'))
    $stderr = Join-Path $tempRoot ("fake-{0}.err.log" -f [guid]::NewGuid().ToString('N'))
    $process = Start-Process -FilePath $PythonExe `
        -ArgumentList @($fakeServer, '--scenario', $ScenarioPath, '--port', '0', '--port-file', $portFile) `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $port = $null
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path -LiteralPath $portFile) {
            $port = [int](Get-Content -Raw $portFile).Trim()
            if ($port -gt 0) { break }
        }
        if ($process.HasExited) { break }
    }
    if (-not $port) {
        $err = if (Test-Path $stderr) { Get-Content -Raw $stderr } else { '' }
        throw "fake API 未启动: $err"
    }
    $baseUrl = "http://127.0.0.1:$port"
    $ready = $false
    for ($i = 0; $i -lt 50; $i++) {
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health/readiness" -TimeoutSec 2
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch {}
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "fake API readiness 不通过: $baseUrl"
    }
    return [pscustomobject]@{ Process = $process; BaseUrl = $baseUrl }
}

function Stop-FakeApi($Server) {
    if ($Server -and $Server.Process -and -not $Server.Process.HasExited) {
        Stop-Process -Id $Server.Process.Id -Force -ErrorAction SilentlyContinue
        $Server.Process.WaitForExit(5000) | Out-Null
    }
}

function Invoke-AcceptanceScript {
    param(
        [string]$BaseUrl,
        [string]$OutputRoot,
        [string]$TaskId,
        [string]$Epochs,
        [switch]$Start,
        [switch]$CreateOnly
    )
    $arguments = @(
        '-NoProfile', '-File', $runScript,
        '-BaseUrl', $BaseUrl,
        '-BearerToken', 'fake-token',
        '-TaskId', $TaskId,
        '-Epochs', $Epochs,
        '-OutputRoot', $OutputRoot,
        '-PollSeconds', '1',
        '-TimeoutMinutes', '2'
    )
    if ($Start) { $arguments += '-Start' }
    if ($CreateOnly) { $arguments += '-CreateOnly' }
    $output = & pwsh @arguments 2>&1 | Out-String
    return [pscustomobject]@{ Exit = $LASTEXITCODE; Output = $output }
}

function Get-StartCount([string]$BaseUrl) {
    return [int](Invoke-RestMethod -Uri "$BaseUrl/api/pre-defense/start-count" -TimeoutSec 5).start_count
}

try {
    # 1) 未传 -Start 时只做启动前校验，不调用 start。
    $scenario = New-FakeScenario -Name 'no-start'
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-no-start'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5'
        Assert-True ($run.Exit -eq 0) "未传 -Start 应退出 0，实际 $($run.Exit): $($run.Output)"
        Assert-True ((Get-StartCount $server.BaseUrl) -eq 0) "未传 -Start 仍调用了 start"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.state -eq 'not_started') "未传 -Start 的 acceptance.state 不是 not_started: $($summary.acceptance.state)"
        Assert-True ($summary.started -eq $false) "未传 -Start 的 started 不是 false"
    } finally { Stop-FakeApi $server }

    # 2) 复用任务参数不一致时，必须在 start 前拒绝。
    $scenario = New-FakeScenario -Name 'mismatch'
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-mismatch'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '1' -Start
        Assert-True ($run.Exit -eq 2) "参数不一致应退出 2，实际 $($run.Exit)"
        Assert-True ((Get-StartCount $server.BaseUrl) -eq 0) "参数不一致仍调用了 start"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.state -eq 'preflight_failed') "参数不一致的 acceptance.state 不是 preflight_failed: $($summary.acceptance.state)"
    } finally { Stop-FakeApi $server }

    # 3) 正常路径：显式 -Start，任务 success，报告/产物/租约证据齐全。
    $scenario = New-FakeScenario -Name 'success'
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-success'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 0) "正常路径应退出 0，实际 $($run.Exit): $($run.Output)"
        Assert-True ((Get-StartCount $server.BaseUrl) -eq 1) "正常路径 start 调用次数不是 1"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.task_status -eq 'success') "task_status 不是 success"
        Assert-True ($summary.acceptance.passed -eq $true) "acceptance.passed 不是 true"
        Assert-True ($summary.acceptance.state -eq 'passed') "acceptance.state 不是 passed: $($summary.acceptance.state)"
    } finally { Stop-FakeApi $server }

    # 4) 任务 success 但报告缺失：不得退出 0，必须标记 evidence_incomplete。
    $scenario = New-FakeScenario -Name 'missing-report' -ReportAvailable $false
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-missing-report'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "报告缺失应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.task_status -eq 'success') "报告缺失时 task_status 仍应为 success"
        Assert-True ($summary.acceptance.passed -eq $false) "报告缺失时 acceptance.passed 不得为 true"
        Assert-True ($summary.acceptance.state -eq 'evidence_incomplete') "报告缺失的 acceptance.state 不是 evidence_incomplete: $($summary.acceptance.state)"
    } finally { Stop-FakeApi $server }

    # 5) 非 pending 任务必须在 start 前拒绝。
    $scenario = New-FakeScenario -Name 'running' -InitialStatus 'running'
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-running'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 2) "非 pending 任务应退出 2，实际 $($run.Exit)"
        Assert-True ((Get-StartCount $server.BaseUrl) -eq 0) "非 pending 任务仍调用了 start"
    } finally { Stop-FakeApi $server }

    # 6) 模型文件列表只有空项：过滤后不得计为已记录。
    $scenario = New-FakeScenario -Name 'blank-model-entry' -BlankModelEntry
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-blank-model-entry'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "空模型项应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $false) "空模型项 acceptance.passed 不得为 true"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'model files recorded') "空模型项未命中 model files recorded"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'model files list clean') "空模型项未命中 model files list clean"
    } finally { Stop-FakeApi $server }

    # 7) 模型文件存在但为 0 字节：不得判通过。
    $scenario = New-FakeScenario -Name 'empty-model' -EmptyModel $true
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-empty-model'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "空模型文件应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $false) "空模型文件 acceptance.passed 不得为 true"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'model file non-empty') "空模型文件未命中 model file non-empty"
    } finally { Stop-FakeApi $server }

    # 8) 日志缺失：即使任务 success，也不能通过。
    $scenario = New-FakeScenario -Name 'missing-log' -IncludeLog $false
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-missing-log'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "日志缺失应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $false) "日志缺失 acceptance.passed 不得为 true"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'log files recorded') "日志缺失未命中 log files recorded"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'actual epochs completed') "日志缺失未命中 actual epochs completed"
    } finally { Stop-FakeApi $server }

    # 9) 旧 attempt 产物：文件时间不在本次 run 窗口内，不得通过。
    $scenario = New-FakeScenario -Name 'old-artifact' -OldArtifact $true
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-old-artifact'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "旧产物应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $false) "旧产物 acceptance.passed 不得为 true"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'time in run window') "旧产物未命中 time in run window"
    } finally { Stop-FakeApi $server }

    # 10) 配置为 5 epochs 但日志只出现 3 轮：不得通过。
    $scenario = New-FakeScenario -Name 'short-epochs' -LoggedEpochs 3
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-short-epochs'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "实际轮数不足应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $false) "实际轮数不足 acceptance.passed 不得为 true"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'actual epochs completed') "实际轮数不足未命中 actual epochs completed"
    } finally { Stop-FakeApi $server }

    # 11) 模型文件缺失：即使报告 success，也不得通过。
    $scenario = New-FakeScenario -Name 'missing-model' -IncludeModel $false
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-missing-model'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '5' -Start
        Assert-True ($run.Exit -eq 4) "模型缺失应退出 4，实际 $($run.Exit)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $false) "模型缺失 acceptance.passed 不得为 true"
        Assert-True (($summary.acceptance.failed_checks -join '; ') -match 'model files recorded') "模型缺失未命中 model files recorded"
    } finally { Stop-FakeApi $server }

    # 12) 1 epoch 只有 Epoch: 0，仍应按 1 轮通过，不能被 5 轮规则误杀。
    $scenario = New-FakeScenario -Name 'one-epoch' -Epochs 1 -LoggedEpochs 1
    $server = Start-FakeApi $scenario
    try {
        $out = Join-Path $tempRoot 'out-one-epoch'
        $run = Invoke-AcceptanceScript -BaseUrl $server.BaseUrl -OutputRoot $out -TaskId '2122901116713205761' -Epochs '1' -Start
        Assert-True ($run.Exit -eq 0) "1 epoch 正常路径应退出 0，实际 $($run.Exit): $($run.Output)"
        $summary = Get-Content -Raw (Join-Path $out 'run-summary.json') | ConvertFrom-Json
        Assert-True ($summary.acceptance.passed -eq $true) "1 epoch acceptance.passed 不是 true"
        Assert-True ($summary.acceptance.state -eq 'passed') "1 epoch acceptance.state 不是 passed: $($summary.acceptance.state)"
    } finally { Stop-FakeApi $server }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Host "FAIL $failure" }
    exit 1
}
Write-Host 'LDD acceptance run script checks OK: explicit start, server params, acceptance evidence'
exit 0
