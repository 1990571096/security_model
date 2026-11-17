# 数据库边界、旧工程归档与运行监控收敛计划

更新时间：2026-11-17

状态：**初始化链路已收口、旧工程已归档（2026-11-17）**。已创建并从空库初始化 `security_model`（48 表），已用该库启动后端并验证登录、执行池、知识索引与检索；未迁移旧 `eladmin` 数据库。旧 `eladmin/`、`eladmin-web/` 源码已归档到 Tag `migration-r4-verified` 与 `legacy` 分支并从主分支删除，`sql/eladmin.sql` 保留。阶段 3 五组监控的后端字段与前端页面已实现。

## 1. “生产库迁移”的含义

本文的“生产库”指**实际运行平台所连接的业务数据库**，不一定是线上正式服务器。判断标准不是机器位置，而是：

- 后端启动时 `DB_NAME` 指向哪个库；
- 该库是否已有当前代码所需的表、列和索引；
- 该库中是否有真实历史任务、报告、导出和用户数据。

当前配置默认仍是：

```text
migration-backend/src/main/resources/application.yml:13
${DB_NAME:eladmin}
```

迁移脚本会在目标库增加或创建任务归属字段、执行快照、`run_id`/`attempt`、GPU 租约、导出任务、审计表、调度意图/幂等表、知识库与向量清理状态、Assistant 确认、Outbox、消费账本等对象。缺表或缺列时，对应功能会显式失败，不能把“服务能启动”当作迁移完成。

## 2. 三种场景的决策

| 场景 | 目标库 | 是否迁移旧 `eladmin` | 当前结论 |
| --- | --- | --- | --- |
| 开发、截图演示、论文工程验证 | 新建 `security_model` | 不迁移 | 推荐；旧库保留回退 |
| 继续使用原有任务和历史实验 | `eladmin` 或复制出的业务库 | 需要 | 先备份、审核、回填、验证 |
| 正式部署给其他人使用 | 正式业务库 | 需要 | 脚本纳入版本管理，按发布流程执行 |

当前明确选择第一种：**使用新库 `security_model`，暂不迁移旧 `eladmin`**。不改 `application.yml` 默认值，启动时通过环境变量 `DB_NAME=security_model` 指向新库，避免影响仍连接 `eladmin` 的旧启动方式。

## 3. 新库 `security_model` 初始化清单

执行前确认 MySQL 实例、字符集和账号权限。建议顺序：

1. 创建空库：

```sql
CREATE DATABASE IF NOT EXISTS security_model
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

2. 执行基础 schema 与种子数据。开发库不要手工逐条 `SOURCE`，统一用 `scripts/dev/InitializeSecurityModelDatabase.ps1`：

```powershell
$env:MYSQL_IT_PASSWORD = '<本机 root 密码>'
.\scripts\dev\InitializeSecurityModelDatabase.ps1 -DbHost 127.0.0.1 -Port 3306
```

脚本只允许初始化 `security_model`，顺序为 `sql/eladmin.sql` → 执行快照 → run context → 子集导出 → 审计 → 归属 → GPU 租约 → 调度意图 → Outbox → 幂等 → 知识库 → 向量对账 → Assistant → MVP seed → legacy running task 基线 → 历史样例数据集基线 → VaB 种子。基础 schema 成功后立即写入 `sys_dev_init_marker`；无 marker 且目标 schema 已有**任意表或视图**时拒绝执行，因为 `sql/eladmin.sql` 是 `DROP TABLE IF EXISTS` 后重建，不是幂等脚本。legacy 基线只处理种子任务 ID 3/4（精确名称 + `create_by=test`），其他无 run 上下文的 running 行只输出审核清单；MVP seed 只补缺失，历史算法禁用/菜单显隐需显式执行 `security_model_mvp_legacy_convergence.sql`。

3. 增量脚本顺序以脚本内清单和 `06-mvp-runbook.md` 的“数据库初始化顺序”为准，至少包括：

```text
security_model_execution_snapshot.sql
security_model_task_run_context.sql
security_model_clean_subset_export.sql
security_model_operation_audit.sql
security_model_ownership.sql
security_model_gpu_lease.sql
security_model_gpu_lease_block.sql（仅老库缺 blocked 列时执行）
security_model_task_dispatch.sql
security_model_task_outbox.sql
security_model_request_idempotency.sql
security_model_knowledge.sql
security_model_knowledge_vector_reconcile.sql
security_model_assistant.sql
security_model_mvp_seed.sql
security_model_legacy_running_task_baseline.sql
security_model_dataset_verification_baseline.sql
security_model_vab_seed.sql（由 -VabDatasetRoot 注入 SET @vab_dataset_root）
```

`security_model_mvp_legacy_convergence.sql` 是可选的一次性历史迁移，不在上述自动顺序中；只有明确需要恢复旧算法禁用/菜单显隐行为时才手工执行。`Test-InitializeSecurityModelDatabaseIdempotency.ps1` 会在隔离 schema 完整初始化两次并比较业务行/更新时间，再修改资源后重跑验证保留。

4. 启动前设置：

```text
DB_NAME=security_model
DB_USER=<本机账号>
DB_PWD=<本机密码>
SECURITY_MODEL_JWT_SECRET=<至少 32 字节>
```

5. 启动后验证：

- `GET /actuator/health` 返回 UP，且不泄露连接信息；
- 管理员登录、验证码、JWT 正常；
- 数据集/算法资源可读，任务列表为空但可创建；
- `sys_gpu_lease` 存在且槽位为 released/可领取；
- 知识库、Assistant、导出表存在；
- 创建一条测试任务，确认归属、快照、日志目录和取消链路正常。

6. 记录：库名、字符集、脚本列表、执行人、执行时间、行数核对、健康检查结果、测试任务 ID。脚本使用的密码不写入仓库。

## 4. 迁移旧 `eladmin` 的前置条件

只有需要继续使用历史任务或正式部署时才执行，且必须按以下顺序：

1. **备份**：`mysqldump --single-transaction --routines --triggers`，备份文件放仓库外。
2. **只读核对**：执行 `security_model_ownership.sql` 只读段，输出任务归属候选、unattributed 和 demo 数量。
3. **归属审核**：对历史任务逐条确认 owner，不能把未知归属默认给管理员或当前用户。
4. **回填**：写入 `sys_pre_defense_ownership_decision` 决策行，再在同一 MySQL 会话中设置 `@apply_backfill = 1` 重跑脚本。
5. **验证**：任务、报告、日志、产物、导出、审计、知识库和权限逐项核对；跨用户读写必须仍被拒绝。
6. **留档**：记录备份路径、脚本版本、执行窗口、执行人、行数差异和回滚步骤。

未完成上述步骤前，文档只能写“生产库未迁移”，不能写“权限体系生产完成”或“部署验收通过”。

## 5. 旧工程归档方案（已执行）

当前新后端运行主链路是 `migration-backend`、`migration-frontend`、`algorithm-runtime/vab` 和 `sql`，不依赖旧 `eladmin` Java 工程或 `eladmin-web` 的运行时。旧工程源码已归档到 Tag `migration-r4-verified` 与 `legacy` 分支，主分支只删除目录，不删除历史；`sql/eladmin.sql` 保留。

执行记录：

1. 在初始化链路 CI 全绿的提交 `4dd8005` 上创建并推送 annotated Tag `migration-r4-verified`。
2. 创建并推送 `legacy` 分支，保存 `eladmin/`、`eladmin-web/` 的完整源码。
3. 保留 `sql/eladmin.sql`：MySQL 集成测试和新库初始化仍依赖它作为基础 schema。
4. 确认 Tag 和 `legacy` 分支可推送后，用独立提交从主分支删除 `eladmin/`、`eladmin-web/`（685 个文件）。
5. 同步修改 `README.md`、`02-current-state.md`、`06-mvp-runbook.md`、`11-platform-implementation.md`、`14-framework-migration-plan.md`、项目解析文档和旧版测试命令，明确旧工程已移至 `legacy`。
6. 归档提交后从干净 worktree 重新执行后端测试、初始化幂等与保护脚本；前端和 Python 源码未改动，沿用同一提交前的通过记录。

删除旧目录属于影响面较大的操作。本次提交说明记录“源码已归档，不是功能删除”；Tag 与 `legacy` 分支均已在远端可检出。

## 6. 阶段 3 运行监控页面

目标是把“系统活着”拆成可解释的运行状态，而不是只看一个健康检查。页面至少区分以下五组：

| 分组 | 关键字段 | 数据来源 | 异常判定 |
| --- | --- | --- | --- |
| API 线程池 | active、pool size、queue size、completed、拒绝次数 | `TaskRuntimeMetrics` / Actuator | 队列持续增长或拒绝增加 |
| RabbitMQ Outbox | pending、retry、dead、最老 pending 年龄、发布失败次数 | Outbox 表、Relay 指标 | 最老 pending 超阈值或 dead 增长 |
| Worker 状态 | 是否启动、consumer 是否 ready、最后心跳、归属实例 | Worker 生命周期接口/表 | recovery 未完成或心跳超时 |
| 消费积压 | ready、unacked、消费速率、重投次数 | RabbitMQ 管理接口或消费账本 | ready/unacked 持续上升 |
| GPU 租约 | 槽位持有者、taskId/runId、租约到期时间、隔离槽位 | `sys_gpu_lease`、执行器状态 API | 到期未释放或隔离槽位不空 |

展示要求：

- 五组分区展示，不能把线程池活动线程数当作运行中实验数；
- 每个数字标注采样时间，避免缓存值被当成实时值；
- 管理员可见，普通用户只看自己的任务；
- 指标标签不含 taskId、userId 等高基数标识；
- 数据库或 Broker 不可用时显示“数据不可用/上次刷新时间”，不能显示 0 冒充正常。

实现状态（2026-11-17）：

- 后端 `GET /api/observability/dispatch`（仅管理员）已返回 `mode/role/sampledAt`、Outbox 计数与最久等待、消费账本 `processing/retry/terminal`、过期 claim、Worker 消费者/恢复/归属/本进程 accepted、GPU 租约 `state/taskId/runId/attempt/expiresAt/blockedReason/fencingToken`；`dispatchReady` 来自 `SchedulingReadinessGate`。
- `GET /api/observability/summary` 仍保持聚合，不含 taskId/userId；GPU 明细只放 admin-only `/dispatch`，避免把高基数身份写进指标或普通用户页面。
- 前端 `RuntimeMonitorView.vue` 已改为五组卡片；db 模式显示 Worker“不适用”，Outbox 表不可读显示“不可用”，GPU 表不可读显示“unknown/不可用”，不用 0 冒充。
- 消费积压当前读消费账本，不伪造 RabbitMQ `ready/unacked`；Broker 队列深度尚未接管理接口，页面明确写“Broker 未启用时仍展示表内积压”。
- 单测 `DispatchOperationsServiceTest` 覆盖 db 模式五组字段与 blocked GPU 槽位；尚未跑全量测试与 CI。

## 7. 干净数据库启动手册的验收

手册必须能从空库复现，而不是依赖某台机器的历史状态。验收项：

1. 新建空库并执行第 3 节全部脚本，重复执行一次仍成功。**注意**：`sql/eladmin.sql` 是 DROP 重建，只有 `sys_dev_init_marker` 存在时才跳过它；幂等性由 marker + 增量脚本共同保证，不是“所有脚本都 IF NOT EXISTS”。
2. 后端以 `DB_NAME=security_model` 启动，无缺表/缺列错误。
3. 匿名健康检查、管理员登录、任务创建、取消、报告读取、导出、知识库和 Assistant 预览确认各走一遍。
4. GPU 租约表存在，任务启动前能领取槽位，结束后释放。
5. 停止后端再启动，`ExportRecoveryRunner` 将中断的导出收敛为失败，不自动重派发。2026-11-17 已实测：向空库人工注入 1 条 `running` + 1 条 `pending` 导出行，重启后日志 `scanned=2, recovered=2`，两条分别收敛为 `failed`（`EXPORT_INTERRUPTED` / `EXPORT_NOT_STARTED`）并写入 `security_operation_audit`。这是恢复器的故障注入测试，不是真实导出进程中断；验证后已删除测试导出行、保留审计。
6. 记录脚本清单、执行顺序、耗时、失败点和修复记录，形成可重复的手册。

## 8. 执行状态与证据

| 项 | 状态 | 证据/说明 |
| --- | --- | --- |
| 新库 `security_model` | 已完成（2026-11-17） | 从空库初始化 48 表；日志 `tmp/security-model-init2.log`、幂等复跑 `tmp/security-model-init-rerun.log` |
| 新库启动验证 | 部分完成 | `DB_NAME=security_model` 在 18102 启动，健康检查 UP、管理员登录成功、执行池与 GPU 读取正常；知识索引 23 文档 / 574 chunks，检索返回 5 条引用；导出恢复用注入的 running/pending 行实测收敛为 failed（`scanned=2, recovered=2`）。真实导出进程中断、任务创建/取消/报告读取未在本轮逐项执行 |
| 旧 `eladmin` 迁移 | 未执行 | 未改表、未回填、未备份 |
| `application.yml` 默认库 | 未改 | 仍为 `DB_NAME=eladmin` |
| Tag `migration-r4-verified` | 已执行（2026-11-17） | annotated Tag 已推送，指向归档前提交 `4dd8005` |
| `legacy` 分支/归档仓库 | 已执行（2026-11-17） | `legacy` 分支已推送，含 `eladmin/`、`eladmin-web/` 完整 685 文件；主分支已删除两目录 |
| 旧工程干净检出验证 | 待执行（归档提交后） | 从 `legacy`/Tag 可检出源码；主分支工作树验证见后续记录 |
| 阶段 3 监控页面 | 实现完成，已通过本机全量与 CI | 后端五组字段 + 前端五组卡片；本机 `verify -Pmysql-it` 289 单测 + 106 IT（0 失败/9 环境跳过），前端 `vite build` 通过；提交 `f70711f`，CI run `43995845889` 全绿 |
| 干净库启动手册 | 已形成，部分验收 | `06-mvp-runbook.md` 已补“干净库启动手册”；第 7 节第 3、5 项尚未逐项执行 |

执行后应在 `09-project-status.md` 和本表同步写入真实命令、日志路径、库名、Tag、分支和 run 记录；未执行项不得改成“通过”。

## 9. 边界与回滚

- 不修改旧 `eladmin` 的数据和表结构，除非进入第 4 节的授权迁移流程。
- 不把新库初始化成功写成“生产迁移完成”。
- 不先删除旧源码再补归档；Tag 和 `legacy` 分支必须可检出后才能动主分支。
- 监控页面不得用硬编码数字或 Mock 数据填充。
- 任何数据库脚本先在 `security_model` 或随机隔离 schema 验证，再考虑正式库。
- 出现缺表、缺列、归属回填错误时，停止启动/调度，保留日志和库快照，按备份回滚，不做“先跑起来再说”的绕过。
