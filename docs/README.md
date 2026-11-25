# 安全模型训练平台文档

本文档按 2026 年 11 月 25 日当前工作区核对。初次学习先走完整实验，再按课程读源码；准备面试时直接看项目讲解和追问；需要契约与验收证据时回到 SDD。

## 1. 文档地图

| 文档 | 主要回答 | 适合什么时候读 |
| --- | --- | --- |
| [从一份数据走到一份报告](learning/00-one-experiment.md) | 一次实验在页面上怎样完成 | 第一次接触项目 |
| [学习计划与实操课程](learning/README.md) | 按什么顺序读源码和做练习 | 系统学习 |
| [技术栈学习地图](learning/technology-map.md) | Java、Python、MySQL、Redis、MQ 各自负责什么 | 补开发基础 |
| [设计思路](project-design-overview.md) | 系统为什么这样分层，页面和状态如何组织 | 画架构图、写论文设计章 |
| [实现指南](project-implementation-guide.md) | 沿着接口、类、SQL 和文件协议看代码 | 开发、调试、改功能 |
| [业务流程详解](project-business-flows.md) | 用户点下按钮后，13 个典型场景分别发生什么 | 验收、答辩、排查 |
| [项目讲解与演示](project-introduction-and-interview-guide.md) | 30 秒、90 秒、三分钟怎样讲 | 面试和现场演示 |
| [面试题与模拟面试](project-interview-preparation.md) | 技术追问怎样落到代码和边界 | 面试前自测 |
| [简历参考](resume-project-entry.md) | 项目经历怎样写成可追问的条目 | 写简历 |
| [Agent / RAG 实操](agent-rag-demo.md) | 知识检索和受控助手怎样落地 | 学扩展能力 |
| [SDD 索引](sdd/README.md) | 设计演进、契约、验收证据在哪里 | 核对交付事实 |

## 2. 当前实现一览

| 维度 | 当前事实 |
| --- | --- |
| 真实执行链路 | 训练前 LDD 数据清洗；训练中、训练后算法目前只在目录展示 |
| 可执行数据集 | CIFAR-10、CIFAR-100、GTSRB；Tiny ImageNet-200 可登记和校验，但 LDD 不消费 |
| 默认派发 | `dispatch.mode=db`、`role=api`，事务提交后进入 API 进程的 `taskAsync` 线程池 |
| 可选派发 | `dispatch.mode=rabbit`，同一事务写 Outbox，Relay 发布到真实 RabbitMQ，Worker 手动 ACK 消费 |
| 消息队列现状 | 代码和单机启停流程已具备；当前本机实例是 `db/api`，Broker 未启动，不能表述为“MQ 正在启用” |
| 任务事实源 | MySQL；Redis 只做资源目录缓存和可选知识向量，RabbitMQ 只传派发事件 |
| 页面统计口径 | 工作台用于行动概览；数据统计用于图表分析；没有真实采集源的 ASR、GPU 利用率等不展示估算值 |
| 前端验收 | 数据集短类型与二态状态、任务页与运行监控分工、统计页图表化均已构建并在浏览器复验 |

动态运行状态不要写死在文档里。判断当前有没有启用 RabbitMQ，以 `GET /api/observability/dispatch` 的 `mode`、`role`、`workerConsumerStarted`、`workerOwned` 和端口监听为准，步骤见 [实现指南 §16](project-implementation-guide.md#16-当前运行状态与消息队列核查)。

## 3. 推荐阅读路线

### 第一次接触项目

1. [设计思路](project-design-overview.md)：先建立系统图、页面分工和任务状态机。
2. [业务流程详解](project-business-flows.md)：按“创建 → 启动 → 执行 → 回写 → 监控 → 统计”走一遍。
3. [实现指南](project-implementation-guide.md)：对照 Controller、Service、Mapper、前端页面和 Python 文件协议。

### 准备面试

1. [项目讲解与演示](project-introduction-and-interview-guide.md)：练 30 秒、90 秒、三分钟白板和页面走查。
2. [面试准备与追问题](project-interview-preparation.md)：覆盖业务、事务、MQ、缓存、前端和可观测性。
3. 准备一个真实改动，按“场景 → 代码 → 验证 → 边界”讲，不要只背架构名词。

### 做开发和排查

- 前端页面：`migration-frontend/src/views`
- 前端路由：`migration-frontend/src/router/index.js`
- 前端数据映射：`DatasetView.vue`、`TaskCenterView.vue`、`RuntimeMonitorView.vue`、`StatisticsView.vue`
- 后端任务命令：`PreDefenseTaskCommandService`
- 调度：`TaskDispatchCoordinator`、`TaskOutboxRelay`、`DispatchMessageHandler`
- 执行器：`PythonLddTaskExecutor`
- 数据集验证：`ResourceCommandService`
- 算法配置：`AlgorithmConfigCommandService`
- 缓存：`ResourceCatalogCache`
- Rabbit 拓扑：`RabbitDispatchConfiguration`
- 当前事实基线：`docs/sdd/26-current-delivery-facts.md`

## 4. 当前界面与统计口径

### 4.1 数据集资源

- 后端仍保存领域类型 `image_classification/text_classification/tabular`；页面只显示短名 `image/text/table`，不改变后端语义。
- 状态列只保留 **已核验 / 待验证**。停用信息放在名称旁，不混入验证状态。
- “已核验”表示文件内容和统计字段已验证；不等于当前算法一定能消费。Tiny ImageNet 可以是“已核验”，但创建 LDD 任务仍会被拒绝。

### 4.2 安全防御任务与运行监控

- 任务页只保留 4 个任务统计和任务列表，负责创建、启动、取消、重试、报告、日志和导出。
- 执行池、运行配额、GPU 租约、平台接入和消息派发集中到运行监控，按“执行资源 / 平台接入 / 消息派发”分类展示。
- 本机 CPU、内存、JVM 堆和磁盘指标只代表 API 进程所在机器，不能和分布式派发指标混在一起读。

### 4.3 工作台与数据统计

- 工作台回答“现在要做什么”：任务/资源概览、固定 7 日趋势、最近任务、执行池和快捷入口。
- 数据统计回答“这一段时间做得怎样”：4 个 KPI、5 状态环图、7/14/30 日趋势、完成质量、防御阶段构成、最近明细和统计口径。
- 两者共享真实接口但展示深度不同，不是复制同一张表。统计页明确区分当前账号与管理员全量范围，并排除 `demo` 来源任务。

## 5. 维护规则

改代码时，先更新接口、参数、状态或运行步骤所属的主文档，再检查业务流程、演示脚本和面试答案是否仍然成立。验收结果要带提交、环境、运行范围和执行/跳过结果；动态数字标注采样时间，旧证据保留在原 SDD 页的历史块中。

当前稳定口径：

- 新任务 `hyperId=null`，算法默认配置来自参数配置中心；
- `sys_hyper` 只保留历史读取，不作为新任务来源；
- 全站时间展示统一为 `YYYY-MM-DD HH:mm:ss`；
- 运行监控前半部分是本机真实指标，后半部分是 MySQL 调度账本、线程池、GPU 和 Worker 指标；
- 数据集页面整体报错先查后端/JWT，不要先怀疑某条路径；
- `dispatchReady=true` 只代表当前派发模式可以接单，不代表 RabbitMQ 已启用。
