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
    [string]$KnowledgeRoot = $env:SECURITY_MODEL_KNOWLEDGE_ROOT,
    [string]$StateRoot = '',
    [switch]$EnableDeepSeek,
    [string]$DeepSeekKeyFile = $env:DEEPSEEK_API_KEY_FILE,
    [int]$HealthTimeoutSeconds = 120,
    [ValidateSet('all', 'api', 'worker')][string]$Role = 'api'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SecurityModelStack.Common.ps1')

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
if ($EnableDeepSeek) {
    if ($Role -eq 'worker') { throw '实验助手只在 API 角色启用' }
    if ([string]::IsNullOrWhiteSpace($DeepSeekKeyFile)) {
        $DeepSeekKeyFile = Join-Path $repoRoot 'secrets\deepseek.key.txt'
    }
    $keyFile = Get-Item -LiteralPath $DeepSeekKeyFile -ErrorAction SilentlyContinue
    if (!$keyFile -or $keyFile.PSIsContainer -or $keyFile.Length -eq 0) {
        throw 'DeepSeek 密钥文件不存在或为空，请检查 -DeepSeekKeyFile'
    }
    $env:DEEPSEEK_API_KEY_FILE = $keyFile.FullName
}
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $repoRoot 'migration-backend\target\security-model-backend-0.1.0-SNAPSHOT.jar'
}
if (!(Test-Path -LiteralPath $JarPath)) { throw "后端 jar 不存在，先执行 mvn package: $JarPath" }
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
if ([string]::IsNullOrWhiteSpace($KnowledgeRoot)) { $KnowledgeRoot = Join-Path $repoRoot 'knowledge-base' }
if (!(Test-Path -LiteralPath $KnowledgeRoot -PathType Container)) { throw "知识库目录不存在: $KnowledgeRoot" }
$KnowledgeRoot = (Resolve-Path -LiteralPath $KnowledgeRoot).Path
if ([string]::IsNullOrWhiteSpace($StateRoot)) {
    $StateRoot = Join-Path $repoRoot ("tmp\deploy-stack\{0}\{1}" -f $SchemaName, $Mode)
}
New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (!(Test-Path -LiteralPath $java) -and $java -ne 'java') { throw "JAVA_HOME 下没有 java.exe: $java" }
if ($java -ne 'java') { $java = (Resolve-Path -LiteralPath $java).Path }

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
$env:SECURITY_MODEL_KNOWLEDGE_ROOT = $KnowledgeRoot
$env:SECURITY_MODEL_KNOWLEDGE_EMBEDDING_ENABLED = 'false'
$env:SECURITY_MODEL_KNOWLEDGE_VECTOR_ENABLED = 'false'
$env:SECURITY_MODEL_KNOWLEDGE_MODEL_ENABLED = 'false'
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
    "--security-model.knowledge.root=$KnowledgeRoot",
    '--security-model.knowledge.embedding.enabled=false',
    '--security-model.knowledge.vector.enabled=false',
    '--security-model.knowledge.model.enabled=false'
)

function Assert-PortFree([int]$port, [string]$name) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        throw "$name 端口已被占用: $port (pid=$($listeners[0].OwningProcess))"
    }
}

if ($Role -in @('all', 'api')) { Assert-PortFree $ApiPort 'api' }
if ($Role -in @('all', 'worker')) { Assert-PortFree $WorkerPort 'worker' }

function Get-ProcessIdentity([System.Diagnostics.Process]$process) {
    $process.Refresh()
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction SilentlyContinue
    if (-not $cim) {
        throw "无法读取进程身份: pid=$($process.Id)"
    }
    $executablePath = $process.Path
    if ([string]::IsNullOrWhiteSpace($executablePath)) { $executablePath = $cim.ExecutablePath }
    return [ordered]@{
        pid = $process.Id
        processStartTime = $process.StartTime.ToUniversalTime().ToString('o')
        executablePath = $executablePath
        commandLine = $cim.CommandLine
    }
}

function Start-StackProcess([string]$name, [string]$role, [int]$port) {
    $log = Join-Path $StateRoot "$name.log"
    $err = Join-Path $StateRoot "$name.err.log"
    $env:SECURITY_MODEL_DISPATCH_ROLE = $role
    $args = $commonArgs + @("--server.port=$port", "--security-model.dispatch.role=$role")
    if ($EnableDeepSeek -and $role -eq 'api') {
        $args += @('--spring.profiles.include=deepseek', '--security-model.assistant.model.enabled=true',
            '--security-model.assistant.model.provider=deepseek')
    } else {
        $args += @('--security-model.assistant.model.enabled=false', '--security-model.assistant.model.provider=disabled')
    }
    $argString = ($args | ForEach-Object { ConvertTo-ProcessArgument ([string]$_) }) -join ' '
    $process = Start-Process -FilePath $java -ArgumentList $argString -WorkingDirectory $repoRoot `
        -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err -PassThru
    Start-Sleep -Milliseconds 500
    if ($process.HasExited) {
        $tail = if (Test-Path -LiteralPath $err) { (Get-Content -LiteralPath $err -Tail 20) -join "`n" } else { 'no stderr' }
        throw "$name 启动后立即退出 pid=$($process.Id): $tail"
    }
    return [ordered]@{
        process = $process
        identity = (Get-ProcessIdentity $process)
        role = $role
        port = $port
    }
}

function Wait-StackHealth([string]$name, [int]$port) {
    $base = "http://127.0.0.1:$port"
    Wait-HealthEndpoint -Name "$name liveness" -Url "$base/actuator/health/liveness" -TimeoutSeconds $HealthTimeoutSeconds
    Wait-HealthEndpoint -Name "$name readiness" -Url "$base/actuator/health/readiness" -TimeoutSeconds $HealthTimeoutSeconds
    if ($Mode -eq 'rabbit') {
        # rabbit 栈还必须确认 broker 连接健康，不能只看 API 的 scheduling readiness。
        Wait-HealthEndpoint -Name "$name health" -Url "$base/actuator/health" -TimeoutSeconds $HealthTimeoutSeconds
    }
    Write-Host "$name scheduling readiness UP port=$port"
}

$started = [ordered]@{}
try {
    if ($Role -in @('all', 'api')) {
        $api = Start-StackProcess 'api' 'api' $ApiPort
        $started.api = $api
        Write-Host "api starting pid=$($api.process.Id) port=$ApiPort"
        Wait-StackHealth 'api' $ApiPort
    }
    if ($Role -in @('all', 'worker')) {
        $worker = Start-StackProcess 'worker' 'worker' $WorkerPort
        $started.worker = $worker
        Write-Host "worker starting pid=$($worker.process.Id) port=$WorkerPort"
        Wait-StackHealth 'worker' $WorkerPort
    }

    $state = [ordered]@{
        schema = $SchemaName
        mode = $Mode
        role = $Role
        apiPort = $ApiPort
        workerPort = $(if ($Mode -eq 'rabbit' -and $Role -in @('all', 'worker')) { $WorkerPort } else { $null })
        dbHost = $DbHost
        dbPort = $DbPort
        dbUser = $DbUser
        jarPath = $JarPath
        javaPath = $java
        knowledgeRoot = $KnowledgeRoot
        startedAt = (Get-Date).ToString('o')
        processes = @(
            foreach ($entry in $started.GetEnumerator()) {
                $identity = $entry.Value.identity
                [ordered]@{
                    name = $entry.Key
                    role = $entry.Value.role
                    pid = $identity.pid
                    port = $entry.Value.port
                    processStartTime = $identity.processStartTime
                    executablePath = $identity.executablePath
                    commandLine = $identity.commandLine
                    jarPath = $JarPath
                }
            }
        )
    }
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "stack ready mode=$Mode schema=$SchemaName state=$statePath"
} catch {
    foreach ($entry in $started.GetEnumerator()) {
        if ($entry.Value -and $entry.Value.process -and -not $entry.Value.process.HasExited) {
            Stop-Process -Id $entry.Value.process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    throw
}
