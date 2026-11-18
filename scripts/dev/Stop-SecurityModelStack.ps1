param(
    [string]$StateRoot = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($StateRoot)) { $StateRoot = Join-Path $repoRoot 'tmp\deploy-stack' }

$stateFiles = @(Get-ChildItem -LiteralPath $StateRoot -Recurse -File -Filter 'state.json' -ErrorAction SilentlyContinue)
if ($stateFiles.Count -eq 0) {
    Write-Host "no running stack state under $StateRoot"
    exit 0
}

function Get-DescendantProcessIds([int]$parentId) {
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parentId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        Get-DescendantProcessIds ([int]$child.ProcessId)
        [int]$child.ProcessId
    }
}

foreach ($stateFile in $stateFiles) {
    $state = Get-Content -LiteralPath $stateFile.FullName -Raw | ConvertFrom-Json
    foreach ($entry in @($state.processes)) {
        $processId = [int]$entry.pid
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) {
            Write-Host "$($entry.name) pid=$processId already stopped"
            continue
        }
        if ($process.ProcessName -notmatch '^java') {
            throw "拒绝停止：pid=$processId 不是 java 进程，实际=$($process.ProcessName)"
        }
        $children = @(Get-DescendantProcessIds $processId)
        foreach ($childId in $children) {
            Stop-Process -Id $childId -Force -ErrorAction SilentlyContinue
        }
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Write-Host "$($entry.name) stopped pid=$processId"
    }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stoppedPath = Join-Path $stateFile.DirectoryName "state-stopped-$stamp.json"
    $state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (Get-Date).ToString('o') -Force
    $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stoppedPath -Encoding utf8
    Remove-Item -LiteralPath $stateFile.FullName -Force
    Write-Host "stack stopped mode=$($state.mode) schema=$($state.schema) evidence=$stoppedPath"
}
