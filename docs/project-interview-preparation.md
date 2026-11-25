# 面试准备与追问题

先讲完一次 [正常业务流程](learning/00-one-experiment.md)，再做 [90 秒口述](project-introduction-and-interview-guide.md)。下面的 36 题用来检查知识面，后面的对话用来练连续追问。每个答案都用“场景 → 代码 → 结果”讲成完整句子。

## 业务与接口

| 问题 | 建议回答 | 查代码 |
| --- | --- | --- |
| 1. 用户是谁，做哪件事？ | 实验者登记可用数据，配置 LDD，执行数据清洗，然后读报告和可用子集。平台管理每轮实验的输入和输出。 | [设计思路](project-design-overview.md) |
| 2. 哪些算法真的能跑？ | 真实链路允许训练前、启用且 `implementationPath` 为 `vab://pre-defense/ldd` 的算法。三个阶段可展示。 | `resolveCommand` |
| 3. 创建和启动分别发什么？ | 创建按钮只发 `POST /api/pre-defense`，得到 `pending` 任务。用户确认启动后独立发送 `POST /api/pre-defense/start/{taskId}`。启动预检失败时原任务保留。 | `submitCreate`、`runCommand` |
| 4. 数据集为什么选不到？ | 后端需要启用且解析状态为 `success`。演示目录的新登记状态是 `pending`，先走验证接口。 | `loadResources`、`resolveCommand` |
| 5. 参数谁说了算？ | 页面负责 JSON 格式反馈，后端合并算法默认、模板和用户值，并按 LDD 策略限制。启动记录最终请求。 | `LddConfigurationPolicy`、`buildExecutionRequest` |
| 6. 管理员能改他人的任务吗？ | 项目区分读与写。管理员有按规则跨用户读取渠道，写命令在 `lockOwned` 后校验 owner。 | `TaskAccessGuard`、`lockOwned` |
| 7. MyBatis-Plus 和手写 SQL 如何配合？ | 普通查询可使用 Mapper 和 Wrapper，任务抢占和终态写入需要有明确状态谓词的 SQL。 | `PreDefenseTaskMapper` |

## Java 和 Python

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 8. Java 如何把对象发给 Python？ | Jackson 写 UTF-8 请求文件，Java 用 `ProcessBuilder` 把路径放进参数列表；`--request` 后面是路径。 | `PythonLddTaskExecutor.invoke` |
| 9. Python 如何拿到参数？ | `argparse` 得到路径，`json.loads(read_text(...))` 解析请求。`customParams` 和 `configJson` 各自保存 JSON 字符串，交给 `load_json_object` 解析。 | `main`、`run`、`build_config` |
| 10. 训练进程是谁启动的？ | Java 启动 `algorithm_entry.py`。适配器生成 attempt 级脚本，再用 `subprocess.Popen` 启动 VaB 训练。 | `make_task_script`、`run` |
| 11. Python 怎么回调？ | 写 `progress.json` 和 `result.json`。等待器运行期间采样进度，Java 等进程退出后读结果，再按 SQL 条件更新。 | `PythonProcessWaiter`、`validateResult` |
| 12. 为什么不用 stdout 当结果？ | stdout 适合接训练输出和诊断日志。结构化文件可直接校验、留存和复盘，也减少日志格式对结果解析的影响。 | `redirectOutput`、`resultFile` |
| 13. 退出码 0 就能成功吗？ | Java 还要求有结果文件、完整必要字段、匹配阶段与轮次。`status` 为 `failed` 时要提供失败码和原因。 | `invoke`、`validateResult` |
| 14. 为什么使用子进程？ | 能沿用独立 Python/PyTorch 环境，Java 管任务和资源，Python 管训练适配；进程身份可跟踪。需要额外管理文件、超时和进程树。 | `DefenseTaskExecutor`、`executeAndWait` |

## 事务、并发与消息

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 15. 为什么 `running` 还没启动进程？ | SQL 事务领取后即是 `running`，派发和执行发生在事务提交后。查意图状态、accepted 标记和当前 attempt 目录。 | `launchTask`、`dispatchAfterCommit` |
| 16. 为什么要等事务提交？ | 执行侧需要看到已提交任务、请求快照和意图，也可避免回滚后仍启动进程。 | `AfterCommitTaskDispatcher` |
| 17. 点两次启动会怎样？ | 同键同请求可返回原响应。不同键仍须锁行并满足 `pending`，第二个请求无法同时领取同一行。 | `RequestIdempotencyService`、`claimForExecution` |
| 18. Python 晚到写回如何处理？ | Java 校验结果的 `run_id` 和 attempt，SQL 用当前 run 及 `running` 限定写入。旧 run 或已取消任务影响 0 行。 | `validateResult`、`updateResultForRun` |
| 19. 重试会改变任务 ID 吗？ | 失败任务保留 `taskId`。领取新的运行，重新生成 runId 和 attempt，并按轮分目录。 | `retry`、`createContext` |
| 20. 数据库提交后进程挂了怎么办？ | 意图与任务一起提交。恢复扫描按阶段、运行身份、快照和进程证据决定补派发或收敛。 | `DispatchRecoveryCoordinator` |
| 21. GPU 槽位怎么管理？ | MySQL 单槽租约和 fencing 标识当前持有轮次。执行器负责检查进程树，确认退出后释放，否则隔离。 | `GpuLeaseService`、`ensureGone` |
| 22. API 取消能立即回收 GPU 吗？ | API 提交业务取消，随后本机通知或 Worker 观察后终止进程。GPU 回收取决于进程退出证据。 | `cancel`、`TaskCancellationDispatcher` |
| 23. Rabbit 模式为什么需要 Outbox？ | 任务状态与待发布事件同事务落库。Relay 之后发布并记录结果，减少两个系统间各自提交造成的缺口。 | `TaskOutboxService`、`TaskOutboxRelay` |
| 24. 发布确认与消费 ACK 区别？ | 发布确认来自 Broker，供 Relay 确认发布。消费 ACK 来自 Worker，依赖消息有效性、消费账本和业务结果。 | `RabbitDispatchTransport`、`DispatchMessageListener` |
| 25. 消息可能重复，怎么解释处理语义？ | Relay 和 Worker 都可能遇到中断窗口。用 `messageId` 账本、意图领取、当前运行、进程标记及终态处理重复。运行语义依赖这些持久条件。 | `DispatchMessageHandler`、`TaskDispatchConsumptionService` |
| 26. 两种模式在哪领 GPU？ | `db` 在启动事务中领，`rabbit` 在 Worker 消费时领。后者在消息等待时保留 GPU 给可运行任务。 | `launchTask`、`DispatchClaimTransactionService` |

## 数据、助手与验证

| 问题 | 建议回答 | 代码锚点 |
| --- | --- | --- |
| 27. 快照记录了什么？ | 本轮请求、运行配置、输入指纹、配额及租约信息。Python 记录解释器和依赖等环境信息。普通文件内容哈希有配置上限。 | `appendExecutionSnapshot`、`ExecutionFingerprintService` |
| 28. 日志如何增量读取？ | 浏览器按文件记录字节 offset。后端返回本段内容和新 offset，页面遇到 reset 时覆盖旧片段。 | `loadLogs`、`PreDefenseLogQueryService` |
| 29. 报告成功却无法下载 ZIP？ | 导出还依赖有效的筛选索引、数据根、边界校验和独立导出状态。短桩只提供调用标记。 | `CleanSubsetExportService.describe` |
| 30. 导出过程中服务重启？ | 启动事件扫描开放导出，区分未执行与执行中，标成可重新提交的失败。 | `ExportRecoveryRunner` |
| 31. Redis 在哪里用到？ | 资源目录缓存和可选知识向量排序。任务事实由 MySQL 持久化，资源和检索都有降级路径。 | `ResourceQueryService`、`KnowledgeRetrievalService` |
| 32. 私人报告怎样防止被别人通过 RAG 读到？ | 检索候选按身份和可见性过滤。报告作为私有、脱敏摘要索引，回答使用经许可的候选和引用。 | `indexTaskReport`、`KnowledgeRetrievalService` |
| 33. 助手启动任务有几步？ | 工具只给预览。服务器创建绑定身份、上下文及有效期的确认；确认服务重新核对业务权限与资源状态，再调用启动命令。 | `AssistantPreviewService`、`AssistantConfirmationService` |
| 34. 如何证明设计有效？ | 分别举针对性测试、隔离集成验收和真实 LDD 的证据。例如超时进程退出再释放租约的测试，之后说明它覆盖的环境。 | [验收事实](sdd/26-current-delivery-facts.md) |
| 35. 将来怎么多机？ | 先明确 Worker 对共享数据、持久化产物和计算资源的访问方式，再定义节点隔离、跨机租约和故障演练指标。 | [设计边界](project-design-overview.md) |
| 36. 项目最大的工程难点是什么？ | 选实际遇到的问题。用“触发场景 → 原行为 → 代码改动 → 测试证据 → 仍有的边界”回答。 | [任务工作表](learning/task-notebook.md) |

## 模拟一 从项目介绍问到跨语言

**面试官**。你说 Java 和 Python 用 JSON，具体是哪一层通信？

**回答**。浏览器对 Java 用 HTTP 请求体。Java 到 Python 用本地文件协议。`buildExecutionRequest` 生成 `DefenseTaskRequest`，执行器用 Jackson 把它写入本轮 `request.json`，`ProcessBuilder` 参数列表把绝对路径传到 `--request`。Python 的 `argparse` 取路径，`read_text` 和 `json.loads` 拿到字典，`build_config` 再解析里面的配置文本。

**追问**。哪个回调接口接 Python 的结果？训练日志里能不能直接解析结果？

**回答**。本轮交付通过 `progress.json`、`result.json`，Java 等待期间读取进度，进程退出后读取结果。stdout 和 stderr 用于看运行输出。结构化结果带 `run_id`、`attempt`、`status`、`artifacts`，可做必填校验，也保留在对应执行目录。

**再追问**。退出码为 0，结果却丢了，页面会显示成功吗？

**回答**。`invoke` 还要求存在 `result.json`，缺失会进入 `RESULT_MISSING`。有文件时也会检查结构和阶段、轮次身份。浏览器显示的是 Java 按运行条件持久化后的状态。

练习证据用 [JSON 短桩](learning/02-java-python.md) 和 [执行器实现](project-implementation-guide.md#3-java-怎样把参数交给-python)。白板上必须画出适配器启动 VaB 子进程。

## 模拟二 从“点两次”问到事务和恢复

**面试官**。两个启动请求同时到达，会出现两个训练进程吗？

**回答**。同用户、同操作、同键、同内容的已完成请求复用响应。用不同键时，两个请求仍会争同一任务行。A 先锁行，检查 `pending` 后提交 `running/r1` 与派发意图。B 取得锁时已经是 `running`，检查失败。Mapper 的 `claimForExecution` 也要求 `status='pending'`，服务只接受一行被更新。

**追问**。事务已经提交，应用还没派发就退出呢？

**回答**。`42/r1/running` 和待派发意图已经持久化。恢复扫描检查意图、身份、快照和进程证据，满足条件时才补派发。已经有 accepted 标记时，先判断旧轮是否接手或者进程是否存活。退出证据不足时按恢复策略收敛并隔离资源。

**再追问**。取消和成功结果几乎同时发生，哪一个生效？

**回答**。两条终态路径都要求匹配当前轮且仍 `running`，先完成条件更新的动作把任务带离 `running`。另一条路径需要根据更新结果或后续读取处理竞争。若任务已经是 `42/r2/running`，`r1` 的 SQL 也匹配不到。槽位在确认证据表明进程退出或无需清理时才释放。

指出 [可靠性课程](learning/03-reliability.md) 里的行锁、运行谓词和超时测试。追问“为什么不用 Java 锁”时，解释数据库里有任务事实，独立 API/Worker 或重启都需要依据同一组持久状态判断。

## 模拟三 从“成功”问到数据追溯

**面试官**。同一个任务重试之后，你怎么知道两次参数是否一致？

**回答**。`taskId` 连同一个人可操作的任务，`runId/attempt` 连本轮快照和文件。`execution_snapshot` 按轮记录实际请求、输入指纹、租约和配额，也有本轮快照的摘要。Python 采集解释器、依赖和设备信息。两轮比较时看各自快照，普通文件根据配置计算内容 SHA-256，目录指纹看实现记录的属性。

**追问**。成功报告里有筛选结果，为什么下载失败？

**回答**。报告查询时验证成功的 CIFAR-10 LDD 结果、可读索引和训练批次。导出又有自己的 `exportId`。如果已有导出是成功但 ZIP 已经丢失，下载返回 `410`。源批次和索引仍有效时，可复用同一个导出记录重新生成 ZIP，任务 `42/r1` 不会重新执行。

**再追问**。别人知道导出 ID，该怎么防止被下载？

**回答**。`findReadable` 找到导出后还要对父任务做权限检查。下载另外确认状态和真实路径。导出提交检查父任务所有者，同时锁行，协调相同比例的并发提交。

练习时解释 [第 04 课](learning/04-report-and-export.md) 的两条测试用例，再说清自己负责过哪个分支。

## 模拟四 从 MQ 问到重复消费

**面试官**。默认为什么可以不用 MQ，有 MQ 时哪一步改了？

**回答**。默认 `db` 由 JVM 在启动事务提交后将工作送入本地有界执行池。`rabbit` 的 API 将 Outbox 与任务和意图同一事务写入，Relay 再向 Broker 发布。独立 Worker 根据意图领取任务、取得 GPU 租约，以相同文件协议运行 Python。

**追问**。Broker 确认成功，Relay 还没写回就崩了？

**回答**。Outbox 可能再次被发布。同一 `messageId` 用来关联消费账本，Worker 还检查当前 `runId`、派发意图和执行证据。首次和重投递走同一组状态约束。

**再追问**。Worker 已经写入终态，ACK 没到怎么办？

**回答**。Broker 可能重投。Worker 根据账本及任务终态决定怎样归结这条投递。发布确认和消费 ACK 各负责一段交付。不会把这套设计口述成 Broker 保证训练恰好执行一次。

把时序画在 [扩展组件课程](learning/05-extensions-and-tests.md) 的双模式图上。面试只需先说当前默认模式，再说为何需要独立 Worker。

## 继续发散

| 面试官继续问 | 把基础知识落到项目上 |
| --- | --- |
| `@Transactional` 为什么会失效？ | 讲默认代理和自调用。指出 Controller 进入 Service Bean 的事务边界，再说 `afterCommit` 怎样注册 |
| 行锁与乐观条件有什么区别？ | 用启动请求解释 `FOR UPDATE` 串行化读和判断，再用 `WHERE status='pending'` 和结果写回解释写入约束 |
| 线程池满了会怎样？ | 找 `taskAsync` 的有界队列和 `AbortPolicy`，说出拒绝后的派发意图及失败收敛 |
| 线程中断一定能终止 Python 吗？ | 画 Java 线程、适配器进程、训练子进程，找超时等待和进程树退出检查 |
| Redis 缓存和 MySQL 如何协作？ | 资源列表查缓存，资源修改后失效，启动重新查并校验。解释缓存短期旧值对读列表和启动的影响 |
| 你会怎样测试改动？ | 用参数校验单测、短进程协议测试、MySQL 集成、真实 GPU 执行分别证明对应行为 |

按 [八股学习地图](learning/technology-map.md) 展开这六题。每题准备一个具体输入、一个实现落点和一种失败情景。
