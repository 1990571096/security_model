param(
    [int]$StaticPort = 18220,
    [int]$ApiPort = 18200,
    [string]$DistRoot = '',
    [string]$StateRoot = '',
    [string]$NodeExe = 'node',
    [int]$HealthTimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SecurityModelStack.Common.ps1')
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($DistRoot)) { $DistRoot = Join-Path $repoRoot 'migration-frontend\dist' }
if (!(Test-Path -LiteralPath (Join-Path $DistRoot 'index.html'))) {
    throw "前端 dist 不存在或不完整，先执行 npm run build: $DistRoot"
}
$DistRoot = (Resolve-Path -LiteralPath $DistRoot).Path
$serverScript = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'frontend-static-server.mjs')).Path

if ([string]::IsNullOrWhiteSpace($StateRoot)) { $StateRoot = Join-Path $repoRoot 'tmp\deploy-stack\frontend' }
New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null
$statePath = Join-Path $StateRoot 'state.json'
if (Test-Path -LiteralPath $statePath) { throw "该 StateRoot 已有运行记录: $statePath" }

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $StaticPort -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) { throw "前端端口已被占用: $StaticPort (pid=$($listeners[0].OwningProcess))" }

$node = Get-Command $NodeExe -ErrorAction SilentlyContinue
if (-not $node) { throw "找不到 node: $NodeExe" }
$nodePath = $node.Source

$env:STATIC_ROOT = $DistRoot
$env:STATIC_PORT = "$StaticPort"
$env:API_TARGET = "http://127.0.0.1:$ApiPort"
$log = Join-Path $StateRoot 'frontend.log'
$err = Join-Path $StateRoot 'frontend.err.log'
$process = Start-Process -FilePath $nodePath -ArgumentList (ConvertTo-ProcessArgument $serverScript) `
    -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err -PassThru
Start-Sleep -Milliseconds 500
if ($process.HasExited) {
    $tail = if (Test-Path -LiteralPath $err) { (Get-Content -LiteralPath $err -Tail 20) -join "`n" } else { 'no stderr' }
    throw "前端静态服务启动后立即退出 pid=$($process.Id): $tail"
}
$cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
$identity = [ordered]@{
    pid = $process.Id
    processStartTime = $process.StartTime.ToUniversalTime().ToString('o')
    executablePath = $process.Path
    commandLine = $cim.CommandLine
}
try {
    Wait-HealthEndpoint -Name 'frontend static' -Url "http://127.0.0.1:$StaticPort/healthz" -TimeoutSeconds $HealthTimeoutSeconds
    $state = [ordered]@{
        kind = 'frontend-static'
        staticPort = $StaticPort
        apiPort = $ApiPort
        distRoot = $DistRoot
        serverScript = $serverScript
        startedAt = (Get-Date).ToString('o')
        processes = @([ordered]@{
            name = 'frontend'
            pid = $identity.pid
            port = $StaticPort
            processStartTime = $identity.processStartTime
            executablePath = $identity.executablePath
            commandLine = $identity.commandLine
        })
    }
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "frontend static ready url=http://127.0.0.1:$StaticPort state=$statePath"
} catch {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    throw
}
