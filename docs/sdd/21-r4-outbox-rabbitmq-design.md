# R4 设计：单机 API + 独立 Worker + Transactional Outbox + RabbitMQ

状态：**设计稿，未实施；不启用 RabbitMQ/Outbox/Worker，不迁移业务库**
日期：2026-11-16
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

## 2. 现状与边界

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

1. 与任务/租约/意图在**同一事务**插入；业务回滚时 Outbox 不残留。
2. 唯一键至少覆盖 `(task_id, run_id, attempt, event_type)`，防止同一意图重复建 Outbox；`message_id` 唯一。
3. 业务事务提交后，Outbox 行对 Relay 可见；API 不再直接 `@Async` 启动 Python（rabbit 模式）。
4. 保留策略：`published` 行按天归档/清理，`failed/dead` 行保留到人工处理完成；清理不得删除未终态任务对应的行。

## 6. 发布器与确认

Relay 可在 API 进程内作为单实例后台组件，也可独立进程；本轮设计选**API 内单实例 Relay + 数据库条件领取**，避免额外进程。若未来 API 多实例，必须改为数据库 leader 租约或独立 Relay。

发布流程：

1. 条件领取：`status in ('new','failed') and next_at <= now`，原子置 `publishing`，记录 `attempts+1`；使用 `FOR UPDATE SKIP LOCKED` 或等价条件更新，保证单行只有一个发布者。
2. 发布持久消息，`deliveryMode=2`，`mandatory=true`，开启 publisher confirm。
3. `basic.ack` 且未被 `basic.return`：置 `published`，清空 `last_error`。
4. `basic.nack` / confirm 超时 / 连接断开：置 `failed`，按 `5s, 30s, 120s` 退避；超过 3 次置 `dead` 并告警。
5. `basic.return`（不可路由）：无论 confirm 是否 ack，都置 `dead`/进入 DLQ 处理，不无限重发。
6. confirm 丢失但 Broker 已接收：Relay 会重发同一 `messageId`；这是允许的重复，由 Consumer 幂等兜底。

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
2. 查任务、意图、租约、owner、资源可用性和当前 `runId/attempt`；任何不一致：ACK + 审计，不执行。
3. 消费幂等：`sys_dispatch_consumption` 以 `message_id` 唯一；已存在终态记录则 ACK；`processing` 记录由同一 Worker/租约持有，崩溃后按恢复规则处理。
4. 复用 `TaskDispatchCoordinator`：条件 `pending→submitting`，释放旧租约、领取新租约，启动 `PythonLddTaskExecutor`；不新建 attempt。
5. 终态条件更新（success/failed/canceled）后，写消费终态，再 ACK。
6. 重复消息：若任务/意图已 `submitted` 或终态已持久化，ACK，不重复执行；若 `submitting` 且进程未确认退出，交给恢复协调器，不直接重跑。

**执行次数不变量**：同一 `taskId + runId + attempt` 最多一次有效执行；重复消息允许重复“检查”，不允许重复启动 Python。

## 9. ACK 时机

- 只在以下条件之一后 ACK：
  1. 任务终态已持久化（success/failed/canceled），且消费记录已终态；
  2. 消息已被受控转入重试队列/DLQ，且新消息发布确认或 DLQ 落盘成功；
  3. 消息被判定为重复/过期/非法，且审计已写入。
- 异常、DB 超时、租约竞争、Worker shutdown 未完成上述条件时**不 ACK**；Broker 会 redeliver。
- 禁止 `autoAck=true`；禁止在启动 Python 前 ACK。

## 10. 重试与 DLQ

| 情况 | 行为 |
|---|---|
| Broker 连接失败/confirm 超时 | Outbox 行 `failed`，5/30/120 秒退避，最多 3 次，之后 `dead` |
| Worker 暂时无法领取租约（GPU_BUSY/BLOCKED） | 不执行；按受控重试投递（有限次数），仍失败则 DLQ + 任务明确失败/blocked，不无限 running |
| DB 暂时不可用/事务冲突 | 不 ACK，redeliver；超过次数进 DLQ |
| 算法 OOM/协议错误/非法结果 | 任务 `failed` + 明确错误码，ACK；**不自动重训** |
| 毒消息/schema 不支持 | 直接 DLQ + 审计，不执行 |
| Worker 崩溃 | 未 ACK 消息 redeliver；孤儿进程由进程登记/恢复协调器隔离，未确认退出不释放槽位 |

DLQ 重放：只允许 owner/admin 触发，保留原 `taskId/runId/attempt`，生成新的 `messageId` 并写审计；不得借重放创建新 attempt 或绕过租约。

## 11. 取消与迟到结果

- 取消仍走现有 `TaskCommandService.cancel`：条件更新任务状态、写取消意图/审计；Rabbit 模式额外写 `task.cancel` 事件，但**取消是否生效以 MySQL 条件更新为准**。
- 取消在消费前：Worker 查到任务已 canceled，ACK，不启动 Python。
- 取消在执行中：Worker/取消调度器杀进程树；终态 `canceled`；未确认退出则保持隔离，不释放槽位。
- 取消后迟到结果：`runId/attempt` 条件更新拒绝旧结果，不能把 canceled 改回 success。
- 消息重复/乱序不作为取消依据；只认数据库当前状态。

## 12. 孤儿进程与恢复

复用 R3：

- `AttemptProcessRegistry` + `process.json` + `python-children.json` 识别真实进程；
- Worker 崩溃后，新 Worker/恢复协调器先确认进程退出，再释放租约；
- `KILL_FAILED/UNKNOWN` -> blocked/quarantined，人工处理；不重新分配槽位；
- 消息 redeliver 不等于进程已退出，禁止“收到重复消息就重跑”。

## 13. db/rabbit 模式互斥与切换

配置：`security-model.dispatch.mode=db|rabbit`，启动时校验。

| 模式 | 派发路径 | 恢复路径 |
|---|---|---|
| `db` | `AfterCommitTaskDispatcher` + API 内 `@Async` | 扫描 pending 意图并直接重放；沿用 R3 |
| `rabbit` | 只写 Outbox；Relay 发布；Worker 消费 | 扫描 pending 意图只补写/重发 Outbox；Worker 执行；禁止 API 直接派发 |

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

## 16. 验收计划（R4-01..R4-06）

环境：真实 MySQL 8 + 真实 RabbitMQ 3.x/4.x + 短 Python 协议桩；**不运行 LDD、不占 GPU**。每个用例使用隔离 schema/独立 vhost/唯一队列，结束后清理。记录：commit SHA、tree、CI run ID、数据库行、消息计数、Python 执行次数。

| ID | 场景 | 预期数据库状态 | 预期执行次数 |
|---|---|---|---|
| R4-01 | 提交后停 Broker，再恢复 | Outbox 先 `new/failed`，恢复后 `published`；任务最终 success/failed 明确 | Python 启动 1 次 |
| R4-02 | confirm 丢失/同一消息重复投递 | 同一 `messageId` 两条投递；`sys_dispatch_consumption` 1 条终态；意图 `submitted` 1 条 | 有效执行 1 次 |
| R4-03 | Worker 启动 Python 后崩溃 | 任务不进入新 attempt；租约 blocked/隔离；孤儿进程被杀并确认退出；Outbox published | Python 启动 1 次，无重跑 |
| R4-04 | 临时租约冲突/DB 故障超过重试 | Outbox/DLQ 有记录；任务明确 failed/blocked；不无限 running | 0 次或受控 1 次，重试不重复执行 |
| R4-05 | 取消在消费前/执行中/完成后到达 | 终态 `canceled`；迟到 success 被拒绝；租约释放只在确认退出后 | 0 次或 1 次被终止 |
| R4-06 | db→rabbit、rabbit→db 切换 | 活跃任务/队列未清空时拒绝；drain 后切换；无双派发；Outbox/消息可追溯 | 每次任务最多 1 次有效执行 |

验收断言必须包含：任务终态、意图状态、租约 token、进程是否退出、Outbox 状态、消费记录、消息 ACK/DLQ、Python 执行计数。单元 Mock 不能作为 R4 阶段完成证据。

## 17. 实施顺序与回退

1. 冻结事件 v1、Outbox 表、消费记录表、配置键和模式互斥规则（本设计评审通过后）。
2. 先写迁移脚本 + 表约束 + 单测；不接 MQ。
3. 接真实 RabbitMQ，完成 Relay/Consumer/ACK/DLQ。
4. 完成 R4-01..06 真实环境验收，记录 SHA/tree/run。
5. 回退：`dispatch.mode=db`，停 Consumer，drain/标记 Outbox，确认无活跃 Worker；不删除已发布的审计数据。

## 18. 未决问题

- Relay 是否长期留在 API 进程：本轮选 API 内单实例；多实例前必须改 leader 租约或独立 Relay。
- Outbox/消费记录保留天数与归档策略。
- DLQ 人工重放是否允许创建新 `messageId` 并保留原 attempt；本设计暂定允许，但需审计与权限。
- RabbitMQ 单机持久化/镜像队列的运维参数，待实际部署环境确定。
