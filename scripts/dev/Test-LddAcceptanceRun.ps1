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
        [string]$InitialStatus = 'pending'
    )
    $dir = Join-Path $tempRoot $Name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $artifactPath = Join-Path $dir 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
    $modelFile = Join-Path $artifactPath '0.1.pth'
    $logFile = Join-Path $artifactPath 'execution.log'
    Set-Content -LiteralPath $modelFile -Value 'fake-pth' -Encoding utf8
    Set-Content -LiteralPath $logFile -Value 'fake-log' -Encoding utf8
    $params = [ordered]@{
        dataset = 'cifar10'
        trigger_type = 'SIG'
        trigger_label = 0
        poisoned_mode = 'all2one'
        poisoned_portion = 0.1
        batch_size = 128
        num_epochs = 5
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
    }
    $scenarioPath = Join-Path $dir 'scenario.json'
    $scenario | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $scenarioPath -Encoding utf8
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
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Host "FAIL $failure" }
    exit 1
}
Write-Host 'LDD acceptance run script checks OK: explicit start, server params, acceptance evidence'
exit 0
