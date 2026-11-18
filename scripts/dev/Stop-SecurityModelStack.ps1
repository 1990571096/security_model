param(
    [string]$StateRoot = '',
    [switch]$Force,
    [string]$DbPassword = $env:MYSQL_IT_PASSWORD,
    [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [int]$DrainTimeoutSeconds = 60
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

function Normalize-ExecutablePath([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return '' }
    return [IO.Path]::GetFullPath($path).TrimEnd('\', '/')
}

function Assert-ProcessIdentity($entry, [System.Diagnostics.Process]$process) {
    $pidValue = [int]$entry.pid
    if ([string]::IsNullOrWhiteSpace([string]$entry.processStartTime) -or
        [string]::IsNullOrWhiteSpace([string]$entry.executablePath) -or
        [string]::IsNullOrWhiteSpace([string]$entry.jarPath)) {
        throw "拒绝停止：pid=$pidValue 的运行记录缺少进程身份字段，保留 state 并请人工核对"
    }
    if ($process.ProcessName -notmatch '^java') {
        throw "拒绝停止：pid=$pidValue 不是 java 进程，实际=$($process.ProcessName)；保留 state"
    }

    $actualPath = $process.Path
    if ([string]::IsNullOrWhiteSpace($actualPath)) {
        $cimPath = Get-CimInstance Win32_Process -Filter "ProcessId=$pidValue" -ErrorAction SilentlyContinue
        $actualPath = if ($cimPath) { $cimPath.ExecutablePath } else { '' }
    }
    if ([string]::IsNullOrWhiteSpace($actualPath) -or
        (Normalize-ExecutablePath $actualPath) -ne (Normalize-ExecutablePath ([string]$entry.executablePath))) {
        throw "拒绝停止：pid=$pidValue 可执行文件不匹配，记录=$($entry.executablePath) 实际=$actualPath；保留 state"
    }

    $recordedStartUtc = ([DateTimeOffset]::Parse([string]$entry.processStartTime)).UtcDateTime
    $actualStartUtc = $process.StartTime.ToUniversalTime()
    if ([Math]::Abs(($actualStartUtc - $recordedStartUtc).TotalSeconds) -gt 5) {
        throw "拒绝停止：pid=$pidValue 启动时间不匹配，记录=$recordedStartUtc 实际=$actualStartUtc；保留 state"
    }

    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$pidValue" -ErrorAction SilentlyContinue
    if (-not $cim -or [string]::IsNullOrWhiteSpace($cim.CommandLine) -or
        $cim.CommandLine.IndexOf([string]$entry.jarPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "拒绝停止：pid=$pidValue 命令行未包含记录 jar=$($entry.jarPath)；保留 state"
    }
}

function Assert-PortReleased([int]$port, [string]$name) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        throw "$name 端口仍未释放: $port (pid=$($listeners[0].OwningProcess))；保留 state"
    }
}

function Invoke-MySqlRunningTaskCount($state) {
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        throw '正常停止需要 -DbPassword 或 MYSQL_IT_PASSWORD 以检查任务排空；如确认无任务可显式 -Force'
    }
    $mysql = if ($env:MYSQL_EXE) { $env:MYSQL_EXE } else { $MysqlExe }
    if (!(Test-Path -LiteralPath $mysql)) { throw "mysql 客户端不存在: $mysql" }
    $schema = [string]$state.schema
    if ($schema -notmatch '^security_model(_[a-z0-9_]+)?$') {
        throw "拒绝操作：state schema 不合法: $schema"
    }
    $query = "SELECT COUNT(*) FROM ``$schema``.``sys_pre_defense`` WHERE status='running';"
    $oldPassword = $env:MYSQL_PWD
    $env:MYSQL_PWD = $DbPassword
    try {
        $output = & $mysql -h ([string]$state.dbHost) -P ([string]$state.dbPort) `
            -u ([string]$state.dbUser) -N -B -e $query 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "查询运行任务失败: $($output -join ' ')；保留 state"
        }
        return [int](($output | Select-Object -Last 1).ToString().Trim())
    } finally {
        if ($null -eq $oldPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $oldPassword }
    }
}

function Wait-StackDrain($state) {
    if ($Force) {
        Write-Host "force stop requested; skip task drain check"
        return 0
    }
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSeconds)
    $lastCount = 0
    do {
        $lastCount = Invoke-MySqlRunningTaskCount $state
        if ($lastCount -eq 0) {
            Write-Host "task drain confirmed running=0"
            return 0
        }
        Write-Host "waiting for task drain running=$lastCount"
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw "任务未排空 running=$lastCount；拒绝停止并保留 state。确认可中断后使用 -Force"
}

function Stop-VerifiedProcessTree($entry, [System.Diagnostics.Process]$process) {
    Assert-ProcessIdentity $entry $process
    $children = @(Get-DescendantProcessIds ([int]$entry.pid))
    foreach ($childId in $children) {
        $child = Get-Process -Id $childId -ErrorAction SilentlyContinue
        if (-not $child) { continue }
        try {
            Stop-Process -Id $childId -ErrorAction Stop
        } catch {
            if (-not $Force) {
                throw "停止子进程失败 pid=${childId}: $($_.Exception.Message)；保留 state；可显式 -Force"
            }
            Stop-Process -Id $childId -Force -ErrorAction Stop
        }
        Wait-Process -Id $childId -Timeout 5 -ErrorAction SilentlyContinue
        if (Get-Process -Id $childId -ErrorAction SilentlyContinue) {
            if (-not $Force) {
                throw "子进程未退出 pid=$childId；保留 state；可显式 -Force"
            }
            Stop-Process -Id $childId -Force -ErrorAction Stop
        }
    }

    try {
        Stop-Process -Id ([int]$entry.pid) -ErrorAction Stop
    } catch {
        if (-not $Force) {
            throw "停止进程失败 pid=$($entry.pid): $($_.Exception.Message)；保留 state；可显式 -Force"
        }
        Stop-Process -Id ([int]$entry.pid) -Force -ErrorAction Stop
    }
    Wait-Process -Id ([int]$entry.pid) -Timeout 10 -ErrorAction SilentlyContinue
    if (Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue) {
        if (-not $Force) {
            throw "进程未退出 pid=$($entry.pid)；保留 state；可显式 -Force"
        }
        Stop-Process -Id ([int]$entry.pid) -Force -ErrorAction Stop
        Wait-Process -Id ([int]$entry.pid) -Timeout 5 -ErrorAction SilentlyContinue
        if (Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue) {
            throw "强制后进程仍未退出 pid=$($entry.pid)；保留 state"
        }
    }
}

foreach ($stateFile in $stateFiles) {
    $state = Get-Content -LiteralPath $stateFile.FullName -Raw | ConvertFrom-Json -DateKind String
    $drained = Wait-StackDrain $state

    foreach ($entry in @($state.processes)) {
        $processId = [int]$entry.pid
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) {
            Write-Host "$($entry.name) pid=$processId already stopped"
            continue
        }
        Stop-VerifiedProcessTree $entry $process
        Write-Host "$($entry.name) stopped pid=$processId"
    }

    foreach ($entry in @($state.processes)) {
        if ($null -ne $entry.port) { Assert-PortReleased ([int]$entry.port) ([string]$entry.name) }
    }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
    $stoppedPath = Join-Path $stateFile.DirectoryName "state-stopped-$stamp.json"
    $state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (Get-Date).ToString('o') -Force
    $state | Add-Member -NotePropertyName stopForced -NotePropertyValue ([bool]$Force) -Force
    $state | Add-Member -NotePropertyName drainedRunningTasks -NotePropertyValue $drained -Force
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $stoppedPath -Encoding utf8
    Remove-Item -LiteralPath $stateFile.FullName -Force
    Write-Host "stack stopped mode=$($state.mode) schema=$($state.schema) evidence=$stoppedPath"
}
