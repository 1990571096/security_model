param(
    [Parameter(Mandatory = $true)][string]$SchemaName,
    [Parameter(Mandatory = $true)][string]$SecretsPath,
    [int]$ApiPort = 18100,
    [int]$WorkerPort = 18101,
    [string]$JarPath = '',
    [ValidateSet('all', 'api', 'worker')][string]$Role = 'all'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'R4.Common.ps1')
$secrets = Get-R4Secret $SecretsPath
Assert-R4SchemaName $SchemaName

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $script:R4RepoRoot 'migration-backend\target\security-model-backend-0.1.0-SNAPSHOT.jar'
}
if (!(Test-Path -LiteralPath $JarPath)) { throw "后端 jar 不存在，先执行 mvn package: $JarPath" }

$stack = Join-Path $script:R4Tmp 'r4-stack'
$workspace = Join-Path $stack 'workspace'
New-Item -ItemType Directory -Force -Path $stack, $workspace | Out-Null
$pythonScript = Join-Path $script:R4RepoRoot 'scripts\r4\r4_python_stub.py'
if (!(Test-Path -LiteralPath $pythonScript)) { throw "短 Python 桩不存在: $pythonScript" }

$jwtPath = Join-Path $script:R4Tmp 'r4-jwt-secret.txt'
if (!(Test-Path -LiteralPath $jwtPath)) {
    $jwt = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 64 | ForEach-Object { [char]$_ })
    Set-Content -LiteralPath $jwtPath -Value $jwt -Encoding utf8
}
$env:SECURITY_MODEL_JWT_SECRET = (Get-Content -LiteralPath $jwtPath -Raw).Trim()

$env:JAVA_HOME = 'F:\Study\postgraduate\毕业论文\security_model\tmp\jdk17\extracted\jdk-17.0.21+9'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$env:DB_HOST = '127.0.0.1'
$env:DB_PORT = '3306'
$env:DB_NAME = $SchemaName
$env:DB_USER = 'root'
$env:DB_PWD = $secrets.mysqlRootPassword
$env:RABBITMQ_HOST = '127.0.0.1'
$env:RABBITMQ_PORT = '5673'
$env:RABBITMQ_USERNAME = $secrets.rabbitUser
$env:RABBITMQ_PASSWORD = $secrets.rabbitPassword
$env:RABBITMQ_VHOST = $secrets.vhost
$env:SECURITY_MODEL_DISPATCH_MODE = 'rabbit'
$env:SECURITY_MODEL_DISPATCH_ROLE = 'api'
$env:SECURITY_MODEL_DISPATCH_OUTBOX_RELAY_INTERVAL_MS = '1000'
$env:SECURITY_MODEL_DISPATCH_OUTBOX_CONFIRM_TIMEOUT_MS = '5000'
$env:SECURITY_MODEL_DISPATCH_WORKER_CONSUMPTION_CLAIM_SECONDS = '20'
$env:SECURITY_MODEL_DISPATCH_WORKER_MAX_RETRIES = '3'
$env:SECURITY_MODEL_DISPATCH_SCAN_INITIAL_DELAY_MS = '5000'
$env:SECURITY_MODEL_DISPATCH_SCAN_INTERVAL_MS = '5000'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT = $pythonScript
$env:SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE = 'python'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE = $workspace
$env:R4_PYTHON_MARKER = Join-Path $workspace 'r4-python-invocations.jsonl'
$env:SECURITY_MODEL_EXECUTOR_WORKSPACE = $workspace
$env:MANAGEMENT_HEALTH_RABBIT_ENABLED = 'false'

$commonArgs = @(
    '-jar', $JarPath,
    "--server.port=$ApiPort",
    "--security-model.dispatch.mode=rabbit",
    "--security-model.executor.python.script=$pythonScript",
    "--security-model.executor.workspace=$workspace"
)
$apiLog = Join-Path $stack 'api.log'
$apiErr = Join-Path $stack 'api.err.log'
$apiPid = Join-Path $stack 'api.pid'
if ($Role -in @('all', 'api')) {
    $api = Start-Process -FilePath $java -ArgumentList ($commonArgs + @('--security-model.dispatch.role=api')) `
        -WindowStyle Hidden -RedirectStandardOutput $apiLog -RedirectStandardError $apiErr -PassThru
    $api.Id | Set-Content -LiteralPath $apiPid -Encoding utf8
    Write-Host "api starting pid=$($api.Id) port=$ApiPort"
}

$workerArgs = @(
    '-jar', $JarPath,
    "--server.port=$WorkerPort",
    "--security-model.dispatch.mode=rabbit",
    "--security-model.executor.python.script=$pythonScript",
    "--security-model.executor.workspace=$workspace"
)
$workerLog = Join-Path $stack 'worker.log'
$workerErr = Join-Path $stack 'worker.err.log'
$workerPid = Join-Path $stack 'worker.pid'
if ($Role -in @('all', 'worker')) {
    $env:SECURITY_MODEL_DISPATCH_ROLE = 'worker'
    $worker = Start-Process -FilePath $java -ArgumentList ($workerArgs + @('--security-model.dispatch.role=worker')) `
        -WindowStyle Hidden -RedirectStandardOutput $workerLog -RedirectStandardError $workerErr -PassThru
    $worker.Id | Set-Content -LiteralPath $workerPid -Encoding utf8
    Write-Host "worker starting pid=$($worker.Id) port=$WorkerPort"
}

if ($Role -in @('all', 'api')) {
    & (Join-Path $PSScriptRoot 'R4.Health.ps1') -Port $ApiPort -Name 'api' -TimeoutSeconds 120
}
if ($Role -in @('all', 'worker')) {
    & (Join-Path $PSScriptRoot 'R4.Health.ps1') -Port $WorkerPort -Name 'worker' -TimeoutSeconds 120
}
Write-Host 'R4 stack ready'
