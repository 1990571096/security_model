param(
    [Parameter(Mandatory = $true)][string]$SchemaName,
    [string]$RootPassword = $env:MYSQL_IT_PASSWORD
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'R4.Common.ps1')
Assert-R4SchemaName $SchemaName
if ([string]::IsNullOrWhiteSpace($RootPassword)) { throw '需要 MYSQL_IT_PASSWORD 或 -RootPassword' }

$sql = @"
SELECT
  (SELECT COUNT(*) FROM sys_dispatch_outbox WHERE status IN ('new','failed','publishing')) AS unpublished_outbox,
  (SELECT COUNT(*) FROM sys_dispatch_outbox WHERE status = 'publishing') AS publishing_outbox,
  (SELECT COUNT(*) FROM sys_dispatch_consumption WHERE status = 'processing') AS processing_consumption,
  (SELECT COUNT(*) FROM sys_pre_defense WHERE status = 'running') AS running_tasks;
"@
$rows = Invoke-R4Mysql $SchemaName $sql $RootPassword
$line = ($rows | Select-Object -Last 1).Trim()
$parts = $line -split "`t"
$unpublished = [int]$parts[0]
$publishing = [int]$parts[1]
$processing = [int]$parts[2]
$running = [int]$parts[3]
Write-Host "drain check: unpublished=$unpublished publishing=$publishing processing=$processing running=$running"
if ($unpublished -gt 0 -or $publishing -gt 0 -or $processing -gt 0 -or $running -gt 0) {
    throw '拒绝切换/回退：仍有未发布 Outbox、处理中消费或 running 任务；先 drain'
}
Write-Host 'drain check passed'
