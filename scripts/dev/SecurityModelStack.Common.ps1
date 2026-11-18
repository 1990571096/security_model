# Shared helpers for the single-node deployment scripts.
# Keep this file free of side effects so it can be dot-sourced by tests.

function ConvertTo-ProcessArgument {
    param([AllowEmptyString()][string]$Value)
    if ($Value -eq '') {
        return '""'
    }
    # Start-Process joins ArgumentList with spaces. Quote any token containing
    # whitespace or a quote so jar paths and SDD roots with spaces stay one argv entry.
    if ($Value -notmatch '[ \t"]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Assert-HealthResponse {
    param(
        [int]$StatusCode,
        [string]$Body,
        [string]$Name
    )
    if ($StatusCode -ne 200) {
        throw "$Name 未就绪：HTTP $StatusCode body=$Body"
    }
    if ($Body -notmatch '"status"\s*:\s*"UP"') {
        throw "$Name 未就绪：body=$Body"
    }
}

function Wait-HealthEndpoint {
    param(
        [string]$Name,
        [string]$Url,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = ''
    while ((Get-Date) -lt $deadline) {
        $statusCode = 0
        $body = ''
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            $statusCode = [int]$response.StatusCode
            $body = $response.Content
            if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
        } catch {
            $last = $_.Exception.Message
            $response = $_.Exception.Response
            if ($response) {
                try { $statusCode = [int]$response.StatusCode } catch { $statusCode = 0 }
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message }
            }
        }
        if ($statusCode -eq 200 -and $body -match '"status"\s*:\s*"UP"') {
            Write-Host "$Name UP url=$Url"
            return
        }
        $last = "status=$statusCode body=$body"
        Start-Sleep -Seconds 2
    }
    throw "$Name 未就绪 url=$Url，最后错误=$last"
}
