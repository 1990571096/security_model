param(
    [string]$PythonExe = $env:SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE,
    [string]$VabRoot = $env:VAB_ROOT,
    [string]$DatasetRoot = $env:SECURITY_MODEL_VAB_DATASET_ROOT,
    [string]$ExecutorScript = $env:SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT,
    [string]$Workspace = $env:SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE,
    [string]$ProcessGuardWorkspace = $env:SECURITY_MODEL_EXECUTOR_WORKSPACE,
    [string]$ResultRoot = $env:SECURITY_MODEL_VAB_RESULT_ROOT,
    [string]$RequestFile = '',
    [int]$MinFreeDiskGb = 20,
    [string]$JsonOutput = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$checks = New-Object System.Collections.Generic.List[object]
$errors = 0
$warnings = 0

function Add-Check([string]$name, [bool]$ok, [string]$detail, [string]$severity = 'error') {
    $checks.Add([ordered]@{
        name = $name
        ok = $ok
        severity = $severity
        detail = $detail
    })
    if (-not $ok) {
        if ($severity -eq 'error') { $script:errors++ } else { $script:warnings++ }
    }
}

function Resolve-RequiredPath([string]$path, [string]$label) {
    if ([string]::IsNullOrWhiteSpace($path)) {
        Add-Check $label $false "$label 未提供；请通过参数或环境变量指定" 'error'
        return $null
    }
    try {
        $resolved = (Resolve-Path -LiteralPath $path -ErrorAction Stop).Path
        Add-Check $label $true $resolved 'error'
        return $resolved
    } catch {
        Add-Check $label $false "路径不存在或不可解析: $path" 'error'
        return $null
    }
}

if ([string]::IsNullOrWhiteSpace($PythonExe)) { $PythonExe = 'python' }
if ([string]::IsNullOrWhiteSpace($VabRoot)) {
    Add-Check 'VAB_ROOT' $false '未设置 VAB_ROOT；请指向外部 VaB-main 目录' 'error'
} else {
    $VabRoot = Resolve-RequiredPath $VabRoot 'VAB_ROOT'
}
if ([string]::IsNullOrWhiteSpace($DatasetRoot) -and $VabRoot) { $DatasetRoot = Join-Path $VabRoot 'dataset' }
if ([string]::IsNullOrWhiteSpace($ExecutorScript)) { $ExecutorScript = Join-Path $repoRoot 'algorithm-runtime\vab\algorithm_entry.py' }
if ([string]::IsNullOrWhiteSpace($Workspace)) { $Workspace = Join-Path $repoRoot 'tmp\ldd-acceptance\workspace' }
if ([string]::IsNullOrWhiteSpace($ResultRoot)) { $ResultRoot = Join-Path $repoRoot 'tmp\ldd-acceptance\results' }
if ([string]::IsNullOrWhiteSpace($RequestFile)) { $RequestFile = Join-Path $repoRoot 'algorithm-runtime\vab\sample-request.json' }
if ([string]::IsNullOrWhiteSpace($JsonOutput)) { $JsonOutput = Join-Path $repoRoot 'tmp\ldd-acceptance-environment.json' }

if ([string]::IsNullOrWhiteSpace($ProcessGuardWorkspace)) {
    Add-Check 'process guard workspace' $false '未设置 SECURITY_MODEL_EXECUTOR_WORKSPACE；执行器会在启动 Python 前以 PROCESS_RECORD_FAILED 拒绝执行' 'error'
} else {
    $workspaceFull = [IO.Path]::GetFullPath($Workspace).TrimEnd('\')
    $guardFull = [IO.Path]::GetFullPath($ProcessGuardWorkspace).TrimEnd('\')
    Add-Check 'process guard workspace' ($guardFull -eq $workspaceFull) "SECURITY_MODEL_EXECUTOR_WORKSPACE=$guardFull; SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE=$workspaceFull（两者必须一致）" 'error'
}

$ExecutorScript = Resolve-RequiredPath $ExecutorScript 'executor script'
$DatasetRoot = Resolve-RequiredPath $DatasetRoot 'dataset root'
$RequestFile = Resolve-RequiredPath $RequestFile 'validate-only request'
New-Item -ItemType Directory -Force -Path $Workspace, $ResultRoot | Out-Null
$workspaceProbe = Join-Path $Workspace ('.write-probe-{0}' -f [guid]::NewGuid().ToString('N'))
try {
    Set-Content -LiteralPath $workspaceProbe -Value 'probe' -Encoding utf8
    Remove-Item -LiteralPath $workspaceProbe -Force
    Add-Check 'workspace writable' $true $Workspace 'error'
} catch {
    Add-Check 'workspace writable' $false "$Workspace : $($_.Exception.Message)" 'error'
}
$resultProbe = Join-Path $ResultRoot ('.write-probe-{0}' -f [guid]::NewGuid().ToString('N'))
try {
    Set-Content -LiteralPath $resultProbe -Value 'probe' -Encoding utf8
    Remove-Item -LiteralPath $resultProbe -Force
    Add-Check 'result root writable' $true $ResultRoot 'error'
} catch {
    Add-Check 'result root writable' $false "$ResultRoot : $($_.Exception.Message)" 'error'
}

if ($VabRoot) {
    $lddSource = Join-Path $VabRoot 'LDD.py'
    Add-Check 'VaB LDD.py' (Test-Path -LiteralPath $lddSource -PathType Leaf) $lddSource 'error'
}

# Dataset structure: the adapter requires a directory root and the seed validator
# checks these CIFAR-10 batch files. A directory-only check is not a sample parse.
if ($DatasetRoot) {
    $batchDir = Join-Path $DatasetRoot 'cifar-10-batches-py'
    $required = @('batches.meta', 'data_batch_1', 'data_batch_2', 'data_batch_3', 'data_batch_4', 'data_batch_5', 'test_batch')
    $missing = @()
    foreach ($name in $required) {
        $path = Join-Path $batchDir $name
        $item = Get-Item -LiteralPath $path -ErrorAction SilentlyContinue
        if (-not $item -or $item.Length -le 0) { $missing += $name }
    }
    Add-Check 'CIFAR-10 batch files' ($missing.Count -eq 0) $(if ($missing.Count -eq 0) { 'batches.meta + data_batch_1..5 + test_batch 均存在且非空' } else { "缺失或为空: $($missing -join ', ')" }) 'error'
}

# Free disk on the workspace volume. This is a preflight gate, not a capacity claim.
$workspaceQualifier = Split-Path -Qualifier $Workspace
$driveName = $workspaceQualifier.TrimEnd(':')
$drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
if ($drive) {
    $freeGb = [math]::Round($drive.Free / 1GB, 1)
    Add-Check 'free disk' ($freeGb -ge $MinFreeDiskGb) "$workspaceQualifier free=$freeGb GB, required=$MinFreeDiskGb GB" 'error'
} else {
    Add-Check 'free disk' $false "无法读取磁盘 $workspaceQualifier" 'error'
}

$os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
if ($os) {
    $freeMemoryGb = [math]::Round($os.FreePhysicalMemory / 1MB, 1)
    Add-Check 'free memory' ($freeMemoryGb -ge 4) "free=$freeMemoryGb GB（低内存会放大 CUDA OOM 风险）" 'warning'
}

# A live VaB lock means another training process owns the GPU/runtime. Do not kill it here.
if ($VabRoot) {
    $lockPath = Join-Path $VabRoot '.security-model-vab.lock'
    if (Test-Path -LiteralPath $lockPath) {
        try {
            $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
            $lockPid = [int]$lock.pid
            $live = Get-Process -Id $lockPid -ErrorAction SilentlyContinue
            Add-Check 'VaB runtime lock' (-not $live) $(if ($live) { "lock 由存活 pid=$lockPid 持有；等待其退出，不要删除锁" } else { "lock 记录 pid=$lockPid 已退出，需人工确认后清理" }) 'error'
        } catch {
            Add-Check 'VaB runtime lock' $false "lock 存在但内容不可解析: $lockPath" 'warning'
        }
    } else {
        Add-Check 'VaB runtime lock' $true '无 lock 文件' 'error'
    }
}

# Python / CUDA probe. Run the configured interpreter, not a PATH alias.
$probePath = Join-Path $Workspace '_ldd-environment-probe.py'
$probe = @'
import json, sys
result = {"ok": False, "python": sys.version.split()[0], "executable": sys.executable}
try:
    import torch
    import numpy
    import scipy
    import sklearn
    import PIL
    import cv2
    import h5py
    result.update({
        "torch": torch.__version__,
        "cuda_available": bool(torch.cuda.is_available()),
        "cuda_version": getattr(torch.version, "cuda", None),
        "device_count": int(torch.cuda.device_count()),
        "devices": [torch.cuda.get_device_name(i) for i in range(torch.cuda.device_count())],
        "numpy": numpy.__version__,
        "scipy": scipy.__version__,
        "sklearn": sklearn.__version__,
        "pillow": PIL.__version__,
        "opencv": cv2.__version__,
        "h5py": h5py.__version__,
    })
    result["ok"] = True
except Exception as exception:
    result["error"] = repr(exception)
print(json.dumps(result))
'@
[IO.File]::WriteAllText($probePath, $probe, (New-Object Text.UTF8Encoding($false)))
try {
    $probeOutput = & $PythonExe $probePath 2>&1
    $probeExit = $LASTEXITCODE
    $probeJson = ($probeOutput | Select-Object -Last 1)
    $runtime = $probeJson | ConvertFrom-Json
    Add-Check 'python executable' ($probeExit -eq 0 -and $runtime.ok) "exit=$probeExit python=$($runtime.python) executable=$($runtime.executable) error=$($runtime.error)" 'error'
    if ($runtime.ok) {
        Add-Check 'torch CUDA' ([bool]$runtime.cuda_available -and [int]$runtime.device_count -ge 1) "torch=$($runtime.torch) cuda=$($runtime.cuda_available) version=$($runtime.cuda_version) devices=$($runtime.devices -join ', ')" 'error'
    }
} catch {
    Add-Check 'python executable' $false "无法执行 $PythonExe : $($_.Exception.Message)" 'error'
}

# validate-only proves the adapter can parse LDD and generate a task copy; it does not train.
$validateResultPath = Join-Path $Workspace 'validate-only-result.json'
$validateStdoutPath = Join-Path $Workspace 'validate-only.stdout.log'
$validateStderrPath = Join-Path $Workspace 'validate-only.stderr.log'
if ($VabRoot) {
    $env:VAB_ROOT = $VabRoot
    if ($ResultRoot) { $env:SECURITY_MODEL_VAB_RESULT_ROOT = $ResultRoot }
    try {
        $validateOutput = & $PythonExe $ExecutorScript --stage pre_defense --request $RequestFile `
            --result $validateResultPath --validate-only 2> $validateStderrPath
        $validateExit = $LASTEXITCODE
        $validateOutput | Set-Content -LiteralPath $validateStdoutPath -Encoding utf8
        if ($validateExit -eq 0 -and (Test-Path -LiteralPath $validateResultPath)) {
            $validateResult = Get-Content -LiteralPath $validateResultPath -Raw | ConvertFrom-Json
            Add-Check 'validate-only' ($validateResult.status -eq 'success' -and $validateResult.runtime.cudaAvailable) "status=$($validateResult.status) cuda=$($validateResult.runtime.cudaAvailable) result=$validateResultPath" 'error'
        } else {
            Add-Check 'validate-only' $false "exit=$validateExit resultExists=$(Test-Path -LiteralPath $validateResultPath) stderr=$validateStderrPath" 'error'
        }
    } catch {
        Add-Check 'validate-only' $false "执行失败: $($_.Exception.Message)" 'error'
    }
}

$summary = [ordered]@{
    generated_at = (Get-Date).ToString('o')
    status = if ($errors -gt 0) { 'blocked' } elseif ($warnings -gt 0) { 'ready_with_warnings' } else { 'ready' }
    errors = $errors
    warnings = $warnings
    inputs = [ordered]@{
        python = $PythonExe
        vab_root = $VabRoot
        dataset_root = $DatasetRoot
        executor_script = $ExecutorScript
        workspace = $Workspace
        process_guard_workspace = $ProcessGuardWorkspace
        result_root = $ResultRoot
        request = $RequestFile
    }
    checks = $checks
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $JsonOutput -Encoding utf8
Write-Host "LDD acceptance environment: status=$($summary.status) errors=$errors warnings=$warnings evidence=$JsonOutput"
foreach ($check in $checks) {
    if (-not $check.ok) { Write-Host "  $($check.severity): $($check.name) - $($check.detail)" }
}
if ($errors -gt 0) { exit 1 }
exit 0
