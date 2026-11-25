# 从项目学开发与八股

先完成 [贯穿案例](00-one-experiment.md)。每次挑一个问题，找到代码、预测行为，再用观察结果纠正自己的解释。前五节对应真实任务主线。第六节以后按简历和岗位选学。

## 1 HTTP、JSON 与前后端分工

**项目问题**。点“提交”后，为什么我已经看到任务详情，后台还没有 Python？

**从零理解**。一个 HTTP 请求有方法、路径、请求头和可选请求体。这个项目的创建请求用 POST，`Authorization` 带认证令牌，`Idempotency-Key` 标识一次提交，请求体带表单。`@RequestBody` 接请求体，`@PathVariable` 接路径里的 ID。创建响应 `201` 让页面知道资源已经建好。之后另一次 POST 用该 ID 请求启动。`GET` 查询任务，不承担启动动作。

**动手**。打开浏览器 Network，分别记下 `POST /api/pre-defense` 和 `POST /api/pre-defense/start/{taskId}`。在 Controller 找两份入参。看到 `customParams` 时分别写出 Java 类型 `String`、外层 JSON 的带转义字符串、解析后内部对象三种形式。

**说出口**。创建传配置，响应返 ID。启动操作引用已存在的 ID，后端重新做权限、资源和状态校验。长任务的结果随后由 GET 查询。页面校验负责即时反馈，规则由后端实施。

[请求封装](../../migration-frontend/src/api/preDefense.js) · [HTTP Controller](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseTaskController.java)

## 2 Spring Bean、事务代理与提交后执行

**项目问题**。领取都写了 `running`，能直接在事务中启动 Python 吗？

**从零理解**。Spring 容器管理 Service 等 Bean，创建并注入依赖。默认代理模式下，外部经过 Bean 代理调用 `@Transactional` 方法时，代理建立事务边界。同一个对象内部的方法调用通常绕过这个代理。这个项目在命令事务里一起写本轮任务、租约和派发意图，使用 `AfterCommitTaskDispatcher` 把提交执行推迟到提交成功之后。

**动手**。从 Controller 跟到 `start`，在 `launchTask` 标记数据库操作与进程副作用。读 `AfterCommitTaskDispatcherTest.dispatchesOnlyAfterTransactionCommit`，分别看调用派发器后和手动触发提交回调后，测试如何断言实际执行次数。用自己的话解释数据库回滚为什么无法替你停止一个已启动的 OS 进程。

**说出口**。只在提交成功以后做外部执行。若提交成功后 JVM 中断，已经持久化的 intent 留给恢复流程判断。`afterCommit` 解决执行时序，恢复流程解决进程退出后的空窗。

[命令服务](../../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java) · [提交后派发](../../migration-backend/src/main/java/com/securitymodel/application/task/AfterCommitTaskDispatcher.java) · [测试](../../migration-backend/src/test/java/com/securitymodel/application/task/AfterCommitTaskDispatcherTest.java)

## 3 MySQL 行锁、条件更新与幂等

**项目问题**。用户连点两次启动，两个 HTTP 请求能不能各起一个进程？

**从零理解**。行锁在事务期间协调对同一行的访问。`FOR UPDATE` 后的校验和写入共享事务。条件更新用 `WHERE` 将前置条件纳入写操作，受影响行数告诉服务是否成功领取。HTTP 幂等记录在另一个层次处理重发，使同用户、同操作、同键、同请求内容的已完成请求能复用原响应。

**动手**。画请求 A、B 两列。A 读到 `pending` 并更新，B 等锁后读到 `running`。接着模拟 A 已失败、第二轮 `r2` 运行、`r1` 又写结果。标出哪条 SQL 的哪个条件不匹配。最后替换成 A 与 B 使用同一键和相同请求，定位幂等服务如何短路。

**说出口**。同请求重发靠幂等键，不同请求争同一任务靠锁与状态条件。迟到结果是第三种问题，后端用当前 `runId` 和 `running` 条件限定回写。

[幂等实现](../../migration-backend/src/main/java/com/securitymodel/application/task/RequestIdempotencyService.java) · [状态 SQL](../../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java)

## 4 Java 线程、OS 进程和 GPU 租约

**项目问题**。API 已返回 `canceled`，GPU 为什么仍显示占用？

**从零理解**。线程池中的 Java 线程负责执行 Java 方法。`ProcessBuilder` 创建的 Python 进程由操作系统管理，适配器又可启动训练子进程。取消命令先改变业务状态，随后发起进程终止。资源能否分配给下一轮，要看原进程树的退出证据。单槽租约记录持有身份，fencing token 区分租约代次，进程状态解决物理资源占用问题。

**动手**。按 [第 03 课](03-reliability.md) 跑短进程测试，找到 `invoke`、等待器和 `finally`。画出“取消提交 → 发终止请求 → 确认进程退出 → 释放租约”。再看 `TaskExecutorConfig` 的队列与拒绝策略，解释队列满时为什么要让任务状态收敛。

**说出口**。数据库终态和计算资源回收有不同证据。终态依赖条件更新，资源清理依赖进程树确认。退出不确定时槽位会隔离。

[线程池](../../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/TaskExecutorConfig.java) · [子进程等待](../../migration-backend/src/main/java/com/securitymodel/application/task/PythonProcessWaiter.java) · [租约](../../migration-backend/src/main/java/com/securitymodel/application/task/GpuLeaseService.java)

## 5 读取接口、独立状态与权限

**项目问题**。知道 `taskId` 或 `exportId` 就能看报告和下载吗？

**从零理解**。JWT 验证通过后只确认了请求身份。后端还要对具体任务检查可读归属，写命令检查所有者。导出使用独立 ID，读取时回查父任务权限。文件读取还受真实路径边界和可用性检查约束。前端是否显示按钮只是交互。

**动手**。从 `PreDefenseReportController` 跟到 `getReport`、`findReadable` 和 `resolveDownloadFile`。画出“身份 → 父任务权限 → 导出状态 → 真实文件”的检查顺序。再用 [第 04 课](04-report-and-export.md) 推演 ZIP 消失时的重建。

**说出口**。JWT 标识人，服务对任务行做授权。下载时用 `exportId` 找导出后还要查父任务。一个训练结果可供多次导出，各自有状态。

[认证过滤器](../../migration-backend/src/main/java/com/securitymodel/infrastructure/security/JwtAuthenticationFilter.java) · [任务授权](../../migration-backend/src/main/java/com/securitymodel/application/task/TaskAccessGuard.java) · [报告和下载入口](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseReportController.java)

## 6 RabbitMQ、Outbox 与重复交付

**项目问题**。MySQL 已领取任务，给 Broker 发消息时崩溃，重启从哪恢复？

**从零理解**。Outbox 是与业务变更同事务保存的待发布记录。Relay 查它并发消息。发布确认告诉 Relay Broker 如何处理发布。消费者 ACK 告诉 Broker Worker 如何归结本次投递。发布、执行和确认都可能遇到中断，因此消费端需要抵御重复。

**动手**。照第 05 课画两条模式。先追 `TaskOutboxRelay`，再读 `DispatchMessageHandlerTest`。在图中插入两个宕机点，分别是“Broker 已收到，Relay 未标记发送”和“Worker 已写终态，ACK 未到 Broker”。给每个点标出留下的持久证据和下一次处理动作。

**说出口**。默认单 JVM 使用 `db`。选择 Rabbit 后，API 写 Outbox，Relay 发布，Worker 根据消息账本、派发意图及当前 `runId` 检查并领取。允许重投递，业务侧负责收敛。

[Relay](../../migration-backend/src/main/java/com/securitymodel/application/task/TaskOutboxRelay.java) · [消费测试](../../migration-backend/src/test/java/com/securitymodel/application/task/DispatchMessageHandlerTest.java) · [模式与契约](../sdd/21-r4-outbox-rabbitmq-design.md)

## 7 Redis 缓存、权威读取与知识检索

**项目问题**。资源列表在短时间内仍有旧选项，后端是否会因此启动不合法任务？

**从零理解**。缓存旁路读取通常先查缓存，未命中查数据库并填充。TTL 和写后失效用来减少旧数据窗口。启动涉及状态变更，需要再次读取资源事实并验证。知识模块另有词法检索和可选 Redis 向量排序，候选可见性仍由后端约束。

**动手**。在 `ResourceQueryService` 对比目录查询和启动计划读取，找到缓存故障后的查询路径。纸上假设一个数据集刚停用，分别写出列表可能显示什么、启动时应该校验什么。选做一次引用回查，用行号找 SDD 原文。

**说出口**。Redis 帮资源读请求提速，启动计划使用当时校验过的资源，任务状态由 MySQL 管。RAG 用检索证据回答项目问题，工具写操作还要走后端预览和确认路径。

[资源查询](../../migration-backend/src/main/java/com/securitymodel/application/resource/ResourceQueryService.java) · [Agent/RAG 实操](../agent-rag-demo.md)

## 用五分钟检验是否学会

抽一题，先画出正常路径，再在图中放一个错误，最后指到一处代码或测试说明行为。一分钟内能讲完“业务为什么需要、源码在哪里、错了谁处理”，就把这题加入自己的面试材料。每新增一个简历术语，就用同样方式准备一轮追问。
