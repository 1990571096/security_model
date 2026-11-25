# R4 契约与实现：单机 API + 独立 Worker + Transactional Outbox + RabbitMQ

## 当前派发契约

### 事务与发布

`db/api` 为默认模式。`rabbit` 以一套 MySQL 上的独立 `api` 和 `worker` 进程运行。启动任务的事务一并写入任务、`pending` 意图和 Outbox。`TaskOutboxRelay` 领取 Outbox，发布事件并依据 Broker 确认及返回结果更新发送状态。[TaskOutboxRelay](../../migration-backend/src/main/java/com/securitymodel/application/task/TaskOutboxRelay.java)

### 消息身份

业务消息使用 `task.dispatch` v1，携带 `messageId`、`taskId`、`runId`、`attempt`、`ownerUserId`、`intentId` 和 `requestHash` 等标识。执行参数保存在 MySQL 派发意图中。收到消息后 Worker 以数据库中的任务与请求为准执行。[DispatchEvent](../../migration-backend/src/main/java/com/securitymodel/application/task/DispatchEvent.java)

### 领取与消费确认

Worker 利用消息消费账本与条件更新检查重复投递、过期轮次和所有者。`DispatchClaimTransactionService` 将意图领取与 GPU 租约申请放在一个短事务中。运行与结果落库完成后按账本处理 ACK。可重试的暂态故障通过延迟消息处理，交接确认后再 ACK 原消息。耗尽重试时先收敛对应业务状态，再处理死信。[DispatchMessageHandler](../../migration-backend/src/main/java/com/securitymodel/application/task/DispatchMessageHandler.java)

### 切换模式

切换模式前停止旧模式进程，核对运行中任务、租约及未归结派发。读同一个任务的结果时，两种模式使用同样的报告和日志接口。[单机运行说明](../../README.md) · [可选组件课程](../learning/05-extensions-and-tests.md)

## 历史记录

<!-- knowledge:historical -->
> **当前交付事实唯一入口**：[26-current-delivery-facts.md](26-current-delivery-facts.md)。
> 本文保留 2026-11-16 的设计、实现和验收证据；“部署验收未执行”是当时口径，后续单机 db/rabbit 启停演练见 [24](24-stage3-single-node-deployment.md)。生产级多机部署仍未验收。

状态：**R4 实现验收通过（隔离环境）；单机部署与生命周期演练通过；生产级多机部署未验收**。四项阻断整改已完成：恢复返回 `RecoveryResult`（未安全分类/本地 accepted 未清零/GPU 隔离槽位不空时 gate 不开放）、执行器 accepted 在进程处置与槽位决策后清零、历史-only 文件退役 active 知识并保留源行号、重复投递只 ACK 不提前终结原账本。真实 RabbitMQ 4.1.8 + Erlang 27.3.4.18、隔离 schema/vhost、双 JVM 已实测 R4-01（含事务回滚）、R4-02（重复幂等/不可路由/旧 claim token/真实 confirm-return/7s 延迟 confirm）、R4-03、R4-04（GPU_BUSY/毒消息/DB 中断/无重启自动恢复消费）、R4-05（运行中取消+完成后取消）、R4-06（启动拒绝/drain 拒绝/v2 旧队列隔离/db→rabbit→db 完整双向停机切换）、R4-07、R4-08（Broker 故障自然 dead 后重放+权限+replay/cancel 真实并发+旧 dead 与新 attempt 真实竞争）。剩余边界仅 R4-04 长期 DB 故障/多任务退避压力与跨主机。提交 `ee10704`/`cd5a6ae`，run `43846823071` backend/python/frontend 全绿；该次记录的 287 单测 + 106 IT 是历史快照，当前门禁以 26 为准。默认 `dispatch.mode=db`，不启用 RabbitMQ/Outbox/Worker，不迁移业务库**
日期：2026-11-16
事实源：本文件 §16 验收表与 [26-current-delivery-facts.md](26-current-delivery-facts.md) 为当前入口；`20-stage-execution-and-acceptance.md` §6.8、`09-project-status.md` §5.17 是历史设计/状态记录。其他章节或旧文档中的“RabbitMQ 未安装 / R4-01..08 未执行 / 252+100 为最新”均为历史记录。
范围：一台机器上的 API 进程 + 一个独立 Worker 进程；复用现有 R3 调度意图、GPU 单槽租约、执行器协议、终态条件更新和恢复组件。

## 1. 目标与非目标

**目标**

1. 把“业务事务提交”和“跨进程派发”解耦：API 只在业务事务内写 Outbox，不在进程内直接启动 Python。
2. Worker 从 RabbitMQ 消费派发事件，执行现有短协议/真实 LDD 适配器；消息至少一次投递，业务侧幂等，不承诺 exactly-once。
3. API 崩溃、Broker 重启、publisher confirm 丢失、重复投递、Worker 崩溃、取消与迟到结果、db/rabbit 模式切换均有确定行为。
4. 不引入第二个事实源：MySQL 仍是任务、意图、租约、终态和权限的唯一权威；RabbitMQ 只承载“请处理这个已提交的意图”的通知。

**非目标**

- 不做多机、Kubernetes、对象存储、Kafka；不做 exactly-once。
- 不重跑真实 LDD 128/5，不接真实 GPU，不迁移生产库 `eladmin`。
- 不把 Outbox/MQ 设计写成已实现；未完成真实 RabbitMQ + MySQL + 短 Python 桩验收前，R4 不标记完成。

## 2. 设计起点（2026-11-16 历史）

- 当前 API 在任务事务提交后通过 `AfterCommitTaskDispatcher` / `@Async("taskAsync")` 在 API 进程内派发 Python；没有独立 Worker，没有 MQ。
- R3 已有 `pending → submitting → submitted` 意图、条件抢占、租约、`DispatchRecoveryCoordinator`、`AttemptProcessRegistry`、`PythonLddTaskExecutor` 和终态条件更新。
- R4 不重写这些状态机，只增加“事务 Outbox → 发布 → 消费”的跨进程传输层。**任何恢复器都不得绕过数据库状态直接重复执行。**

## 3. 组件职责

| 组件 | 职责 | 不负责 |
|---|---|---|
| API 命令服务 | 在同一个 MySQL 事务内写任务/租约/调度意图/Outbox；提交后不再直接启动 Python | 不消费消息、不执行算法 |
| Outbox Relay | 条件领取待发布 Outbox 行，发布到 RabbitMQ，处理 confirm/return/nack，写回 `published/failed/dead` | 不执行任务、不改任务终态 |
| RabbitMQ | durable exchange/queue、持久消息、publisher confirm、manual ACK、DLQ | 不作为任务状态事实源 |
| 独立 Worker | 消费消息，校验 run/attempt，复用协调器抢占意图、领取租约、启动执行器、落终态，按规则 ACK | 不创建新 attempt、不改业务授权 |
| 恢复协调器 | 只处理数据库中 `pending/submitting` 的意图；rabbit 模式下只补写/重发 Outbox，不直接派发 | 不消费 MQ、不重复执行已 submitted 的意图 |
| 进程登记/租约 | 维持 R3 语义：未确认退出不释放槽位；KILL_FAILED/UNKNOWN 隔离 | 不把“消息已 ACK”当作进程已退出 |

## 4. 事件契约 v1

事件只携带定位与幂等信息，不携带路径、命令、凭据、参数快照或用户隐私。

```json
{
  "schemaVersion": 1,
  "messageId": "outbox-<id>",
  "eventType": "task.dispatch.requested",
  "taskId": 42,
  "runId": "run-...",
  "attempt": 3,
  "ownerUserId": 7,
  "intentId": 99,
  "requestHash": "sha256:...",
  "occurredAt": "2026-11-16T..."
}
```

规则：

- `messageId` 由 Outbox 行生成，全局唯一；重复发布使用同一 `messageId`。
- Worker 必须回查 MySQL 的任务/意图/租约/参数快照；消息中的 `requestHash` 只用于快速比对，不作为执行依据。
- `runId + attempt` 必须与数据库当前值一致；不一致的消息视为迟到/旧消息，只 ACK，不执行。
- 事件不可变；重试不修改业务字段，只更新投递元数据。

## 5. Transactional Outbox

新增 `sys_dispatch_outbox`（名称以最终迁移脚本为准）：

| 字段 | 说明 |
|---|---|
| `id` | 主键；`message_id = outbox-<id>` |
| `event_type` | v1 固定 `task.dispatch.requested` |
| `task_id/run_id/attempt/intent_id` | 业务定位；与意图表一致 |
| `payload_hash` | 事件体哈希，防篡改/快速比对 |
| `status` | `new / publishing / published / failed / dead` |
| `attempts` | 发布尝试次数 |
| `next_at` | 下次可发布时间 |
| `created_at/published_at/last_error` | 审计与排障 |
| `version` | 乐观锁/条件更新 |

约束：

1. 与任务冻结快照、调度意图在**同一事务**插入；业务回滚时 Outbox 不残留。rabbit 模式 API **不领取物理 GPU 租约**，租约由 Worker 在消费事务中领取；db 模式仍沿用 R3 的“任务/租约/意图”同事务。
2. 唯一键至少覆盖 `(task_id, run_id, attempt, event_type)`，防止同一意图重复建 Outbox；`message_id` 唯一。
3. 业务事务提交后，Outbox 行对 Relay 可见；API 不再直接 `@Async` 启动 Python（rabbit 模式）。
4. 保留策略：`published` 行按天归档/清理，`failed/dead` 行保留到人工处理完成；清理不得删除未终态任务对应的行。

## 6. 发布器与确认

Relay 可在 API 进程内作为单实例后台组件，也可独立进程；本轮设计选**API 内单实例 Relay + 数据库条件领取**，避免额外进程。若未来 API 多实例，必须改为数据库 leader 租约或独立 Relay。

发布流程：

1. 条件领取：`status in ('new','failed') and next_at <= now`，原子置 `publishing`，写入 `claim_owner`、`claim_token`、`claim_until=now+领取期限`，记录 `publish_attempts+1`；使用 `FOR UPDATE SKIP LOCKED` 或等价条件更新，保证单行只有一个发布者。
2. `publishing` 不是终态：Relay 每轮先回收 `status='publishing' and claim_until < now` 的行，条件改回 `failed`、`next_at=now`、`last_error='PUBLISH_CLAIM_EXPIRED'`，允许下一轮重新领取。旧发布者的确认必须带原 `claim_token` 条件回写；若行已被回收/重领，旧确认返回 0 行并丢弃，不能覆盖新一轮发布状态。
3. 发布持久消息，`deliveryMode=2`，`mandatory=true`，开启 publisher confirm；重复发布保持同一业务 `messageId`（Outbox 行生成一次，不随重试变化），但每次发布生成独立 `publishId`，写入消息 `correlationId`；发布使用**同一 Channel** 上的 `ReturnListener` + `confirmSelect/waitForConfirms`，`basic.return` 与 `basic.ack` 的顺序由 Broker/Channel 事件保证，不建立按业务 `messageId` 共享的 return map。
4. `basic.ack` 且该次发布未收到 `basic.return`：条件置 `published`，清空 `last_error`/claim 字段。return 监听器只对本次 `publishId` 生效；`returnSettleMs` 仅作为回调投递的短结算窗口，不替代 confirm，也不使用固定 `Thread.sleep`。
5. `basic.nack` / confirm 超时 / 连接断开：条件置 `failed`，按 `5s, 30s, 120s` 退避；`publish_attempts >= 3` 置 `dead` 并告警。
6. `basic.return`（不可路由）：Relay 立即置 `dead`，保留 `last_error=RABBIT_UNROUTABLE...`，并收敛 task `failed`、intent `abandoned`；**不进入 `published`，也不走 5/30/120 重试**。真实不可路由消息验收必须证明 Outbox 不进入 `published`。
7. confirm 丢失但 Broker 已接收：Relay 会重发同一业务 `messageId`；这是允许的重复，由 Consumer 幂等兜底。发布中的进程崩溃由领取期限回收，不依赖 JVM 退出回调。
8. 长任务使用 manual ACK，Broker `consumer_timeout` 必须大于平台任务时限；本机隔离环境配置为 2 小时，应用侧不依赖默认 30 分钟。

**不把“publisher confirm”当作业务完成**：confirm 只证明 Broker 接收，不证明 Worker 执行。

## 7. RabbitMQ 拓扑

- Exchange：`security-model.dispatch`，durable，direct/topic。
- Routing key：`task.dispatch`；取消事件可新增 `task.cancel`，但任务终态仍以 MySQL 为准。
- 主队列：`security-model.dispatch.worker`，durable，持久消息，`prefetch=1`，manual ACK。
- DLX：`security-model.dispatch.dlx`；DLQ：`security-model.dispatch.dlq`，durable。
- 单 Worker 单 active consumer；不承诺消息顺序，顺序由数据库 run/attempt 条件更新保证。
- 消息 TTL 与队列长度上限作为运维参数；超过上限进入 DLQ，不静默丢弃。

## 8. Worker 消费与幂等

消费步骤：

1. 解析事件，校验 `schemaVersion/eventType`；非法消息直接 DLQ，不执行。
2. 查任务、意图、租约、owner、资源可用性和当前 `runId/attempt`；任何不一致：ACK + 审计，不执行。rabbit 模式的请求快照**不包含物理租约**，Worker 在消费时按 `taskId/runId/attempt` 重新领取，事件里不携带 `lease_token`。
3. 消费幂等：`sys_dispatch_consumption` 以 `message_id` 唯一；已存在终态记录则 ACK；`processing` 记录由领取它的 Worker 持有 `claim_owner/claim_token/claim_until`。**`processing` 不是完成证据**：Worker 在插入 processing 后、启动 Python 前崩溃时，消息重投会看到未过期 claim；此时不得直接 ACK，必须发布同业务 `messageId` 的延迟副本（120 秒）并只在副本确认后 ACK 旧消息；claim 过期后由副本重新领取。
4. 复用 `TaskDispatchCoordinator`：**独立短事务**内完成身份检查、条件 `pending→submitting`、GPU 租约领取；事务提交后才启动 Python。任一步失败整体回滚，不能留下“submitting 但无租约/无进程”的状态。`GPU_BUSY/GPU_LEASE_BLOCKED` 时事务回滚并返回 BUSY；数据库异常不得吞成 BUSY，必须抛出并走受控延迟重试。
5. Worker 使用**真实完成信号**决定 ACK：`PythonLddTaskExecutor` 增加阻塞式 `executeAndWait`，只有 Python 进程结束、终态条件更新完成、消费记录终态写入后才 ACK。`runPreDefense` 的 `void @Async` 返回不代表完成，不能作为 Worker ACK 依据。
6. 取消使用独立巡检通道：Worker 定时扫描数据库 `canceled` 任务并调用本进程执行器终止信号，不依赖可能被训练任务阻塞的消费线程。取消先成功则任务保持 `canceled`，迟到结果被 `runId/attempt` 条件更新拒绝；完成先成功则取消返回冲突，任务保留 success/failed 终态。
7. 终态条件更新（success/failed/canceled）后，写消费终态，再 ACK。重复消息：若任务/意图已 `submitted` 或终态已持久化，ACK，不重复执行；若 `submitting` 且进程未确认退出，交给恢复协调器，不直接重跑。
8. 执行状态不确定（`submitting` 且进程证据不足、DB 写失败、ACK 前崩溃）禁止重跑旧 attempt；只能由恢复协调器确认进程退出后收敛为失败/隔离，或走既有授权重试创建新 attempt。

**执行次数不变量**：同一 `taskId + runId + attempt` 最多一次有效执行；重复消息允许重复“检查”，不允许重复启动 Python。

## 9. ACK 时机

- 只在以下条件之一后 ACK：
  1. 任务终态已持久化（success/failed/canceled），且消费记录已终态；
  2. 消息已被受控转入重试队列/DLQ，且新消息发布确认或 DLQ 落盘成功；
  3. 消息被判定为重复终态/过期/非法，且审计已写入；
  4. `processing` 未过期且未完成时，延迟副本已发布确认（旧消息可 ACK，但不得把 processing 记成终态）。
- 异常、DB 超时、租约竞争、Worker shutdown 未完成上述条件时**不 ACK**；Broker 会 redeliver。但 DB 故障必须先把消息转入 120 秒延迟队列再 ACK 旧消息，禁止 `basicNack(requeue=true)` 形成立即热循环。
- 禁止 `autoAck=true`；禁止在启动 Python 前 ACK；禁止把 `IN_PROGRESS` 当作 DUPLICATE 直接 ACK。

## 10. 重试与 DLQ

| 情况 | 行为 |
|---|---|
| Broker 连接失败/confirm 超时 | Outbox 行 `failed`，5/30/120 秒退避，最多 3 次，之后 `dead` |
| Worker 暂时无法领取租约（GPU_BUSY/BLOCKED） | 独立事务回滚 `submitting→pending`；按受控重试投递（有限次数），仍失败则 DLQ + 任务明确失败/blocked，不无限 running |
| DB 暂时不可用/事务冲突 | **不立即 requeue**：发布同一业务 `messageId` 到 120 秒延迟队列，确认后 ACK 旧消息；DB 恢复后正常领取。持续不可用不得形成热循环 |
| 重试耗尽（`retryCount >= maxRetries`） | 先收敛 task `failed`、intent `abandoned`、Outbox `dead`，再把消费记录置 `failed`，最后发布 DLQ；DB 失败则延迟重投，不能出现“DLQ 有消息、task 仍 running、管理接口查不到死信” |
| 算法 OOM/协议错误/非法结果 | 任务 `failed` + 明确错误码，ACK；**不自动重训** |
| 毒消息/schema 不支持/负数重试头 | 直接 DLQ + 审计，不执行 |
| Worker 崩溃 | 未 ACK 消息 redeliver；孤儿进程由进程登记/恢复协调器隔离，未确认退出不释放槽位 |

DLQ 重放必须区分执行状态：

- **确认未接受执行**：意图仍为 `pending`，且 `AttemptProcessRegistry`/租约证据证明没有执行进程启动；允许保留原 `taskId/runId/attempt`，生成新的 `messageId` 后重新投递，不创建新 attempt。
- **执行失败、已废弃或执行状态不确定**：不得复用旧 attempt 重放；必须走既有授权重试接口，创建新 `attempt`、新意图和新 Outbox 行，旧 attempt 保留审计。
- 重放操作只允许任务 owner 或具备现有管理员权限的用户触发，复用既有权限规则和审计字段；不默认扩大管理员写权限，不因 DLQ 重放绕过租约、owner 或 `runId/attempt` 校验。
- **当前实现**：`POST /api/observability/dispatch/dead/{messageId}/replay` 在单个事务内按顺序锁任务、锁意图、锁 Outbox；校验 `task.running + currentRunId/currentAttempt == outbox.runId/attempt + intent.pending`；仅任务 owner 或 admin 可触发；生成**新 `messageId`**，同时重算 `payload_hash`，保留原 `taskId/runId/attempt`；审计记录旧 `previousMessageId` 与新 `messageId`。failed/uncertain 返回 `DLQ_REPLAY_REQUIRES_NEW_ATTEMPT`，必须走既有任务重试创建新 attempt。2026-11-16 已用真实 HTTP 并发调用 replay 与 cancel：replay 先提交、cancel 随后成功，任务终态 `canceled`、consumption `terminal`、Python 0 次；重放与新 attempt 的真实并发竞争仍待补。

## 11. 取消与迟到结果

- 取消仍走现有 `TaskCommandService.cancel`：条件更新任务状态、写取消意图/审计；Rabbit 模式额外写 `task.cancel` 事件，但**取消是否生效以 MySQL 条件更新为准**。
- 取消在消费前：Worker 查到任务已 canceled，ACK，不启动 Python。
- 取消在执行中：独立取消巡检/Worker 杀进程树；任务已经是 `canceled`；未确认退出则保持隔离，不释放槽位。
- 取消后迟到结果：`runId/attempt` 条件更新拒绝旧结果，不能把 canceled 改回 success。
- **完成先于取消**：任务已是 success/failed 时，取消条件更新失败并返回冲突（`TASK_ALREADY_TERMINAL` 或既有等价错误码），任务保留原终态；不允许把已完成任务改写成 canceled。
- 消息重复/乱序不作为取消依据；只认数据库当前状态。

## 12. 孤儿进程与恢复

复用 R3：

- `AttemptProcessRegistry` + `process.json` + `python-children.json` 识别真实进程；
- Worker 崩溃后，新 Worker/恢复协调器先确认进程退出，再释放租约；
- `KILL_FAILED/UNKNOWN` -> blocked/quarantined，人工处理；不重新分配槽位；
- 消息 redeliver 不等于进程已退出，禁止“收到重复消息就重跑”。

**角色化恢复责任**：

| 角色 | 启动恢复 | pending 扫描 | submitting 扫描 | 执行归属 |
|---|---|---|---|---|
| db / api | 沿用 R3 全量恢复 | 直接 `replayPending` | 本 JVM `isLocallyAccepted` 保护 | API |
| rabbit / api | **不扫描、不判 running**，只标记就绪 | 不直接派发 | 不扫描 | 无 |
| rabbit / worker | **先取得 MySQL Worker 归属锁，再恢复；恢复完成且本地已接受执行清零后才启动消费容器** | 只补写/重发 Outbox，不启动 Python | 以 Worker 进程登记和持久执行归属判断 | Worker |
| rabbit / all | 同上，但仅测试/单进程演练 | 同上 | 同上 | Worker |

**单机归属锁与自动重连**：Worker 用专用 MySQL 连接执行 `GET_LOCK(security_model_r4_worker_owner, 0)` 并保持连接；第二个 Worker 取得失败则**不运行恢复、也不启动消费容器**。连接断开即锁释放；心跳检测到锁丢失时先停止消费并关闭调度门，**不在此直接恢复消费**。后续心跳按“重新 `acquire()` → `recoverAtStartup()` → `startAfterRecovery()`”顺序自动重开：`acquire()` 失败继续保持关闭；恢复抛错保持关闭；只有重取归属、恢复扫描完成、且 `AttemptProcessRegistry.localAcceptedCount()==0` 时才 `container.start()`。本地仍有已接受执行时每轮心跳复查，直到执行器清除 accepted 标记后才开消费，避免 DB 重连后旧执行与新投递并行。API 的运维接口通过 `IS_USED_LOCK` 读取共享 Worker 占用，不再用 API 本地 `localAcceptedCount` 代表独立 Worker。

心跳超时只说明“当前没有心跳”，不能证明进程已退出；释放槽位必须依赖进程退出证据或 `KILL_FAILED/UNKNOWN` 隔离。API 重启不得把仍在 Worker 执行的任务判失败。

## 13. db/rabbit 模式互斥与切换

配置：`security-model.dispatch.mode=db|rabbit`，`security-model.dispatch.role=api|worker|all`；启动时校验角色组合。

| 模式/角色 | 派发路径 | 恢复路径 | 执行归属 |
|---|---|---|---|
| `db/api` | 提交后 `AfterCommitTaskDispatcher` + API 内 `@Async` | 扫描 pending 直接重放；沿用 R3 | API |
| `db/worker` | **非法组合，拒绝启动** | - | - |
| `rabbit/api` | 只写 Outbox；不领取 GPU；不直接派发 | 不扫描 running；只标记就绪 | 无 |
| `rabbit/worker` | Relay 发布；Worker 消费 | 先取得单机 Worker 锁，恢复后启动消费；扫描 submitting 收敛孤儿 | Worker（DB 锁唯一） |
| `rabbit/all` | 仅测试/单进程演练 | 同上 | Worker（DB 锁唯一） |

互斥规则：

1. 启动时若发现另一种模式仍有活跃派发器/消费者/未清空队列/未完成 Outbox，拒绝启动或拒绝切换。
2. 同一时刻只有一个派发路径写 `submitting`；数据库条件更新仍是最终仲裁。
3. 切换必须停机 drain：
   - 停 API 写命令与直接派发；
   - 等 Outbox 全部 `published` 或人工 `dead`；
   - 等 Worker 空闲、队列无 unacked、无 running 任务；
   - 切配置并重启；记录模式、实例、时间、操作者。
4. 回退 `rabbit → db` 前必须停止 Consumer、确认无 unacked/活跃 Worker、把未发布 Outbox 标记为待人工处理；否则拒绝热切换。
5. 不允许两个恢复器同时直接派发：`rabbit` 模式的恢复器只能写 Outbox；`db` 模式不消费 MQ。

## 14. 权限、租约与终态约束

- 复用 `TaskAccessGuard`/owner 校验；Worker 使用最小权限服务账号，不信任消息里的 owner。
- 复用 GPU 单槽租约和 `lease_token`；消息不带租约 token，Worker 必须重新领取。
- 任务终态只用条件更新；任何模式都不能覆盖新 attempt 或已终态。
- 消息不携带凭据、绝对路径、完整参数快照；Worker 从数据库白名单字段读取。
- 审计记录 `messageId/taskId/runId/attempt/mode/resultCode`，不记录消息正文。

## 15. 故障矩阵

| 故障点 | 预期 |
|---|---|
| 业务事务提交前 API 崩溃 | 任务/意图/Outbox 均不存在；无消息、无执行 |
| 提交后、发布前 API 崩溃 | Outbox `new`；重启 Relay 补发；最终一次有效执行 |
| 发布后、confirm 前崩溃 | 消息可能已到 Broker；Outbox 仍 `publishing/failed`；重启重发同一 `messageId`；Consumer 幂等 |
| 重复投递/confirm 丢失 | 同一 `messageId` 多次到达；消费记录唯一；只一次有效执行 |
| Worker 启动 Python 前崩溃 | 消息 redeliver；意图仍 pending/submitting；恢复后按 R3 规则处理 |
| Worker 启动 Python 后、终态前崩溃 | 孤儿进程隔离；未确认退出不释放槽位；不重复执行 |
| 取消与完成竞争 | 条件更新只有一个终态；迟到结果被 run/attempt 拒绝 |
| 不可路由消息 | `basic.return` + `mandatory` -> `dead`/DLQ；不无限重发 |
| 模式切换时仍有活跃任务 | 拒绝切换；drain 后重试 |

## 16. 验收计划（R4-01..R4-08）

环境：真实 MySQL 8 + 真实 RabbitMQ 3.x/4.x + 短 Python 协议桩；**不运行 LDD、不占 GPU**。每个用例使用隔离 schema/独立 vhost/唯一队列，结束后清理。记录：commit SHA、tree、CI run ID、数据库行、消息计数、Python 执行次数。以下“必须”项在缺少 RabbitMQ 时只能登记为阻塞，不能以 Mock 替代。

| ID | 场景 | 预期数据库状态 | 预期执行次数 |
|---|---|---|---|
| R4-01 | 事务回滚；提交后停 Broker，再恢复 | 回滚无任务/意图/Outbox；恢复后 Outbox `published`；任务终态明确 | 回滚 0 次；正常 1 次 |
| R4-02 | confirm 丢失/同一消息重复投递；迟到确认 | 同一 `messageId` 重复投递；`sys_dispatch_consumption` 1 条终态；旧确认不覆盖新 `claim_token` | 有效执行 1 次 |
| R4-03 | Worker 启动 Python 后崩溃 | 任务不进入新 attempt；租约 blocked/隔离；孤儿进程被杀并确认退出；Outbox published | 1 次，无重跑 |
| R4-04 | GPU_BUSY、数据库暂时故障、毒消息 | GPU_BUSY 回滚 `submitting→pending` 且任务仍可重试；DB 故障不 ACK；毒消息进 DLQ；不无限 running | 0 次或受控 1 次 |
| R4-05 | 取消在消费前/执行中/完成后到达 | 消费前/执行中 -> `canceled`，租约只在确认退出后释放；**完成后取消 -> 保留 success/failed，取消返回冲突** | 0 次或 1 次被终止；完成后 1 次且不改写 |
| R4-06 | db→rabbit、rabbit→db 切换；旧消息到达 | 活跃任务/队列未清空时拒绝；drain 后切换；旧 vhost/队列消息被隔离或拒收；无双派发 | 每次任务最多 1 次有效执行 |
| R4-07 | API 重启时 Worker 正常执行 | API 不扫描 running、不释放 Worker 租约；Worker 终态正常；误判失败为 0 | 1 次 |
| R4-08 | 权限隔离与人工重试 | 非 owner 不能重放；pending 可同 attempt 续传；failed/uncertain 创建新 attempt；审计完整 | 按授权路径，最多 1 次/attempt |

验收断言必须包含：任务终态、意图状态、租约 token/隔离状态、进程是否退出、Outbox 状态与 `claim_token`、消费记录、消息 ACK/DLQ、Python 执行计数。单元 Mock 不能作为 R4 阶段完成证据。

**本轮验收对照（2026-11-16 四项阻断修复后；本机 287 单测 + 106 IT 全绿 `tmp/stage2-full-verify-new.log`；真实 RabbitMQ 4.1.8 + 隔离 MySQL + 双 JVM + 短 Python 桩；提交 `ee10704`/`cd5a6ae`，run `43846823071` 全绿）**：

| 验收 ID | 测试方法 | 关键证据 | 结果 | 未覆盖边界 |
|---|---|---|---|---|
| R4-01 | 真实 Broker + MySQL + API/Worker；正常派发；停 Broker 后创建任务再恢复；真实 MySQL 事务回滚 | 任务 `2122209791151980545` / `2122210212130078722`；`MySqlTaskPersistenceIT#rabbitDispatchRollbackLeavesNoTaskIntentOrOutbox`；`tmp/r4-rollback-it1.log` | 通过（正常链路 + 回滚零残留） | 跨主机未执行 |
| R4-02 | 管理 API 重发同一 payload；关闭 RabbitAdmin 自动声明并删除 worker queue 后发布；真实 Broker confirm/return IT；旧 claim token 条件发布 IT；AMQP 代理延迟 `Basic.Ack` 7s（应用 confirm 超时 5s） | Python 次数不增；任务 `2122214846991208449` Outbox dead、未 published；`RabbitDispatchBrokerIT` 2/2；`expiredPublishingIsReclaimedAndOldConfirmCannotWin`；任务 `2122269157720920066` 连续 3 次 `RABBIT_PUBLISH_FAILED` 后 dead、task 保持 running，`tmp/r4-confirm-proxy.log` 记 delayed 18:02:52→released 18:02:59 | 通过（幂等 + 不可路由 + 旧 token 不覆盖新状态 + 真实延迟 confirm 不误判成功） | 跨主机/生产网络分区未执行；代理为本地真实 AMQP 帧注入，不改应用与 DB |
| R4-03 | sleep 300 秒任务启动后强杀 Worker，重启恢复 | 任务 `2122215986243866625`；孤儿 PID 4736 被杀；无新 attempt；`tmp/r4-stack/taskid-orphan.txt` | 通过 | 跨主机未执行 |
| R4-04 | 人工占用 GPU + 三次退避耗尽；发布 `not-json` 毒消息；worker 通过 TCP 代理连 MySQL 后切断代理；恢复代理但不重启 Worker | 任务 `2122216323369439234`；DLQ 1→2；DB 中断任务 `2122228571957653506`：消息留在 worker 队列、未 ACK，恢复代理并重启 worker 后 success、consumption terminal、Python 1 次；补充任务 `2122257618284634114`：`17:17:18` 停止消费，`17:17:25` 恢复代理、`17:17:28` 自动重取归属并重跑恢复，本地 accepted=1 时连续四次拒绝开消费，`17:17:46` accepted 清零后 `17:17:48` 重跑一次恢复并启动容器，任务 success、intent submitted、Outbox published 1、consumption `TASK_SUCCESS`、Python 1 次；`tmp/r4-db-auto-recover3-worker.txt`、`tmp/r4-db-auto-recover3-db.txt`、`tmp/r4-db-outage-db.txt` | 通过（DB 中断不误 ACK + 无重启自动恢复 + 旧执行未结束前不开放消费） | 长期 DB 故障、多任务退避、跨主机未压测 |
| R4-05 | 真实任务启动 2 秒后取消；任务 success 后再取消 | 任务 `2122210811173199873` canceled、Python 1 次；任务 `2122227016860782594` 完成先返回 `TASK_COMMAND_INVALID`，终态保持 success；`tmp/r4-cancel-after-success.json` | 通过 | 无 |
| R4-06 | `mode=db role=worker` 启动拒绝；30 秒 sleep 任务运行中执行 drain 脚本；v2 队列启动后向旧队列投递；完整双向停机切换 | `tmp/r4-mode-reject2.log`；`tmp/r4-drain-refusal2.log`；旧队列 1 条/0 consumer、新 `.v2` 0 条/1 consumer；`db` 任务 `2122266301773209601` success → 停 API、drain → `rabbit` 任务 `2122266483373920257` success → 停 API/drain/停 Worker → 切回 `db`，任务 `2122266707249192962` success；`tmp/r4-06-drain-db.txt`、`tmp/r4-06-drain-rabbit.txt`、`tmp/r4-06-db-task.json`、`tmp/r4-06-rabbit-task.json`、`tmp/r4-06-db-back-task.json`、`tmp/r4-06-db-rows.txt` | 通过（旧队列隔离 + 停机切换拒绝 + 双向切换无丢失/无双派发） | 应用层无运行期热切换入口；跨主机切换未执行 |
| R4-07 | Worker 执行 sleep 任务时重启 API | 任务 `2122210676426948609`；Worker 不中断、任务 success、Python 1 次 | 通过 | 多 API 实例未执行 |
| R4-08 | failed 重放拒绝；test 用户重放；Broker 停止后让 Outbox 自然耗尽为 dead，重启 Broker 后重放；真实 HTTP 并发 replay + cancel；旧 dead 与新 attempt 真实竞争 | admin 400、test 403；自然 dead 重放成功；并发 replay/cancel 后终态 canceled、Python 0 次；任务 `2122268541581815809` attempt 1 `RABBIT_UNROUTABLE` dead `74532340-55a4-4114-90b2-a17c57fdcafe`、task failed，重试 attempt 2 运行中 replay 返回 HTTP 400 `DLQ_REPLAY_IDENTITY_MISMATCH`，attempt 2 success、consumption `969a2a34-08e9-4ec2-b622-f9ca1e8604a4`、旧 dead 保持、Python 仅 1 次；`tmp/r4-08-replay-response.json`、`tmp/r4-08-new-attempt-final.json`、`tmp/r4-08-new-attempt-rows.txt` | 通过（自然 dead + 成功重放 + 权限 + replay/cancel 并发 + replay/新 attempt 竞争） | 跨主机/多 Worker 并发未执行；旧 SQL 注入 dead 仅保留历史 |


### 16.1 2026-11-19 远端发布前证据复核（只校准口径，不重跑）

以下三项是 2026-11-16 R4 历史实测的精确证据，不与 2026-11-19 单机短验收混写：

- **7 秒 confirm 延迟**：AMQP 代理将 `Basic.Ack` 延迟 **7 秒**（`18:02:52` → `18:02:59`），应用 confirm 超时为 5 秒。任务 `2122269157720920066` 连续 3 次 `RABBIT_PUBLISH_FAILED` 后 Outbox 进入 `dead`，任务保持 `running`，没有把迟到确认误判为发布成功。证据：`tmp/r4-confirm-proxy.log`。
- **replay 与取消并发**：真实 HTTP 并发执行 replay 与 cancel，最终任务终态为 `canceled`，Python 执行 **0 次**；没有出现取消后又被 replay 拉起。证据：R4-08 行。
- **新 attempt 已启动后拒绝旧 replay**：任务 `2122268541581815809` 的 attempt 1 因 `RABBIT_UNROUTABLE` 进入 `dead`（dead id `74532340-55a4-4114-90b2-a17c57fdcafe`），任务 `failed`；重试启动 attempt 2 后，对旧 dead 的 replay 返回 HTTP 400 `DLQ_REPLAY_IDENTITY_MISMATCH`；attempt 2 最终 `success`，consumption `969a2a34-08e9-4ec2-b622-f9ca1e8604a4`，旧 dead 保留，Python 仅执行 1 次。证据：`tmp/r4-08-replay-response.json`、`tmp/r4-08-new-attempt-final.json`、`tmp/r4-08-new-attempt-rows.txt`。

口径提醒：本节上方 `287 单测 + 106 IT` 是 **2026-11-16 历史快照**，不是当前交付门禁。
当前 2026-11-19 门禁为 **351 单测 + 126 IT**；126 项中实际执行 116 项、跳过 10 项，
专项补跑覆盖 4 项（Redis 1 项、RabbitMQ 3 项），另有 6 项知识检索/向量测试本轮未复跑。
完整拆分见 `26-current-delivery-facts.md` §4.6，不能把 10 项跳过整体写成“已通过”。

**2026-11-16 阶段 2 追加（代码与测试已落地，真实故障矩阵仍有边界）**：

- `DispatchRecoveryCoordinator.recoverAtStartup()` 返回 `RecoveryResult(safeToConsume, scanned, replayed, converged, deferred, errors, localAccepted, quarantinedSlots, failedTasks)`；单任务异常计入 `errors` 与 `failedTasks`，扫描异常返回 `TASK_SCAN_FAILED`；只有“无单任务错误、本地 accepted=0、GPU 隔离槽位为空”才 `safeToConsume=true`，否则 `markNotReady()`。`WorkerConsumerLifecycle.startAfterRecovery(result)` 先检查该结果，再检查归属、accepted 和容器；归属仍持有与重新取得两分支都检查。
- `DispatchMessageHandler` 在读 event 后、claim 前检查 `isLocallyAccepted`，命中只 ACK 当前重复投递，不 claim、不写原账本 terminal、不启动 Python；claim 后到 process 前的窄竞态改为 `deferLocalAccepted`（`releaseForRetry` + 120s 延迟重投，不终结账本）；`SUBMITTED`/`ABANDONED` 无本地 accepted 时先 `failAndAbandon(..., DISPATCH_UNCERTAIN_RECOVERY)` 再 `terminalFromTask`，避免 JVM 崩溃后任务长期 running。
- `KnowledgeIndexService.reindexSdd()` 索引前删除 `&lt;!-- knowledge:historical --&gt;...&lt;!-- /knowledge:historical --&gt;` 块，按块内换行数替换等量换行（单行内联块替换空格），`KnowledgeSanitizer` 前导空行在 sanitize 后补回，保持引用 1-based 行号；历史-only 文件不再跳过，而是 `retireSource` 退役 active 版本并清理向量，失败留 pending 给 `reconcileRetiredVectors` 补偿；`IndexStats` 新增 `retired`。
- `PythonLddTaskExecutor` finally 改为 `ensureGone` → `finalizeAttempt` → GPU 释放/隔离决策 → `clearAccepted`；清理异常保留 accepted 并记录错误，消费不重开。
- 测试：`WorkerConsumerLifecycleTest` 8 项；`DispatchMessageHandlerTest` 12 项；`DispatchRecoveryCoordinatorTest` 7 项；`TaskRecoveryRunnerTest` 5 项；`KnowledgeIndexServiceTest` 11 项；`PythonLddTaskExecutorCleanupOrderTest` 3 项；`RabbitDispatchTransportTest` 5 项（含 confirm 超时）。`KnowledgeRetrievalMySqlIT` 8 项含历史-only 退役不可召回与向量清理补偿。定向 `tmp/stage2-fix-targeted2.log` 51/51、`tmp/stage2-fix-knowledge-it.log` 8/8。
- 真实证据：任务 `2122257618284634114` 在不重启 Worker 的前提下完成 DB 代理断链→恢复→自动重取归属→accepted 清零后重跑恢复并延迟开消费→success。**已补真实故障**：7s 延迟 confirm（`2122269157720920066`）、db→rabbit→db 双向停机切换（`2122266301773209601`/`2122266483373920257`/`2122266707249192962`）、旧 dead 与新 attempt 竞争（`2122268541581815809`）。
- 残余边界（安全取舍）：①恢复后结果对账/补写重试未实现；DB 中断期间旧执行结果写库失败、accepted 清零后二次恢复可能把 running 收敛 failed，这是“不补成功”的安全取舍。②消费账本与任务终态极窄竞态（执行清 accepted 后、handler 写 terminal 前若有重投 claim 账本）本轮未构造真实竞争证据，需全量/真实链路继续观察。③`Recovery` 只保证不重复启动 Python，不保证失败结果可恢复为成功。

全量门禁（2026-11-16 四项阻断修复后）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`MYSQL_IT_PASSWORD`、`REDIS_IT=true`、`RABBIT_IT=true`，Temurin 17.0.21）→ **287 单测 + 106 IT，0 失败/0 跳过，BUILD SUCCESS**，`tmp/stage2-full-verify-new.log`（含 `RabbitDispatchBrokerIT` 2/2、`WorkerConsumerLifecycleTest` 8/8、`DispatchMessageHandlerTest` 12/12、`MySqlTaskPersistenceIT` 28/28、`KnowledgeRetrievalMySqlIT` 8/8）。**远端 CI 全绿**：提交 `ee10704`/`cd5a6ae` 的 run `43846823071` backend/python/frontend success。CI 中 `RabbitDispatchBrokerIT` 只覆盖真实 Broker 发布、confirm/return，不代表双 JVM 全故障矩阵都已自动化。Maven 包装退出码已用 `scripts/r4/R4.Maven.ps1`（`MAVEN_TERMINATE_CMD=on`）修正为 0。



## 17. 实施顺序与回退

1. 冻结事件 v1、Outbox 表、消费记录表、配置键和模式互斥规则（本设计评审通过后）。**已完成**：`DispatchEvent` v1、`sql/security_model_task_outbox.sql`、`dispatch.mode/role` 配置键。
2. 先写迁移脚本 + 表约束 + 单测；不接 MQ。**已完成**：`TaskOutboxServiceTest` 4/4、`TaskDispatchConsumptionServiceTest` 4/4、`DispatchRuntimePolicyTest` 4/4、`DispatchMessageHandlerTest` 5/5、`TaskOutboxRelayTest` 3/3；MySQL 四项 IT `outboxClaimAndConditionalPublishAreDurable`、`expiredPublishingIsReclaimedAndOldConfirmCannotWin`、`consumptionLedgerIsIdempotent`、`deadOutboxReplayIsConditionalAndKeepsMessageId` 通过（`tmp/round5-r4-replay-it3.log`）。
3. 接真实 RabbitMQ，完成 Relay/Consumer/ACK/DLQ。**已完成**：`RabbitDispatchConfiguration`、`RabbitDispatchTransport`、`TaskOutboxRelay`、`DispatchMessageHandler`/`DispatchMessageListener`；本机 RabbitMQ 4.1.8 + Erlang 27.3.4.18 已用于 R4-01..08 真实双 JVM 验收。
4. 完成 R4-01..08 真实环境验收，记录 SHA/tree/run。**当前状态**：R4 实现验收通过（隔离），部署验收未执行；R4-02 7s 延迟 confirm、R4-06 db→rabbit→db 双向停机切换、R4-08 旧 dead 与新 attempt 竞争均已实测；R4-04 长期 DB 故障/多任务退避仍待压测；replay/cancel 并发已执行。提交 `ee10704`/`cd5a6ae`，run `43846823071` backend/python/frontend 全绿；本机 287 单测 + 106 IT 全绿（`tmp/stage2-full-verify-new.log`）。
5. 回退：`dispatch.mode=db`，停 Consumer，drain/标记 Outbox，确认无活跃 Worker；不删除已发布的审计数据。**操作顺序**：先停 `role=worker` 进程并确认无 `running`/本地接受进程 → API 切 `mode=db, role=api` 重启 → 确认 Outbox 无 `publishing` 后执行 `sql/security_model_task_outbox_rollback.sql`；有活跃任务或未确认进程时不得删表/切模式。回退不删除已发布消息的审计行；Rabbit 队列保留用于事后核对。

## 17.1 部署与配置键

- `security-model.dispatch.mode=db|rabbit`：默认 `db`；`db` 保持 R3 同事务派发。
- `security-model.dispatch.role=api|worker|all`：`db` 仅允许 `api|all`；`db/worker` 启动时拒绝。
- `security-model.dispatch.outbox.*`：`batch-size`、`claim-seconds`、`max-publish-attempts`、`retry-delays-seconds`（默认 5/30/120 秒）、`relay-interval-ms`、`confirm-timeout-ms`。
- `security-model.dispatch.worker.*`：`consumption-claim-seconds`、`max-retries`、`cancel-scan-interval-ms`、`cancel-lookback-seconds`；本地接受记录仍按 taskId/runId/attempt。
- `security-model.executor.scheduler-pool-size`：默认 4，保证 Relay、取消巡检、恢复扫描不共用单线程；取消巡检不依赖消费线程。
- `spring.rabbitmq.*`：host/port/username/password、`publisher-confirm-type=correlated`、`publisher-returns=true`、manual ack、`prefetch=1`。
- 独立 Worker 建议单独 JVM 启动同一 jar，设置 `--security-model.dispatch.mode=rabbit --security-model.dispatch.role=worker`；API 设置 `role=api`。`all` 仅用于测试/单进程演练。
- 运维查询：`GET /api/observability/dispatch`（outbox 各状态、积压、最老等待、发布失败、死信数、worker 心跳/当前执行）与 `/dead`（不含 payload），均要求现有 admin 权限。Actuator 的 Rabbit 健康指示器默认关闭（`management.health.rabbit.enabled=false`），避免默认 `db` 模式因本机无 Broker 将整体健康判为 503；Rabbit 模式的实际发布/消费健康以该运维接口和日志为准。默认 `db` 且 R4 表未迁移时，`summary` 返回 `outboxAvailable=false`/`OUTBOX_TABLE_UNAVAILABLE`，不把整个管理页打成 500；`/dead` 返回空列表。


## 18. 未决问题与未来扩展

**本项目单机部署必选范围**：单 API、单 Worker、单 GPU 槽、MySQL + RabbitMQ；不把组织级双人审批、多实例网络分区、多机高可用作为本阶段门槛。

- Relay 是否长期留在 API 进程：本轮选 API 内单实例；多实例前必须改 leader 租约或独立 Relay（未来扩展）。
- Outbox/消费记录保留天数与归档策略：本阶段先保留 `dead`/审计行，`published` 按运维参数清理。
- DLQ 人工重放：已在上文区分 pending 同 attempt 与 failed/uncertain 新 attempt；后续可补独立运维页面（未来扩展）。
- RabbitMQ 单机持久化/镜像队列：单机部署只需 durable exchange/queue + 持久消息；镜像队列/仲裁队列属于多机高可用，未来扩展。
- 组织级审批、双人复核、跨机房网络分区：未来扩展，不进入 R4 必选验收。
<!-- /knowledge:historical -->
