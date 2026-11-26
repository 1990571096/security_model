# SDD 索引

当前源码核对基线为 `4f304b6` 之后、2026-11-25 的数据集与文档工作区。首次学习请先走 [六课实操](../learning/README.md)。本目录供查设计决策、运行依据和历史验收。

## 当前契约

| 我需要确认 | 当前入口 |
| --- | --- |
| 系统边界、业务对象、技术栈 | [设计思路](../project-design-overview.md) |
| 任务状态、文件协议、恢复及导出 | [实现指南](../project-implementation-guide.md) |
| 按页面动作走完整业务流程 | [业务流程详解](../project-business-flows.md) |
| 运行参数、脚本与本地演示 | [仓库运行说明](../../README.md) |
| 交付结论和对应测试记录 | [交付事实](26-current-delivery-facts.md) |
| 知识与助手的运行方式 | [Agent / RAG 实操](../agent-rag-demo.md) |
| 实验助手技术扩展与验收 | [实验助手开发与验收方案](28-experiment-assistant-development-plan.md)、[实现与实测记录](29-experiment-assistant-acceptance.md) |

## 知识索引可用摘要

**系统范围**。平台支持训练前 LDD 的真实任务执行。任务资源包含可执行数据集、算法和算法参数配置；新任务不选独立超参模板，`sys_hyper` 只兼容历史任务。训练中、训练后页面为展示。数据集启用且解析为 `success` 后才可选用，Tiny ImageNet 可登记但不进入当前 LDD。

**任务与文件协议**。`POST /api/pre-defense` 插入 `pending`，`POST /api/pre-defense/start/{taskId}` 领取运行并写入运行 ID、轮次、快照和意图。Java 将执行请求写为 UTF-8 `request.json`，以 `--request` 传文件路径。Python 适配器读取请求并写 `progress.json` 和 `result.json`。Java 读回文件后用当前运行 ID 和 `running` 条件写入任务。用户按任务 ID 查询报告与日志。清洗 ZIP 需实际产物包含相应比例的筛选索引与可用的 CIFAR-10 数据。

**调度与恢复**。默认 `db` 模式用事务提交后的本机派发。可选 `rabbit` 模式从事务内 Outbox 到 Relay、RabbitMQ 和独立 Worker。Worker 领取单槽 GPU 租约，执行并按消息与业务结果完成消费确认。持久意图支持补派发；已开始或状态不明的运行要先确认进程退出，再决定如何恢复或隔离槽位。

**数据权限与助手**。任务读写由稳定用户身份与归属判定，管理员按业务规则可以跨用户读取。知识检索按权限获取候选并返回章节/行号引用，词法匹配可由 Redis 中的哈希向量辅助重排。助手提供参数校验、任务预检和结果解释。任务写操作由预览与一次性确认触发，确认时仍执行当前业务规则。

## 历史索引

| 编号 | 保留的资料 | 当前学习入口 |
| --- | --- | --- |
| [01](01-system-scope.md)、[02](02-current-state.md)、[03](03-target-architecture.md) | 范围、旧架构分析、目标架构演进 | [设计思路](../project-design-overview.md) |
| [04](04-refactor-backlog.md)、[05](05-decisions.md) | 早期待办与设计决策 | [可靠性课程](../learning/03-reliability.md) |
| [06](06-mvp-runbook.md)、[08](08-local-runtime-notes.md) | MVP 运行记录、本机历史环境 | [运行说明](../../README.md) |
| [07](07-executor-contract.md)、[10](10-vab-integration.md)、[11](11-platform-implementation.md) | 执行器契约、VaB 接入与实现演进 | [实现指南](../project-implementation-guide.md) |
| [09](09-project-status.md)、[16](16-engineering-improvement-roadmap.md)、[20](20-stage-execution-and-acceptance.md) | 阶段状态、路线图与阶段验收 | [交付事实](26-current-delivery-facts.md) |
| [12](12-resume-project-narrative.md) | 早期简历与面试叙述 | [简历](../resume-project-entry.md) |
| [13](13-framework-migration-readiness.md)、[14](14-framework-migration-plan.md)、[15](15-demo-display-upgrade.md) | 框架迁移与展示升级阶段 | [设计思路](../project-design-overview.md) |
| [17](17-agent-development-guide.md)、[23](23-r7-scenario-integration-closeout.md) | R0 至 R7 开发与助手验收 | [助手实操](../agent-rag-demo.md) |
| [18](18-multiuser-ownership-and-resources.md)、[19](19-local-data-layout-and-retention.md) | 权限、单机 GPU、产物保留演进 | [实现指南](../project-implementation-guide.md) |
| [21](21-r4-outbox-rabbitmq-design.md) | R4 派发契约与历史验收 | [可选组件课程](../learning/05-extensions-and-tests.md) |
| [22](22-database-boundary-and-convergence-plan.md)、[24](24-stage3-single-node-deployment.md) | 数据库边界与部署演练 | [运行说明](../../README.md) |
| [25](25-ldd-real-acceptance-checklist.md) | 真实算法验收准备、步骤与结果 | [交付事实](26-current-delivery-facts.md) |

历史页保持原文件名和证据。`KnowledgeIndexService.reindexSdd` 先剔除历史块，再索引当前正文。更新当前事实时优先修改上表对应的主文档，若涉及知识问答，再同步本页或对应 SDD 摘要。
