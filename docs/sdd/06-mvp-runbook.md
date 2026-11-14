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
SOURCE sql/security_model_assistant.sql;
```

已有开发库：

```sql
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
SOURCE sql/security_model_knowledge.sql;
SOURCE sql/security_model_assistant.sql;
```

seed 脚本不删除历史表和权限数据，只隐藏训练中、训练后及无关后台菜单，并禁用历史 mock baseline。当前展示算法为 `LDD`。

## 后端验证

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am compile '-Denforcer.skip=true'
```

当前后端仍基于 Spring Boot 2.7 和 Java 8。跳过 Maven Enforcer 是因为原项目存在既有循环依赖检查问题。

## 前端验证

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin-web
$env:NODE_OPTIONS='--openssl-legacy-provider'
npm run build:prod
```

当前前端仍基于 Vue 2 和 Vue CLI。构建中的 asset size 与 Browserslist 提示属于旧前端栈已知警告。

2026-09-10 旧 ELADMIN 验证记录：定向测试 42 项、Python 适配器语法检查均通过。该记录属于旧后端历史基线，不计入迁移版覆盖。前端生产构建命令已执行，
但当前 Windows 环境对旧版 Vue CLI 的 `node_modules/.cache` 和构建输出目录写入返回 `EPERM`，
因此本轮不能把前端构建记为通过；该问题属于本地依赖目录权限或文件占用，不是业务编译错误。
本轮验证不启动真实 LDD 训练，也不产生新的论文实验结果。

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

后端测试默认由原项目配置跳过，需要显式使用：

```powershell
mvn -pl eladmin-system -am test "-Denforcer.skip=true" "-Dsecurity-model.skip-tests=false" "-Dtest=CleanSubsetExportTaskServiceImplTest,PreDefenseControllerTest,LddConfigurationPolicyTest,PreDefenseTaskStatePolicyTest,ExecutionFingerprintServiceTest,CleanSubsetExportServiceImplTest,AfterCommitTaskDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

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

### 数据库初始化顺序

只在新开发库执行；生产库 `eladmin` 的迁移仍需要单独授权、执行窗口、备份路径、执行人与回滚方案。

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
SOURCE sql/security_model_request_idempotency.sql;
SOURCE sql/security_model_knowledge.sql;
SOURCE sql/security_model_assistant.sql;
```

所有脚本均使用 `CREATE TABLE IF NOT EXISTS`，可重复执行；回滚脚本只删除本阶段新增表，不删除业务任务。R3 两张表没有外键到任务表，回滚不会级联删除任务。

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
- 指标名：`security_model_task_count`、`security_model_stale_running_tasks`、`security_model_executor_tasks`、`security_model_gpu_slot`、`security_model_recovery_total`、`security_model_task_execution_duration`、`security_model_cache_requests`、`security_model_cache_stale_fill_skipped_total`。
- 指标标签只有 status/state/result/outcome 等低基数枚举；taskId、runId、userId 不得作为标签。
- 日志格式包含 `requestId`、`taskId`、`runId`、`attempt`；请求头 `X-Request-Id` 只接受 8–64 位安全字符，否则生成 UUID。日志不记录 token、数据库密码、完整数据集路径。

### 知识检索与外部模型

- 索引不随启动自动执行。管理员先调用 `POST /api/knowledge/reindex` 导入 `docs/sdd`，再调用 `POST /api/knowledge/algorithms/reindex` 导入启用算法；用户只能调用 `POST /api/knowledge/tasks/{taskId}/index` 索引自己的任务报告摘要。
- 配置前缀为 `security-model.knowledge.*`：`sdd-root`、`max-chunk-chars=800`、`candidate-limit=5000`、`top-k=5`、`min-coverage=0.25`、`min-score=20`。
- 外部模型默认 `security-model.knowledge.model.enabled=false`；`security-model.knowledge.model.provider` 选择一个适配器（默认 `openai-compatible`），新增供应商时只增加实现并切换 provider，不改查询业务。只有同时配置 `base-url`、`name`、`api-key` 才可能调用，且仍受超时、一次重试和引用校验约束。没有供应商/费用/数据留存授权时保持关闭，页面显示 `retrieval_only` 或 `model_not_configured`，不得写成联调完成。
- 验收查询 `POST /api/knowledge/query` 的 `status` 为 `answered`/`retrieval_only`/`unknown`；`citations` 必须带 sourceKey、version、章节和行号。`GET /api/knowledge/status` 只返回 active 文档数。

### 受控实验助手

- 入口：`/assistant` 页面或 `/api/assistant/*`。模型默认 `security-model.assistant.model.provider=disabled`、`enabled=false`；只有实现并选择 `AssistantModel` 适配器后才启用自然语言编排。
- 上限：每请求最多 6 次模型调用、5 次工具调用、60 秒；每用户每日默认 50 次，超出 429；核心任务 API 不受影响。
- 读写边界：`get_parameter_guide`、`get_my_task_summary`、`get_my_report_summary`、`search_knowledge` 只读；`preview_create_task`、`preview_start_task`、`preview_cancel_task`、`preview_retry_task`、`preview_export_task` 只生成预览。
- 写操作必须调用确认接口：预览返回 `confirmationId` 与一次性 `token`，TTL 默认 300 秒；服务端只存 token 的 SHA-256。确认后重校验用户、task/run/attempt、参数哈希和状态，并返回 `confirmationReplayed`。
- 审计表 `sys_assistant_tool_audit` 只存工具名、operator、目标、结果码和耗时；不存 prompt、参数正文或 token。会话 7 天，用户可删除自己的会话。
- 未配置模型时页面明确显示 `model_disabled`，不得写成“助手联调完成”。

### 备份与回滚

备份必须放在仓库外，且不得提交 Git。以下命令只作为模板，路径和数据库名由执行人替换；生产执行前仍需单独授权。

```powershell
# 在 cmd 中执行，避免 PowerShell 对 --result-file 表达式的重定向歧义
cmd /c "mysqldump --single-transaction --routines --triggers -h 127.0.0.1 -P 3306 -u root -p eladmin --result-file=D:\db-backup\eladmin-before-r5.sql"
```

回滚顺序：停止应用 → 确认无写入 → 按需执行 `security_model_assistant_rollback.sql`、`security_model_knowledge_rollback.sql`、`security_model_request_idempotency_rollback.sql`、`security_model_task_dispatch_rollback.sql`、`security_model_gpu_lease_block_rollback.sql`、`security_model_gpu_lease_rollback.sql`。回滚不会删除任务表；生产回滚前必须再次核对备份与业务影响。

### 排障

- Redis 不可用：资源查询应继续回源 MySQL，日志出现缓存降级计数；不要因为缓存失败阻塞任务创建。
- 指标为空：确认管理员 JWT、`management.endpoints.web.exposure.include` 和 `TaskRuntimeMetrics` 的 30 秒刷新是否执行；数据库短暂不可用时保留上次值并标记刷新失败。
- 缓存更新后仍读旧值：检查 key 代际是否在提交后递增；TTL 只是兜底，不能替代代际失效。
- 任务统计包含演示数据：确认查询使用 `data_origin != 'demo'`；普通用户统计只包含本人任务。
