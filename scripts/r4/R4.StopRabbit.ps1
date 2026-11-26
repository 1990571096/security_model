param()

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'R4.Common.ps1')
if (!(Test-Path -LiteralPath $script:R4RabbitHome)) { Write-Host 'RabbitMQ 未安装，跳过'; exit 0 }
$env:ERLANG_HOME = $script:R4ErlangHome
$env:RABBITMQ_BASE = (Join-Path $script:R4Tmp 'r4-rabbitmq')
$ctl = Join-Path $script:R4RabbitHome 'sbin\rabbitmqctl.bat'
& $ctl -n 'r4test@localhost' shutdown 2>$null
Start-Sleep -Seconds 2
$pidPath = Join-Path $env:RABBITMQ_BASE 'rabbitmq.pid'
if (Test-Path -LiteralPath $pidPath) {
    $pidValue = (Get-Content -LiteralPath $pidPath -Raw).Trim()
    if ($pidValue -match '^\d+$') {
        $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
        if ($process) { $process | Stop-Process -Force }
    }
    Remove-Item -LiteralPath $pidPath -Force
}
Write-Host 'RabbitMQ stopped'
