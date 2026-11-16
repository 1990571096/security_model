# Invoke Maven without the mvn.cmd tail `cmd /C exit /B` line. On this machine
# `cmd` is not always resolvable from the PowerShell host, which makes a successful
# Maven build return 1. MAVEN_TERMINATE_CMD=on uses the wrapper's own `exit` instead.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Arguments
)

$env:MAVEN_TERMINATE_CMD = 'on'
& "$env:MAVEN_HOME\bin\mvn.cmd" @Arguments
if ($null -eq $LASTEXITCODE) {
    exit 0
}
exit $LASTEXITCODE
