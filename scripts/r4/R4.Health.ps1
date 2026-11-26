param(
    [Parameter(Mandatory = $true)][int]$Port,
    [string]$Name = 'process',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$last = ''
while ((Get-Date) -lt $deadline) {
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/actuator/health" -UseBasicParsing -TimeoutSec 5
        $body = $response.Content
        if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
        if ($response.StatusCode -eq 200 -and $body -match '"status"\s*:\s*"UP"') {
            Write-Host "$Name health UP port=$Port"
            exit 0
        }
        $last = "status=$($response.StatusCode) body=$body"
    } catch {
        $last = $_.Exception.Message
    }
    Start-Sleep -Seconds 2
}
throw "$Name health 未就绪 port=$Port，最后错误=$last"
