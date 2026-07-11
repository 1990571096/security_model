# MVP 运行与联调手册

## 目标

本手册用于验证当前安全模型训练平台 MVP 是否形成可演示闭环：

- 可登录系统并进入安全训练工作台。
- 可维护数据集、算法、模型、超参数模板。
- 可创建并启动训练前防御任务。
- 可创建并启动训练中防御任务。
- mock 执行器可将任务从 `pending` 推进到 `success`，并写入结果 JSON。

## 数据库初始化

新开发库初始化顺序：

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
```

已有开发库只需要执行：

```sql
SOURCE sql/security_model_mvp_seed.sql;
```

说明：

- `security_model_mvp_seed.sql` 不删除历史菜单和权限数据，只隐藏当前 MVP 不需要的旧后台模块。
- 该脚本会补充内置基线算法和默认超参数模板，并禁用明显测试数据。

当前 MVP 内置算法：

- 训练前防御：`基线-统计异常清洗`、`基线-低置信样本过滤`、`基线-IQR四分位清洗`、`基线-IsolationForest异常清洗`。
- 训练中防御：`基线-鲁棒训练`、`基线-标签平滑训练`、`基线-对抗训练占位`。
- 这些算法当前由 `MockDefenseTaskExecutor` 模拟执行，目的是先验证平台任务流、结果协议和前端展示，后续真实算法接入时复用同一执行器接口。

## 后端验证

编译命令：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am compile '-Denforcer.skip=true'
```

说明：

- 当前跳过 Maven Enforcer 是因为原项目存在既有循环依赖检查问题。
- 本阶段的验收以 `eladmin-system` 及其依赖模块可编译为准。

## 前端验证

构建命令：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin-web
$env:NODE_OPTIONS='--openssl-legacy-provider'
npm run build:prod
```

说明：

- 旧 Vue CLI/Webpack 在 Node 17+ 环境下需要 `--openssl-legacy-provider`。
- 构建中的 asset size 和 Browserslist 过期提示属于旧前端栈的已知警告，不阻塞 MVP 联调。

本地开发服务：

- 当前前端开发服务已启动在 `http://localhost:8014/`。
- 当前后端开发服务已启动在 `http://localhost:8000/`，接口文档入口为 `http://localhost:8000/doc.html`。
- 后端日志位于 `eladmin/backend-dev.out.log` 和 `eladmin/backend-dev.err.log`。
- 前端日志位于 `eladmin-web/dev-server.out.log` 和 `eladmin-web/dev-server.err.log`。
- 日志文件已由 `.gitignore` 的 `*.log` 规则忽略。

## 训练前防御任务验收

前置数据：

- 数据集必须满足 `enabled = 1` 且 `parse_status = 'success'`。
- 算法必须满足 `enabled = 1` 且 `defense_stage = 'pre'`。
- 如果选择超参数模板，模板必须启用且 `algorithm_id` 与任务算法一致。

验收路径：

1. 在“防御场景/训练前防御”中新建任务。
2. 选择可用数据集、训练前算法和可选超参数模板。
3. 启动任务。
4. 观察任务进度从 `running` 到 `success`。
5. 打开任务详情，确认展示清洗后样本数、移除样本数、保留率和清洗结果 JSON。

结果 JSON 需要包含：

- 基础字段：`executor`、`stage`、`status`、`message`、`started_at`、`finished_at`、`artifacts`。
- 清洗统计：`sample_stats` 和 `metrics`。
- 如果任务失败，详情页应展示 `failure_reason`。

本地冒烟记录：

- 2026-07-11 已通过接口启动 `codex-pre-smoke-20260711205832`，任务 `task_id = 8`。
- 任务从 `pending` 成功推进到 `success`，`progress = 100`。
- `cleaning_result` 已写入 `sample_stats`、`metrics`、`cleanSampleCount`、`poisonedSampleCount` 等字段。
- 2026-07-11 已通过接口启动 `smoke_iqr_20260711212417`，任务 `task_id = 9`。
- 任务从 `pending` 成功推进到 `success`，`progress = 100`，`cleaning_result.algorithm_key = iqr_cleaning`。

## 训练中防御任务验收

前置数据：

- 数据集必须满足 `enabled = 1` 且 `parse_status = 'success'`。
- 输入模型必须满足 `status = 'available'` 且 `parse_status = 'success'`。
- 算法必须满足 `enabled = 1` 且 `defense_stage = 'during'`。
- 如果选择超参数模板，模板必须启用且 `algorithm_id` 与任务算法一致。

验收路径：

1. 在“防御场景/训练中防御”中新建训练任务。
2. 选择可用数据集、可用输入模型、训练中防御算法和可选超参数模板。
3. 启动任务。
4. 观察任务进度从 `running` 到 `success`。
5. 打开任务详情，确认展示训练轮数、最终精度、训练日志、损失曲线和精度曲线。

训练日志 JSON 需要包含：

- 基础字段：`executor`、`stage`、`status`、`message`、`started_at`、`finished_at`、`artifacts`。
- 曲线字段：`lossCurve` 和 `accuracyCurve`。
- 如果任务失败，详情页应展示 `failure_reason`。

本地冒烟记录：

- 2026-07-11 已通过接口启动 `codex-during-smoke-20260711205832`，任务 `task_id = 8`。
- 任务从 `pending` 成功推进到 `success`，`progress = 100`。
- `training_log` 已写入 `lossCurve` 和 `accuracyCurve`，曲线长度均为 10。
- 2026-07-11 已通过接口启动 `smoke_label_smoothing_20260711212417`，任务 `task_id = 9`。
- 任务从 `pending` 成功推进到 `success`，`progress = 100`，`training_log.algorithm_key = label_smoothing_training`，`lossCurve` 长度为 10。

## 当前限制

- 任务执行结果来自 `MockDefenseTaskExecutor`，仅用于验证平台流程和前后端协议。
- mock 执行器暂不生成真实输出数据集或输出模型，因此下载输出 artifact 不是当前 MVP 验收重点。
- 训练后防御暂时隐藏，不作为当前“面向数据清洗”的主流程。
- Spring Boot 3、Vue 3 升级已作为目标技术栈记录，但不在当前 MVP 闭环中强制迁移。
