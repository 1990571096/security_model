param([ValidateSet('all', 'api', 'worker')][string]$Role = 'all')

$ErrorActionPreference = 'Continue'
$stack = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path 'tmp\r4-stack'
$names = switch ($Role) {
    'api' { @('api') }
    'worker' { @('worker') }
    default { @('worker', 'api') }
}
foreach ($name in $names) {
    $pidPath = Join-Path $stack "$name.pid"
    if (!(Test-Path -LiteralPath $pidPath)) { continue }
    $value = (Get-Content -LiteralPath $pidPath -Raw).Trim()
    if ($value -notmatch '^\d+$') { continue }
    $process = Get-Process -Id ([int]$value) -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "stopping $name pid=$value"
        Stop-Process -Id ([int]$value) -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
}
Write-Host 'R4 API/Worker stopped'
