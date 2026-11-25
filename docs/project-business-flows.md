# 业务流程详解

本文不从类名开始，而从**用户点下按钮之后系统发生什么**开始。每个场景都按同一模板说明：

```text
页面动作 → HTTP 请求 → 后端事务/校验 → 异步执行 → 文件与数据库 → 页面如何看到结果
```

先读 [设计思路](project-design-overview.md) 建立整体图，再用 [实现指南](project-implementation-guide.md) 对照具体代码。

## 0. 全局角色与状态

### 0.1 系统角色

| 角色 | 看什么 | 能做什么 |
| --- | --- | --- |
| 实验者 | 数据集、算法、任务、报告 | 建任务、启动、取消、重试、导出 |
| 管理员 | 资源目录、账号、监控 | 登记/验证数据集、维护算法配置、查看全局状态 |
| API JVM | HTTP、事务、Outbox | 接收命令，写任务事实 |
| Worker JVM | Rabbit 消息、执行器 | 领取意图、执行 Python |
| Python 适配器 | `request.json`、VaB | 训练、写进度和结果 |

### 0.2 状态与“已启动”的含义

```text
pending ──确认启动──> running ──结果通过──> success
   │                    ├──失败──────────> failed
   │                    └──取消──────────> canceled
   └──预检失败：仍为 pending
```

一个容易误解的点：`running` 表示“启动事务已经领取本轮”，不表示 Python 已经创建。判断后台是否接手，要看：

1. `sys_task_dispatch_intent.status`；
2. 对应 `<workspace>/pre_defense-{taskId}/attempt-{attempt}/request.json`；
3. `db` 模式的 accepted 标记；
4. `rabbit` 模式的 Outbox/消费账本。

## 1. 场景一：登记并验证真实数据集

### 1.1 页面动作

管理员在数据集页填写：

- 名称；
- 类型；
- 路径；
- 说明；
- 启用状态。

点“保存数据集”只登记，不解析。页面提示“已登记，待文件验证”。

### 1.2 请求链路

```text
DatasetView.submitForm()
  → POST /api/dataset
  → ResourceController.createDataset
  → ResourceCommandService.saveDataset
  → INSERT sys_dataset
  → parse_status='pending'
  → 返回 datasetId
```

登记时会检查路径语法，但不会因为路径存在就标记成功。这样避免“填了一个目录，系统假装已经理解文件格式”。

### 1.3 验证链路

管理员点“验证”：

```text
DatasetView.verify(row)
  → POST /api/dataset/{datasetId}/validate
  → ResourceCommandService.verifyDataset
  → 按路径形态识别格式
  → 写 parse_status / parse_message / parse_time / 统计字段
  → 事务提交后清 Redis 缓存
```

### 1.4 四种真实路径的处理

| 用户路径 | 后端识别 | 结果 |
| --- | --- | --- |
| `.../cifar-10-batches-py` | 官方 7 个批次文件 + MD5 | `success`，50,000 训练 / 10,000 测试 / 10 类 |
| `.../cifar-100-python` | `meta/train/test` + MD5 | `success`，50,000 / 10,000 / 100 类 |
| `.../gtsrb_dataset.h5` | HDF5 文件头 + 约定统计 | `success`，39,209 训练 / 12,630 测试 / 43 类 |
| `.../tiny-imagenet-200` | `wnids.txt/train/val` | `success`，但注明“当前 LDD 暂不支持” |

GTSRB 运行时的特殊点：

```text
Java 解析所选 gtsrb_dataset.h5
  → runtimePath = 文件父目录（.../dataset）
  → dataset 参数自动填 gtsrb
  → Python 以 VaB 根目录为 cwd
  → VaB 相对路径 dataset/gtsrb_dataset.h5 命中真实文件
```

如果直接把 HDF5 的完整路径当成 `data_path` 传给 VaB，VaB 的 `gtsrb` 分支不会使用它，训练仍会去找相对路径。因此系统把“用户登记的路径”和“运行时根目录”分开。

### 1.5 失败时页面看到什么

- 路径不存在：`路径不存在：...`
- 路径不可读：`路径不可读：...`
- CIFAR 校验和不匹配：`CIFAR 官方批次校验和不匹配：文件名`
- GTSRB 文件头错误：`GTSRB 文件不是有效的 HDF5`
- Tiny 缺目录：`Tiny ImageNet 目录缺少必要条目：train, val`

只有 `enabled=true && parseStatus=success` 的数据集才会进入任务创建。

## 2. 场景二：CIFAR-10 正常任务（`db` 模式）

这是默认的单机单进程路线，也是理解全链路的主线。

### 2.1 页面提交创建

```text
TaskCenterView 选择数据集/算法/参数
  → submitCreate()
  → JSON.parse 校验自定义参数
  → POST /api/pre-defense
```

请求体包含：

```json
{
  "taskName": "LDD CIFAR-10 实验",
  "datasetId": 2125427172150165505,
  "algorithmId": 14,
  "hyperId": null,
  "customParams": "{\"num_epochs\":1}"
}
```

`hyperId=null` 是当前新任务的固定口径。算法默认配置来自参数配置中心。

### 2.2 后端建档

```text
PreDefenseTaskController.create
  → PreDefenseTaskCommandService.create
  → 校验当前用户、数据集、算法
  → LddConfigurationPolicy.merge
  → INSERT sys_pre_defense(status='pending')
  → 返回 201 + taskId
```

参数合并顺序：

```text
算法默认配置
  → 历史模板配置（仅兼容旧任务）
  → 本次自定义参数
```

同时校验：

- `dataset` 必须是 `cifar10/cifar100/gtsrb`；
- 所选数据集路径推断的类型不能与参数冲突；
- `data_path` 若显式给出，必须等于运行时根目录；
- `trigger_type`、`num_epochs`、`batch_size` 等范围合法。

此时数据库里只有一条 `pending` 任务，没有运行身份，也没有 GPU 租约。

### 2.3 用户确认启动

```text
用户点击“启动任务”
  → ElMessageBox 确认
  → POST /api/pre-defense/start/42
```

启动接口不是“再建一条任务”，而是把 `42` 领取成一次运行：

```text
BEGIN
  SELECT ... FOR UPDATE
  校验 owner / pending / 资源 / 执行器
  attempt = 1
  runId = UUID
  db 模式：取得 GPU 单槽租约
  buildExecutionRequest
  appendExecutionSnapshot
  UPDATE ... WHERE status='pending'
  创建 dispatch_intent(pending)
COMMIT
```

事务提交后：

```text
afterCommit
  → markSubmitting
  → markAccepted
  → taskAsync 线程池
  → PythonLddTaskExecutor
```

如果线程池拒绝，执行器不会启动，任务被收敛为失败并释放租约。

### 2.4 Java 准备本轮目录

```text
<workspace>/pre_defense-42/attempt-1/
  request.json
  progress.json
  stdout.log
  stderr.log
  result.json
```

`request.json` 中的关键内容：

```json
{
  "taskId": 42,
  "runId": "r1",
  "attempt": 1,
  "stage": "pre_defense",
  "dataset": { "path": "F:/.../VaB-main/dataset" },
  "algorithm": {
    "path": "vab://pre-defense/ldd",
    "configJson": "{\"dataset\":\"cifar10\",...}"
  },
  "customParams": "{\"num_epochs\":1}"
}
```

### 2.5 Python 执行 VaB

```text
Java 写入 request.json
  → ProcessBuilder 启动 algorithm_entry.py
  → Python 解析请求
  → 生成单任务 VaB 脚本
  → subprocess.Popen(cwd=VaB 根目录)
  → VaB 读取 dataset/data_path
  → 训练、筛选、写产物
```

执行期间：

- Python 更新 `progress.json`；
- Java 轮询进度并更新 `sys_pre_defense.progress`；
- 页面每 3 秒拉任务和日志；
- 用户看到的是“运行中”，不是最终结果。

### 2.6 Python 写结果

`result.json` 至少包含：

```json
{
  "stage": "pre_defense",
  "run_id": "r1",
  "attempt": 1,
  "status": "success",
  "executor": "vab-ldd",
  "started_at": "...",
  "finished_at": "...",
  "artifacts": {
    "retained_clean_subset": "..."
  }
}
```

失败时 `status=failed`，并带 `error_code` 和 `failure_reason`。

### 2.7 Java 校验并落库

```text
进程退出
  → 读 result.json
  → validateResult：stage/run_id/attempt/status/artifacts
  → persistResult
  → finish（WHERE current_run_id='r1' AND status='running'）
  → 释放或隔离 GPU 租约
  → 清 accepted 标记
```

页面随后看到 `success`、结束时间、报告和产物。如果结果文件的 `run_id` 是 `r1`，而数据库已经是 `r2`，SQL 影响 0 行，页面不会误判成功。

### 2.8 报告与日志

```text
GET /api/pre-defense/report/42
GET /api/pre-defense/logs/42?offsets=...
```

报告从任务、结果、资源和导出状态拼装；日志按字节 offset 增量读取。报告不是重新跑训练。

## 3. 场景三：GTSRB 任务的差异

GTSRB 和 CIFAR 的前端、创建、启动、调度、结果回写完全相同，差异只在数据集解析和 Python 运行时路径：

```text
选择 .../gtsrb_dataset.h5
  → 验证：HDF5 头 + 统计
  → 预览：dataset 参数自动为 gtsrb
  → 运行时根目录 = 文件父目录
  → VaB cwd = VaB-main
  → VaB 读取 dataset/gtsrb_dataset.h5
```

创建任务时如果自定义参数写了 `"dataset":"cifar10"`，会触发 `DATASET_PARAMETER_MISMATCH`，因为所选数据集已经被推断为 `gtsrb`。

如果 HDF5 被移动到 `VaB-main` 之外，即使绝对路径存在，VaB 的相对读取仍可能失败。此时应保证 `dataset/gtsrb_dataset.h5` 位于 VaB 根目录下，或修改 VaB 数据加载逻辑。

## 4. 场景四：Tiny ImageNet 只登记不执行

```text
登记 .../tiny-imagenet-200
  → 目录结构校验通过
  → sampleCount=100000, classCount=200
  → parseMessage 明确“当前 LDD 暂不支持”
```

创建 LDD 任务时：

```text
resolveCommand
  → VabTinyImageNetDataset.fromDirectory(path)
  → 抛出 DATASET_RUNTIME_UNSUPPORTED
```

这是有意的边界：**能验证目录，不等于当前算法能消费它**。未来接入 Tiny ImageNet 训练时，再扩展参数和 VaB 数据加载器。

## 5. 场景五：`rabbit` 模式正常任务

### 5.1 用户视角

页面操作完全一样：创建、确认启动、查询。用户不需要知道后面是线程池还是 RabbitMQ。

### 5.2 启动事务差异

```text
db 模式：
  启动事务内拿 GPU 租约
  提交后本机线程池执行

rabbit 模式：
  启动事务内不拿 GPU 租约
  同一事务写 Outbox
  提交后 Relay 发消息
  Worker 消费时才拿租约
```

为什么 Rabbit 模式不在 API 启动时拿租约？消息可能在队列里等待，提前占卡会让其他可执行任务饿死。

### 5.3 消息链路

```text
API 启动事务
  → sys_pre_defense.running
  → sys_task_dispatch_intent.pending
  → sys_dispatch_outbox.new
COMMIT
  ↓
TaskOutboxRelay（每 3 秒）
  → claim outbox（publishing）
  → RabbitMQ publish + confirm
  → markPublished
  ↓
RabbitMQ: security-model.dispatch.worker
  ↓
Worker DispatchMessageListener
  → 消费账本 claim
  → 校验任务/意图/身份
  → 同一事务 submitting + GPU 租约
  → executeAndWait
  → 任务终态
  → 消费账本 terminal
  → ACK
```

### 5.4 页面如何看到进度

Worker 执行 Python 时仍写同一套 `progress.json/result.json`，Java Worker 进程读取并更新同一个 MySQL 任务行。页面轮询接口不变。

### 5.5 单机部署

同一台 Windows 机器可以同时运行：

```text
RabbitMQ broker（localhost:5673）
API JVM（:18210，role=api）
Worker JVM（:18211，role=worker）
MySQL / Redis
```

也可以 `role=all` 让一个 JVM 同时是 API 和 Worker，但消息仍经过 Broker。启动脚本和参数见 [实现指南第 12 节](project-implementation-guide.md#12-rabbitmq-关键细节)。

## 6. 场景六：用户取消

### 6.1 请求

```text
任务详情点“取消”
  → POST /api/pre-defense/cancel/42
  → 校验 owner
  → UPDATE ... SET status='canceled' WHERE status='running' AND current_run_id='r1'
```

### 6.2 两种模式的通知

- `db`：事务提交后 `TaskCancellationDispatcher` 通知本机执行器；
- `rabbit`：Worker 的 `WorkerCancellationMonitor` 扫描取消状态，终止对应进程。

### 6.3 为什么不能立即释放 GPU

取消只改变业务状态，Python 进程可能还在退出。执行器必须确认进程树结束，或确认进程从未启动，才释放租约。无法确认时隔离槽位，避免下一个任务和旧进程争 GPU。

### 6.4 旧结果晚到

```sql
UPDATE sys_pre_defense
SET cleaning_result=?, status='success'
WHERE task_id=42 AND current_run_id='r1' AND status='running'
```

取消后 `status='canceled'`，SQL 影响 0 行；旧结果不会把任务改回成功。

## 7. 场景七：失败与重试

### 7.1 失败来源

| 来源 | 示例 |
| --- | --- |
| 参数/资源预检 | 数据集未验证、参数越界 |
| 派发 | 线程池拒绝、执行器未配置 |
| Python | 非零退出、结果缺失、超时 |
| 结果校验 | `run_id` 不匹配、缺少 `artifacts` |
| Worker | 重试耗尽、消息不可路由 |

### 7.2 重试

```text
用户点“重试”
  → POST /api/pre-defense/retry/42
  → 同一 taskId
  → 新 runId=r2，attempt=2
  → 新快照追加
  → 新意图/Outbox
```

旧 `r1` 的目录和快照保留，便于比较两轮差异。

### 7.3 重试不是“重置”

- 任务创建时间不变；
- 历史失败结果不被覆盖；
- 新执行重新做资源/配置校验；
- `execution_snapshot` 数组保留两轮。

## 8. 场景八：服务重启与恢复

### 8.1 启动恢复做什么

`DispatchRecoveryCoordinator` 在接单前扫描：

- `running` 任务 + 未完成意图；
- accepted 标记；
- 进程身份/attempt 目录；
- 快照指纹；
- 租约状态。

可能结论：

| 证据 | 动作 |
| --- | --- |
| 意图 pending，进程从未启动 | 可安全补派发 |
| 意图 submitting，无 accepted | 视证据收敛或隔离 |
| accepted 且进程存活 | 保持，不重复启动 |
| 结果已落库但状态未收敛 | 按结果修复终态 |
| 资源指纹变化 | 拒绝重放，明确失败 |

### 8.2 Rabbit 模式的额外启动门

`WorkerConsumerLifecycle`：

```text
Worker 启动
  → 取得 Worker ownership
  → 运行恢复
  → 打开 scheduling readiness
  → 启动消费者
```

数据库断连后，不能只重连就开始消费；必须重新取得 ownership、重跑恢复、确认没有本地存活进程后才开消费。

## 9. 场景九：清洗子集导出

### 9.1 前提

- 父任务 `success`；
- 结果是成功 LDD；
- 存在 `retained_clean_subset` 索引；
- CIFAR-10 训练批次可读；
- 用户有父任务权限。

### 9.2 提交流程

```text
报告页选择 isolationRatio
  → POST /api/pre-defense/cleaned-dataset/exports/42
  → 锁父任务
  → 查同 taskId + ratio 的已有导出
  → 复用或新建 exportId(pending)
  → 异步 executor
  → Python 按索引抽样本打 ZIP
```

### 9.3 查询与下载

```text
GET /api/pre-defense/cleaned-dataset/exports/{exportId}
GET /api/pre-defense/cleaned-dataset/exports/{exportId}/download
```

下载不是“重新跑 LDD”，而是读已经生成的 ZIP。ZIP 丢失返回 `410`，可重建；父任务状态不受影响。

## 10. 场景十：实验助手预览与确认

实验助手不是直接执行工具。写操作固定两步：

```text
用户在助手输入“用 CIFAR-10 建一个 1 epoch 的 LDD”
  → POST /api/assistant/chat
  → 模型/工具生成草案
  → 服务器生成 preview + confirmationId + token
  → 页面展示参数、资源和影响
  → 用户点确认
  → POST /api/assistant/confirmations/confirm
  → 服务重新校验权限、资源、参数、版本
  → 调用与手动操作相同的创建/启动服务
```

助手不能绕过：

- 管理员资源限制；
- 数据集验证状态；
- 参数范围；
- 任务归属；
- 确认令牌有效期和一次性。

模型不可用时，助手仍可基于规则和本系统数据回答；这属于降级，不是“假回答”。

## 11. 场景十一：数据集页面“读取失败”的真实排查

用户看到：

```text
数据集读取失败，请检查服务连接后重试。
```

这通常不是某条数据路径错误，而是**列表接口整体失败**：

| 原因 | 页面/网络表现 | 处理 |
| --- | --- | --- |
| 后端未启动 | 前端代理返回 502/503 | 启动 8102 后端 |
| JWT 过期/密钥变化 | 401，页面跳登录 | 重新登录 |
| MySQL 不可用 | 500，后端日志有连接异常 | 恢复数据库 |
| 单条数据集解析失败 | 列表仍能打开，该行 `failed` | 看 `parseMessage` |

本次真实排查就是第一种：前端静态服务仍在，代理 `/api` 到 8102 时后端已经退出，页面把 502 显示成“数据集读取失败”。重启后端后列表恢复；四条真实路径再逐条验证，均得到 `success`。

排查顺序：

```text
页面是否能打开
  → Network 看 /api/dataset 状态码
  → 502/503：查后端端口和进程
  → 401：查登录会话
  → 200 但某行 failed：查该行 parseMessage 和路径
```

## 12. 一页串起全链路

```text
[浏览器]
  选数据集/算法/参数
      ↓ POST /api/pre-defense
[API]
  校验身份、资源、参数
      ↓ INSERT
[MySQL] pending
      ↓ 用户确认 POST /start
[API 事务]
  FOR UPDATE → runId/attempt → 快照 → running → 意图 → (Outbox/租约)
      ↓ COMMIT
[db] afterCommit → 线程池
[rabbit] Relay → RabbitMQ → Worker
      ↓
[Java 执行器]
  request.json → ProcessBuilder
      ↓
[Python 适配器]
  解析 → 生成脚本 → VaB 子进程
      ↓
[文件]
  progress.json / result.json / 日志 / 产物
      ↓
[Java]
  校验身份和结果 → 条件 UPDATE
      ↓
[MySQL] success/failed/canceled
      ↓
[页面]
  轮询详情 → 报告 → 日志 → 按需导出 ZIP
```

记住三句话：

1. **创建只是建档，启动才分配 runId。**
2. **数据库事务先提交，异步执行/消息后发生。**
3. **任务事实在 MySQL，消息和缓存都不是事实源。**
