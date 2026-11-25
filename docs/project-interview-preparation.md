# 面试准备与追问题

先看完 [一次正常实验](learning/00-one-experiment.md) 和 [业务流程详解](project-business-flows.md)，再练 [90 秒口述](project-introduction-and-interview-guide.md)。回答统一用“场景 → 代码 → 结果 → 边界”，不要只背名词。

## 一、业务与接口

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 1. 用户是谁，做哪件事？ | 实验者登记数据、选择 LDD、创建并确认启动任务，查看报告和导出子集。平台管理每轮实验的输入和输出。 | `TaskCenterView` |
| 2. 哪些算法真的能跑？ | 训练前、启用、`implementationPath=vab://pre-defense/ldd` 的算法。其它阶段只展示，不进入执行。 | `resolveCommand` |
| 3. 创建和启动分别做什么？ | 创建只写 `pending` 和参数；启动锁任务行、分配 `runId/attempt`、写快照/意图并置 `running`。 | `create`、`launchTask` |
| 4. 数据集为什么选不到？ | 必须启用且 `parseStatus=success`。新登记是 `pending`，要管理员验证。列表整体打不开则是后端/登录问题，不是某条路径。 | `loadDatasets`、`verifyDataset` |
| 5. 参数谁说了算？ | 算法默认配置是唯一来源；历史模板只兼容旧任务；本次自定义参数最后覆盖。后端统一校验。 | `LddConfigurationPolicy` |
| 6. 管理员能改别人的任务吗？ | 读有管理员跨用户规则；写仍检查 owner。 | `TaskAccessGuard`、`lockOwned` |
| 7. MyBatis-Plus 和手写 SQL 怎么配合？ | 普通查询用 Mapper/Wrapper；任务领取、终态、租约需要明确状态谓词的手写 SQL。 | `PreDefenseTaskMapper` |
| 8. 为什么任务列表有“超参模板”消失？ | 模板与算法绑定会产生两份事实。现在算法详情页参数配置中心维护 `default_config`，新任务不选模板。 | `AlgorithmConfigCommandService` |
| 9. Tiny ImageNet 为什么能登记不能跑？ | 目录可验证，但当前 VaB LDD 没有对应数据加载器。创建时返回 `DATASET_RUNTIME_UNSUPPORTED`，避免跑到一半才失败。 | `VabTinyImageNetDataset` |

## 二、Java 与 Python

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 10. Java 如何把对象发给 Python？ | Jackson 写 UTF-8 `request.json`，`ProcessBuilder` 参数列表传绝对路径。 | `PythonLddTaskExecutor.invoke` |
| 11. Python 如何拿参数？ | `argparse` 取路径，`json.loads` 外层请求，再解析 `configJson/customParams` 内层 JSON。 | `algorithm_entry.py` |
| 12. 训练进程是谁启动的？ | Java 启动适配器；适配器生成 attempt 级脚本，再由 `subprocess.Popen` 启动 VaB。 | `make_task_script` |
| 13. Python 怎么回调？ | 写 `progress.json` 和 `result.json`；Java 等待期读进度，退出后校验结果。 | `PythonProcessWaiter` |
| 14. 为什么不用 stdout 当结果？ | stdout 是日志流，格式不稳定；结构化文件可校验、留档、按轮追溯。 | `redirectOutput` |
| 15. 退出码 0 就能成功吗？ | 还要有 `result.json`，字段完整、阶段/轮次匹配。缺失是 `RESULT_MISSING`。 | `invoke`、`validateResult` |
| 16. 为什么用子进程而不是 JNI？ | Python/PyTorch 环境独立，崩溃隔离，进程身份可跟踪；代价是文件、超时、进程树管理。 | `DefenseTaskExecutor` |
| 17. GTSRB 为什么要特殊处理路径？ | VaB 对 `gtsrb` 用相对路径 `dataset/gtsrb_dataset.h5`，忽略通用 `--data_path`。Java 因此把 HDF5 父目录作为运行根，并把 dataset 参数推断为 `gtsrb`。 | `VabGtsrbDataset`、`dataloader_data.py` |

## 三、事务、并发与状态

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 18. 为什么 `running` 还没启动进程？ | 事务先领取，派发在提交后。查意图、accepted、attempt 目录。 | `launchTask`、`dispatchAfterCommit` |
| 19. 为什么事务提交后才执行？ | 回滚后不能收回已启动进程；执行侧也要看到已提交的请求和租约。 | `AfterCommitTaskDispatcher` |
| 20. 点两次启动会怎样？ | 同键复用；不同键争同一行，`FOR UPDATE` + `WHERE status='pending'` 只有一个成功。 | `RequestIdempotencyService`、`claimForExecution` |
| 21. 旧 Python 结果晚到？ | 校验 `run_id/attempt`，SQL 带 `current_run_id` 和 `status='running'`，影响 0 行。 | `updateResultForRun` |
| 22. 重试会换 taskId 吗？ | 不换。新 `runId/attempt`，旧快照保留。 | `retry` |
| 23. 提交后 JVM 挂了？ | 意图与任务同事务；恢复扫描按意图、accepted、进程证据决定补派发或收敛。 | `DispatchRecoveryCoordinator` |
| 24. GPU 怎么管理？ | 单槽租约 + fencing token；确认进程树退出后释放，否则隔离。 | `GpuLeaseService` |
| 25. 取消能立即回收 GPU 吗？ | 不能。先改业务状态并通知，等进程退出证据。 | `cancel`、`TaskCancellationDispatcher` |
| 26. 为什么不用 Java `synchronized`？ | 多 JVM/重启后锁丢失；任务事实在 MySQL，必须用持久条件。 | 设计取舍 |
| 27. `@Transactional` 为什么会失效？ | 自调用、非 public、异常被吞、异步线程都在事务外。入口在 Service Bean，`afterCommit` 单独注册。 | `TransactionSynchronization` |
| 28. 线程池满了？ | 有界队列 + `AbortPolicy`，拒绝后释放租约、意图 abandoned、任务失败。 | `TaskExecutorConfig` |

## 四、RabbitMQ 深挖

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 29. 单机可以不用 MQ 吗？ | 默认 `db` 可以。`rabbit` 是可选模式，单机也能跑真实 Broker，不是假队列。 | `DispatchRuntimePolicy` |
| 30. 为什么要 Outbox？ | 任务状态和待发消息同事务，避免“库成功、消息丢”或“消息先到、事务回滚”。 | `TaskOutboxService` |
| 31. Relay 怎么防并发？ | 短事务 claim 成 `publishing`，带 `claimToken/claimUntil`；发布在事务外；确认写回匹配 token。 | `TaskOutboxRelay` |
| 32. 发布确认和消费 ACK 区别？ | Confirm 是 Broker 对 Relay 的接收确认；ACK 是 Worker 对消息处理的归结。 | `RabbitDispatchTransport`、`DispatchMessageListener` |
| 33. 消息重复怎么办？ | `sys_dispatch_consumption` 以 `messageId` 唯一；重复/处理中/过期 claim 分别 ACK、延迟重投、条件抢占。 | `TaskDispatchConsumptionService` |
| 34. Worker 已写终态但 ACK 丢了？ | Broker 重投；账本已 terminal → duplicate → ACK，不重复启动 Python。 | `DispatchMessageHandler` |
| 35. 处理中 Worker 崩了？ | 账本 claim 过期后可抢占；若本地 accepted 仍存活则不开第二条执行路径。 | `WorkerConsumerLifecycle` |
| 36. 重试队列怎么实现？ | TTL 队列 5s/30s/120s + DLX 回主队列；消息等待不占消费者线程。 | `RabbitDispatchConfiguration` |
| 37. 重试耗尽怎么收敛？ | 先失败任务/abandon 意图/终态账本，再发 DLQ；DLQ 只是索引，不是事实源。 | `DispatchMessageHandler.retry` |
| 38. 不可路由消息？ | `mandatory=true` + ReturnListener，识别 `RABBIT_UNROUTABLE` 并收敛，避免任务永久 running。 | `RabbitDispatchTransport` |
| 39. 能保证恰好执行一次吗？ | 不能承诺。系统保证“最多一个有效执行 + 重复消息收敛”，不是 Broker 的 exactly-once。 | 设计边界 |

## 五、Redis 缓存深挖

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 40. 缓存哪些数据？ | 数据集/算法列表和单条资源；执行契约不用缓存。 | `ResourceQueryService` |
| 41. 为什么缓存列表？ | 页面频繁读、变更少；减少 MySQL 重复查询。 | `ResourceCatalogCache` |
| 42. 更新后怎么失效？ | 事务提交后 `afterCommit` 删除 key 并递增 generation。 | `evict` |
| 43. 为什么需要 generation？ | 防止慢读者在删除后把旧值写回。Lua 比较 generation 后才 SET。 | `PUT_IF_GENERATION` |
| 44. Redis 挂了会怎样？ | 读路径回源 MySQL，写路径不受阻；指标记 degraded。 | `loadThrough` |
| 45. 缓存和任务状态的关系？ | 任务状态、参数、权限、租约从不放缓存；缓存只加速目录读。 | 设计原则 |
| 46. 知识向量也用 Redis 吗？ | 可选 Redis 8 vector set 做候选；ACL 过滤仍在 MySQL/Java，失败回退词法。 | `KnowledgeVectorStore` |

## 六、数据、报告、助手与验证

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 47. 快照记录什么？ | 本轮请求、资源指纹、配额、租约；每 attempt 追加。 | `appendExecutionSnapshot` |
| 48. 日志如何增量读？ | 浏览器按文件记字节 offset，后端返回片段和新 offset，`reset` 时替换。 | `PreDefenseLogQueryService` |
| 49. 报告成功但 ZIP 下载失败？ | 导出还依赖索引、数据根、边界和独立导出状态；ZIP 丢失返回 410，可重建。 | `CleanSubsetExportService` |
| 50. 导出时服务重启？ | 启动扫描遗留 `pending/running`，标为可重试失败。 | `ExportRecoveryRunner` |
| 51. 助手能直接启动任务吗？ | 不能。先预览，服务器生成一次性确认；确认时重新校验权限/资源/版本，再调用同一业务服务。 | `AssistantPreviewService` |
| 52. 私人报告会被 RAG 读到吗？ | 候选按身份/可见性过滤，报告以私有脱敏摘要索引。 | `indexTaskReport` |
| 53. 如何证明设计有效？ | 单测覆盖协议/边界，MySQL 集成覆盖事务，隔离环境验收覆盖真实链路，真实 GPU 证据覆盖运行范围。 | `docs/sdd/26-current-delivery-facts.md` |
| 54. 项目最大工程难点？ | 用“触发场景 → 原行为 → 改动 → 测试 → 边界”回答，不要泛泛说“高并发”。 | 自己的改动记录 |

## 七、前端、监控与统计

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 55. 为什么把执行池和接入范围从任务页移走？ | 任务页回答“怎样完成一条实验”，运行监控回答“系统现在能不能接单、后台是否接手”。两者混在一起会让业务操作和资源状态互相干扰。执行池、GPU、平台接入和 Outbox 现在按“执行资源/平台接入/消息派发”展示。 | `TaskCenterView.vue`、`RuntimeMonitorView.vue` |
| 56. 数据集类型为什么显示 `image`？ | 后端仍保存 `image_classification`，页面用 `displayDatasetType()` 映射成短名，只是为了列宽和可读性，不改变领域模型和接口。 | `DatasetView.vue` |
| 57. 状态为什么只留“已核验/待验证”？ | 用户需要快速判断文件是否通过内容校验，不需要看到 pending/failed/仅路径可用等内部差异。失败原因仍在 tooltip 的 `parseMessage`；停用移出状态列，放名称旁标签。 | `hasVerifiedStats(row)` |
| 58. “已核验”到底代表什么？ | `parseStatus=success` 且 `sampleCount/classCount` 是整数。它只说明文件和统计已验证，不代表当前算法可执行。Tiny ImageNet 可以已核验，但创建 LDD 时仍会 `DATASET_RUNTIME_UNSUPPORTED`。 | `VabTinyImageNetDataset`、`PreDefenseTaskCommandService` |
| 59. 工作台和数据统计有什么区别？ | 工作台是行动导向：任务/资源概览、固定 7 日趋势、最近任务、执行池和入口。统计页是分析导向：7/14/30 日趋势、五状态环、完成质量、防御阶段、明细和口径。 | `DashboardView.vue`、`StatisticsView.vue` |
| 60. 统计页的数据从哪里来？ | 没有新增专用后端接口，任务计数和状态来自 `/api/task-progress/statistics`，趋势、资源、报告就绪和最近任务来自 `/api/dashboard/overview?days=`。 | `getTaskStatistics`、`getDashboardOverview` |
| 61. 统计指标怎么算？ | 成功率=成功/总数；待处理=等待+运行；报告就绪率=有清洗结果的成功任务/成功任务；资源启用率=数据集启用率与算法启用率的平均。 | `StatisticsView.vue` 的 computed |
| 62. 为什么只提供 7/14/30 天？ | 后端允许 1–30 天，页面只暴露三个常用窗口，兼顾可读性和请求量。30 天以上标签会过密，且当前没有大数据降采样。 | `DashboardOverviewService.overview` |
| 63. 为什么不用 ECharts？ | 当前数据量小，CSS `conic-gradient` 和堆叠柱足够，能减少依赖和包体。代价是没有缩放、刷选、导出；数据量增大或多图联动时再引入图表库。 | `StatisticsView.vue` |
| 64. 为什么统计页没有 ASR、GPU 利用率？ | 没有真实采集源。平台只展示任务、资源、报告和状态聚合；未知指标明确写“未采集”，不用 0 或常量冒充实验结论。 | 统计口径卡片 |
| 65. 怎么判断当前有没有启用消息队列？ | 查 `/api/observability/dispatch` 的 `mode/role/workerConsumerExpected/workerConsumerStarted/workerOwned`，再查 5672/5673/15672 和 `rabbit/erl` 进程。当前实例是 `db/api`、Broker 未启动。 | `DispatchOperationsService` |
| 66. `dispatchReady=true` 等于 MQ 已就绪吗？ | 不等于。它只说明当前派发模式完成启动恢复、可以接单；可能是 `db` 线程池，也可能是 `rabbit` Worker。必须结合 `mode` 和消费者字段。 | `DispatchRecoveryCoordinator` |
| 67. `db` 和 `rabbit` 的核心区别？ | `db` 是事务提交后 API 进程内直接派发；`rabbit` 是同事务写 Outbox，Relay 发 Broker，Worker 消费。两者都以 MySQL 为事实源，`db` 不是假队列，`rabbit` 也不替代数据库。 | `DispatchRuntimePolicy`、`AfterCommitTaskDispatcher`、`TaskOutboxRelay` |
| 68. Rabbit 模式数据库故障时会不会一直重投？ | 不热循环 `nack requeue`。消费层把数据库故障转延迟队列，保留未确认消息；恢复后重新 claim，先用消费账本和任务身份判断能否执行。 | `DispatchMessageHandler` |
| 69. 前端窄屏布局踩过什么坑？ | Element Plus `el-card` 包了 `.el-card__body`，直接子选择器会失效；7/14/30 单选在 855px 下竖排压标题。改为显式 `kpi-value` 类名和窄 `el-select`，并按窄屏复验。 | `StatisticsView.vue`、`styles.css` |
| 70. 页面显示短类型会改变后端数据吗？ | 不会。类型和状态映射都在 Vue 展示层，后端继续保存 `image_classification`、`parse_status` 和统计字段；改 UI 不等于改数据模型。 | `DatasetView.vue` |

## 模拟一：从项目介绍问到跨语言

**面试官**：你说 Java 和 Python 用 JSON，具体是哪一层？

**回答**：浏览器到 Java 是 HTTP JSON；Java 到 Python 是本地文件协议。`buildExecutionRequest` 生成 DTO，执行器用 Jackson 写 `request.json`，`ProcessBuilder` 把路径放进 `--request`；Python `argparse` 取路径，`json.loads` 解析，再解析 `configJson/customParams` 两层 JSON。

**追问**：为什么不让 Python 回调 HTTP？

**回答**：训练进程和 API 生命周期解耦，文件结果可留档、可按 attempt 定位；HTTP 回调会引入端口、鉴权和网络重试问题。Java 等进程退出后读结果更确定。

**再追问**：退出码 0 但结果丢了？

**回答**：`invoke` 要求 `result.json` 存在并通过 `validateResult`，缺失是 `RESULT_MISSING`，不会因为退出码 0 就成功。

## 模拟二：从“点两次”问到事务和恢复

**面试官**：两个启动请求同时到达，会启动两个训练吗？

**回答**：同键同内容复用响应；不同键仍争同一任务行。A 先 `FOR UPDATE` 看到 `pending`，写 `running/r1` 和意图；B 拿到锁时已是 `running`，检查失败。`claimForExecution` 还带 `WHERE status='pending'`，只有一个更新成功。

**追问**：事务提交后应用退出？

**回答**：任务、快照、意图已持久化。恢复扫描看意图阶段、accepted、进程证据和指纹；能证明没启动才补派发，否则收敛。

**再追问**：取消和成功同时到？

**回答**：两条路径都匹配 `current_run_id` 且 `status='running'`，先成功更新的把状态带离 running，另一条影响 0 行。GPU 仍要等进程退出证据。

## 模拟三：从 GTSRB 问到数据路径

**面试官**：GTSRB 为什么不能直接把 h5 路径传给 Python？

**回答**：VaB 的 GTSRB 分支把 `dataset_path` 写死成 `dataset/gtsrb_dataset.h5`，不使用通用 `data_path`。适配器 cwd 是 VaB 根，所以 Java 必须把 h5 父目录作为运行根，并保证文件在 `VaB-main/dataset/gtsrb_dataset.h5`。参数预览会把 `dataset` 推断成 `gtsrb`；如果用户自定义成 `cifar10`，会报 `DATASET_PARAMETER_MISMATCH`。

**追问**：Tiny ImageNet 为什么拒绝？

**回答**：目录结构可以验证，但当前 VaB LDD 没有 Tiny 加载器。让它在创建阶段失败，比跑一半再报错更可控。

## 模拟四：从 MQ 问到重复消费

**面试官**：单机为什么还要 MQ？

**回答**：为了验证生产级派发边界：API 和 Worker 分离、消息持久化、发布确认、消费重投。单机 Broker 也能真实经历这些故障；不是用内存队列假装。

**追问**：Broker 确认成功，Relay 崩了？

**回答**：Outbox 可能重复发布，`messageId` 稳定；消费账本先插入 processing，重复投递不会启动第二个 Python。

**再追问**：Worker 写终态后 ACK 丢了？

**回答**：Broker 重投，账本 terminal → duplicate → ACK。任务事实已经终态，不需要再执行。

## 模拟五：从 Redis 问到缓存一致性

**面试官**：资源更新后直接删缓存不就行了吗？

**回答**：普通删除有竞态：慢读者在删除前读到旧值，删除后把旧值写回。这里用 generation，每次失效递增；回填用 Lua 比较 generation，只有没变才 SET。执行契约还直接查 MySQL 权威行，缓存旧值不会影响启动。

**追问**：Redis 挂了业务会怎样？

**回答**：资源目录读回源 MySQL，写不阻塞；只有性能下降，任务正确性不变。知识向量也回退词法检索。

## 模拟六：从“统计页和工作台重复”问到指标口径

**面试官**：你的数据统计页是不是把工作台又做了一遍？

**回答**：不是同一层。工作台是行动视角，固定 7 日趋势、最近任务、执行池和快捷入口，回答“现在做什么”；统计页是分析视角，支持 7/14/30 日窗口、五状态环、完成质量、防御阶段、最近明细和统计口径，回答“这段时间结果结构怎样”。两边共享真实接口，但统计页多了窗口、分布和定义。

**追问**：为什么没有 ASR 或 GPU 利用率？

**回答**：当前没有对应的真实采集源。页面只展示从 MySQL 聚合得出的任务、资源和报告指标；没有采样链路就不展示估算值，避免用户把界面数字当成实验结论。

**再追问**：那你怎么证明图表不是假数据？

**回答**：每个块都能追到接口：任务计数来自 statistics，趋势和资源来自 dashboard overview；页面还显示采样时间和统计范围。当前样例是 7 个任务、成功 4 个、失败 1 个、等待 2 个，能和数据表逐项核对。

## 模拟七：从“消息队列启用了吗”问到 db/rabbit

**面试官**：你说支持 RabbitMQ，现在到底启用了吗？

**回答**：当前实例没有启用。`GET /api/observability/dispatch` 返回 `mode=db`、`role=api`，`workerConsumerExpected=false`、`workerConsumerStarted=false`、`workerOwned=false`；5672/5673/15672 也没监听，没有 Rabbit/Erlang 进程。代码支持和当前运行状态是两件事。

**追问**：`dispatchReady=true` 不是说明已经 ready 吗？

**回答**：它只说明当前派发链路完成启动恢复、可以接单。`db` 模式下 ready 指的是本地线程池，不代表 Broker 已连接。要看 `mode` 和消费者字段才能判断 RabbitMQ。

**再追问**：那 db 和 rabbit 的本质区别是什么？

**回答**：`db` 是事务提交后由 API 的 `taskAsync` 直接执行；`rabbit` 是同事务写 Outbox，Relay 发到 Broker，Worker 消费并手动 ACK。两者都复用同一套任务、意图、Python 协议和 MySQL 事实源；`db` 不是“假 MQ”，`rabbit` 也不是“把任务状态搬到 MQ”。

## 快速自检

合上文档，能连续说出这七句即可：

1. 创建是 `pending`，启动才产生 `runId/attempt`。
2. 事务内写任务、快照、意图；提交后才执行/发消息。
3. Java 和 Python 用请求/结果 JSON 文件，不用 stdout 当结果。
4. 旧轮结果被 `current_run_id + running` 条件挡住。
5. Redis 只加速目录，Rabbit 只负责派发，MySQL 才是任务事实。
6. `dispatchReady=true` 不等于 MQ 启用；当前实例是 `db/api`。
7. 页面展示短类型和二态状态不改后端模型，统计页没有真实采集源的指标不编造。

再准备一个自己真正改过的文件、一个测试、一个失败场景。面试时沿这条证据链往下讲，比背架构图更可信。
