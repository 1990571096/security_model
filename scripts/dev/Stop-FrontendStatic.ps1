param(
    [string]$StateRoot = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($StateRoot)) { $StateRoot = Join-Path $repoRoot 'tmp\deploy-stack\frontend' }
$statePath = Join-Path $StateRoot 'state.json'
if (!(Test-Path -LiteralPath $statePath)) {
    Write-Host "no running frontend state: $statePath"
    exit 0
}

$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -DateKind String
$entry = @($state.processes)[0]
$processId = [int]$entry.pid
$process = Get-Process -Id $processId -ErrorAction SilentlyContinue
if (-not $process) {
    Write-Host "frontend pid=$processId already stopped"
} else {
    if ([string]::IsNullOrWhiteSpace([string]$entry.processStartTime) -or
        [string]::IsNullOrWhiteSpace([string]$entry.executablePath)) {
        throw "拒绝停止：pid=$processId 缺少前端进程身份字段；保留 state"
    }
    if ($process.ProcessName -notmatch '^node') {
        throw "拒绝停止：pid=$processId 不是 node 进程，实际=$($process.ProcessName)；保留 state"
    }
    $actualPath = $process.Path
    if ([string]::IsNullOrWhiteSpace($actualPath) -or
        ([IO.Path]::GetFullPath($actualPath).TrimEnd('\', '/')) -ne ([IO.Path]::GetFullPath([string]$entry.executablePath).TrimEnd('\', '/'))) {
        throw "拒绝停止：pid=$processId 可执行文件不匹配；保留 state"
    }
    $recordedStart = ([DateTimeOffset]::Parse([string]$entry.processStartTime)).UtcDateTime
    if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $recordedStart).TotalSeconds) -gt 5) {
        throw "拒绝停止：pid=$processId 启动时间不匹配；保留 state"
    }
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if (-not $cim -or [string]::IsNullOrWhiteSpace($cim.CommandLine) -or
        $cim.CommandLine.IndexOf([string]$state.serverScript, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "拒绝停止：pid=$processId 命令行未包含前端服务脚本；保留 state"
    }
    try { Stop-Process -Id $processId -ErrorAction Stop } catch {
        if (-not $Force) { throw "停止前端失败 pid=${processId}: $($_.Exception.Message)；保留 state；可显式 -Force" }
        Stop-Process -Id $processId -Force -ErrorAction Stop
    }
    Wait-Process -Id $processId -Timeout 5 -ErrorAction SilentlyContinue
    if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
        if (-not $Force) { throw "前端进程未退出 pid=$processId；保留 state；可显式 -Force" }
        Stop-Process -Id $processId -Force -ErrorAction Stop
    }
    Write-Host "frontend stopped pid=$processId"
}

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort ([int]$state.staticPort) -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) { throw "前端端口仍未释放: $($state.staticPort)；保留 state" }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$stoppedPath = Join-Path $StateRoot "state-stopped-$stamp.json"
$state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (Get-Date).ToString('o') -Force
$state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $stoppedPath -Encoding utf8
Remove-Item -LiteralPath $statePath -Force
Write-Host "frontend static stopped evidence=$stoppedPath"
