param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'SecurityModelStack.Common.ps1')

$failures = New-Object System.Collections.Generic.List[string]
function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { $script:failures.Add($message) }
}

# 1) 参数引号化：含空格路径必须整体带引号，普通开关不能带引号。
$quoted = ConvertTo-ProcessArgument 'C:\path with space\security-model.jar'
Assert-True ($quoted -eq '"C:\path with space\security-model.jar"') "含空格路径未正确加引号: $quoted"
$plain = ConvertTo-ProcessArgument '-jar'
Assert-True ($plain -eq '-jar') "普通参数被错误加引号: $plain"

# 2) 默认值必须一致：无参数运行应落到 db + api，而不是被自身校验拒绝。
$tokens = $null
$parseErrors = $null
$startAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'Start-SecurityModelStack.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors -and $parseErrors.Count -gt 0) {
    throw "Start-SecurityModelStack.ps1 解析失败: $($parseErrors[0].Message)"
}
$defaults = @{}
foreach ($parameter in $startAst.ParamBlock.Parameters) {
    $name = $parameter.Name.VariablePath.UserPath
    if ($parameter.DefaultValue) {
        $defaults[$name] = $parameter.DefaultValue.Extent.Text.Trim().Trim("'").Trim('"')
    }
}
Assert-True ($defaults['Mode'] -eq 'db') "Start 默认 Mode 不是 db: $($defaults['Mode'])"
Assert-True ($defaults['Role'] -eq 'api') "Start 默认 Role 不是 api: $($defaults['Role'])"

# 3) 身份校验失败必须拒绝停止并保留 state。当前 pwsh 不是 java，且伪造 state 指向自己。
$stateRoot = Join-Path ([IO.Path]::GetTempPath()) "security-model-scripttest-$PID"
New-Item -ItemType Directory -Force -Path $stateRoot | Out-Null
$statePath = Join-Path $stateRoot 'state.json'
$current = Get-Process -Id $PID
$cim = Get-CimInstance Win32_Process -Filter "ProcessId=$PID" -ErrorAction SilentlyContinue
$state = [ordered]@{
    mode = 'db'
    role = 'api'
    schema = 'security_model_deploy_ci'
    dbHost = '127.0.0.1'
    dbPort = 3306
    dbUser = 'root'
    processes = @([ordered]@{
        name = 'api'
        role = 'api'
        pid = $PID
        port = 18299
        processStartTime = $current.StartTime.ToUniversalTime().ToString('o')
        executablePath = $current.Path
        commandLine = if ($cim) { $cim.CommandLine } else { '' }
        jarPath = 'C:\tmp\security-model-dummy.jar'
    })
}
$state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8

$output = & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'Stop-SecurityModelStack.ps1') `
    -StateRoot $stateRoot -Force 2>&1 | Out-String
$stopExit = $LASTEXITCODE
Assert-True ($stopExit -ne 0) "伪造身份在 -Force 下仍被接受停止"
Assert-True (Test-Path -LiteralPath $statePath) "身份校验失败后 state.json 被删除"
Assert-True ($output -match '不是 java 进程') "缺少身份拒绝原因: $output"
Assert-True ([bool](Get-Process -Id $PID -ErrorAction SilentlyContinue)) "身份校验失败后当前测试进程被误杀"
Remove-Item -LiteralPath $stateRoot -Recurse -Force -ErrorAction SilentlyContinue

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Host "FAIL $failure" }
    exit 1
}
Write-Host 'deployment script targeted checks OK: quoting, defaults, identity refusal, state retention'
exit 0
