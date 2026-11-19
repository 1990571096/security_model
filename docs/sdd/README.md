# SDD 文档索引

本文档集用于驱动“面向数据清洗的安全模型训练系统”的重构迭代。**当前交付状态、测试数字和验收边界的唯一权威入口是 [26-current-delivery-facts.md](26-current-delivery-facts.md)**；README、部署说明和面试材料不得高于代码、配置和测试可证明的范围。后续新增算法、裁剪模块、调整接口或数据库结构时，先更新对应 SDD，再实施代码改动。

## 文档清单

- [01-system-scope.md](01-system-scope.md)：系统目标、用户角色、核心闭环、非目标范围。
- [02-current-state.md](02-current-state.md)：现有项目结构、可复用模块、待裁剪模块、主要技术债。
- [03-target-architecture.md](03-target-architecture.md)：目标架构、领域模型、算法执行边界、任务生命周期。
- [04-refactor-backlog.md](04-refactor-backlog.md)：按优先级拆分的重构任务和验收标准。
- [05-decisions.md](05-decisions.md)：重要设计决策记录。
- [06-mvp-runbook.md](06-mvp-runbook.md)：MVP 数据库、后端、前端和任务闭环联调步骤。
- [07-executor-contract.md](07-executor-contract.md)：算法执行器输入、输出和 Python 进程接入契约。
- [08-local-runtime-notes.md](08-local-runtime-notes.md)：本地 MySQL、Redis、前后端运行环境记录。
- [09-project-status.md](09-project-status.md)：历史状态汇编（各阶段测试数字、故障和结论按当时状态保留）；当前事实引用 26。
- [10-vab-integration.md](10-vab-integration.md)：LDD 训练前防御算法的 VaB 适配、参数和产物约定。
- [11-platform-implementation.md](11-platform-implementation.md)：当前平台实现、业务流程、LDD 执行链路和扩展思路。
- [12-resume-project-narrative.md](12-resume-project-narrative.md)：简历项目描述与面试讲解提纲（历史版本，当前口径引用 26 与学习入口）。
- [13-framework-migration-readiness.md](13-framework-migration-readiness.md)：Spring Boot 3、Java 17、Vue 3 迁移验收记录。
- [14-framework-migration-plan.md](14-framework-migration-plan.md)：框架迁移实施路线、兼容策略和阶段验收标准。
- [16-engineering-improvement-roadmap.md](16-engineering-improvement-roadmap.md)：迁移后质量门禁、可靠任务执行、Redis/MQ 引入条件和迭代验收项。
- [17-agent-development-guide.md](17-agent-development-guide.md)：后续逐轮开发 Agent 的任务范围、依赖门槛、验收条件和交接模板（规划文档，非实现状态）。
- [18-multiuser-ownership-and-resources.md](18-multiuser-ownership-and-resources.md)：数据归属矩阵、共享资源权限方案、历史无 owner 迁移、GPU 并发与租约实现（R0 设计契约 + R1/R2 历史实现记录）。
- [19-local-data-layout-and-retention.md](19-local-data-layout-and-retention.md)：受控本地目录布局与文件保留策略草案（R0 草案）。
- [20-stage-execution-and-acceptance.md](20-stage-execution-and-acceptance.md)：逐阶段执行任务书，明确默认决策、实施顺序、故障用例、验收指标和交接证据；与 17 号总纲配套使用；§6.8 为 R3/R6/R7 逐 ID 实现/部署验收表。
- [21-r4-outbox-rabbitmq-design.md](21-r4-outbox-rabbitmq-design.md)：R4 单机 API + 独立 Worker + Transactional Outbox + RabbitMQ 契约（实现验收通过（隔离）；单机部署演练通过；生产多机未验收；默认关闭）。
- [22-database-boundary-and-convergence-plan.md](22-database-boundary-and-convergence-plan.md)：生产库迁移含义、`security_model` 新库初始化、旧 `eladmin` 归档、阶段 3 监控与干净库启动（当前为历史计划，隔离库已初始化，生产库仍未迁移）。
- [23-r7-scenario-integration-closeout.md](23-r7-scenario-integration-closeout.md)：R7 助手参数策略、模型证据投影、隔离后端 + 真实前端 + 脚本化假模型的浏览器联调证据与未执行边界。
- [24-stage3-single-node-deployment.md](24-stage3-single-node-deployment.md)：阶段 3 单机部署复现与收尾整改：固定依赖版本、干净部署库初始化、db / Rabbit 启停与 broker 故障恢复、liveness/readiness 分离、数据库备份恢复、前端生产静态托管、CI 针对性脚本与托管测试（含历史测试数字）。
- [25-ldd-real-acceptance-checklist.md](25-ldd-real-acceptance-checklist.md)：真实 LDD 验收准备、1 epoch 与正式 5 epochs 工程验收证据；顶部准备阶段“未启动”是历史快照。
- [26-current-delivery-facts.md](26-current-delivery-facts.md)：**当前交付事实唯一入口**：交付口径、当前代码事实、正式 5 epochs 证据、本轮测试结果、R4/部署结论和不可宣称事项。
- [../learning/README.md](../learning/README.md)：从系统架构到源码、测试和运行命令的学习路线。
- [../project-introduction-and-interview-guide.md](../project-introduction-and-interview-guide.md)：完整项目介绍、业务流程、当前进度和面试讲解稿。
- [../resume-project-entry.md](../resume-project-entry.md)：简历项目简介和 4-5 条核心项目要点。

## 迭代原则

1. 先稳定领域边界，再做大规模删除。
2. 安全训练主链路优先于后台管理通用功能。
3. 算法以插件/执行器方式接入，平台只负责编排、参数、数据和结果管理。
4. 训练前防御是第一优先级，训练中防御预留统一任务接口，训练后防御作为扩展项保留但不作为当前主线。
5. 每次代码重构必须能对应到 SDD 中的目标、模块或任务。
