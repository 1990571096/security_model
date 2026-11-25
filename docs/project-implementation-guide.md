# 安全模型训练平台实现指南

本文沿着“前端创建任务 → 后端建档 → 确认启动 → 派发 → Python/VaB 执行 → 结果回写 → 报告/导出”逐段看代码。默认以 `db/api` 单进程模式讲解，再标出 `rabbit` 模式的差异。示例任务号沿用教学中的 `42`，真实 `runId` 由 UUID 生成。

配套阅读：[业务流程详解](project-business-flows.md) 用多个场景串起同一套链路；[设计思路](project-design-overview.md) 解释为什么这样分层。

## 1. 代码地图与启动顺序

| 层 | 目录 | 主要职责 |
| --- | --- | --- |
| 前端 | `migration-frontend/src` | 页面、表单、轮询、错误提示 |
| HTTP | `migration-backend/.../interfaces/web` | 路由、DTO、HTTP 状态 |
| 应用服务 | `.../application/task`、`resource`、`report`、`export` | 事务、校验、编排 |
| 领域 | `.../domain` | 任务、数据集、算法、租约、消息账本 |
| 持久化 | `.../infrastructure/persistence` | MyBatis Mapper、条件 SQL |
| 执行器 | `.../infrastructure/executor` | 进程启动、文件协议、等待 |
| 缓存 | `.../infrastructure/cache` | Redis 资源目录缓存 |
| Python 适配器 | `algorithm-runtime/vab/algorithm_entry.py` | 解析请求、生成任务脚本、启动 VaB |

后端启动后先完成两件事，才开始接收可执行请求：

1. `DispatchRuntimePolicy` 解析 `dispatch.mode` 与 `dispatch.role`；`db + worker` 是非法组合，直接启动失败。
2. `TaskRecoveryRunner` / `WorkerConsumerLifecycle` 完成启动恢复。`SchedulingReadinessGate` 未就绪时，启动接口返回 `SCHEDULING_NOT_READY`。

这解释了一个常见现象：进程端口已经监听，但“启动任务”仍可能被拒绝，因为恢复尚未完成。

## 2. 登录、资源与参数配置

### 2.1 登录

`POST /auth/login` → `AuthController.login`：

1. 按用户名查询 `sys_user`；
2. 校验启用状态和 BCrypt 密码；
3. 签发 JWT，后续请求由 `JwtAuthenticationFilter` 解析身份；
4. Controller 通过 `CurrentUserService` 取当前用户。

JWT 只回答“你是谁”。任务写操作还要 `ownerUserId` 校验，管理员读取也有单独规则。

### 2.2 数据集登记与验证

登记 `POST /api/dataset` 只写路径和元数据，`parseStatus=pending`，不假装已经解析。管理员点“验证”调用 `POST /api/dataset/{datasetId}/validate`，进入 `ResourceCommandService.verifyDataset`。

当前支持的真实格式：

| 路径形态 | 识别方式 | 运行根目录 | LDD 参数 |
| --- | --- | --- | --- |
| `.../cifar-10-batches-py` | 七个官方批次文件 + MD5 校验 | 父目录 | `cifar10` |
| `.../cifar-100-python` | `meta/train/test` + MD5 校验 | 父目录 | `cifar100` |
| `.../gtsrb_dataset.h5` | HDF5 文件头 + GTSRB 约定统计 | 文件父目录 | `gtsrb` |
| `.../tiny-imagenet-200` | `wnids.txt/train/val` 目录结构 | 不进入执行 | 当前不支持 |

CIFAR 校验会计算官方 MD5，避免把同名但损坏的文件当成可用数据。GTSRB 校验会读取文件头 `89 48 44 46 0D 0A 1A 0A`，记录训练 39,209、测试 12,630、43 类、3,072 维。Tiny ImageNet 目录可以登记和计数，但任务创建阶段会返回 `DATASET_RUNTIME_UNSUPPORTED`。

为什么 GTSRB 的运行根目录是 HDF5 的父目录？VaB 的 `dataloader_data.py` 对 `gtsrb` 使用相对路径 `dataset/gtsrb_dataset.h5`，而适配器把 VaB 根目录作为子进程 cwd。因此 Java 传的通用 `data_path` 对 GTSRB 不是唯一决定项，HDF5 必须保持在 VaB 工作目录下的 `dataset` 目录中。

共享的 `.../dataset` 根目录不会被误判为 GTSRB，因为它可能同时包含 CIFAR 和 GTSRB；选择它时由算法参数 `dataset` 决定训练集。

验证完成后写 `parseStatus/parseMessage/parseTime`，清理或写入统计字段，并在事务提交后失效资源缓存。

### 2.3 算法默认参数

独立“超参模板”入口已移除。算法详情页“参数配置中心”编辑 `sys_algorithm.default_config`：

```text
前端结构化表单（根据 config_schema 生成）
        ↓ PUT /api/algorithm/{id}/config
AlgorithmConfigCommandService
        ↓ 管理员校验 + JSON 对象校验 + LddConfigurationPolicy 校验
UPDATE sys_algorithm.default_config
        ↓ 审计 UPDATE_CONFIG + 清缓存
新任务读取该默认配置
```

历史 `sys_hyper` 仍可读，只用于旧任务/旧报告兼容；新任务 `hyperId=null`。

## 3. 创建任务：从页面到 `pending`

### 3.1 前端

`TaskCenterView.vue` 的 `submitCreate`：

1. 校验数据集、算法和必填项；
2. 解析自定义参数 JSON；
3. 生成 `payload`，其中 `hyperId: null`；
4. 调用 `createTask(payload, idempotencyKey)`；
5. 成功后用响应 `taskId` 打开详情。

```javascript
const created = unwrap(await createTask(payload, pendingCreateKey))
await openDetail(created)
```

创建按钮只建档，不启动训练。

### 3.2 HTTP 到服务

`POST /api/pre-defense` → `PreDefenseTaskController.create` → `PreDefenseTaskCommandService.create`。

服务做四类工作：

1. **权限**：当前用户必须有效；写操作使用稳定 `ownerUserId`。
2. **资源解析**：数据集必须启用、`parseStatus=success`；算法必须启用、`defenseStage=pre`、`implementationPath=vab://pre-defense/ldd`。
3. **参数归一化**：`LddConfigurationPolicy` 合并算法默认、历史模板、自定义参数，并校验键、类型、范围和数据集一致性。
4. **写任务**：插入 `sys_pre_defense`，`status=pending`、`progress=0`，保存最终参数和资源引用。

响应 `201` 返回任务行。此时还没有 `runId`，也不会占用 GPU。

### 3.3 幂等键

`RequestIdempotencyService` 用 `(userId, operation, key, requestHash)` 记录请求：

- 同键同内容已完成 → 返回原响应；
- 同键不同内容 → `REQUEST_IN_PROGRESS` / 冲突；
- 处理中重复 → 返回进行中，不重复写任务。

幂等键解决 HTTP 重发，不替代数据库行锁。

## 4. 启动任务：事务内建立“这一轮”

### 4.1 入口

`POST /api/pre-defense/start/{taskId}` → `start` → `launchTask`。

前端在用户确认后调用。`runCommand` 先弹确认框，再用独立的启动幂等键发请求。

### 4.2 事务内步骤

```text
BEGIN
  SELECT * FROM sys_pre_defense WHERE task_id=? FOR UPDATE
  校验 owner、status=pending、资源仍可用、执行器配置有效
  attempt = max(历史 attempt)+1
  runId = UUID
  db 模式：申请 GPU 单槽租约
  构建 DefenseTaskRequest（最终参数、资源路径、配额）
  buildRuntimeSnapshot + requestHash
  appendExecutionSnapshot 到 execution_snapshot JSON
  UPDATE sys_pre_defense
     SET status='running', current_run_id=?, current_attempt=?, start_time=?, execution_snapshot=?
     WHERE task_id=? AND status='pending'
  创建 sys_task_dispatch_intent(status=pending)
  rabbit 模式：同一事务插入 sys_dispatch_outbox(status=new)
COMMIT
```

关键点：

- `SELECT FOR UPDATE` 让同一任务的并发启动排队；
- `claimForExecution` 仍带 `WHERE status='pending'`，只有一行能成功；
- 租约、任务领取、意图在同一事务，任一失败一起回滚；
- `rabbit` 模式启动事务**不拿 GPU 租约**，避免消息在队列里等待时占着卡。

### 4.3 快照内容

`execution_snapshot` 是 JSON 数组，每次 attempt 追加一项，至少包含：

- 本轮 `DefenseTaskRequest`；
- 请求/运行指纹；
- 配额；
- GPU 租约信息（若已取得）；
- 开始时间。

它的作用不是“日志”，而是回答“这一轮实际提交了什么”。算法配置在启动后被修改，不会改写历史快照。

### 4.4 启动后返回什么

响应 `200` 只表示启动命令已受理，任务已进入 `running`。此时 Python 可能还没创建。正确表述是“已提交执行”，不是“已经开始训练”。

## 5. `db` 模式：事务提交后进入本地线程池

`AfterCommitTaskDispatcher` 在事务提交后执行：

```text
dispatchAfterCommit(request)
  → intent.markSubmitting()
  → processRegistry.markAccepted()
  → executor.runPreDefense(request)   // @Async("taskAsync")
```

线程池由 `TaskExecutorConfig` 配置有界队列和 `AbortPolicy`。线程池拒绝时：

- 清除 accepted 标记；
- 释放本轮租约；
- 把任务/意图收敛为失败；
- 不允许“数据库 running、实际没人执行”长期悬挂。

执行线程进入 `PythonLddTaskExecutor`，详见第 7 节。

## 6. `rabbit` 模式：Outbox → RabbitMQ → Worker

### 6.1 为什么需要 Outbox

启动事务同时写任务、意图、Outbox。若先提交数据库再发消息，进程可能在两步之间崩溃，消息永远丢失；若先发消息再提交，数据库回滚后 Worker 可能执行一个不存在的任务。Outbox 把“要发什么”与业务事实放在同一事务。

### 6.2 Relay

`TaskOutboxRelay.scheduledRelay()` 默认每 3 秒运行：

1. `reclaimExpired` 回收超时 claim；
2. `claimBatch` 把 `new/retry` 行条件更新为 `publishing`，带 `claimOwner + claimToken + claimUntil`；
3. 事务外调用 `RabbitDispatchTransport.publish`；
4. 发布成功后 `markPublished`，失败按 5s/30s/120s 退避，超过次数置 `dead`。

`publishing` 行在 Relay 崩溃后会过期重领。旧 Relay 的确认不能覆盖新 claim，因为 `markPublished` 同时匹配 `claimToken`。

### 6.3 发布确认

`RabbitDispatchTransport` 在同一个 channel 上：

- `confirmSelect()`；
- `basicPublish(..., mandatory=true)`；
- `waitForConfirms(timeout)`；
- 注册 `ReturnListener` 捕获不可路由消息；
- 业务 `messageId` 在重发时保持稳定，`correlationId` 每次发布唯一。

所以“Broker ACK”与“消息可路由”是两件事。不可路由的 mandatory return 会转成 `RABBIT_UNROUTABLE`，避免任务无限等待。

### 6.4 队列拓扑

| 名称 | 作用 |
| --- | --- |
| `security-model.dispatch` | 主 Direct Exchange |
| `security-model.dispatch.worker` | Worker 主队列，手动 ACK，prefetch=1 |
| `security-model.dispatch.retry.5s/30s/120s` | TTL 延迟队列，到期经 DLX 回主队列 |
| `security-model.dispatch.dlx` / `.dlq` | 死信 Exchange / 队列 |

重试队列不是“消费者 sleep”，消息在队列里等待，不占 Worker 线程。

### 6.5 Worker 消费

`DispatchMessageListener` 手动 ACK，交给 `DispatchMessageHandler`：

1. 解析事件；
2. 若本地已 accepted → 直接 ACK 重复投递；
3. `TaskDispatchConsumptionService.claim` 插入/抢占 `sys_dispatch_consumption`；
4. 校验任务仍是当前 `runId/attempt`；
5. 查 `sys_task_dispatch_intent`；
6. `DispatchClaimTransactionService` 同一事务把意图 `pending→submitting` 并取得 GPU 租约；
7. `executor.executeAndWait(request)` 同步阻塞到 Python 退出；
8. 根据任务终态写消费账本并 ACK。

**ACK 时机**：只有任务终态、消费账本终态、明确的 stale/duplicate，或消息已转移到重试/死信后才 ACK。异常且结果不确定时，不盲目重投，避免第二个 Python。

### 6.6 重复消费的收敛

`messageId` 是消费账本主键。重复投递有三种结果：

- 已终态 → duplicate，ACK；
- 处理中且 claim 未过期 → 延迟重投，保留原消息或确认转移；
- claim 过期 → 条件抢占，只有一个 Worker 获得新 token。

即使 Broker 重投，任务身份和意图状态仍会阻止第二个训练进程。

## 7. Java 到 Python：文件协议

### 7.1 目录

`PythonLddTaskExecutor.createContext` 按 attempt 建立目录：

```text
<workspace>/pre_defense-42/attempt-1/
  request.json
  progress.json
  stdout.log
  stderr.log
  result.json
  python-children.json
  artifacts/...
```

### 7.2 Java 写请求

```java
Files.writeString(context.requestFile,
    objectMapper.writeValueAsString(request), UTF_8);

List<String> command = List.of(pythonExecutable, pythonScript,
    "--stage", request.getStage(),
    "--request", context.requestFile.toString(),
    "--result", context.resultFile.toString(),
    "--progress-file", context.progressFile.toString());

ProcessBuilder builder = new ProcessBuilder(command)
    .redirectOutput(context.stdoutFile.toFile())
    .redirectError(context.stderrFile.toFile());
builder.start();
```

请求中有任务/运行身份、数据集路径、算法路径、最终参数 JSON、配额和租约信息。`gpuLeaseToken` 不序列化到子进程。

### 7.3 Python 解析

`algorithm_entry.py`：

```python
request = json.loads(request_path.read_text(encoding="utf-8"))
config = load_json_object(algorithm.get("configJson"), "algorithm.configJson")
config.update(load_json_object(hyper.get("configJson"), "hyperParameter.configJson"))
config.update(load_json_object(request.get("customParams"), "request.customParams"))
```

然后生成单任务 VaB 脚本，以 `subprocess.Popen` 启动：

```python
process = subprocess.Popen(
    build_command(generated_script, config),
    cwd=str(vab_root),
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
)
```

`cwd` 是 VaB 根目录，这也是 GTSRB 相对路径 `dataset/gtsrb_dataset.h5` 能工作的原因。

### 7.4 进度与结果

Python/生成脚本写：

- `progress.json`：阶段、百分比、消息；
- `result.json`：`stage`、`run_id`、`attempt`、`status`、`executor`、时间、`artifacts`，失败时还要 `error_code`、`failure_reason`；
- 日志：stdout/stderr 重定向文件。

Java 等待期间读取进度；进程退出后读结果。退出码为 0 不等于成功，必须有合法结果文件；退出码非零但有结构化失败结果也可以进入失败收敛。

## 8. 结果回写与终态

`validateResult` 校验：

- 文件存在且 JSON 可解析；
- `stage` 匹配；
- `run_id/attempt` 匹配本轮；
- `status` 合法；
- `artifacts` 是对象；
- 失败结果有错误码和原因。

写回 SQL 统一带：

```sql
WHERE task_id = ?
  AND current_run_id = ?
  AND status = 'running'
```

再执行：

1. `persistResult` 写 `cleaning_result`、进度、结束时间；
2. `finish` 条件更新终态；
3. 释放/隔离 GPU 租约；
4. 清除 accepted 标记；
5. 写审计。

如果结果属于旧 `r1`，数据库当前是 `r2`，更新影响 0 行。页面不会因为一个旧文件变成成功。

## 9. 查询、报告、日志与导出

### 9.1 任务详情轮询

`TaskCenterView` 在运行期间约每 3 秒查询任务和日志。进入终态或 `currentRunId` 变化时刷新报告、审计和列表。轮次变化会清空旧日志 offset，`detailVersion` 丢弃切走后的旧响应。

### 9.2 报告

`PreDefenseReportQueryService.getReport`：

1. 校验 owner/管理员读取权限；
2. 读取任务、本轮结果、资源和导出状态；
3. 拼装参数、时间、产物、可导出选项。

报告不是重新执行训练，而是对本轮结构化结果和资源的投影。

### 9.3 日志

`PreDefenseLogQueryService.getLogs` 接收每个文件的字节 offset，返回片段和新 offset。页面按文件路径合并；`reset=true` 时替换该文件已有内容。这样长日志不需要每次全量传输。

### 9.4 清洗子集导出

`CleanSubsetExportService`：

1. 锁父任务，校验成功 LDD、CIFAR-10 批次、`retained_clean_subset` 索引；
2. 同一 `taskId + isolationRatio` 已有导出可复用；
3. 登记 `pending` 导出记录，异步 `CleanSubsetExportExecutor` 执行；
4. Python 按索引从训练批次抽样本打 ZIP；
5. 下载按 `exportId`，文件丢失返回 `410`，可重建；
6. 服务重启时 `ExportRecoveryRunner` 把遗留 `pending/running` 标为可重试失败。

导出是独立生命周期，父任务保持 `success`。

## 10. 失败、取消、重试与恢复

| 场景 | 代码入口 | 结果 |
| --- | --- | --- |
| 创建/启动预检失败 | `resolveCommand` / `launchTask` | 任务仍 pending，返回明确错误码 |
| 线程池拒绝 | `dispatchAfterCommit` catch | 释放租约，失败收敛，意图 abandoned |
| Python 退出无结果 | `PythonLddTaskExecutor.invoke` | `RESULT_MISSING` |
| Python 失败结果 | `validateResult` | 写 failed + error_code |
| 用户取消 | `cancel` | 条件更新 canceled，通知执行器/Worker 终止进程 |
| 取消后旧结果晚到 | `updateResultForRun` | `current_run_id/status` 不匹配，0 行 |
| 启动事务后 JVM 崩溃 | `DispatchRecoveryCoordinator` | 查意图、accepted、进程证据，安全时补派发，否则收敛 |
| 失败重试 | `retry` | 同一 taskId，新 runId/attempt，历史快照保留 |
| Rabbit 重投 | `DispatchMessageHandler` | 消费账本 + 任务身份收敛，不重复启动 |

取消和 GPU 回收不是同一时刻：API 先把业务状态改为 canceled，执行器收到通知后终止进程树；只有确认进程退出或确认无需清理，才释放槽位。

## 11. Redis 缓存实现

### 11.1 缓存什么

`ResourceQueryService`：

- `findDatasets()` → `security-model:resource:dataset:list`
- `findDataset(id)` → `security-model:resource:dataset:{id}`
- `findAlgorithms()` → `security-model:resource:algorithm:list`
- `findAlgorithm(id)` → `security-model:resource:algorithm:{id}`
- 历史模板列表仍保留键，但新任务不使用。

启动、预览、确认使用 `findDatasetAuthoritative` / `findAlgorithmAuthoritative`，直接查 MySQL。

### 11.2 读写与失效

`ResourceCatalogCache.getListOrLoad`：

1. 读取 generation；
2. 读缓存；命中时再读一次 generation，若变化则视为 miss；
3. miss 回源 MySQL；
4. 用 Lua `PUT_IF_GENERATION` 条件写回，generation 不一致则放弃，防止慢读者把旧值写回；
5. TTL 5 分钟，generation TTL 1 天。

资源修改：

```text
saveDataset/verifyDataset
  → cache.evict(...)
  → 事务提交后 afterCommit
  → increment generation + DEL key
```

Redis 异常只记 debug 并降级，不让资源维护接口失败。代价是缓存可能短暂旧值，所以执行契约不使用缓存。

### 11.3 知识向量

`KnowledgeVectorStore` 使用 Redis 8 vector set 做相似度候选，ACL 过滤仍在 MySQL/Java。向量索引不可用时回退词法排序。这个用途和资源目录缓存分开，不能把向量库当成权限来源。

## 12. RabbitMQ 关键细节

### 12.1 发布语义

- 业务消息 `messageId` 稳定，Outbox 重发不换 ID；
- `correlationId` 每次 publish 唯一，confirm/return 不串；
- `mandatory=true`，不可路由立即识别；
- 发布失败按退避重试，不把 Broker 故障误判成训练失败；
- 只有 `RABBIT_UNROUTABLE` 才立即把任务收敛为失败。

### 12.2 消费语义

- prefetch=1，单 Worker 串行处理；
- 手动 ACK；
- 数据库故障不热循环 `nack requeue`，转延迟队列；
- 处理中重复投递延迟 120 秒再试，避免 Worker 崩溃后消息永久 ACK；
- 重试耗尽：先收敛任务/意图/消费账本，再发 DLQ；DLQ 是索引，不是任务事实。

### 12.3 单机也可以是真的消息队列

不需要“假 MQ”。在本机启动 RabbitMQ，再用两个 JVM：

```text
API   :8102  role=api
Worker:8103  role=worker
Broker:localhost:5672/5673
```

或一个 JVM `role=all` 同时提供 API 和消费者，仍然经过 Broker。脚本：

```powershell
.\scripts\r4\R4.StartRabbit.ps1 -NodePort 5673 -SecretsPath .\tmp\r4-secrets.json
.\scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode rabbit -Role all -ApiPort 18210 -WorkerPort 18211 `
  -SchemaName security_model_deploy_20261118 `
  -DbPassword $env:MYSQL_IT_PASSWORD -JwtSecret $secret `
  -RabbitHost 127.0.0.1 -RabbitPort 5673 `
  -RabbitUser $user -RabbitPassword $password -RabbitVhost $vhost
```

具体参数以 [单机部署文档](sdd/24-stage3-single-node-deployment.md) 为准。

## 13. 数据集故障的排查顺序

| 看到的现象 | 先查 | 再查 |
| --- | --- | --- |
| 数据集页面整体“读取失败” | 后端 8102 是否存活、代理是否 502、JWT 是否过期 | 不是某条数据路径的问题 |
| 单条 `parseStatus=failed` | `parseMessage` | 路径是否存在、可读、格式是否匹配 |
| CIFAR 校验和不匹配 | 文件名与 MD5 | 是否官方 Python 批次、文件是否损坏 |
| GTSRB 不是 HDF5 | 文件头 8 字节 | 是否下载了错误文件 |
| 创建任务提示数据集不可用 | 启用 + `parseStatus=success` | 参数 `dataset` 是否与所选路径冲突 |
| Tiny ImageNet 创建被拒 | `DATASET_RUNTIME_UNSUPPORTED` | 当前 LDD 不支持，换 CIFAR/GTSRB |

真实路径示例：

```text
F:\Study\postgraduate\1下\VB backdoor defence\VaB-main\dataset\cifar-10-batches-py
F:\Study\postgraduate\1下\VB backdoor defence\VaB-main\dataset\cifar-100-python
F:\Study\postgraduate\1下\VB backdoor defence\VaB-main\dataset\gtsrb_dataset.h5
F:\Study\postgraduate\1下\VB backdoor defence\VaB-main\dataset\tiny-imagenet-200
```

前三个可创建 LDD 任务；Tiny 仅登记/校验。

## 14. 调试证据链

1. 先看 HTTP 响应码和 `code`；
2. 有 `taskId` 后看 `sys_pre_defense` 的 `status/current_run_id/current_attempt`；
3. 再看 `sys_task_dispatch_intent` 是否 `pending/submitting/submitted/abandoned`；
4. 看对应 attempt 目录是否存在 `request.json`；
5. 看 `progress.json`、`stderr.log`、`result.json`；
6. 结果存在但页面不一致，核对 `run_id/attempt` 与 SQL 条件；
7. `rabbit` 模式再加看 Outbox 状态、消费账本、Broker 队列和 DLQ。

排查时不要先改数据。先确定事实停在哪一层，再决定修复动作。
