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
```

已有开发库：

```sql
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
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

2026-09-10 验证结果：后端定向测试 32 项、前端生产构建和 Python 适配器语法检查均通过。
本轮验证不启动真实 LDD 训练，也不产生新的论文实验结果。

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
12. 重启应用，确认运行中的导出任务被收敛为可重试失败，等待中的导出任务会重新派发。
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
