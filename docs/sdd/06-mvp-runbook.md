# LDD MVP 运行与联调手册

## 目标

当前联调只验证 LDD 训练前数据清洗闭环：

- 登录并进入安全训练工作台。
- 维护图像数据集、LDD 算法和超参数模板。
- 创建、启动和查询 LDD 清洗任务。
- 查看任务进度、失败原因、参数快照和实验报告。
- 按 LDD 保留索引导出 CIFAR-10 清洗子集。

## 数据库初始化

新开发库：

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
SOURCE sql/security_model_knowledge.sql;
SOURCE sql/security_model_knowledge_vector_reconcile.sql;
SOURCE sql/security_model_assistant.sql;
```

已有开发库：

```sql
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
SOURCE sql/security_model_knowledge.sql;
SOURCE sql/security_model_knowledge_vector_reconcile.sql;
SOURCE sql/security_model_assistant.sql;
```

MVP seed 只补缺失的算法/模板，不再在每次初始化时禁用历史算法或改写菜单显隐；原历史副作用在 `sql/security_model_mvp_legacy_convergence.sql`，该文件不自动执行。当前目录仍展示 builtin 算法，但创建/启动只接受 `vab://pre-defense/ldd`，其他算法由执行能力校验拒绝。

## 后端验证

```powershell
cd F:\Study\postgraduate\毕业论文\security_model
mvn -B -f migration-backend/pom.xml test
```

迁移版使用 Java 17 / Spring Boot 3.5；单元测试不依赖 MySQL/Redis。

## 前端验证

```powershell
cd F:\Study\postgraduate\毕业论文\security_model
npm --prefix migration-frontend run build
```

迁移版前端使用 Vue 3 / Vite。旧 ELADMIN 的 Spring Boot 2.7 / Vue 2 工程已归档到 Tag `migration-r4-verified` 与 `legacy` 分支，主分支不再提供旧构建命令。2026-09-10 的旧 ELADMIN 定向测试 42 项记录只属于归档基线，不计入迁移版覆盖。

## 迁移版 MySQL 集成测试

迁移版集成测试通过 `MYSQL_IT_HOST`、`MYSQL_IT_PORT`、`MYSQL_IT_USERNAME` 和
`MYSQL_IT_PASSWORD` 连接 MySQL。测试创建唯一命名的 `security_model_it_*` 临时 schema，
只在确认 schema 名称符合该前缀后删除本次创建的 schema，不连接或清理业务库。

```powershell
cd F:\Study\postgraduate\毕业论文\security_model
mvn -B -f migration-backend/pom.xml verify -Pmysql-it
```

默认连接本机 `localhost:3306`，用户名 `root`。本机如使用非空密码，通过
`MYSQL_IT_PASSWORD` 环境变量提供。普通迁移版 `mvn verify` 只执行单元测试，不依赖 MySQL。

## LDD 任务验收

前置数据：

- 数据集满足 `enabled = 1` 且 `parse_status = 'success'`。
- 算法满足 `enabled = 1`、`defense_stage = 'pre'`，且实现路径为 `vab://pre-defense/ldd`。
- 超参数模板如被选择，必须启用且关联同一个 LDD 算法。

验收路径：

1. 在“防御场景 / 训练前防御”中新建任务。
2. 选择图像数据集、LDD 和参数配置。
3. 启动任务，观察状态从 `pending` 到 `running`，再到 `success`、`failed` 或 `canceled`。
4. 失败任务执行重试，确认重新生成执行参数快照。
5. 打开任务详情，确认显示样本统计、LDD 指标、产物和失败原因。
6. 打开实验报告，确认显示执行尝试、快照和清洗结果。
7. 对成功的 CIFAR-10 任务按隔离比例提交清洗子集导出，轮询导出状态至 `success` 后下载 ZIP。
8. 在任务进度页按任务名称搜索，确认搜索条件能够作用于训练前任务列表。
9. 确认运行中的任务不能编辑，非法状态流转和超出 `0-100` 的进度会被拒绝。
10. 确认每次启动或重试都有独立的 `run_id`、`attempt` 和执行目录，不会覆盖历史尝试产物。
11. 对运行中的任务执行取消，确认任务先显示为 `canceled`，且迟到的进度或结果回调不会覆盖该状态。
12. 重启应用，确认遗留训练任务会收敛为可重试失败；导出任务当前没有启动恢复扫描，遗留 `pending` / `running` 记录不会自动重新派发或收敛。
13. 并发提交相同任务和隔离比例时，确认数据库唯一键只保留一条导出记录且只调度一次执行。

结果 JSON 至少包含：

- `executor`、`stage`、`status`、`message`
- `started_at`、`finished_at`
- `run_id`、`attempt`
- `effective_config`
- `metrics`、`sample_stats`
- `artifacts`

失败结果还应包含 `error_code` 和 `failure_reason`。

## 当前限制

- 当前只接入和展示 LDD，不包含 VS、MetaPurifier、训练中防御或训练后防御。
- LDD 工程验证结果不等同于论文正式实验结论。
- 清洗子集导出已使用本地异步线程执行，适合单机演示；正式部署可替换为消息队列和独立 worker。
- LDD 外部工程、Python 环境、数据集路径和产物目录依赖本机配置。
- Spring Boot 3、Vue 3 升级已记录为后续阶段，不在当前闭环中强制迁移。

## 迁移版 R5 运行、观测与备份（2026-11-13）

以下命令针对 Spring Boot 3 迁移版，旧 ELADMIN 命令仅保留为历史记录。

### 数据库边界与开发库决策（2026-11-17）

“生产库迁移”指实际运行平台所连接的业务数据库发生表结构变更，不一定是线上正式服务器。当前 `application.yml:13` 默认仍是 `DB_NAME=eladmin`，本轮未改动该默认值。

开发、截图演示与论文工程验证的当前决策是：**新建独立库 `security_model`，不修改旧 `eladmin`**；旧库保留为原始数据和回退依据，不做历史任务归属回填。启动时通过环境变量 `DB_NAME=security_model` 指到新库。旧 `eladmin` 只在需要继续使用历史任务/正式部署时迁移，且必须先备份、只读核对、审核归属、回填 `owner_user_id`、验证并留档。

```sql
CREATE DATABASE IF NOT EXISTS security_model
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

只在新开发库执行数据库初始化顺序；生产库 `eladmin` 的迁移仍需要单独授权、执行窗口、备份路径、执行人与回滚方案。2026-11-17 已在本机创建并初始化 `security_model`（48 张表），未对 `eladmin` 执行任何建表或回填。

### 数据库初始化顺序

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
SOURCE sql/security_model_operation_audit.sql;
SOURCE sql/security_model_ownership.sql;
SOURCE sql/security_model_gpu_lease.sql;
SOURCE sql/security_model_gpu_lease_block.sql;
SOURCE sql/security_model_task_dispatch.sql;
SOURCE sql/security_model_task_outbox.sql;
SOURCE sql/security_model_request_idempotency.sql;
SOURCE sql/security_model_knowledge.sql;
SOURCE sql/security_model_knowledge_vector_reconcile.sql;
SOURCE sql/security_model_assistant.sql;
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_legacy_running_task_baseline.sql;
SOURCE sql/security_model_dataset_verification_baseline.sql;
-- 可选：先 SET @vab_dataset_root = '<部署机 VaB-main/dataset>'; 再执行，
-- 没有变量时只注册 LDD 算法与超参模板，不写数据集行。
SOURCE sql/security_model_vab_seed.sql;
```

除 `sql/eladmin.sql` 外，增量脚本按 `CREATE TABLE IF NOT EXISTS` / 条件 `ALTER` 编写，正常可重复执行；`sql/eladmin.sql` 开头是 `DROP TABLE IF EXISTS` 后重建，**不是幂等建表脚本**，重复执行会清空业务表，不能对已有数据的库直接执行。开发库统一用 `scripts/dev/InitializeSecurityModelDatabase.ps1`，脚本只允许 `security_model`，并用 `sys_dev_init_marker` 区分“基础 schema 已应用”和“从未初始化”：没有可信 marker 且目标库存在**任意表或视图**时直接拒绝，不再只检查 `sys_pre_defense`；只有 `sys_dev_init_marker` 中 `schema_name` 匹配的记录才算可信 marker。`sql/security_model_dataset_verification_baseline.sql` 把 `sql/eladmin.sql` 自带的历史样例数据集、以及阶段 5 仅凭目录检查写成 `success` 的 VaB 目录登记，从伪造的 `success` 改为 `pending`、清空样本统计，避免它们进入可用列表；该脚本只匹配仍保留原始种子消息的行，管理员真实验证后不会再次覆盖。`sql/security_model_legacy_running_task_baseline.sql` 只收敛 `sql/eladmin.sql` 自带的无 `current_run_id` / `current_attempt` 的 `running` 演示任务：置为 `failed`、`progress=0`，并写入 `EXECUTOR_RESTARTED` / “应用重启导致任务中断”；它绝不写 `success`，也不碰已有 run 上下文的任务，避免干净库启动时恢复扫描把 GPU 槽位永久隔离。回滚脚本只删除本阶段新增表，不删除业务任务。R3 两张表没有外键到任务表，回滚不会级联删除任务。知识库退役向量清理的增量脚本 `security_model_knowledge_vector_reconcile.sql` 也必须执行，否则 `vector_cleanup_status` 等列缺失，退役对账会失败；回滚先执行 `sql/security_model_knowledge_vector_reconcile_rollback.sql`，要求停止调度且无活跃任务，脚本只删除索引和四个清理列，不删业务行。

### 干净库启动手册（2026-11-17）

目标：从空库到可登录、可调度、可检索，全程不改 `eladmin`。

1. 准备 MySQL 8 与空库 `security_model`；脚本自己会 `CREATE DATABASE IF NOT EXISTS`。
2. 用环境变量提供本机 root 密码（不要写入仓库）；VaB 数据集目录是可选项，提供后必须真实存在：
   ```powershell
   $env:MYSQL_IT_PASSWORD = '<本机 root 密码>'
   $env:SECURITY_MODEL_VAB_DATASET_ROOT = '<本机 VaB-main\dataset 绝对路径>'   # 可选
   .\scripts\dev\InitializeSecurityModelDatabase.ps1 -DbHost 127.0.0.1 -Port 3306
   ```
   成功输出应包含 `security_model initialized: tables=48`，并列出 `sys_user` 的 admin/test 等种子账号；提供 VaB 路径时还会输出 `dataset_count=6`、`parsed_dataset_count=0` 与 `LDD (VaB)` 算法行。脚本顺序：`sql/eladmin.sql` → 执行快照 → run context → 子集导出 → 审计 → 归属 → GPU 租约 → 派发意图 → Outbox → 幂等 → 知识库 → 向量对账 → Assistant → MVP seed → legacy running task 基线 → 历史样例数据集重置 → VaB 种子；新库已含 `blocked_at`，所以 `security_model_gpu_lease_block.sql` 会被条件跳过。VaB 种子通过 `SET @vab_dataset_root` 注入路径，脚本会先把反斜杠规整为 `/`、校验目录存在、可读、非空，并检查 `cifar-10-batches-py` 下 `batches.meta`、`data_batch_1..5`、`test_batch` 都存在且非空，然后才写 `sys_dataset`；登记状态固定为 `pending`，`sample_count/feature_count/class_count` 保持 `NULL`，预期 50000/3072/10 只写在说明里，不是实测统计。管理员执行 `POST /api/dataset/{id}/validate` 后才会进入 `GET /api/dataset/available`。
   **完整初始化**也遵循 additive：MVP seed 只补缺失算法/模板，不更新已有行；legacy running 基线只处理 `sql/eladmin.sql` 自带的任务 ID 3/4（精确名称 + `create_by=test`），其他缺少执行上下文的 running 行保持不动，并在初始化输出中列为待人工审核清单。历史 MVP 的算法禁用/模型废弃/菜单显隐已移出自动种子，需要时显式执行 `sql/security_model_mvp_legacy_convergence.sql`（该文件不在初始化列表中）。未接入的 builtin 算法仍可在目录中展示，但 `PreDefenseTaskCommandService` 的 LDD-only 执行白名单会拒绝为其创建/启动任务。
3. 设置启动环境变量（示例，值按本机替换）：
   ```powershell
   $env:DB_NAME='security_model'; $env:DB_USER='root'; $env:DB_PWD='<密码>'
   $env:SECURITY_MODEL_JWT_SECRET='<至少 32 字节>'
   $env:SECURITY_MODEL_DISPATCH_MODE='db'; $env:SECURITY_MODEL_DISPATCH_ROLE='api'
   $env:SECURITY_MODEL_KNOWLEDGE_SDD_ROOT='<repo>\docs\sdd'
   ```
4. 启动后端 jar 或 `spring-boot:run`，确认日志有 `Tomcat started on port 8100` 与 `Started SecurityModelApplication`。
5. 健康检查 `GET /actuator/health` 为 `UP`；管理员登录后依次验证：
   - `GET /api/task-progress/executor-status`：执行池计数与 GPU `default-gpu-0` 状态；
   - `GET /api/observability/dispatch`（admin）：返回 `mode/role/outbox*/consumption*/worker*/gpuSlot` 五组字段；db 模式下 `workerConsumerExpected=false`，消费账本仍应可读；
   - `GET /api/dataset?` 中 `VaB CIFAR-10 Runtime Root` 初始为 `pending`；管理员调用 `POST /api/dataset/{id}/validate` 后才应出现在 `GET /api/dataset/available?datasetType=image`，同时 `GET /api/algorithm/14` 返回 `vab://pre-defense/ldd`；
   - `POST /api/knowledge/reindex` 后再查 `GET /api/knowledge/status`，`activeDocuments` 应大于 0；
   - `POST /api/knowledge/query` 返回引用，`retrieval_only` 表示外部模型未启用，不是故障。
6. 验收口径：空库任务页 `total=0` 是正常的，因为 MVP seed 的 4 条任务 `owner_user_id` 为 NULL，被 R1 归属规则过滤；不要为了“看起来有数据”直接把 owner 改成当前管理员。需要历史任务时走 API 新建或单独做可审计的归属回填。
7. 验收口径要如实标注（2026-11-17 干净库 `security_model_accept_20261117124258`，18103，db/api）：
   - VaB 种子用真实目录初始化，初始化后 6 个数据集全部 `pending`、0 个 `success`；管理员 `POST /api/dataset/6/validate` 后才进入可用列表。重复初始化不覆盖运维已改的路径、参数、启用状态或更新时间。
   - 未配置 `security-model.executor.python.script`（环境变量 `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT`）时，普通 API `start` 返回 400 `EXECUTOR_NOT_CONFIGURED`，任务保持 `pending`、`progress=0`、无 run/attempt/快照/意图/租约；Assistant `preview_start_task` 预览 200，确认也返回同一错误码且任务仍 `pending`；已失败任务 `retry` 返回同一错误码，任务仍 `failed`、attempt/progress/run 不变（`resetForRetry` 未执行）。以上均为真实 HTTP + 真实 MySQL。
   - 跨用户权限：admin 创建的任务，`test` 用户详情与 `start` 均 403。
   - 成功链路只使用短协议桩和合成产物，不运行真实 LDD/GPU：任务 `2122553484081897474` 为 `success/attempt=1`，`GET /api/pre-defense/report/{id}` 200、`cleanDatasetDownload.available=true`；`POST /api/pre-defense/cleaned-dataset/exports/{id}?isolationRatio=75` 202 后导出成功，下载 165 bytes zip；`GET /api/pre-defense/artifact-preview/{id}?artifactPath=...` 200。
   - 取消链路同为短桩：观察到 `running` 后 `POST /api/pre-defense/cancel/{id}`，任务收敛 `canceled`，GPU 槽位 `idle`、租约 `released_at` 非空且无 blocked。此结果只证明隔离环境的平台链路，不能写成真实 LDD 执行验收。
   - 恢复派发和 Rabbit Worker 的配置缺失分支由 `TaskDispatchCoordinatorTest` 单测覆盖（释放租约、失败收敛、不 `markSubmitting`/不 claim），干净库未做端到端故障注入。
   - 导出恢复仍以 2026-11-17 人工注入的 1 条 `running` + 1 条 `pending` 导出行实测：重启后 `scanned=2, recovered=2`，分别收敛 `failed`（`EXPORT_INTERRUPTED` / `EXPORT_NOT_STARTED`）并写审计；这是故障注入，不是真实导出进程中断。
8. 初始化自检（使用隔离 schema，不碰 `security_model` 业务库）：
   ```powershell
   .\scripts\dev\Test-InitializeSecurityModelDatabaseGuard.ps1 `
     -RootPassword $env:MYSQL_IT_PASSWORD -DbHost 127.0.0.1 -Port 3306
   .\scripts\dev\Test-InitializeSecurityModelDatabaseIdempotency.ps1 `
     -RootPassword $env:MYSQL_IT_PASSWORD -DbHost 127.0.0.1 -Port 3306
   ```
   第一个脚本验证“无可信标记的非空库”被拒绝且哨兵表/行/表数不变；第二个脚本在隔离库完整初始化两次，比较业务行与 `update_time` 完全一致，再修改路径、参数、启用状态并插入未知 running 行，确认第三次初始化不覆盖且只输出审核清单。CI 的 `init-scripts` job 运行同样两项，适用于 `scripts/dev/**` 变更。

### 启动与停止

1. 复制 `.env.example` 为不入库的 `.env`，填入本机数据库密码和至少 32 字节的 `SECURITY_MODEL_JWT_SECRET`。
2. 在 PowerShell 中将变量加载到当前进程，不要把密码写入脚本或仓库。
3. 启动：`mvn -B -f migration-backend/pom.xml spring-boot:run`
4. 前端：`cd migration-frontend && npm ci && npm run dev`
5. 停止：在运行窗口按 `Ctrl+C`；确认 8100 端口没有残留 Java 进程后再重启。
6. 启动后先访问匿名健康检查 `GET http://localhost:8100/actuator/health`，只返回状态，不泄露数据库连接或环境变量。

### 观测接口与权限

- `GET /actuator/health`：匿名可读，`show-details=never`。
- `GET /actuator/metrics`、`GET /actuator/info`：仅管理员 JWT；匿名返回 401。
- `GET /api/observability/summary`：仅管理员；返回任务状态计数、执行池、GPU 槽位和缓存计数，不返回 taskId/userId/路径。
- `GET /api/observability/dispatch`：仅管理员；返回阶段 3 五组运行事实：`mode/role/sampledAt`、API 线程池以外的 Outbox 计数与最久等待、Worker 消费者/归属/本进程 accepted、消费账本 `processing/retry/terminal` 与过期 claim、GPU 租约 `state/taskId/runId/attempt/expiresAt/blockedReason`。该接口是运维明细，允许 taskId/runId；`/summary` 保持聚合不含身份。`dispatchReady` 为 false 时表示启动恢复未完成，不应把任务启动失败当成普通 500。
- `GET /api/dashboard/overview?days=7`：登录用户可读，按 `TaskAccessGuard` 范围过滤（普通用户仅本人、管理员跨用户读开启时全库）。返回 `tasks` 状态计数、`taskTrend` 近 N 日按 `create_time` 分日的真实计数、`resources` 数据集/算法登记数、`reportReadiness` 有清洗结果的成功任务数、`recentTasks` 最近 5 条和执行池摘要。`metrics.available=false` 时必须显示“未采集”，不得用 0 或演示常量代替攻击成功率/干净准确率。
- 不可用态必须和 0 分开：`outboxAvailable=false`（R4 表未迁移）、`gpuSlot.available=false`（租约表不可读）、`workerConsumerExpected=false`（db 模式无独立 Worker）分别显示为“不可用/不适用”，不能显示成 0 个积压或 0 个消费者。
- 指标名：`security_model_task_count`、`security_model_stale_running_tasks`、`security_model_executor_tasks`、`security_model_gpu_slot`、`security_model_recovery_total`、`security_model_task_execution_duration`、`security_model_cache_requests`、`security_model_cache_stale_fill_skipped_total`。
- 指标标签只有 status/state/result/outcome 等低基数枚举；taskId、runId、userId 不得作为标签。
- 日志格式包含 `requestId`、`taskId`、`runId`、`attempt`；请求头 `X-Request-Id` 只接受 8–64 位安全字符，否则生成 UUID。日志不记录 token、数据库密码、完整数据集路径。

### 知识检索与外部模型

- 索引不随启动自动执行。管理员先调用 `POST /api/knowledge/reindex` 导入 `docs/sdd`，再调用 `POST /api/knowledge/algorithms/reindex` 导入启用算法；用户只能调用 `POST /api/knowledge/tasks/{taskId}/index` 索引自己的任务报告摘要。
- 配置前缀为 `security-model.knowledge.*`：`sdd-root`、`max-chunk-chars=800`、`candidate-limit=5000`、`top-k=5`、`min-coverage=0.30`、`min-score=20`。
- 外部模型默认 `security-model.knowledge.model.enabled=false`；`security-model.knowledge.model.provider` 选择一个适配器（默认 `openai-compatible`），新增供应商时只增加实现并切换 provider，不改查询业务。只有同时配置 `base-url`、`name`、`api-key` 才可能调用，且仍受超时、一次重试和引用校验约束。没有供应商/费用/数据留存授权时保持关闭，页面显示 `retrieval_only` 或 `model_not_configured`，不得写成联调完成。
- 验收查询 `POST /api/knowledge/query` 的 `status` 为 `answered`/`retrieval_only`/`unknown`；`citations` 必须带 sourceKey、version、章节和行号。`GET /api/knowledge/status` 只返回 active 文档数。

### 受控实验助手

- 入口：`/assistant` 页面或 `/api/assistant/*`。模型默认 `security-model.assistant.model.provider=disabled`、`enabled=false`；只有实现并选择 `AssistantModel` 适配器后才启用自然语言编排。
- 上限：每请求最多 6 次模型调用、5 次工具调用、60 秒；每用户每日默认 50 次，超出 429；核心任务 API 不受影响。
- 读写边界：`get_parameter_guide`、`get_my_task_summary`、`get_my_report_summary`、`search_knowledge` 只读；`preview_create_task`、`preview_start_task`、`preview_cancel_task`、`preview_retry_task`、`preview_export_task` 只生成预览。
- 写操作必须调用确认接口：预览返回 `confirmationId` 与一次性 `token`，TTL 默认 300 秒；服务端只存 token 的 SHA-256。确认后重校验用户、task/run/attempt、参数哈希和状态，并返回 `confirmationReplayed`。
- 审计表 `sys_assistant_tool_audit` 只存工具名、operator、目标、结果码和耗时；不存 prompt、参数正文或 token。会话 7 天，用户可删除自己的会话。
- 未配置模型时页面明确显示 `model_disabled`，不得写成“助手联调完成”。

### 2026-11-15 隔离库收口演练

目标：不用生产库 `eladmin`，在随机隔离 schema 中验证“全新初始化 → Spring 上下文启动 → 登录/JWT → 助手预览确认 → 报告/任务读取”以及跨用户隔离。

```powershell
# 在仓库根执行；MYSQL_IT_PASSWORD 只放在当前进程环境，不写入文件
$env:MYSQL_IT_PASSWORD = '123456'
$env:REDIS_IT = 'true'
mvn -B -f migration-backend/pom.xml test -Pmysql-it `
  "-Dtest=OwnershipEndToEndIT,AssistantConfirmationEndToEndIT"
```

实际依赖与结果（2026-11-15 本机）：
- 每个 IT 通过 `OwnershipE2ESupport.schemaUrl` 创建随机 schema，执行 `sql/eladmin.sql`、`security_model_mvp_seed.sql` 及 R1/R2/R3/R6/R7 增量脚本，结束后删除 schema；不触碰 `security_model_demo` 或 `eladmin`。
- `OwnershipEndToEndIT` 10 项通过：alice/bob 登录后读取各自任务、跨用户 403、禁用账号立即失效、旧 token 拒绝、管理员观测鉴权、导出/产物归属、审计查询归属；报告内容由任务详情与任务进度接口读取（当前没有独立的 `/api/report` 端点）。
- `AssistantConfirmationEndToEndIT` 22 项通过：真实 HTTP + MySQL 下的五类写工具首次/重复/并发确认、预览零写入、资源变化 409、导出跨用户下载 403、insert 阻塞超时、错误 token 篡改、alice/bob 并发身份隔离。
- 日志：`tmp/round2-isolated-drill.log`。本轮未启动独立 `spring-boot:run` 端口、未打开浏览器；这是全 Spring 上下文 + MockMvc 的隔离库演练，不是人工端到端 UI 验收。

业务库迁移必须单独记录：本演练只证明脚本在空 schema 可重复执行；生产 `eladmin` 的备份、执行窗口、执行人、逐表行数核对和回滚仍需要单独授权，不能把本节写成“生产库已迁移”。

### 2026-11-15 P1/P2/R3 收口验证

不启动真实 LDD、不占 GPU、不改生产库；以下命令在仓库根执行，密码只放当前进程环境。

```powershell
$env:MYSQL_IT_PASSWORD = '123456'
$env:REDIS_IT = 'true'

# P1 外发路径：单字符/数字/隐藏/空格目录 + 两轮最终 body
mvn -B -f migration-backend/pom.xml test -Pmysql-it `
  "-Dtest=KnowledgeSanitizerTest,AssistantOutboundPrivacyTest"

# P2 退役向量：真实 MySQL + Redis 的跨批/失败不阻塞/重启/幂等
mvn -B -f migration-backend/pom.xml verify -Pmysql-it `
  "-Dtest=KnowledgeRetrievalMySqlIT"

# P2 迁移：升级两次、回滚两次、再升级
mvn -B -f migration-backend/pom.xml verify -Pmysql-it `
  "-Dtest=KnowledgeVectorCleanupMigrationIT"

# R3 真实执行器：完整 Spring 异步代理 + 真实 PythonLddTaskExecutor + 短协议桩
mvn -B -f migration-backend/pom.xml verify -Pmysql-it `
  "-Dtest=R3ProductionExecutorChainIT"
```

实际结果（2026-11-16 四项阻断修复后本机）：P1 10 项通过（`tmp/round3-p1.log`）；`KnowledgeRetrievalMySqlIT` 8/8（`tmp/stage2-fix-knowledge-it.log`，含历史-only 退役不可召回与向量清理补偿）；迁移 1/1；R3 `R3ProductionExecutorChainIT` 5/5；R4 定向 51/51（`tmp/stage2-fix-targeted2.log`，含 `WorkerConsumerLifecycleTest` 8、`DispatchMessageHandlerTest` 12、`RabbitDispatchTransportTest` 5、`PythonLddTaskExecutorCleanupOrderTest` 3）、`RabbitDispatchBrokerIT` 2/2；全量门禁 Temurin 17.0.21 为 **287 单测 + 106 IT**（`tmp/stage2-full-verify-new.log`），Python 11/11，前端构建历史通过（仍有 >500 kB chunk 警告）。真实 RabbitMQ 已执行 R4-01..08，含 7s 延迟 confirm、db→rabbit→db 双向停机切换、旧 dead 与新 attempt 真实竞争；**实现验收通过（隔离），部署验收未执行**，长期 DB 故障/多任务退避与跨主机仍是压力边界。提交 `ee10704`/`cd5a6ae`，run `43846823071` 全绿。

边界：R3 的 Python 是短协议桩，不运行 LDD、不占 GPU；崩溃窗口测试的 `R3ProductionCrashChildMain` 是测试最小上下文 + 手动异步包装，不是完整 Spring 容器。以上只证明隔离环境链路，不是生产库迁移或生产部署验收。

### CI 与 Java 17 证据

- 本机 Temurin JDK 17.0.21 已跑 `mvn verify -Pmysql-it`，2026-11-16 四项阻断修复后计数为 **287 单测 + 106 IT**；日志 `tmp/stage2-full-verify-new.log`，此前 277+104 为阶段 2 收尾版 `tmp/stage2-full-verify-final3.log`，267+103 为 R4 六项整改版 `tmp/r4-final-verify-new.log`，263+100 为旧代码日志 `tmp/r4-final-verify.log`。这只证明本机 Java 17 运行；本轮提交 `ee10704`/`cd5a6ae` 的远端 run `43846823071` backend/python/frontend 全绿；green CI 不等于生产部署或 R4 全部故障场景通过。Maven 成功但 shell 退出 1 的问题已用 `scripts/r4/R4.Maven.ps1` 修正。
- 远端 `Migration Quality` 对 `930c934` 曾失败（run `43686578054`）：`python` 与 `frontend` 成功，`backend` 在单测阶段因 `AttemptProcessRegistryTest`/`PythonProcessWaiterTest` 硬编码 Windows `cmd`、`ping -n`，以及 `PythonProcessWaiter` 销毁进程后未等待退出而失败；已改为按 OS 选择 `sh -c 'sleep 20'`/`sh -c 'exit 0'` 或 `cmd /c exit 0`/`ping -n 20`，并在 `destroyProcessTree` 后 `waitFor(2s)`。
- 跨平台修复推送后，提交 `106e4d3` 的 run `43707281672` 已全部 success（backend/frontend/python）；本机定向日志 `tmp/round2-cross-platform.log`。2026-11-15 收口提交 `bb4a79f` 的 run `43726078023` backend/frontend/python 全绿；此前 `5e4efd4` 的 run `43712479071` 曾因知识评估未知题误命中导致 backend failure，修复记录见 `05` ADR-025 与 `09` §5.15.1。远端 CI 通过仍不等于生产部署验收。

### 备份与回滚

备份必须放在仓库外，且不得提交 Git。以下命令只作为模板，路径和数据库名由执行人替换；生产执行前仍需单独授权。

```powershell
# 在 cmd 中执行，避免 PowerShell 对 --result-file 表达式的重定向歧义
cmd /c "mysqldump --single-transaction --routines --triggers -h 127.0.0.1 -P 3306 -u root -p eladmin --result-file=D:\db-backup\eladmin-before-r5.sql"
```

知识库退役向量清理列的回退（仅在已停止调度、无活跃任务时执行；先备份）：

```sql
SOURCE sql/security_model_knowledge_vector_reconcile_rollback.sql;
```

该脚本可重复执行，只删除 `idx_knowledge_document_vector_cleanup` 与 `vector_cleanup_*` 四列，不删除 `sys_knowledge_document` 业务行；重新升级再次执行 `security_model_knowledge_vector_reconcile.sql`。生产库执行前仍需单独授权。

回滚顺序：停止应用 → 确认无写入 → 按需执行 `security_model_assistant_rollback.sql`、`security_model_knowledge_rollback.sql`、`security_model_request_idempotency_rollback.sql`、`security_model_task_dispatch_rollback.sql`、`security_model_gpu_lease_block_rollback.sql`、`security_model_gpu_lease_rollback.sql`。回滚不会删除任务表；生产回滚前必须再次核对备份与业务影响。

### 排障

- Redis 不可用：资源查询应继续回源 MySQL，日志出现缓存降级计数；不要因为缓存失败阻塞任务创建。
- 指标为空：确认管理员 JWT、`management.endpoints.web.exposure.include` 和 `TaskRuntimeMetrics` 的 30 秒刷新是否执行；数据库短暂不可用时保留上次值并标记刷新失败。
- 缓存更新后仍读旧值：检查 key 代际是否在提交后递增；TTL 只是兜底，不能替代代际失效。
- 任务统计包含演示数据：确认查询使用 `data_origin != 'demo'`；普通用户统计只包含本人任务。
