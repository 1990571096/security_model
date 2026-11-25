# 业务流程详解

本文不从类名开始，而从**用户点下按钮之后系统发生什么**开始。每个场景都按同一模板说明：

```text
页面动作 → HTTP 请求 → 后端事务/校验 → 异步执行 → 文件与数据库 → 页面如何看到结果
```

先读 [设计思路](project-design-overview.md) 建立整体图，再用 [实现指南](project-implementation-guide.md) 对照具体代码。

## 场景索引

| 编号 | 场景 | 主要验证点 |
| --- | --- | --- |
| 0 | 全局角色与状态 | 页面职责、`running` 的真实含义 |
| 1 | 登记并验证真实数据集 | 类型、状态、路径与运行根目录 |
| 2 | CIFAR-10 正常任务（`db`） | 创建、启动、线程池、结果回写 |
| 3 | GTSRB 任务 | HDF5 与 VaB 相对路径 |
| 4 | Tiny ImageNet | 可登记、可核验、不可执行 |
| 5 | `rabbit` 模式正常任务 | Outbox、Broker、Worker、ACK |
| 6 | 用户取消 | 状态与 GPU 回收分离 |
| 7 | 失败与重试 | 新 runId/attempt，旧快照保留 |
| 8 | 服务重启与恢复 | 意图、accepted、进程证据 |
| 9 | 清洗子集导出 | 独立导出生命周期 |
| 10 | 实验助手预览与确认 | 写操作不绕过确认 |
| 11 | 数据集整体读取失败 | 后端/登录问题与单行解析问题的区分 |
| 12 | 运行监控与消息队列核查 | 当前 `db`/`rabbit` 判定 |
| 13 | 数据统计复盘 | 图表口径、范围与边界 |

## 0. 全局角色与状态

### 0.1 系统角色

| 角色 | 看什么 | 能做什么 |
| --- | --- | --- |
| 实验者 | 数据集、算法、任务、报告、统计 | 建任务、启动、取消、重试、导出 |
| 管理员 | 资源目录、账号、监控、全量统计 | 登记/验证数据集、维护算法配置、查看全局状态 |
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

### 0.3 页面职责边界

| 页面 | 回答的问题 | 不放在这里的内容 |
| --- | --- | --- |
| 工作台 | 现在整体怎样、下一步做什么 | 深层趋势分析和统计口径 |
| 数据集资源 | 数据是否登记、是否已核验、是否启用 | 训练执行和 GPU 状态 |
| 算法资源 | 算法阶段、参数和默认配置 | 任务运行状态 |
| 安全防御任务 | 创建、启动、取消、重试、报告和日志 | 执行池、接入范围、Outbox |
| 运行监控 | 本机资源、执行池、GPU、平台接入、消息派发 | 任务的完整业务操作 |
| 数据统计 | 一段时间内的状态结构、趋势和质量 | 创建/启动等业务动作 |

任务页移除执行池和接入范围，不是“删功能”，而是把它们放到运行监控中按资源、能力、派发分类展示。这样任务页负责业务，监控页负责运行状态，统计页负责分析。


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

### 1.6 页面怎样展示类型和状态

列表页不需要把后端领域值全部展开。当前 `DatasetView.vue` 做如下映射：

```text
image_classification → image
text_classification  → text
tabular              → table
```

类型列宽度从 140 缩到 90，让训练样本、类别数、状态和操作有空间。这个映射只发生在展示层；接口里的 `datasetType` 仍然是 `image_classification` 等原值。

状态列只保留两个结论：

```text
parseStatus=success 且 sampleCount/classCount 都是整数 → 已核验
其余情况                                                 → 待验证
```

- `failed` 和“只验证了路径”都进入“待验证”，鼠标悬停仍能看到 `parseMessage`；
- `pending` 也是“待验证”；
- `success` 但统计字段缺失时仍显示“待验证”，避免出现“解析成功但没有统计”的误导；
- 停用不是第三种验证状态，改为名称旁的“已停用”标签；
- 顶部摘要分成“已核验 / 待验证 / 已启用”三项。

Tiny ImageNet 会是“已核验”，因为目录和统计校验通过；但创建任务时仍返回 `DATASET_RUNTIME_UNSUPPORTED`。页面上的“已核验”回答的是文件是否通过内容校验，不是“所有算法都能跑”。

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

也可以让一个 JVM 以 `role=all` 同时提供 API 和消费者，但消息仍经过 Broker。注意仓库的 `Start-SecurityModelStack.ps1 -Role all` 实际启动 API 和 Worker 两个 JVM；单 JVM 需要直接设置 `--security-model.dispatch.role=all`。启动脚本和参数见 [实现指南第 12 节](project-implementation-guide.md#12-rabbitmq-关键细节)。

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

## 12. 场景十二：运行监控与消息队列核查

### 12.1 页面看什么

运行监控不是任务页的复制。它分为：

```text
本机运行环境      CPU / 物理内存 / JVM 堆 / 磁盘
执行资源          执行池与运行配额 / GPU 租约
平台接入          可执行算法 / 训练前登记 / 仅目录展示
消息派发          Outbox 积压 / Worker 状态 / 消费积压
当前运行任务      当前账号 owner 范围内的 running 任务
全局观测          仅管理员，聚合层不含 taskId/userId
```

任务页移除执行池和当前接入范围后，用户要回答“为什么现在不能接单”“后台有没有接手”，应来这里看，而不是在任务列表猜。

### 12.2 当前模式怎么查

管理员请求：

```text
GET /api/observability/dispatch
```

判断顺序：

| 现象 | 含义 |
| --- | --- |
| `mode=db`、`role=api` | 任务由 API 的 `taskAsync` 线程池直接执行 |
| `mode=rabbit`、`role=worker/all` | 任务应经过 Broker 和 Worker |
| `dispatchReady=true` | 当前派发链路完成恢复，可以接单；不等于 RabbitMQ 已启用 |
| `workerConsumerExpected=false` | 当前角色不应有独立消费者，通常是 `db` 模式 |
| `outbox=0` 且 `mode=db` | 正常，`db` 不写 Outbox |
| `outboxBacklog>0` 且 `mode=rabbit` | 消息积压，检查 Relay 和 Broker |
| `consumptionProcessing>0` | 有 Worker 已领取但尚未收敛的消息 |

再核对 Broker：

```text
5672 / 5673 / 15672 没有监听
没有 rabbit / erl 进程
→ 即使安装了 RabbitMQ，也不是正在使用消息队列
```

### 12.3 2026-11-25 本地实例

| 项目 | 实测 |
| --- | --- |
| 派发 | `mode=db`、`role=api` |
| Worker | 不适用，`workerConsumerStarted=false` |
| Outbox | 各状态为 0 |
| RabbitMQ | 程序目录存在，但端口未监听、进程未启动 |
| 结论 | 当前没有启用消息队列，任务走本机线程池 |

因此“代码支持 RabbitMQ”与“当前实例启用了 RabbitMQ”必须分开表述。

### 12.4 单机切到 RabbitMQ 的验证

1. 启动本机 Broker，确认 AMQP 和管理端口。
2. 使用隔离 schema/vhost，启动 `mode=rabbit` 的 API 和 Worker；验证阶段不要直接写生产库。
3. 检查 `workerConsumerExpected=true`、`workerConsumerStarted=true`、`workerOwned=true`。
4. 创建短任务，观察 Outbox `new → publishing → published`、消费账本 `processing → terminal`、任务终态和 ACK。
5. 停 Broker，确认 Outbox 积压和重试行为，而不是把任务直接判成训练失败。
6. 停止时先 drain，再切回 `db`；有 running 任务或未清空 Outbox 时不做热切换。

仓库脚本 `-Role all` 实际启动 API 和 Worker 两个 JVM。代码允许单 JVM `role=all`，但必须直接设置 `--security-model.dispatch.role=all`；两种方式消息都经过 Broker。

## 13. 场景十三：数据统计复盘

### 13.1 页面动作

用户打开“数据统计”，选择近 7 / 14 / 30 天，看：

- 任务总数、成功率、待处理任务、报告就绪率；
- 五状态环图；
- 每日任务趋势堆叠柱；
- 任务成功率、报告就绪率、资源启用率三个质量环；
- 训练前/训练中/训练后算法数量；
- 最近任务明细；
- 统计范围、趋势窗口、报告就绪定义、未采集指标、采样时间。

### 13.2 数据从哪里来

```text
GET /api/task-progress/statistics
  → 任务总数、成功/失败/等待/运行/取消、状态分布

GET /api/dashboard/overview?days=7|14|30
  → 任务趋势、资源统计、报告就绪、最近任务、范围、采样时间
```

没有新增“统计专用”接口，也没有前端伪造数据。图表的任务数、成功数和资源数来自 MySQL 聚合；`demo` 来源任务和未归属任务不进入当前范围。

### 13.3 口径

```text
成功率       = 成功任务 / 任务总数
待处理       = 等待中 + 运行中
报告就绪率   = 有清洗结果的成功任务 / 成功任务
资源启用率   = (数据集启用率 + 算法启用率) / 2
趋势         = 按任务创建时间落到每日桶
```

“报告就绪”不是生成了 PDF；它表示成功任务已写入可追溯的清洗结果。页面没有真实采集源的 ASR、GPU 利用率、样本保留率不展示估算值。

### 13.4 和工作台的区别

| 工作台 | 数据统计 |
| --- | --- |
| 行动入口，告诉你现在能做什么 | 分析页，告诉你一段时间的结果结构 |
| 固定 7 日趋势 | 可切换 7/14/30 日 |
| 任务/资源概览、最近任务、执行池 | 状态环、质量环、阶段构成、统计口径 |
| 面向日常操作 | 面向复盘、验收和答辩 |

同一个“任务成功率”在两个页面都存在是允许的，因为它服务于不同决策；区别在于统计页给出窗口、口径、分布和明细，而不是复制一张概览卡片。

### 13.5 2026-11-25 验收样例

| 指标 | 值 |
| --- | --- |
| 任务总数 / 成功 / 失败 / 等待 / 运行 | 7 / 4 / 1 / 2 / 0 |
| 任务成功率 | 57% |
| 报告就绪 | 4 / 4 |
| 数据集 / 算法 | 11 / 14，全部启用 |
| 防御阶段 | 训练前 7 / 训练中 5 / 训练后 2 |
| 近 7 日任务 | 5 个，成功 2 个 |

这些数字只是当前数据库快照，不能写进固定产品文案；每次验收应重新采样并记录时间。

## 14. 一页串起全链路

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

记住四句话：

1. **创建只是建档，启动才分配 runId。**
2. **数据库事务先提交，异步执行/消息后发生。**
3. **任务事实在 MySQL，消息和缓存都不是事实源。**
4. **业务操作在任务页，运行状态在监控页，历史趋势在统计页。**
