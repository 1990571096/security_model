param(
    [string]$NodePort = '5673',
    [Parameter(Mandatory = $true)][string]$SecretsPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'R4.Common.ps1')
$secrets = Get-R4Secret $SecretsPath
Assert-R4Vhost $secrets.vhost

if (!(Test-Path -LiteralPath $script:R4RabbitHome)) { throw "RabbitMQ 未解压: $script:R4RabbitHome" }
if (!(Test-Path -LiteralPath (Join-Path $script:R4ErlangHome 'bin\erl.exe'))) {
    throw "Erlang 未安装: $script:R4ErlangHome"
}

$base = Join-Path $script:R4Tmp 'r4-rabbitmq'
$configDir = Join-Path $base 'config'
$dataDir = Join-Path $base 'data'
$logDir = Join-Path $base 'log'
New-Item -ItemType Directory -Force -Path $configDir, $dataDir, $logDir | Out-Null
$configPath = Join-Path $configDir 'rabbitmq.conf'
@"
listeners.tcp.local = 127.0.0.1:$NodePort
consumer_timeout = 7200000
loopback_users.guest = false
"@ | Set-Content -LiteralPath $configPath -Encoding utf8

$env:ERLANG_HOME = $script:R4ErlangHome
$env:Path = "$script:R4ErlangHome\bin;$script:R4RabbitHome\sbin;$env:Path"
$env:RABBITMQ_BASE = $base
$env:RABBITMQ_CONFIG_FILE = (Join-Path $configDir 'rabbitmq')
$env:RABBITMQ_MNESIA_BASE = $dataDir
$env:RABBITMQ_LOG_BASE = $logDir
$env:RABBITMQ_NODENAME = 'r4test@localhost'
$env:RABBITMQ_NODE_PORT = $NodePort

$stdout = Join-Path $logDir 'rabbitmq-stdout.log'
$stderr = Join-Path $logDir 'rabbitmq-stderr.log'
$pidPath = Join-Path $base 'rabbitmq.pid'
$server = Get-Process -Name erl -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*Erlang OTP*" }
if (-not $server) {
    $process = Start-Process -FilePath (Join-Path $script:R4RabbitHome 'sbin\rabbitmq-server.bat') `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $process.Id | Set-Content -LiteralPath $pidPath -Encoding utf8
    Write-Host "rabbitmq-server starting pid=$($process.Id)"
}

$ctl = Join-Path $script:R4RabbitHome 'sbin\rabbitmqctl.bat'
$deadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep -Seconds 2
    & $ctl -n 'r4test@localhost' -q ping 2>$null
    if ($LASTEXITCODE -eq 0) { break }
} while ((Get-Date) -lt $deadline)
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ 未在 90 秒内就绪，查看 tmp/r4-rabbitmq/log' }
& $ctl -n 'r4test@localhost' await_startup
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ broker 应用未启动完成' }

& $ctl -n 'r4test@localhost' add_vhost $secrets.vhost 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "vhost 已存在或创建失败，继续校验: $($secrets.vhost)" }
& $ctl -n 'r4test@localhost' add_user $secrets.rabbitUser $secrets.rabbitPassword 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "用户已存在或创建失败，继续校验: $($secrets.rabbitUser)" }
& $ctl -n 'r4test@localhost' set_permissions -p $secrets.vhost $secrets.rabbitUser '.*' '.*' '.*'
& $ctl -n 'r4test@localhost' list_vhosts
Write-Host "RabbitMQ ready: localhost:$NodePort vhost=$($secrets.vhost) user=$($secrets.rabbitUser)"
