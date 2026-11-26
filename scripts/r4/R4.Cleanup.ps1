param(
    [Parameter(Mandatory = $true)][string]$SchemaName,
    [Parameter(Mandatory = $true)][string]$SecretsPath,
    [string]$RootPassword = $env:MYSQL_IT_PASSWORD
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'R4.Common.ps1')
$secrets = Get-R4Secret $SecretsPath
Assert-R4SchemaName $SchemaName
Assert-R4Vhost $secrets.vhost
if ([string]::IsNullOrWhiteSpace($RootPassword)) { throw '需要 MYSQL_IT_PASSWORD 或 -RootPassword' }

& (Join-Path $PSScriptRoot 'R4.StopStack.ps1')

$env:ERLANG_HOME = $script:R4ErlangHome
$env:RABBITMQ_BASE = (Join-Path $script:R4Tmp 'r4-rabbitmq')
$ctl = Join-Path $script:R4RabbitHome 'sbin\rabbitmqctl.bat'
if (Test-Path -LiteralPath $ctl) {
    & $ctl -n 'r4test@localhost' delete_vhost $secrets.vhost 2>$null
    & $ctl -n 'r4test@localhost' delete_user $secrets.rabbitUser 2>$null
}

Invoke-R4Mysql $SchemaName "DROP DATABASE IF EXISTS $SchemaName" $RootPassword
Write-Host "R4 isolated resources removed: schema=$SchemaName vhost=$($secrets.vhost)"
