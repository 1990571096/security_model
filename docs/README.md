# 安全模型训练平台文档

本文档按 2026 年 11 月 25 日的当前工作区核对。初次学习先读完整案例，再按课程顺序动手；掌握主线后回到这里选择技术主题和面试练习。

| 我想做什么 | 从哪里开始 |
| --- | --- |
| 读完一次正常实验 | [从一份数据走到一份报告](learning/00-one-experiment.md) |
| 按顺序读源码和做练习 | [学习计划与实操课程](learning/README.md) |
| 用项目学开发基础和八股 | [技术栈学习地图](learning/technology-map.md) |
| 看懂系统怎么分工 | [设计思路](project-design-overview.md) |
| 顺着接口读代码 | [实现指南](project-implementation-guide.md) |
| 按场景看完整业务流程 | [业务流程详解](project-business-flows.md) |
| 练自我介绍和白板 | [项目讲解与演示](project-introduction-and-interview-guide.md) |
| 准备技术追问 | [面试题与模拟面试](project-interview-preparation.md) |
| 写简历 | [简历参考](resume-project-entry.md) |
| 学习知识检索和受控助手 | [Agent / RAG 实操](agent-rag-demo.md) |
| 查设计演进、契约和验收证据 | [SDD 索引](sdd/README.md) |

当前真实算法链路是训练前 LDD 数据清洗。默认 `db/api` 单机模式；`rabbit` 模式可在同一台机器上通过真实 RabbitMQ 使用 API + Worker 双进程。可执行数据集为 CIFAR-10、CIFAR-100、GTSRB；Tiny ImageNet 可登记和校验目录，但当前 LDD 不消费。

## 建议阅读顺序

### 第一次接触项目

1. [设计思路](project-design-overview.md)：先建立系统图和状态机。
2. [业务流程详解](project-business-flows.md)：按“创建 → 启动 → 执行 → 回写”走一遍。
3. [实现指南](project-implementation-guide.md)：对照 Controller、Service、Mapper 和 Python 文件协议。

### 准备面试

1. [项目讲解与演示](project-introduction-and-interview-guide.md)：30 秒、90 秒、三分钟白板。
2. [面试准备与追问题](project-interview-preparation.md)：业务、事务、MQ、缓存和模拟追问。
3. 准备一个真实改动，按“场景 → 代码 → 验证 → 边界”讲。

### 做开发和排查

- 前端创建任务：`migration-frontend/src/views/TaskCenterView.vue`
- 后端任务命令：`PreDefenseTaskCommandService`
- 调度：`TaskDispatchCoordinator`、`TaskOutboxRelay`、`DispatchMessageHandler`
- 执行器：`PythonLddTaskExecutor`
- 数据集验证：`ResourceCommandService`
- 算法配置：`AlgorithmConfigCommandService`
- 缓存：`ResourceCatalogCache`
- Rabbit 拓扑：`RabbitDispatchConfiguration`

## 文档维护规则

改代码时，先更新接口、参数、状态或运行步骤所属的主文档，再检查课程练习和面试答案。验收结果要带提交、环境、运行范围和执行/跳过结果。旧版本证据保存在原 SDD 页的历史块中。

当前口径：

- 新任务 `hyperId=null`，算法默认配置来自参数配置中心；
- `sys_hyper` 只保留历史读取，不作为新任务来源；
- 全站时间展示统一为 `YYYY-MM-DD HH:mm:ss`；
- 运行监控前半部分是本机真实 CPU/内存/JVM/磁盘指标，后半部分是分布式派发指标；
- 数据集页面整体报错先查后端/JWT，不要先怀疑某条路径。
