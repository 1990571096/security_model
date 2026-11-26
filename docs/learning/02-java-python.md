# 02 亲手交接 JSON

**目标**是说清楚文件由谁写、路径怎么传、Python 怎么解析以及谁最终更新数据库。

## 三分钟实验

在仓库根目录用 PowerShell 执行。实验使用当前仓库的标准库短桩，输出保存在一个新的临时目录。可独立于第一课操作。

```powershell
$lab = Join-Path ([IO.Path]::GetTempPath()) ("sm-learning-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $lab | Out-Null
@{
  taskId = 42
  runId = 'learning-run-42'
  attempt = 1
  stage = 'pre_defense'
  customParams = '{"num_epochs":1}'
} | ConvertTo-Json | Set-Content -Encoding utf8NoBOM (Join-Path $lab 'request.json')

$savedMode = $env:R4_PYTHON_MODE
$savedMarker = $env:R4_PYTHON_MARKER
try {
  $env:R4_PYTHON_MODE = 'success'
  $env:R4_PYTHON_MARKER = Join-Path $lab 'invocations.jsonl'
  python scripts/r4/r4_python_stub.py --stage pre_defense `
    --request "$lab/request.json" --result "$lab/result.json" `
    --progress-file "$lab/progress.json"
  if ($LASTEXITCODE -ne 0) { throw "Python 退出码为 $LASTEXITCODE" }
  Get-ChildItem -LiteralPath $lab
  Get-Content -Raw "$lab/request.json"
  Get-Content -Raw "$lab/progress.json"
  Get-Content -Raw "$lab/result.json"
} finally {
  $env:R4_PYTHON_MODE = $savedMode
  $env:R4_PYTHON_MARKER = $savedMarker
}
```

把请求里的 `runId` 与结果里的 `run_id` 连起来。结果应为 `success`，`attempt` 为 `1`。短桩进度文件中的 `progress` 为 `10`，它只写一次。后端真实读取时，会根据终态写入最终进度。本实验由你代替 Java 准备文件并启动短桩。

2026 年 11 月 23 日已按上述原样命令执行，检查了进度值、结果状态以及请求和结果的轮次一致性。

## 换回真实代码

| 顺序 | 在源码搜索 | 读完能说什么 |
| --- | --- | --- |
| 1 | `buildExecutionRequest` | 用当前资源和参数计划填充 `DefenseTaskRequest` |
| 2 | `createContext`、`invoke` | 在 `pre_defense-<taskId>/attempt-<attempt>` 写 `request.json`，把文件路径放入 `ProcessBuilder` 参数列表 |
| 3 | `main`、`run`、`build_config` | `argparse` 取路径，`read_text` 读 UTF-8，`json.loads` 还原外层对象。配置字符串要再解析一层 |
| 4 | `make_task_script`、`subprocess.Popen` | 真适配器生成隔离脚本，然后启动训练子进程 |
| 5 | `waitFor`、`validateResult` | Java 等待期间采进度，退出后读结果，校验身份和协议后持久化 |

读这四个文件时按上表顺序搜索。[命令服务](../../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java) · [Python 执行器](../../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutor.java) · [等待器](../../migration-backend/src/main/java/com/securitymodel/application/task/PythonProcessWaiter.java) · [真实适配器](../../algorithm-runtime/vab/algorithm_entry.py)

## 自测

**题目一**。`--request` 后面传的是路径还是请求体？为什么 `customParams` 中有转义引号？

**答案**。传路径。`customParams` 在 Java DTO 中是 `String`，它保存一段 JSON 文本，所以外层序列化后出现转义。Python `json.loads` 第一次解析文件，第二次把配置字符串解析成字典。

**题目二**。Python 返回 `success` 后任务怎么变绿？

**答案**。Java 等 Python 结束，读 `result.json` 并校验结果，按 `taskId + current_run_id + running` 写回。浏览器轮询或刷新任务详情读取数据库结果。日志文件可用于诊断，也由 Java 对外提供读取接口。

[协议和源码摘录](../project-implementation-guide.md#7-java-到-python文件协议) · [下一课：状态与恢复](03-reliability.md)
