# 安全模型训练平台实现指南

沿着一次 `taskId=42` 的示例运行读代码。`r1` 是示意用的运行 ID，生产代码生成 UUID。文中的缩短片段省略了日志和少量分支。首次阅读先记住业务闭环，再到这里核对每次调用的输入与输出。[贯穿案例](learning/00-one-experiment.md)

## 1. 页面到数据库

按 `submitCreate → createTask → PreDefenseTaskController.create → PreDefenseTaskCommandService.create` 找到各层。表单用 `JSON.parse` 检查 `customParams`，`JSON.stringify` 把它恢复成文本字段。浏览器把整个表单作为 HTTP JSON 发送。Spring MVC 用 Jackson 将请求体绑定到 `PreDefenseTaskCommand`。

```javascript
// submitCreate, shortened
const created = unwrap(await createTask(payload, pendingCreateKey))
await openDetail(created)
```

创建提交成功后，页面拿响应中的 `taskId` 打开详情。用户点击“启动任务”才走下面的路径。两个按钮各有自己的响应。

```javascript
// TaskCenterView.vue
function start(row) { return runCommand(row, '启动', startTask, startKeys) }

// runCommand 的顺序，省略提示、幂等键管理和错误处理
await ElMessageBox.confirm(/* 确认提示 */)
await request(taskId, keys?.get(taskId))
```

`PreDefenseTaskCommand` 接收表单业务字段。状态和运行身份由服务管理。`create` 调用 `plan` 校验资源与参数，写 `pending`、`progress=0` 和稳定 `ownerUserId`。`resolveCommand` 要求数据集启用且解析为 `success`，算法启用且阶段为 `pre`、实现为 `vab://pre-defense/ldd`。可选模板必须启用且绑定同一个算法。`LddConfigurationPolicy` 归一化参数。合并顺序为算法默认值、模板、自定义值，后者覆盖前者。创建持久化最终参数。启动重新走计划解析和资源检查，并持久化本轮实际请求。

`AuthController.login` 接收用户名与密码，验证成功签发 JWT。后续 Controller 由认证上下文取得调用者。`lockOwned` 用记录的 `ownerUserId` 校验写权限。对数据集，`ResourceCommandService.validateDataset` 会针对可识别的 CIFAR 目录检查预期文件。以登记 `D:/datasets/cifar-10-batches-py` 为例，`resolveCommand` 形成的实际运行路径是 `D:/datasets`，实际 `dataset` 是 `cifar10`。数据集参数与所选 CIFAR 类型矛盾或 `data_path` 指向其他目录会报错。[登录](../migration-backend/src/main/java/com/securitymodel/interfaces/web/AuthController.java) · [资源验证](../migration-backend/src/main/java/com/securitymodel/application/resource/ResourceCommandService.java) · [CIFAR 路径](../migration-backend/src/main/java/com/securitymodel/domain/dataset/VabCifarDataset.java)

`RequestIdempotencyService` 处理创建、启动、重试的可选 `Idempotency-Key`。同一用户、操作、键和请求内容已完成时返回缓存的响应。复用相同键提交不同内容会冲突，处理中会返回 `REQUEST_IN_PROGRESS`。这层解决请求重发。任务状态仍由服务和 SQL 约束。

[页面](../migration-frontend/src/views/TaskCenterView.vue) · [HTTP 客户端](../migration-frontend/src/api/http.js) · [Controller](../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseTaskController.java) · [命令服务](../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java) · [幂等服务](../migration-backend/src/main/java/com/securitymodel/application/task/RequestIdempotencyService.java)

## 2. 启动怎样变成后台运行

`start` 先通过 `selectByIdForUpdate` 锁定任务行并校验归属，只允许 `pending`。`launchTask` 检查调度 readiness 与角色。本地模式在领取前检查脚本可读、解释器配置、两个 workspace 是否一致。配置为 `python` 这类裸命令时，实际能否启动由操作系统解析。Rabbit API 不需要本地执行器，Worker 消费时检查。两模式的代码路径可读成以下伪代码。

```text
事务内:
  锁定任务行，检查 owner、pending、资源与配置
  分配新的 runId 和 attempt
  db 模式取得 GPU 单槽租约
  生成 DefenseTaskRequest，检查配额，追加执行快照
  claimForExecution: UPDATE ... WHERE task_id = ? AND status = 'pending'
  创建 pending 派发意图，保存请求与运行指纹
  rabbit 模式同时保存 Outbox

事务提交后:
  db: afterCommit -> dispatchAfterCommit -> markSubmitting
      -> 写 accepted 标记 -> @Async("taskAsync") runPreDefense
  rabbit: Relay 发布 -> Worker 查 MySQL -> 领取意图和租约
          -> 写 accepted 标记 -> executeAndWait
```

MySQL 查询结果已是 `running` 时，Python 可能仍未创建。定位时把任务状态与派发状态分开。`pending` 意图表示尚待派发。`submitting` 表示交接中。`submitted` 已完成相应派发登记，`abandoned` 表示该轮放弃派发。这几项是派发表的值。

### 两个请求为什么只能领取一次

以不同请求键、同时启动 `42` 为例。A 在事务内通过 `SELECT ... FOR UPDATE` 锁定该行，看到 `pending`，写 `running/r1/1` 和派发意图后提交。B 取得锁后读到 `running`，状态检查失败。Mapper 的 `claimForExecution` 仍有 `WHERE status='pending'`，服务检查受影响行数，只有匹配的一次领取才成立。行锁让同一事务内“读、判断、写”可按顺序执行，条件更新让写入本身检查合法前态。

读 `AfterCommitTaskDispatcher` 时在纸上写出两个时间点。事务提交之前，任务变更和意图可以一起回滚。事务提交之后，派发失败已经不能靠回滚收回启动，此时要用派发失败处理和恢复扫描收敛状态。线程池满时，`TaskExecutorConfig` 的 `AbortPolicy` 产生拒绝，派发协调器处理失败与交接前租约。

`execution_snapshot` 记录各 attempt 的请求、运行指纹、配额和租约快照。`ExecutionFingerprintService` 对符合配置和大小条件的普通文件计算内容 SHA-256，目录输入记录路径及可取得的文件属性。`snapshotHash` 是快照摘要，不能据此推断整个目录逐文件内容都冻结。Python 另外采集解释器、依赖和设备环境。

[任务 Mapper](../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java) · [派发协调器](../migration-backend/src/main/java/com/securitymodel/application/task/TaskDispatchCoordinator.java) · [输入指纹](../migration-backend/src/main/java/com/securitymodel/application/task/ExecutionFingerprintService.java)

## 3. Java 怎样把参数交给 Python

`PythonLddTaskExecutor.createContext` 根据 `fingerprintService.getWorkspace()` 创建 attempt 目录。`AttemptProcessRegistry` 使用另一项配置定位受控目录。启动时将 `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE` 和 `SECURITY_MODEL_EXECUTOR_WORKSPACE` 指向同一路径。以下是 `invoke` 中可对照的核心代码。

```java
Files.writeString(context.requestFile,
        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8);
List<String> command = List.of(pythonExecutable, pythonScript,
        "--stage", request.getStage(),
        "--request", context.requestFile.toString(),
        "--result", context.resultFile.toString(),
        "--progress-file", context.progressFile.toString());
ProcessBuilder builder = new ProcessBuilder(command)
        .redirectOutput(context.stdoutFile.toFile())
        .redirectError(context.stderrFile.toFile());
```

随后设置工作目录，写启动前记录，`builder.start()` 启动 Python，再记录实际进程身份。命令参数按列表传递，路径中的空格仍位于同一参数。`request.json` 的一部分长这样。

```json
{
  "taskId": 42,
  "attempt": 1,
  "runId": "r1",
  "stage": "pre_defense",
  "dataset": { "path": "<dataset-directory>" },
  "algorithm": {
    "path": "vab://pre-defense/ldd",
    "configJson": "{\"dataset\":\"cifar10\"}"
  },
  "customParams": "{\"num_epochs\":1}"
}
```

真实请求还有资源和配置字段。这个片段用于看结构。`gpuLeaseToken` 由 `@JsonIgnore` 排除在 JSON 外。

`algorithm_entry.py` 的 `main` 用 `argparse` 取四个参数，再进入 `run`。这里发生两层反序列化。外层是文件中的请求对象，内层是各配置字段中保存的 JSON 文本。

```python
# algorithm_entry.py, shortened
request = json.loads(request_path.read_text(encoding="utf-8"))
algorithm = request.get("algorithm") or {}
hyper = request.get("hyperParameter") or {}
config = load_json_object(algorithm.get("configJson"), "algorithm.configJson")
config.update(load_json_object(hyper.get("configJson"), "hyperParameter.configJson"))
config.update(load_json_object(request.get("customParams"), "request.customParams"))
```

Python 核对算法和输入环境，生成任务隔离脚本，再用 `subprocess.Popen` 执行 VaB 子进程。Java 启动的是适配器，适配器启动的是训练。适配器汇聚训练输出到 `execution.log`，同时打印到自己的 stdout，Java 重定向后可在 `stdout.log` 看到。Java 也单独接收适配器的 stderr。

[请求 DTO](../migration-backend/src/main/java/com/securitymodel/application/task/DefenseTaskRequest.java) · [执行器](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutor.java) · [适配器](../algorithm-runtime/vab/algorithm_entry.py) · [独立短桩实验](learning/02-java-python.md)

## 4. Python 的进度与结果怎样回到页面

| 文件 | 谁写 | 谁读 |
| --- | --- | --- |
| `request.json` | Java 执行器 | Python 适配器 |
| `progress.json` | Python 适配器或生成的运行脚本 | Java `PythonProcessWaiter` 触发进度读取，日志查询也提供进度 |
| `stdout.log` / `stderr.log` | Java 重定向适配器输出 | Java 日志查询接口 |
| `result.json` | Python 适配器 | Java 等待进程退出后读取 |
| `python-children.json` | Python 适配器 | Java 进程检查与恢复逻辑 |

`invoke` 在进程退出后读取 `result.json`。退出码非零时，只有带 `status=failed` 的结构化结果才会继续交给 `validateResult`。退出码为零也必须有结果文件并通过校验。`validateResult` 核对 `stage`、`run_id`、`attempt`、`status`，要求有 `executor`、`message`、起止时间和 JSON 对象 `artifacts`。失败结果还需 `error_code` 和 `failure_reason`。随后 `persistResult` 和 `finish` 分别更新结果和终态。

```sql
-- Shared WHERE clause, shown with bind placeholders
WHERE task_id = ?
  AND current_run_id = ?
  AND status = 'running'
```

这条条件限制进度、结果和终态写入的目标。轮次匹配在 Python 协议检查中也出现。比如 `r1` 的结果对 `r1` 执行器合法，但数据库已经是 `42/r2/running`，SQL 影响 `0` 行。业务状态已经为 `canceled` 时同理。因此“文件是哪个进程写的”和“数据库现在让哪轮写”需要分别检查。

详情抽屉在运行期间每 3 秒查询任务和日志。进入终态或发现 `currentRunId` 变化时还刷新报告、审计和列表。轮次变化会清空旧日志和 offset，`detailVersion` 用于丢弃已经切走的抽屉响应。进程退出无结果时从 `invoke` 查看错误分类。退出码为零且缺文件对应 `RESULT_MISSING`。非零退出还可能归到子进程启动、CUDA 或其他执行故障，具体要对照 stderr 与错误码。

[等待器](../migration-backend/src/main/java/com/securitymodel/application/task/PythonProcessWaiter.java) · [任务 Mapper](../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java) · [报告接口](../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseReportController.java)

## 5. 失败、重试、取消与恢复

| 场景 | 从哪读 | 该观察什么 |
| --- | --- | --- |
| 提交到执行池被拒绝 | `TaskDispatchCoordinator.dispatchAfterCommit` | 放弃意图，为匹配运行收敛失败，释放交接前租约 |
| Java 提交后异常退出 | `DispatchRecoveryCoordinator.recoverAtStartup` / `scanPending` | 检查意图、运行身份、指纹、进程证据与有效期。可安全补派发的才补派发 |
| 用户取消 | `PreDefenseTaskCommandService.cancel` | 原子取消当前运行。db 在事务提交后通知本机执行器，rabbit Worker 扫描取消状态 |
| 超时或子进程退出异常 | `PythonProcessWaiter` / `PythonLddTaskExecutor` | 区分取消、超时、结构化失败和结果缺失 |
| 槽位清理 | `executeAndWait` 的 `finally` / `GpuLeaseService` | 拿到退出证据后释放。无法确认时把槽位隔离。完成清理决策后才清除 accepted 标记 |
| 重试失败任务 | `PreDefenseTaskCommandService.retry` | 同一个 `taskId`，新 `runId` 和 attempt，重新提交运行 |

Fencing token 标识单槽租约的取得代次。更新和清理遵守当前租约身份。进程树退出检查负责处理仍占有 GPU 的操作系统进程。MQ 交付是独立的一层。发布确认解决 Relay 到 Broker 的投递确认，手动 ACK 解决 Worker 的消费归结，领取账本与 MySQL 终态处理重投递窗口。

[恢复协调器](../migration-backend/src/main/java/com/securitymodel/application/task/DispatchRecoveryCoordinator.java) · [租约服务](../migration-backend/src/main/java/com/securitymodel/application/task/GpuLeaseService.java) · [Rabbit 实现](sdd/21-r4-outbox-rabbitmq-design.md)

## 6. 报告、日志与 ZIP 怎样生成

1. `PreDefenseReportQueryService.getReport` 检查可读归属，再用任务、执行结果、资源和导出状态拼装报告。页面可以下载报告 JSON。
2. `PreDefenseLogQueryService.getLogs` 接收每个文件的字节 offset。返回片段及新 offset。页面按文件路径合并，遇到 `reset` 或从头读取时替换。
3. `describe` 在报告查询时确认成功的 CIFAR-10 LDD 结果、五个训练批次及可读的 `retained_clean_subset` 索引。`submit` 锁父任务，按 `taskId + isolationRatio` 查询已有导出。进行中的导出或 ZIP 仍可读的成功导出可直接复用。需要生成时再验证比例并登记待执行的导出记录。
4. `CleanSubsetExportExecutor` 在异步执行阶段重新检查所选索引与数据根，再调用 Python 的导出入口生成 ZIP。下载按 `exportId` 查，文件消失时返回 `410 EXPORT_FILE_UNAVAILABLE`，页面可以重新提交一次。重建沿用原导出 ID，父任务状态不变。
5. API 角色启动时，`ExportRecoveryRunner` 将遗留 `pending` 和 `running` 导出分别记为 `EXPORT_NOT_STARTED` 和 `EXPORT_INTERRUPTED`。用户可以重新提交。

读取与下载通过后端路径边界校验。报告读取允许 owner 或管理员按后端规则读取。任务变更和导出提交检查 owner。实际导出需要 `retained_clean_subset`、可读选中索引和兼容数据。短桩可用于看报告流程，其产物清单只有调用标记。

[报告](../migration-backend/src/main/java/com/securitymodel/application/report/PreDefenseReportQueryService.java) · [日志](../migration-backend/src/main/java/com/securitymodel/application/report/PreDefenseLogQueryService.java) · [导出](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportService.java) · [导出恢复](../migration-backend/src/main/java/com/securitymodel/application/export/ExportRecoveryRunner.java)

## 7. 调试时按证据往下走

创建失败先查 HTTP 响应与资源状态。已经有 `taskId` 时先查 MySQL 的任务状态、当前运行身份和意图，再找对应 attempt 目录。目录存在时依次对照 `request.json`、`progress.json`、`stderr.log` 和 `result.json`。结果存在且页面状态不一致时，先核对 `run_id`、`attempt` 与数据库当前值。最后再检查接口返回与页面展示。

| 看到的问题 | 第一个证据 | 如何缩小范围 |
| --- | --- | --- |
| 找不到数据集 | 可用数据集接口及数据集验证结果 | 先查启用状态、`parseStatus`，再查服务器目录与 CIFAR 文件 |
| 创建成功，启动返回 `EXECUTOR_NOT_CONFIGURED` | 任务仍为 `pending` | 把预检分成脚本、解释器、workspace 三项逐个核对 |
| `running` 但没有 attempt 目录 | 当前 `runId` 对应派发意图 | 检查是否已提交后台、线程池是否拒绝、是否中断待恢复 |
| 有请求但没有结果 | 对应轮次的 stderr、进程状态 | 查路径、退出码、超时和协议错误，避免用旧轮次目录诊断 |
| 有 `success` 结果文件，页面是失败或取消 | 真实 `currentRunId` 及任务状态 | 文件是执行器输出，终态由 Java 的协议校验和数据库条件更新决定 |
| 报告成功却不能下载 | `available`、索引与数据文件、`exportId` | 先区分导出条件不满足、导出任务失败、生成 ZIP 后文件丢失 |

[带练习的排查路线](learning/README.md) · [验证范围与历史验收](sdd/26-current-delivery-facts.md)
