# 安全模型训练平台：实现说明

更新时间：2026-10-26

本文按代码执行顺序拆解平台如何工作，包含关键类、协议示例和伪代码。业务设计见[设计思路](project-design-overview.md)，面试表达见[面试准备手册](project-interview-preparation.md)。

## 1. 仓库与运行边界

```text
migration-frontend/       Vue 3 前端
migration-backend/        Java 17 / Spring Boot 3 后端
algorithm-runtime/vab/    Python 算法适配器
eladmin/                  旧后端基线及部分历史测试
docs/sdd/                 系统设计、运行和状态说明
sql/                      数据库脚本
```

当前真实链路由 `migration-frontend`、`migration-backend` 与 `algorithm-runtime/vab/algorithm_entry.py` 组成。`eladmin` 是旧工程基线，不能把其测试代码或历史实现默认等同为迁移版后端能力。

**主要技术栈**

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Element Plus、Pinia、Vue Router 4、Axios |
| 后端 | Java 17、Spring Boot 3.5、Spring Security 6、JWT、MyBatis-Plus |
| 业务数据库 | MySQL；保存资源、任务、快照、结果、审计和导出任务 |
| 缓存 | Redis；缓存可重建的资源目录元数据，默认 TTL 为 5 分钟 |
| 算法运行时 | Python、PyTorch、外部 VaB 工程及 LDD |
| 构建 | Maven、npm |

迁移版引入 Spring Data Redis，并通过 `ResourceCatalogCache` 缓存部分数据集、算法和超参数目录查询；MySQL 仍是资源和任务状态的事实数据源，Redis 不可用时缓存访问降级回源 MySQL。Redis 不缓存任务状态，也不是任务队列。任务调度当前使用事务提交后的本 JVM 有界 `ThreadPoolTaskExecutor`，RabbitMQ/Kafka 尚未接入。

## 2. 从 HTTP 请求到任务命令

训练前任务接口位于 `PreDefenseTaskController`：

| 操作 | 路由 |
| --- | --- |
| 创建 | `POST /api/pre-defense` |
| 更新 | `PUT /api/pre-defense` 或 `PUT /api/pre-defense/{taskId}` |
| 删除 | `DELETE /api/pre-defense` |
| 详情 | `GET /api/pre-defense/{taskId}` |
| 启动 | `POST /api/pre-defense/start/{taskId}` |
| 失败重试 | `POST /api/pre-defense/retry/{taskId}` |
| 取消 | `POST /api/pre-defense/cancel/{taskId}` |

其他报告、日志和导出接口由 `PreDefenseReportController` 提供。迁移版的安全配置是无状态 JWT：除登录、验证码、健康检查和版本接口外，其余请求需要认证。

### 2.1 创建与更新校验

`PreDefenseTaskCommandService.create()` / `update()` 的主要职责：

1. 检查任务名、数据集 ID 和算法 ID。
2. 检查数据集存在、启用且解析状态为 `success`。
3. 检查算法存在、启用、`defenseStage == "pre"`，且实现路径严格等于 `vab://pre-defense/ldd`。
4. 如有关联超参数模板，检查模板启用且归属同一个算法。
5. 解析自定义参数，要求 JSON 顶层为对象；兼容旧字段 `epochs`，规范为 `num_epochs`。
6. 调用 `LddConfigurationPolicy` 合并并校验 LDD 参数。
7. 写入任务并在同一事务记录操作审计。

任务服务将真实执行范围在后端硬性限制为 LDD。只改前端算法卡片或数据库显示标签，不能让其他算法进入真实执行。

### 2.2 配置合并与校验

顺序是：

```text
有效配置 = 算法默认配置
         覆盖为超参数模板配置
         再覆盖为任务自定义参数
```

当前 Java 策略支持的参数及主要规则如下：

| 参数 | 校验 |
| --- | --- |
| `dataset`、`trigger_type` | 必填文本 |
| `trigger_label` | 整数，0–99 |
| `poisoned_mode` | `all2one` 或 `all2all` |
| `poisoned_portion` | 数值，0.01–0.6 |
| `batch_size` | 整数，16–512 |
| `num_epochs` | 整数，1–200 |
| `lr_poison` | 数值，0.00001–1.0 |
| `r` | 数值，0–1 |
| `alpha1`、`alpha2` | 数值，0–10 |
| `seed` | 整数，0–999999 |
| `data_path` | 可选文本；未指定时使用数据集资源路径 |

未知参数会被拒绝，而不是静默传给 Python。参数兼容和运行参数验证分别由 Java 的 `LddConfigurationPolicy` 与 Python 的 `build_config()` 承担。

代码位置：[任务命令服务](../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java)、[LDD 参数策略](../migration-backend/src/main/java/com/securitymodel/application/task/LddConfigurationPolicy.java)。

## 3. 启动：快照、配额、原子领取

`start()` 要求任务当前为 `pending`，然后进入 `launchTask()`：

```text
重新校验任务与配置
  → 构造 DefenseTaskRequest
  → 检查任务总配额、用户配额和执行池接收状态
  → 追加本次 execution_snapshot
  → SQL 条件更新 pending → running
  → 注册 afterCommit 回调
  → 事务提交后调用 DefenseTaskExecutor
```

简化伪代码：

```java
@Transactional
void launchTask(Task task) {
    validateTaskAndConfig(task);
    Request request = buildRequest(
        task.id,
        nextAttempt(task.executionSnapshot),
        UUID.randomUUID().toString());

    QuotaSnapshot quota = quotaPolicy.validateAndBuildSnapshot(task);
    String snapshot = appendAttempt(
        task.executionSnapshot, request, runtimeFingerprint(request), quota);

    int claimed = mapper.claimForExecution(
        task.id, request.runId, request.attempt, now(), snapshot);
    if (claimed != 1) {
        throw new TaskCommandException("任务状态已变化");
    }

    afterCommitDispatcher.dispatch(
        () -> executor.runPreDefense(request),
        error -> markDispatchFailure(request, error));
}
```

真正防止同一任务重复启动的是 `PreDefenseTaskMapper.claimForExecution()` 中的条件更新：

```sql
UPDATE sys_pre_defense
SET status = 'running',
    current_run_id = :runId,
    current_attempt = :attempt,
    execution_snapshot = :snapshot
WHERE task_id = :taskId
  AND status = 'pending';
```

更新结果为 1 表示领取成功；为 0 表示任务状态已被其他请求改变。应用层先查状态只能用于友好报错，数据库条件更新才是并发下的最终判定。

`AfterCommitTaskDispatcher` 使用 Spring `TransactionSynchronization` 的 `afterCommit()`。如果线程池拒绝任务，会把当前 `run_id + attempt` 对应的记录写成 `failed`，错误码为 `TASK_QUEUE_FULL`，避免任务长期卡在 `running`。

代码位置：[Mapper 条件更新](../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java)、[事务后派发](../migration-backend/src/main/java/com/securitymodel/application/task/AfterCommitTaskDispatcher.java)、[线程池配置](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/TaskExecutorConfig.java)。

## 4. 执行快照与可复现信息

每次启动在 `execution_snapshot` 的 `attempts` 数组追加一条记录，包含：

- `attempt`、`runId`、启动时间和完整请求快照。
- Java 版本、厂商、操作系统、CPU 并行度、时区。
- Python 解释器路径、脚本路径、执行工作目录。
- 数据集、算法和超参数资源的 ID、名称、路径、真实路径、类型、大小和修改时间。
- 该条尝试的配额快照，以及对尝试 JSON 计算的 SHA-256 摘要。

内容指纹有条件：`fingerprint-content` 开关开启、资源为普通文件且文件大小不超过 `fingerprint-max-bytes` 才读取完整文件内容计算 SHA-256。默认上限为 1 GiB。目录不会递归计算所有文件的 SHA-256；大文件也不能称为已完整内容校验。

Java 的启动快照描述“平台准备运行什么”。Python 结果里的 `runtime` 描述“适配器实际加载了什么”，包括 Python、PyTorch、NumPy、CUDA 可用性/版本、设备名称和关键包版本。二者互补，但不等同于带锁文件和驱动层信息的完整环境容器镜像。

代码位置：[ExecutionFingerprintService.java](../migration-backend/src/main/java/com/securitymodel/application/task/ExecutionFingerprintService.java)、Python `collect_runtime_snapshot()`。

## 5. Java 到 Python 的 JSON 进程协议

`DefenseTaskRequest` 是执行器输入契约。Java 将其序列化到任务尝试目录的 `request.json`，并以参数数组启动 Python，而不是拼接一段 shell 命令：

```text
python algorithm_entry.py
  --stage pre_defense
  --request <attempt-dir>/request.json
  --result <attempt-dir>/result.json
  --progress-file <attempt-dir>/progress.json
```

请求结构示意（资源配置 JSON 本身以字符串字段传递）：

```json
{
  "taskId": 81,
  "attempt": 1,
  "runId": "c574ee54-2b8a-4e88-bb0a-e236fdb9ab71",
  "taskName": "CIFAR-10 LDD 工程验证",
  "stage": "pre_defense",
  "customParams": "{\"batch_size\":128,\"num_epochs\":5}",
  "dataset": {
    "id": 3,
    "name": "CIFAR-10",
    "type": "image",
    "path": "D:/datasets/cifar10"
  },
  "algorithm": {
    "id": 4,
    "name": "LDD",
    "path": "vab://pre-defense/ldd",
    "configJson": "{\"dataset\":\"cifar10\",\"trigger_type\":\"SIG\",\"poisoned_portion\":0.1}"
  },
  "hyperParameter": null
}
```

这里请求字段按 Java 对象序列化使用 camelCase；算法结果中的 `run_id`、`started_at` 等采用 snake_case。Python 适配器通过 `request.get("runId")` 取请求运行令牌，回写结果时采用 `run_id`。用户输入的旧参数名 `epochs` 会在 Java 任务命令校验阶段规范化为 `num_epochs`，因此正常执行请求快照中使用规范名。

**注意：请求里有两层 JSON。** 外层是整个 `DefenseTaskRequest`，由 Jackson 序列化成 `request.json`。`customParams`、`algorithm.configJson` 和 `hyperParameter.configJson` 在 Java DTO 中是字符串，所以外层文件里它们表现为“包含 JSON 文本的字符串”，而不是嵌套对象。Python 先解析外层请求，再由 `load_json_object()` 对这些字段各解析一次：

```text
request.json 文本
  └─ json.loads(...)                 -> Python dict
       └─ request["customParams"]    -> '{"batch_size":128,"num_epochs":5}'（字符串）
            └─ json.loads(...)       -> {"batch_size": 128, "num_epochs": 5}（配置 dict）
```

这也是为什么请求样例中的 `customParams` 带有转义引号；这不是多余编码，而是 Java 字段类型为 `String` 的结果。

执行现场路径为：

```text
<workspace>/pre_defense-<taskId>/attempt-<attempt>/
  request.json
  result.json
  progress.json
  stdout.log
  stderr.log
```

工作区由 `security-model.executor.python.workspace` 配置，缺省使用系统临时目录下的 `security-model-executor`。外部算法结果根目录可由 `SECURITY_MODEL_VAB_RESULT_ROOT` 控制；否则适配器在尝试目录下使用 `artifacts/`。

### 5.1 把一次请求的交接过程拆开

这不是 REST 调用。前端到 Java 是 HTTP；Java 到 Python 是本机操作系统进程调用加文件协议；Python 适配器到 LDD 是另一次子进程调用。一次请求从 Java 到 Python 的实际顺序如下：

1. `PreDefenseTaskCommandService.buildExecutionRequest()` 从任务和资源记录中构造 `DefenseTaskRequest`。其中 `customParams` 是 JSON 文本字段；数据集、算法和模板则是资源引用对象。
2. `PythonLddTaskExecutor.createContext()` 建立 `pre_defense-{taskId}/attempt-{attempt}` 目录，并约定本次的 `request.json`、`result.json`、`stdout.log`、`stderr.log` 路径。
3. `objectMapper.writeValueAsString(request)` 将 Java 对象编码为 JSON，再通过 `Files.writeString(..., UTF_8)` 写入 `request.json`。此刻请求内容已经落在文件系统，不是留在 Java 内存中的 HTTP body。
4. 执行器构造 `List<String>` 命令参数：Python 解释器、适配器脚本、`--stage`、阶段值、`--request`、请求文件路径、`--result`、结果文件路径、`--progress-file`、进度文件路径。`ProcessBuilder` 直接按参数数组启动进程，不经过 shell 拼接，也不把 JSON 通过 stdin 推送给 Python。
5. `redirectOutput()` 和 `redirectError()` 把适配器进程自己的 stdout、stderr 分别写入日志文件；`builder.start()` 返回 Java 的 `Process` 句柄。
6. `PythonProcessWaiter` 轮询该进程的存活状态、超时和取消标记，同时检查 `progress.json` 是否变化。适配器读请求、运行算法并写结果后退出；Java 从进程结束状态继续，而不是等待 Python 发 HTTP 请求。
7. Java 从 `result.json` 读回字符串，用 Jackson 解析并校验协议。只有阶段、运行令牌、尝试号、状态和必要字段都匹配，才把该结果写入数据库。

对应的 Java 代码核心片段如下，省略了目录准备、日志和异常处理：

```java
Files.writeString(
        context.requestFile,
        objectMapper.writeValueAsString(request),
        StandardCharsets.UTF_8);

List<String> command = List.of(
        pythonExecutable, pythonScript,
        "--stage", request.getStage(),
        "--request", context.requestFile.toString(),
        "--result", context.resultFile.toString(),
        "--progress-file", context.progressFile.toString());
Process process = new ProcessBuilder(command)
        .redirectOutput(context.stdoutFile.toFile())
        .redirectError(context.stderrFile.toFile())
        .start();
```

这里命令行传的是文件路径，不是 JSON 正文。使用参数列表也避免把路径拼成一段 shell 命令；Python 端通过 argparse 收到相同的 `--request` 和 `--result` 参数。

字段关系可以这样记：

| 请求字段 | 由谁产生 | Python 如何使用 |
| --- | --- | --- |
| `taskId` | Java 业务任务 | 参与产物目录命名和日志定位 |
| `attempt` | Java 根据历史快照递增 | 作为尝试隔离信息原样回写 |
| `runId` | Java 每次启动生成 UUID | 标记当前执行；结果中使用 `run_id` 回传 |
| `stage` | Java 固定为 `pre_defense` | 与命令行 `--stage` 一起限制适配器运行范围 |
| `dataset` | Java 根据 MySQL 资源记录组装 | 提供数据集路径、名称、类型等引用 |
| `algorithm.path` | MySQL 中的算法实现路径 | 作为白名单键，当前只允许 LDD |
| `algorithm.configJson` | 算法默认配置 | 作为合并配置的第一层 |
| `hyperParameter.configJson` | 可选超参数模板 | 覆盖算法默认值 |
| `customParams` | 用户在任务表单中配置 | 最后一层覆盖值；字段本身是 JSON 字符串 |

### 5.2 Python 怎样解析并运行 LDD

命令行入口在 `algorithm_entry.py` 的 `main()`：它解析 `--stage`、`--request`、`--result`，再进入 `run(args)`。核心解析逻辑是：

```python
request_path = Path(args.request).resolve()
result_path = Path(args.result).resolve()
request = json.loads(request_path.read_text(encoding="utf-8"))
algorithm_key = get_algorithm_key(request)
spec = SUPPORTED_ALGORITHMS[algorithm_key]
config = build_config(request, spec)
```

`build_config()` 再分别解析请求中的配置字符串，并按默认配置、模板配置、任务自定义参数的顺序合并。之后才进行路径和运行环境预检、AST 脚本生成及算法子进程执行。完整步骤如下：

```python
request = json.loads(Path(args.request).read_text(encoding="utf-8"))
algorithm_key = get_algorithm_key(request)
spec = SUPPORTED_ALGORITHMS[algorithm_key]  # 白名单只登记 LDD
config = build_config(request, spec)
preflight(VAB_ROOT, source_path, config)
generated_script = make_task_script(source_path, ..., config, spec)
lock_path = vab_root / ".security-model-vab.lock"
acquire_lock(lock_path)  # O_CREAT | O_EXCL；锁已存在则快速失败
try:
    with execution_log.open("w", encoding="utf-8", buffering=1) as log_file:
        process = subprocess.Popen(
            build_command(generated_script, config),
            cwd=str(vab_root),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        for line in process.stdout:
            log_file.write(line)
            print(line, end="", flush=True)
        return_code = process.wait()
finally:
    if lock_path.exists():
        lock_path.unlink()
if return_code != 0:
    raise IntegrationError("VaB process exited with code {}".format(return_code))
result = collect_metrics_artifacts_and_runtime(...)
Path(args.result).write_text(json.dumps(result), encoding="utf-8")
```

`get_algorithm_key()` 从 `request.algorithm.path` 取实现路径；路径不在 `SUPPORTED_ALGORITHMS` 就作为不支持算法失败。`build_config()` 按算法默认配置、模板配置、自定义参数的顺序合并，再根据 LDD 的配置白名单检查未知键和参数值。`preflight()` 检查 `VAB_ROOT`、源码、数据路径、PyTorch 导入和 CUDA；原始 VaB 脚本直接使用 CUDA，因此没有可用 CUDA 设备时不会进入训练。

适配器并不把原始 `LDD.py` 当作普通 Python 模块导入。它用 AST 解析源文件，生成当前任务专用脚本，将固定实验循环调整为单次参数化运行、设置本次输出目录、关闭交互式绘图，并在原有样本选择结果处保存 `selected_indices_<ratio>.npy`。正式训练前，适配器先在 `VAB_ROOT` 创建独占锁，再用 `subprocess.Popen()` 启动生成脚本。算法子进程的 stderr 通过 `stderr=subprocess.STDOUT` 合并到 stdout；适配器逐行读取该管道，同时写 `execution.log` 并 `print(..., flush=True)`。Java 启动适配器时已将其 stdout 重定向到 attempt 目录的 `stdout.log`，因此 LDD 输出会同时出现在这两个文件中。这里形成两个进程边界：

| 进程 | 启动者 | 主要工作 | 输出 |
| --- | --- | --- | --- |
| Python 适配器 `algorithm_entry.py` | Java `ProcessBuilder` | 解析 JSON、校验配置、预检、生成任务脚本、逐行转发算法日志、收集结果、写 `result.json` | stdout 写入 `stdout.log`；适配器 stderr 写入 `stderr.log`；结果写入 `result.json` |
| VaB 任务脚本（AST 生成） | 适配器的 `subprocess.Popen()` | 执行 LDD 数据清洗与模型/样本计算；stdout 和 stderr 合并后通过管道交给适配器 | 适配器双写的 `execution.log`、`stdout.log`，以及索引、图片、模型文件等产物 |

因此，Java 的 `Process` 句柄和 `PythonProcessWaiter` 等待对象直接对应的是适配器进程，不是算法子进程。适配器内部另有算法子进程；取消或超时时，等待器会枚举当时可见的 `ProcessHandle.descendants()` 并尝试强制终止子进程，再终止适配器进程。这是尽力回收已发现进程的实现，不是容器级资源隔离，也不能保证跨平台、所有竞态下的整棵进程树都被清理。

锁由 `acquire_lock()` 使用 `os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)` 创建，并在文件中写入适配器 PID。锁已存在时抛出集成错误，当前任务快速失败而不是排队。正常的子进程退出路径会在 `finally` 中删除锁；如果适配器本身被 Java 强制终止，Python `finally` 可能来不及运行，锁文件可能残留。该锁是同一 `VAB_ROOT` 文件系统路径上的独占标记，不是 Redis 或数据库分布式锁；排障时须先确认没有 VaB 任务正在运行，才人工删除残留文件，不能只凭 PID 或文件年龄自动清理。

### 5.3 Python 怎样把结果交回 Java

Python 侧不是调用 Java Controller，而是把结果对象序列化后写入 Java 预先约定的文件，再以进程退出码结束。核心代码为：

```python
result_path.parent.mkdir(parents=True, exist_ok=True)
result_path.write_text(
    json.dumps(result, ensure_ascii=False, indent=2),
    encoding="utf-8",
)
return 0 if result["status"] == "success" else 1
```

Java 侧的 `invoke()` 等待进程结束后执行 `readIfExists(resultFile)`。适配器成功通常退出码为 0；适配器捕获到业务异常时会尽量写 `status: failed` 的 JSON 并以非 0 退出。Java 允许“非 0 退出码 + 合法 failed 结果”进入统一协议校验和失败状态收敛；如果进程异常退出且没有合法失败结果，Java 会根据退出码、stderr 或缺失结果文件构造平台级失败结果。因此，退出码用于判断进程执行情况，结构化结果用于表达任务业务状态，两者需要结合判断。

示例结果（字段值为示意）：

```json
{
  "executor": "python-vab-adapter",
  "stage": "pre_defense",
  "status": "success",
  "message": "VaB LDD completed",
  "started_at": "2026-10-26T10:00:00+00:00",
  "finished_at": "2026-10-26T10:35:00+00:00",
  "run_id": "c574ee54-2b8a-4e88-bb0a-e236fdb9ab71",
  "attempt": 1,
  "algorithm": "vab://pre-defense/ldd",
  "effective_config": {},
  "metrics": {
    "clean_purity_percent_by_isolation_ratio": {},
    "selected_sample_count_by_isolation_ratio": {}
  },
  "artifacts": {
    "type": "vab-task-output",
    "available": true,
    "path": "<task-artifact-root>",
    "selection_files": []
  },
  "runtime": {
    "pythonVersion": "3.x",
    "torchVersion": "...",
    "cudaAvailable": true,
    "cudaDevices": []
  }
}
```

Java 的 `validateResult()` 要求必填字段存在，并确认 `stage == pre_defense`、`run_id` 和 `attempt` 与本次请求相同、`status` 只能是 `success` 或 `failed`、`artifacts` 是 JSON 对象；失败结果还必须包含 `error_code` 和 `failure_reason`。

可将 Java 端压缩成下面的伪代码：

```java
writeUtf8(requestJson, objectMapper.writeValueAsString(request));
Process process = new ProcessBuilder(
        python, adapter, "--stage", request.getStage(),
        "--request", requestFile.toString(),
        "--result", resultFile.toString())
    .redirectOutput(stdoutFile.toFile())
    .redirectError(stderrFile.toFile())
    .start();

waitForCompletionOrCancelOrTimeout(process);
String rawResult = readUtf8(resultFile);
String status = validateResult(rawResult, request);
mapper.updateResultForRun(taskId, runId, null, rawResult);
mapper.finishForRun(taskId, runId, status, finalProgress, now);
```

这个示意强调职责顺序，不是可复制的完整源码。可在 [PythonLddTaskExecutor.java](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutor.java) 的 `invoke()`、`validateResult()`、`persistResult()` 和 `finish()` 对照实际实现。

## 6. LDD Python 适配器做什么

`algorithm_entry.py` 的 `SUPPORTED_ALGORITHMS` 是实现路径白名单，目前唯一真实映射是：

```text
vab://pre-defense/ldd → <VAB_ROOT>/LDD.py
```

适配器处理步骤：

1. 读取任务请求，验证阶段为 `pre_defense`，并检查实现路径是否在白名单中。
2. 合并算法默认配置、超参数配置和任务自定义参数；拒绝未知 LDD 参数。
3. 从配置或数据集资源解析数据目录，规范化为绝对路径。
4. 预检 `VAB_ROOT`、`LDD.py`、数据目录、PyTorch 导入和 CUDA 可用性。原始 VaB 代码直接使用 CUDA，因此当前真实执行需要可用 CUDA 设备。
5. 使用 Python `ast` 解析原始 `LDD.py`，在生成副本中将固定实验矩阵转换成单任务配置，重定向输出目录，关闭 `plt.show()`，并在算法写出 `perm` 时额外保存选中索引文件。
6. 在 VaB 根目录通过 `O_CREAT | O_EXCL` 建立独占锁；已存在则快速失败。取得锁后用 `Popen` 启动生成脚本，把合并后的子进程输出逐行收集到 `execution.log`，并打印到适配器 stdout；正常结束时在 `finally` 删除锁。
7. 扫描日志、选中索引、图像和检查点，解析 LDD 日志中的纯度信息。
8. 生成结构化结果 JSON；无论成功或失败均记录运行标识和实际 Python 环境。

简化伪代码：

```python
request = read_json(args.request)
spec = allowlisted_algorithms[request.algorithm.path]
config = merge(defaults, hyper_template, custom_params)
validate_config(config)
preflight(VAB_ROOT, spec.source, config.dataset_path)

generated = ast_transform(
    source=VAB_ROOT / spec.source,
    task_config=config,
    output_dir=task_artifact_dir)

lock = create_exclusive_lock(VAB_ROOT / ".security-model-vab.lock", pid=os.getpid())
try:
    process = Popen(
        build_command(generated, config),
        cwd=VAB_ROOT,
        stdout=PIPE,
        stderr=STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    with open(execution_log, "w", encoding="utf-8", buffering=1) as log:
        for line in process.stdout:
            log.write(line)
            print(line, end="", flush=True)  # Java 同时收进 stdout.log
    return_code = process.wait()
finally:
    remove_lock_if_present(lock)
if return_code != 0:
    raise IntegrationError(...)

result = collect_metrics_logs_indices_and_runtime()
write_json(args.result, result)
```

AST 适配的价值是尽量不直接修改研究源码，同时把脚本一次加载就执行的固定循环变成平台指定的一次运行。它不是对任意 Python 算法的通用代码转换器；每种算法仍需单独定义配置映射、产物语义和测试。

代码位置：[PythonLddTaskExecutor.java](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutor.java)、[algorithm_entry.py](../algorithm-runtime/vab/algorithm_entry.py)、[VaB 接入说明](sdd/10-vab-integration.md)。

## 7. 进度、结果校验与状态收敛

### 7.1 进度快照如何产生和读取

进度通过与请求、结果文件并列的 `progress.json` 传递，不依赖 Python 向 Java 发 HTTP 请求。Java 启动适配器时传入 `--progress-file <attempt-dir>/progress.json`。适配器在环境检查、任务准备、训练进程启动、结果整理和结束时写快照；AST 生成的任务脚本还会在 LDD epoch 循环中调用注入的 `platform_report_epoch()`，发布训练阶段、当前 epoch 和心跳。

训练阶段快照的结构示意如下，数值仅用于说明字段含义：

```json
{
  "run_id": "<current-run-id>",
  "attempt": 1,
  "phase": "training",
  "phase_label": "LDD 双网络训练",
  "progress": 50,
  "epoch": 3,
  "total_epochs": 5,
  "message": "正在执行第 3/5 轮训练",
  "heartbeat_at": "<UTC timestamp>"
}
```

Python 写快照时先写同目录临时文件，再用 `os.replace()` 替换正式文件，降低并发读取到半截 JSON 的概率。适配器刚启动、尚未解析 `request.json` 时的环境检查快照可能暂时没有 `run_id` 和 `attempt`；解析请求后以及训练 epoch 快照会带上本次运行标识。

`PythonProcessWaiter` 等待适配器进程期间约每 200 毫秒检查一次进程状态，并比较 `progress.json` 的修改时间和文件大小；发现变化时调用 Java 进度监听器。监听器用 Jackson 解析快照，检查存在的 `run_id`、`attempt` 是否属于当前请求，验证 `progress` 为整数，再将运行期间的数值限制在 `0..99`。随后 `PreDefenseTaskMapper.updateRunningProgress()` 以 `task_id + current_run_id + status='running'` 为条件更新数据库。格式错误、文件短暂不可读或运行标识不匹配只会跳过该次进度，不会单独把任务判失败。

这条链路有两个展示出口，职责不同：

1. 等待器读取快照后，只把数值进度条件更新到任务记录，供任务列表和任务状态查询使用。
2. `GET /api/pre-defense/logs/{taskId}` 由 `PreDefenseLogQueryService` 增量读取日志，并将当前尝试的完整进度快照放在响应的 `progress` 字段中。前端任务详情每 3 秒调用日志接口，显示阶段、消息、epoch 和心跳。运行中超过 60 秒未更新只显示提醒，不改变任务状态。

因此，进度不是单一的“准备 10%、结束 90%”粗粒度指标，而是由适配器阶段事件和 LDD epoch 事件共同驱动的运行观测数据。适配器会发布完成快照，但该快照不等于平台任务完成。

### 7.2 结果协议与数据库终态

最终完成依据仍然是适配器进程退出、`result.json` 存在且通过 Java 协议校验。成功处理时 Java 核对必需字段、`stage`、`run_id`、`attempt`、`status` 和 `artifacts`；之后先保存结构化结果，再使用带当前运行令牌和 `running` 状态条件的 SQL 收敛终态。成功任务由数据库更新为 `success`、进度 100；合法失败结果或执行异常则进入失败处理。进度快照显示 100%、心跳停止或进程仍存活，都不能单独作为成功判据。

Python 结果必须是合法 JSON，并包含 `executor`、`stage`、`status`、`message`、时间字段、`run_id`、`attempt` 和对象类型的 `artifacts`。Java 还会核对：

- `stage` 必须是 `pre_defense`。
- `run_id` 和 `attempt` 必须与当前请求完全一致。
- 状态只能是 `success` 或 `failed`。
- 失败结果必须带 `error_code` 与 `failure_reason`。

示意结果：

```json
{
  "executor": "python-vab-adapter",
  "stage": "pre_defense",
  "status": "success",
  "message": "VaB LDD completed",
  "run_id": "c574ee54-2b8a-4e88-bb0a-e236fdb9ab71",
  "attempt": 1,
  "started_at": "2026-10-21T10:00:00+00:00",
  "finished_at": "2026-10-21T10:35:00+00:00",
  "algorithm": "vab://pre-defense/ldd",
  "effective_config": {},
  "metrics": {
    "clean_purity_percent_by_isolation_ratio": {},
    "selected_sample_count_by_isolation_ratio": {}
  },
  "artifacts": {
    "type": "vab-task-output",
    "available": true,
    "path": "<task-artifact-root>",
    "selection_kind": "retained_clean_subset",
    "selection_files": []
  },
  "runtime": {
    "pythonVersion": "3.x",
    "torchVersion": "...",
    "cudaAvailable": true,
    "cudaDevices": []
  }
}
```

`clean_purity` 使用 VaB 实验中已知的中毒样本标签/索引计算，属于受控实验评估指标，不是未知生产数据上无需标签即可得到的检测指标。

进度、结果和终态 Mapper SQL 都按当前 `run_id` 与 `status = 'running'` 更新。重试后旧进程的迟到写入更新行数为 0，Java 记录警告并忽略，不覆盖新尝试。取消或超时触发时，等待器会尝试强制终止当时可发现的子进程和适配器进程；这不等于容器/操作系统资源隔离，也不构成跨平台、无竞态的子进程树回收保证。

## 8. 取消、重试、启动恢复与线程池

### 8.1 取消

取消请求先以 `task_id + current_run_id + status = running` 条件更新数据库，写入 `canceled` 终态；事务提交后通知执行器将 `run_id` 放入取消集合，并对已登记进程及其当时可发现的后代进程调用强制终止。若尚未登记适配器进程，取消标记仍会阻止该次运行随后启动。取消后的回调因为数据库已不再是 `running` 而无法改写结果。后代进程回收属于尽力尝试，不承诺消除所有进程派生竞态。

### 8.2 失败重试

仅允许 `failed` 任务重试。Mapper 用 `WHERE status = 'failed'` 将任务重置为 `pending`，清理当前结果字段后再走全套校验、快照和启动流程。每次使用新 UUID `run_id`、递增 `attempt` 和独立目录。尝试列表保留启动输入快照；清洗结果列主要保存当前尝试结果。

### 8.3 应用重启

迁移版 `TaskRecoveryRunner` 在 `ApplicationReadyEvent` 扫描 `running` 训练任务，并用当前运行标识将其收敛为带 `EXECUTOR_RESTARTED` 的失败状态，供用户显式重试。它不恢复已中断的 Python 进程。

当前迁移版目录中未发现导出任务的启动恢复 Runner。尽管导出 Mapper 存在 `failOrphaned` 条件更新方法，不能据此宣称迁移版会在启动时自动恢复或扫描导出任务。需要对照的历史实现位于旧 `eladmin` 模块，不应混为迁移版能力。

### 8.4 本地执行池和配额

`TaskExecutorConfig` 默认参数为 core 2、max 4、队列容量 20，拒绝策略为 `AbortPolicy`；运行准入默认最多 4 个 running 任务、单用户最多 2 个。实际值可以由配置覆盖。任务运行配额策略使用同步方法和当前 JVM 内数据库状态计数，配额快照会记录在每次尝试中。

这是一台应用实例内的限流，不是分布式锁。多个后端实例之间没有共享租约或集中式 GPU 调度器；任务级数据库条件领取仍能防止同一个任务被多次领取，但不要把单机准入描述成全局并发硬限制。

## 9. 日志、报告与产物安全

### 9.1 增量日志

`PreDefenseLogQueryService` 在运行期间根据当前 `attempt` 读取 `stdout.log` 和 `stderr.log`；前者包含适配器输出，也包含由适配器逐行转发的 LDD 子进程输出，后者主要记录适配器自身错误。任务结束后，服务改为读取 `result.json` 的 `artifacts.logs` 登记路径：成功结果通常包含 `execution.log` 和解析出的算法文本日志；Java 生成的平台级失败结果会登记 attempt 目录的 stdout/stderr。当前适配器自行生成的失败结果默认 `artifacts` 为空，没有登记这些日志路径，因此任务结束后日志接口可能显示无可读日志，即使 attempt 目录中的日志文件仍存在；排查这类失败时需直接检查对应工作目录。这是现有日志产物登记的边界，不能描述成所有失败任务都能在页面读取到日志。接口可接收客户端上次读取的 byte offset，每个文件单次最多读取 256 KiB，并返回新 offset、是否截断和是否发生 reset。

读取前要求文件为普通文件、后缀是 `.log` 或 `.txt`，并对 `toRealPath()` 做根目录 `startsWith()` 检查，降低符号链接和路径穿越导致的越权读取风险。

### 9.2 报告

`PreDefenseReportQueryService` 聚合任务、资源记录、执行快照、清洗结果、导出能力描述和导出任务列表。前端报告据此展示尝试信息、指标、环境、失败原因、日志和产物。

### 9.3 PNG 预览

预览接口只接受真实存在且位于任务产物根目录内的 PNG。校验目标文件的真实路径在真实产物根目录之下，不应由客户端提交任意服务器路径直接读取。

## 10. CIFAR-10 清洗子集导出

导出只对成功的 CIFAR-10 LDD 任务开放，并且指定的隔离比例必须有对应 `selected_indices_<ratio>.npy` 文件。

流程：

```text
POST 提交导出(taskId, ratio)
  → 根据 (task_id, isolation_ratio) 唯一键幂等插入
  → 事务提交后异步执行
  → 校验算法、dataset=cifar10、索引文件及产物路径
  → Python 读取 CIFAR-10 五个 data_batch 文件
  → 按索引取出原始 RGB 图片和标签
  → 写入 ZIP 临时文件 .part
  → 验证进程退出码与文件非空
  → 移动为正式 ZIP 并更新导出任务成功
  → 下载接口再次校验文件位于任务产物根目录
```

ZIP 包含 `metadata.json`、`selected_indices.npy`、索引 CSV、标签 CSV 和 PNG 图片。它表示“根据 LDD 保留索引从 CIFAR-10 原始训练集重建的子集”，不是 VaB 运行期间内存中的触发器修改张量。

导出任务有自己的 `pending/running/success/failed` 状态和失败重试。Java 使用 `.part` 临时文件，成功后再移动为正式文件，避免将未完成文件标记为可下载结果。当前迁移版未实现导出任务的应用启动恢复扫描，这一点要与旧 ELADMIN 代码区分。

代码位置：[导出服务](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportService.java)、[导出执行器](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportExecutor.java)、Python `export_clean_subset()`。

## 11. 审计与操作人

任务创建、编辑、启动、重试、取消、删除，以及子集导出提交和重试都会写操作审计。审计记录包含资源类型/ID、动作、操作人、状态变化、请求快照、说明和时间。任务命令与审计记录在同一个 Spring 事务内，便于排查“谁对哪个任务执行了什么操作”。

## 12. 本地调试检查顺序

遇到任务无法运行时，按以下顺序排查：

1. **后端可达**：检查 `/actuator/health` 和 `/api/v1/system/version`。
2. **JWT 与资源**：确认请求使用真实后端模式，数据集和算法查询接口返回真实记录；避免误把演示模式样例当后端数据。
3. **算法门禁**：任务所选算法阶段应为 `pre`，实现路径为 `vab://pre-defense/ldd`。
4. **配置**：看 Java 校验错误，核对默认配置、模板配置、自定义 JSON 的覆盖顺序及参数白名单。
5. **Python 启动**：核对 `security-model.executor.python.executable`、`security-model.executor.python.script`、`security-model.executor.python.workspace` 和超时设置。
6. **VaB 预检**：确认 `VAB_ROOT` 指向包含 `LDD.py` 的 VaB 工程，数据集目录存在，Python 环境可导入 PyTorch 且 CUDA 可用。
7. **运行现场**：查看对应 attempt 目录的 `request.json`、`progress.json`、`stdout.log`、`stderr.log`、`result.json`；对照快照中的 `run_id`、`attempt`、`phase`、`epoch` 和 `heartbeat_at`。若错误提示共享 VaB 锁已存在，先确认没有 VaB 任务仍在运行，再检查 `VAB_ROOT/.security-model-vab.lock` 是否为强制终止后残留；不要直接删除可能仍被使用的锁。
8. **结果字段**：核对 `run_id`、`attempt`、`stage` 和必需结果字段，防止结果协议错误。
9. **导出**：检查 LDD 结果中的 `selection_files`、索引比例、产物 containment、原始 CIFAR-10 Python batch 文件以及导出日志。

适配器要求和运行样例另见 [`algorithm-runtime/vab/README.md`](../algorithm-runtime/vab/README.md) 与[本地运行说明](sdd/08-local-runtime-notes.md)。

## 13. 测试与验证证据怎么表述

截至 2026-11-13（R1 及评审三轮修正、R2 第二切片），`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` 实测迁移版 **14 个测试类、69 项单元测试 + 29 项 MySQL 集成测试**全部通过，失败、错误和跳过均为 0；打包 jar 成功；适配器另有 `python -m unittest` 11 项通过（锁陈旧接管 6 项、失败码 5 项）。集成测试分 5 个类：`MySqlTaskPersistenceIT` 5 项（并发原子领取、恢复快照竞争、孤儿任务恢复、owner 范围查询、demo 排除）、`OwnershipMigrationScriptIT` 6 项（决策表迁移：只读段、无决策不认领、demo/assign/reject、未知任务与非法决策中止）、`OwnershipEndToEndIT` 9 项（真实库双账号 + 导出/下载/PNG 预览/审计/导出提交）、`OwnershipEndToEndCrossReadIT` 4 项（管理员开关 false/true）、`GpuLeaseMySqlIT` 5 项（GPU 单槽租约并发唯一、过期接管、fencing、按 run 释放）。单元测试覆盖资源命令和查询、Redis 缓存读写/失效/故障降级、事务提交后派发、任务重试快照、进度等待与查询、任务重启恢复、YAML 配置解析，以及 R1 新增的任务归属守卫（含 demo 写拒绝）、按 owner 配额、资源管理员权限与数据集验证、真实登录取证后的双账号 HTTP 越权与禁用账号/降权令牌失效、R2 的 GPU 租约条件更新与续租/释放语义、适配器无结果时的失败码兜底（7 项）。仓库报告对应的命令为 `mvn -B -f migration-backend/pom.xml test` 和 `mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（后者需 `MYSQL_IT_PASSWORD`）。**生产库 `eladmin` 尚未执行迁移，`sys_gpu_lease` 也未在生产库建表**；GitHub Actions 配置了 MySQL 8.4 service，远端结果以 Actions 记录为准。旧 `eladmin/eladmin-system/src/test` 中的测试是旧版定向测试历史记录，不能合并计入迁移版覆盖。

另外，已有文档记录过一次 CIFAR-10、SIG、batch size 128、5 epochs 的 LDD 工程链路验证。它验证的是平台与算法的工程接入，不是经过完整对照设计的论文正式实验。正式汇报前应以当前可访问的日志、索引文件和报告核对运行记录，不要只依赖演示数据或历史文档。

## 14. 代码导航

| 阅读顺序 | 文件 |
| --- | --- |
| 1. 看任务入口与业务规则 | [`PreDefenseTaskController`](../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseTaskController.java)、[`PreDefenseTaskCommandService`](../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java) |
| 2. 看请求/结果运行边界 | [`DefenseTaskRequest`](../migration-backend/src/main/java/com/securitymodel/application/task/DefenseTaskRequest.java)、[`DefenseTaskExecutor`](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/DefenseTaskExecutor.java) |
| 3. 看并发与异步 | [`PreDefenseTaskMapper`](../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java)、[`TaskExecutorConfig`](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/TaskExecutorConfig.java)、[`AfterCommitTaskDispatcher`](../migration-backend/src/main/java/com/securitymodel/application/task/AfterCommitTaskDispatcher.java) |
| 4. 看进程执行与进度快照 | [`PythonLddTaskExecutor`](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutor.java)、[`PythonProcessWaiter`](../migration-backend/src/main/java/com/securitymodel/application/task/PythonProcessWaiter.java)、[`PreDefenseLogQueryService`](../migration-backend/src/main/java/com/securitymodel/application/report/PreDefenseLogQueryService.java) |
| 5. 看算法适配 | [`algorithm_entry.py`](../algorithm-runtime/vab/algorithm_entry.py) |
| 6. 看环境和追溯 | [`ExecutionFingerprintService`](../migration-backend/src/main/java/com/securitymodel/application/task/ExecutionFingerprintService.java)、`collect_runtime_snapshot()` |
| 7. 看报告、日志与导出 | `application/report/`、[`CleanSubsetExportService`](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportService.java)、[`CleanSubsetExportExecutor`](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportExecutor.java) |
| 8. 看前端真实交互与演示回退 | [`TaskCenterView.vue`](../migration-frontend/src/views/TaskCenterView.vue)、`migration-frontend/src/data/demoData.js` |
