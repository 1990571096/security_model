param(
    [ValidateSet('db', 'rabbit')][string]$Mode = 'db',
    [string]$SchemaName = 'security_model',
    [int]$ApiPort = 8100,
    [int]$WorkerPort = 8101,
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$DbUser = 'root',
    [string]$DbPassword = $env:MYSQL_IT_PASSWORD,
    [string]$RabbitHost = '127.0.0.1',
    [int]$RabbitPort = 5672,
    [string]$RabbitUser = 'guest',
    [string]$RabbitPassword = 'guest',
    [string]$RabbitVhost = '/',
    [string]$JarPath = '',
    [string]$JwtSecret = $env:SECURITY_MODEL_JWT_SECRET,
    [string]$SddRoot = '',
    [string]$StateRoot = '',
    [int]$HealthTimeoutSeconds = 120,
    [ValidateSet('all', 'api', 'worker')][string]$Role = 'all'
)

$ErrorActionPreference = 'Stop'
if ($SchemaName -notmatch '^security_model(_[a-z0-9_]+)?$') {
    throw "拒绝启动：schema 必须使用 security_model 前缀，当前=$SchemaName"
}
if ($Mode -eq 'db' -and $Role -ne 'api') {
    throw 'db 模式只允许 Role=api；独立 Worker 只能用于 rabbit 模式'
}
if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    throw '需要 MYSQL_IT_PASSWORD 或 -DbPassword（不要写入仓库）'
}
if ([string]::IsNullOrWhiteSpace($JwtSecret) -or $JwtSecret.Length -lt 32) {
    throw '需要至少 32 字节的 SECURITY_MODEL_JWT_SECRET 或 -JwtSecret'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $repoRoot 'migration-backend\target\security-model-backend-0.1.0-SNAPSHOT.jar'
}
if (!(Test-Path -LiteralPath $JarPath)) { throw "后端 jar 不存在，先执行 mvn package: $JarPath" }
if ([string]::IsNullOrWhiteSpace($SddRoot)) { $SddRoot = Join-Path $repoRoot 'docs\sdd' }
if (!(Test-Path -LiteralPath $SddRoot -PathType Container)) { throw "SDD 目录不存在: $SddRoot" }
if ([string]::IsNullOrWhiteSpace($StateRoot)) {
    $StateRoot = Join-Path $repoRoot ("tmp\deploy-stack\{0}\{1}" -f $SchemaName, $Mode)
}
New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (!(Test-Path -LiteralPath $java) -and $java -ne 'java') { throw "JAVA_HOME 下没有 java.exe: $java" }

$statePath = Join-Path $StateRoot 'state.json'
if (Test-Path -LiteralPath $statePath) {
    throw "该 StateRoot 已有运行记录，先执行 Stop-SecurityModelStack.ps1: $statePath"
}

$env:DB_HOST = $DbHost
$env:DB_PORT = "$DbPort"
$env:DB_NAME = $SchemaName
$env:DB_USER = $DbUser
$env:DB_PWD = $DbPassword
$env:SECURITY_MODEL_JWT_SECRET = $JwtSecret
$env:SECURITY_MODEL_KNOWLEDGE_SDD_ROOT = $SddRoot
$env:SECURITY_MODEL_KNOWLEDGE_EMBEDDING_ENABLED = 'false'
$env:SECURITY_MODEL_KNOWLEDGE_VECTOR_ENABLED = 'false'
$env:SECURITY_MODEL_KNOWLEDGE_MODEL_ENABLED = 'false'
$env:SECURITY_MODEL_ASSISTANT_MODEL_ENABLED = 'false'
$env:SECURITY_MODEL_ASSISTANT_MODEL_PROVIDER = 'disabled'
$env:SECURITY_MODEL_DISPATCH_MODE = $Mode
$env:RABBITMQ_HOST = $RabbitHost
$env:RABBITMQ_PORT = "$RabbitPort"
$env:RABBITMQ_USERNAME = $RabbitUser
$env:RABBITMQ_PASSWORD = $RabbitPassword
$env:RABBITMQ_VHOST = $RabbitVhost
# rabbit 模式下让 /actuator/health 真正包含 broker 连接；db 模式不引入 Rabbit 健康项。
$env:MANAGEMENT_HEALTH_RABBIT_ENABLED = $(if ($Mode -eq 'rabbit') { 'true' } else { 'false' })

$commonArgs = @(
    '-jar', $JarPath,
    "--security-model.dispatch.mode=$Mode",
    "--security-model.knowledge.sdd-root=$SddRoot",
    '--security-model.knowledge.embedding.enabled=false',
    '--security-model.knowledge.vector.enabled=false',
    '--security-model.knowledge.model.enabled=false',
    '--security-model.assistant.model.enabled=false'
)

function Assert-PortFree([int]$port, [string]$name) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        throw "$name 端口已被占用: $port (pid=$($listeners[0].OwningProcess))"
    }
}

if ($Role -in @('all', 'api')) { Assert-PortFree $ApiPort 'api' }
if ($Role -in @('all', 'worker')) { Assert-PortFree $WorkerPort 'worker' }

function Start-StackProcess([string]$name, [string]$role, [int]$port) {
    $log = Join-Path $StateRoot "$name.log"
    $err = Join-Path $StateRoot "$name.err.log"
    $env:SECURITY_MODEL_DISPATCH_ROLE = $role
    $args = $commonArgs + @("--server.port=$port", "--security-model.dispatch.role=$role")
    $process = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $repoRoot `
        -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err -PassThru
    Start-Sleep -Milliseconds 300
    return $process
}

function Wait-StackHealth([string]$name, [int]$port) {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    $last = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/health" -UseBasicParsing -TimeoutSec 5
            $body = $response.Content
            if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
            if ($response.StatusCode -eq 200 -and $body -match '"status"\s*:\s*"UP"') {
                Write-Host "$name health UP port=$port"
                return
            }
            $last = "status=$($response.StatusCode) body=$body"
        } catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "$name health 未就绪 port=$port，最后错误=$last"
}

$started = [ordered]@{}
try {
    if ($Role -in @('all', 'api')) {
        $api = Start-StackProcess 'api' 'api' $ApiPort
        $started.api = $api
        Write-Host "api starting pid=$($api.Id) port=$ApiPort"
        Wait-StackHealth 'api' $ApiPort
    }
    if ($Role -in @('all', 'worker')) {
        $worker = Start-StackProcess 'worker' 'worker' $WorkerPort
        $started.worker = $worker
        Write-Host "worker starting pid=$($worker.Id) port=$WorkerPort"
        Wait-StackHealth 'worker' $WorkerPort
    }

    $state = [ordered]@{
        schema = $SchemaName
        mode = $Mode
        role = $Role
        apiPort = $ApiPort
        workerPort = $(if ($Mode -eq 'rabbit' -and $Role -in @('all', 'worker')) { $WorkerPort } else { $null })
        startedAt = (Get-Date).ToString('o')
        processes = @(
            foreach ($entry in $started.GetEnumerator()) {
                [ordered]@{ name = $entry.Key; pid = $entry.Value.Id; port = $(if ($entry.Key -eq 'api') { $ApiPort } else { $WorkerPort }) }
            }
        )
    }
    $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "stack ready mode=$Mode schema=$SchemaName state=$statePath"
} catch {
    foreach ($entry in $started.GetEnumerator()) {
        if ($entry.Value -and -not $entry.Value.HasExited) {
            Stop-Process -Id $entry.Value.Id -Force -ErrorAction SilentlyContinue
        }
    }
    throw
}
