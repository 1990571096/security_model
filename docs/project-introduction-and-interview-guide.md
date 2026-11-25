# 项目讲解与演示

## 30 秒版本

> 这是一个面向深度神经网络后门防御实验的单机平台。我接入了训练前 LDD：用户选择数据集和算法、创建任务、确认启动，Java 调度 Python/VaB 执行数据清洗，页面查看进度、日志、报告，并按需导出清洗子集。工程上重点讲三件事：Java 与 Python 的文件协议、长任务在失败/取消/重启下的状态一致性，以及 `db` 与 `rabbit` 两种派发模式怎样共享同一套任务事实。

如果面试官只给 30 秒，最后一句可以换成“我还做过 Redis 缓存失效和 RabbitMQ 重复消费的收敛”。

## 90 秒口述

> 平台默认是单机 `db` 模式。用户在页面选好数据集、LDD 和参数，创建接口只写一条 `pending` 任务，不启动训练。用户核对后点启动，后端用 `SELECT FOR UPDATE` 锁任务行，检查归属和状态，分配 `runId` 和 `attempt`，写执行快照、派发意图，并在事务内把任务改成 `running`。事务提交后才把请求送进本地线程池。
>
> 执行器把本轮请求写成 `request.json`，用 `ProcessBuilder` 启动 Python 适配器；Python 解析后生成任务脚本，再启动 VaB 训练。训练期间写 `progress.json`，结束后写 `result.json`。Java 校验 `stage、run_id、attempt、status、artifacts`，再用带 `current_run_id` 和 `status='running'` 的 SQL 更新终态。旧轮结果晚到会影响 0 行。
>
> 我还做了 `rabbit` 模式：任务事务同时写 Outbox，Relay 发布到本机 RabbitMQ，Worker 手动 ACK 消费。消息可能重复，所以 Worker 用消费账本、派发意图和当前运行身份收敛，不靠“消息只投一次”的假设。Redis 只做资源目录缓存和知识向量，任务事实始终在 MySQL。

## 三分钟白板

先画默认 `db` 路线：

```text
Vue
 │ POST /api/pre-defense
 ▼
Spring Boot ──INSERT──> MySQL(pending)
 │
 │ 用户确认 POST /start
 ▼
事务: FOR UPDATE → runId/attempt → 快照 → running → intent → lease
 │ COMMIT
 ▼
afterCommit → taskAsync 线程池
 │
 ▼
Java 执行器 ──request.json──> Python 适配器 ──> VaB
 │                                  │
 │<──── progress.json / result.json ┘
 ▼
条件 UPDATE MySQL(success/failed)
 │
 ▼
报告 / 日志 / 导出
```

然后补 Rabbit 分支：

```text
启动事务 ──同一事务──> Outbox
                         │
                      Relay
                         │ publish + confirm
                      RabbitMQ
                         │ manual ACK
                      Worker
                         │
                      Python/VaB
```

白板讲解节奏：

| 时间 | 画什么 | 讲什么 |
| --- | --- | --- |
| 0:00–0:40 | 用户目标与四类资源 | 数据、算法、参数、产物 |
| 0:40–1:30 | 创建与启动两个请求 | 为什么创建不执行，runId 何时产生 |
| 1:30–2:10 | Java/Python 文件协议 | 为什么不用 stdout 当结果 |
| 2:10–2:40 | 旧结果晚到 | `r1` 对 `r2`，SQL 0 行 |
| 2:40–3:00 | Redis/MQ 位置 | 缓存不是事实，MQ 可能重复 |

## 现场演示

| 顺序 | 展示什么 | 一句话解释 |
| --- | --- | --- |
| 1 | 数据集页四条真实数据 | CIFAR/GTSRB 校验通过，Tiny 只登记不执行 |
| 2 | 算法详情参数配置中心 | 算法默认配置是唯一来源，无独立模板 |
| 3 | 创建任务与 Network | 创建只返回 `pending`，启动是第二个请求 |
| 4 | 任务详情与 attempt 目录 | `request.json` 串起本轮身份 |
| 5 | 日志/报告 | 日志按 offset 增量读，报告来自结构化结果 |
| 6 | 运行监控 | 本机 CPU/内存/JVM/磁盘在前，分布式指标在后 |
| 7 | Rabbit 模式说明 | 单机也可以是真的 Broker，不是内存假队列 |

演示时不要只展示成功页。准备一个失败/取消场景，说明状态为什么不会被旧结果覆盖。

## 讲清自己的贡献

按四句话准备一个真实改动：

1. 用户遇到什么问题；
2. 改了哪个类、哪个行为；
3. 用什么测试/日志验证；
4. 边界是什么。

示例一：数据集接入。

> 原来数据集验证只认 CIFAR 目录，GTSRB 的 HDF5 和 Tiny ImageNet 只能算“路径存在”。我补了格式识别：CIFAR 走官方 MD5，GTSRB 校验 HDF5 文件头并把父目录作为 VaB 运行根，Tiny 校验目录但在创建任务时明确拒绝。验证时用四条真实路径逐条调用验证接口，并检查参数预览分别得到 `cifar10/cifar100/gtsrb`。

示例二：缓存一致性。

> 资源列表原来直接查 MySQL。我加了 Redis read-through，但资源修改后如果普通删除，慢读者可能把旧值写回。我用 generation + Lua 条件写解决这个竞态；执行契约仍然查 MySQL 权威行。Redis 故障只降级，不影响任务正确性。

示例三：MQ 重复消费。

> Rabbit 模式里，消息重复是正常事件。我用 `messageId` 消费账本先插入 `processing`，重复投递根据 claim 状态决定 ACK、延迟重投还是抢占；任务层再用 `runId/attempt` 校验。验证覆盖 Broker 重投、Worker 崩溃和旧轮晚到。

## 面试官可能追问的转场

| 追问 | 转到哪 |
| --- | --- |
| “为什么不用同步接口？” | 长训练、HTTP 超时、进程生命周期 |
| “为什么不用 Java 锁？” | 多 JVM/重启后事实在 MySQL |
| “MQ 能保证只执行一次吗？” | 不能，讲消费账本和幂等收敛 |
| “Redis 挂了怎么办？” | 回源 MySQL，缓存不阻塞 |
| “Python 挂了怎么知道？” | 退出码 + result.json + 超时/进程树 |
| “为什么保留旧模板表？” | 历史任务可追溯，新任务不再使用 |

继续练 [面试准备与追问题](project-interview-preparation.md)，并按 [业务流程详解](project-business-flows.md) 复述每个场景。
