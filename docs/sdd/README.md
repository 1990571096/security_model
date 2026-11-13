# SDD 文档索引

本文档集用于驱动“面向数据清洗的安全模型训练系统”的重构迭代。实现状态以 SDD 中的“已实现、部分实现、规划中”及验收证据为唯一事实口径；README、部署说明和面试材料不得高于代码、配置和测试可证明的范围。后续新增算法、裁剪模块、调整接口或数据库结构时，先更新对应 SDD，再实施代码改动。

## 文档清单

- [01-system-scope.md](01-system-scope.md)：系统目标、用户角色、核心闭环、非目标范围。
- [02-current-state.md](02-current-state.md)：现有项目结构、可复用模块、待裁剪模块、主要技术债。
- [03-target-architecture.md](03-target-architecture.md)：目标架构、领域模型、算法执行边界、任务生命周期。
- [04-refactor-backlog.md](04-refactor-backlog.md)：按优先级拆分的重构任务和验收标准。
- [05-decisions.md](05-decisions.md)：重要设计决策记录。
- [06-mvp-runbook.md](06-mvp-runbook.md)：MVP 数据库、后端、前端和任务闭环联调步骤。
- [07-executor-contract.md](07-executor-contract.md)：算法执行器输入、输出和 Python 进程接入契约。
- [08-local-runtime-notes.md](08-local-runtime-notes.md)：本地 MySQL、Redis、前后端运行环境记录。
- [09-project-status.md](09-project-status.md)：项目整体目标、当前进度、已完成能力和后续待办。
- [10-vab-integration.md](10-vab-integration.md)：LDD 训练前防御算法的 VaB 适配、参数和产物约定。
- [11-platform-implementation.md](11-platform-implementation.md)：当前平台实现、业务流程、LDD 执行链路和扩展思路。
- [12-resume-project-narrative.md](12-resume-project-narrative.md)：简历项目描述与面试讲解提纲。
- [13-framework-migration-readiness.md](13-framework-migration-readiness.md)：Spring Boot 3、Java 17、Vue 3 迁移验收记录。
- [14-framework-migration-plan.md](14-framework-migration-plan.md)：框架迁移实施路线、兼容策略和阶段验收标准。
- [16-engineering-improvement-roadmap.md](16-engineering-improvement-roadmap.md)：迁移后质量门禁、可靠任务执行、Redis/MQ 引入条件和迭代验收项。
- [17-agent-development-guide.md](17-agent-development-guide.md)：后续逐轮开发 Agent 的任务范围、依赖门槛、验收条件和交接模板（规划文档，非实现状态）。
- [../project-introduction-and-interview-guide.md](../project-introduction-and-interview-guide.md)：完整项目介绍、业务流程、当前进度和面试讲解稿。
- [../resume-project-entry.md](../resume-project-entry.md)：简历项目简介和 4-5 条核心项目要点。

## 迭代原则

1. 先稳定领域边界，再做大规模删除。
2. 安全训练主链路优先于后台管理通用功能。
3. 算法以插件/执行器方式接入，平台只负责编排、参数、数据和结果管理。
4. 训练前防御是第一优先级，训练中防御预留统一任务接口，训练后防御作为扩展项保留但不作为当前主线。
5. 每次代码重构必须能对应到 SDD 中的目标、模块或任务。
