# 01 跟一次页面提交

**目标**是亲眼看到两次操作之间的状态。第一条 POST 创建任务。第二条 POST 在用户核对并确认后才启动。

## 动手路线

1. 按仓库运行说明选择“路径 B：跑通短桩任务”，使用独立的 `security_model` 前缀数据库。先设置短桩和两个 workspace 环境变量，再启动 JVM。验证本地非空演示数据集后，它才会出现在任务可选列表。[启动步骤](../../README.md#路径-b跑通短桩任务)
2. 登录后打开浏览器开发者工具的 Network，勾选 Preserve log，清空记录。进入任务中心，创建一个容易搜索的名称，例如 `learning-first-run-01`，选已验证的数据集和 LDD，参数字段保持 `{}`。
3. 观察 `POST /api/pre-defense` 的 Request Payload、响应状态 `201` 和响应 `taskId`。确认详情显示等待中。点击“启动任务”，在对话框确认后观察 `POST /api/pre-defense/start/{taskId}` 和响应 `200`。记录两个请求各自的 `Idempotency-Key`，遮掉 `Authorization`。
4. 留在详情，观察状态和报告刷新。短桩可能很快完成。若启动失败，先保存错误响应，再按状态复盘。配置预检拒绝启动时，已创建的任务仍留在 `pending`。
5. 选做。用本次隔离库账号执行下面的只读查询，把 `42` 换成本次 `taskId`。一次查询可能只看到最后状态。

```sql
SELECT task_id, task_name, status, progress, current_run_id, current_attempt
FROM sys_pre_defense
WHERE task_id = 42;
```

练习结束后按运行说明停止后端，并执行 `pwsh -File scripts/dev/Stop-FrontendStatic.ps1` 停止默认状态目录对应的前端。若启动时指定了 `-StateRoot`，停止时传同一个值。

## 没装数据库时走源码路线

1. 在任务中心搜 `submitCreate`，看 `createTask` 如何提交参数并打开待启动任务。再跟到 `start` 和 `runCommand`，看用户确认后才调用 `startTask`。从 API 文件确认两条请求的 URL。[页面](../../migration-frontend/src/views/TaskCenterView.vue) · [请求函数](../../migration-frontend/src/api/preDefense.js)
2. 在 Controller 中找 `@RequestMapping`，与 `@PostMapping` 拼成完整路径。`@RequestBody` 负责把 JSON 变成 `PreDefenseTaskCommand`。`@PathVariable` 从启动请求的路径提取 ID。[Controller](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseTaskController.java)
3. 在 `create` 里找到 `requireApprovedPlan`、`setStatus("pending")`、`requireStableIdentity` 和 `insert`。在 `start` 里找 `lockOwned` 与 `launchTask`。顺着 `claimForExecution` 看 SQL 状态条件。[Service](../../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java) · [Mapper](../../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java)

## 对照两条请求

以下值是示意。用自己在资源列表和创建响应中观察到的 ID 替换 `7`、`9` 和 `42`。

```http
POST /api/pre-defense HTTP/1.1
Content-Type: application/json

{"taskName":"learning-first-run-01","datasetId":7,"algorithmId":9,"customParams":"{}"}
```

在 `PreDefenseTaskCommand` 中找到同名字段。再读 `create`，标出哪些值来自表单，哪些值由后端设置。成功得到 `201` 后先停下来查详情。此时能看到 `pending`，还没有执行轮次。随后确认启动。

```http
POST /api/pre-defense/start/42 HTTP/1.1
```

启动命令用路径里的 `42` 找任务配置，认证和幂等头随请求传递。上面的报文省略这些头用于观察业务字段。跟到 `launchTask` 时，把 `runId` 第一次赋值的位置记在工作表里。

## 自测

**题目**。创建和确认启动分别发哪条 POST？创建成功、启动因为未配 Python 失败时，去哪里检查？

**参考答案**。`submitCreate` 调用创建接口取得 `taskId`，用户确认后 `start` 才调用启动接口。创建事务已提交，任务记录仍在。用第一次响应的 ID 查详情或 `sys_pre_defense`，再看启动错误 `EXECUTOR_NOT_CONFIGURED`。配置好后从现有任务启动，保留同一个 `taskId`。

[记录观察](task-notebook.md) · [下一课：JSON 交接](02-java-python.md)
