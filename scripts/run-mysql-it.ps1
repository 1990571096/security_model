# Runs the MySQL-backed ownership tests without ever writing the password to disk,
# the repository or the shell history: the password is read interactively and only
# lives in this PowerShell process's environment.
#
#   pwsh -File scripts\run-mysql-it.ps1
#
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-20' }
$env:Path = 'C:\Users\Jin\.trae\tools\maven\latest\bin;' + (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path

$secure = Read-Host -AsSecureString 'MySQL password for MYSQL_IT_USERNAME (root by default)'
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$env:MYSQL_IT_PASSWORD = $plain

try {
    & mvn -B -f (Join-Path $repo 'migration-backend\pom.xml') verify -Pmysql-it
    exit $LASTEXITCODE
} finally {
    Remove-Item Env:\MYSQL_IT_PASSWORD -ErrorAction SilentlyContinue
}
