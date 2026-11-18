# 阶段 3：单机部署复现、生命周期演练与收尾整改

Date: 2026-11-18（收尾整改同日记入第 3.5–3.8、4.7 节）

## 1. 目标与边界

本轮目标是固定单机依赖版本与启动方式，从干净隔离数据库启动后端，完成两种派发模式的生命周期演练：

- `db`：单 API 进程，进程内派发；
- `rabbit`：API + Worker 双 JVM，Outbox + RabbitMQ；
- 两种模式都覆盖启动、健康检查、停止、重启；
- `rabbit` 额外覆盖 broker 中断时健康检查转 `DOWN`、broker 恢复后回到 `UP`，以及 Worker 消费者重新连接。

本轮**不包含**：

- 真实外部模型调用；当前没有模型凭据、预算和数据留存授权；
- 真实 LDD/GPU 执行；部署库未配置 `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT`；
- 生产库 `eladmin` 的迁移或写入；
- 生产级 Nginx/TLS/域名/多机高可用；本轮只验证一种无 Nginx 的前端生产静态托管。

2026-11-18 收尾整改新增并已验证：

- 启停脚本的进程身份校验、任务排空检查、失败保留状态与显式强停；
- `/actuator/health/liveness` 与 `/actuator/health/readiness` 分离，readiness 覆盖调度恢复、Worker 归属与消费容器；
- 从隔离源库备份并恢复到另一个全新隔离库，核对关键数据、约束与索引，并用应用读取；
- `scripts/dev/frontend-static-server.mjs` + `Start-FrontendStatic.ps1` / `Stop-FrontendStatic.ps1` 的生产静态托管；
- CI 增加 Windows 进程管理失败路径测试与前端静态托管回归测试。

## 2. 固定依赖版本

本次演练主机（Windows）实际使用的版本：

| 依赖 | 版本/位置 | 用途 |
|---|---|---|
| Temurin JDK | 17.0.21+9 | 后端运行时；`JAVA_HOME` 显式传入 |
| Maven | 3.8.1 | 打包可执行 Spring Boot jar |
| MySQL | 8.0.43 | 干净部署库 |
| RabbitMQ | 4.1.8 | rabbit 模式 broker；R4 测试实例 |
| Erlang OTP | OTP 27 | RabbitMQ 运行时 |
| Redis | 8.4.0 | 缓存；不可用时读路径回源 MySQL |
| Node / npm | 22.23.2 / 10.9.8 | 前端生产构建 |
| Python | 3.13.18 | 适配器测试；本轮不配置 LDD 执行器 |

这些版本是本次证据的环境事实，不是写死在启动脚本里的强制版本。脚本通过 `JAVA_HOME`、`JarPath`、数据库和 Rabbit 参数接收运行环境。

## 3. 可复用启停脚本

新增/重写：

- `scripts/dev/SecurityModelStack.Common.ps1`
- `scripts/dev/Start-SecurityModelStack.ps1`
- `scripts/dev/Stop-SecurityModelStack.ps1`
- `scripts/dev/frontend-static-server.mjs`
- `scripts/dev/Start-FrontendStatic.ps1`
- `scripts/dev/Stop-FrontendStatic.ps1`

启动脚本的约束：

- schema 必须是 `security_model` 或以 `security_model_` 开头，拒绝 `eladmin` 和其他业务库；
- `db` 模式只允许 `Role=api`；`rabbit` 模式支持 `Role=all|api|worker`；
- 默认 `Mode=db`、`Role=api`，无参数运行不会再被自身校验拒绝；
- 必须提供数据库密码和至少 32 字节 JWT secret；
- 默认关闭知识 embedding/vector/model 和 Assistant 外部模型，避免部署演练误触发付费调用；
- `rabbit` 模式设置 `MANAGEMENT_HEALTH_RABBIT_ENABLED=true`，让 `/actuator/health` 包含 broker 连接；`db` 模式设为 `false`；
- 启动前检查目标端口无 `LISTEN`，避免把其他服务误判成新进程；
- `Start-Process` 先把 jar、SDD 等路径统一做引号化，再拼成参数串；含空格的 jar / SDD 路径不会在换机器后被拆成多个参数；
- 分别等待 `/actuator/health/liveness` 和 `/actuator/health/readiness`；只 liveness UP 不代表可以接任务；
- 状态写入 `tmp/deploy-stack/<schema>/<mode>/state.json`，记录每个进程的 `pid`、`processStartTime`、`executablePath`、`commandLine`、`jarPath`、`role`、`port`，以及 state 级 `dbHost/dbPort/dbUser/javaPath/sddRoot`；
- 启动失败时终止本轮已创建的进程，不写 `state.json`。

停止脚本：

- 只读取 `state.json` 中记录的 Java 进程；账号密码优先 `-DbPassword`，否则读 `MYSQL_IT_PASSWORD`；
- 正常停止先查 `sys_pre_defense` 中 `status='running'` 的数量并等待排空，默认 60 秒；未排空拒绝停止并保留 `state.json`；
- 每个进程停止前核对进程名 `java`、可执行文件路径、启动时间 ±5 秒、命令行包含记录 jar；任一不匹配拒绝停止并保留 `state.json`；
- 先递归停止子进程，再停止父进程；
- 正常停止使用不带 `-Force` 的 `Stop-Process`；`Stop-Process` 失败、进程未退出或端口未释放都保留 `state.json`；
- 只有显式 `-Force` 才允许强制终止；`-Force` 仍先做身份校验，不能不核对身份直接杀 PID；
- 停止成功后确认每个记录端口无 `LISTEN`，才写 `state-stopped-<timestamp>.json` 并删除 `state.json`，记录 `stopForced`、`drainedRunningTasks`；
- 不会停止 RabbitMQ broker，broker 生命周期由 `scripts/r4/R4.StartRabbit.ps1` / `R4.StopRabbit.ps1` 单独管理。

### 3.1 打包可执行 jar

必须使用 Spring Boot repackage 后的 jar：

```powershell
$env:JAVA_HOME = '<repo>\tmp\jdk17\extracted\jdk-17.0.21+9'
& '<maven>\bin\mvn.cmd' -B -f migration-backend/pom.xml package -DskipTests
```

注意：`-Dspring-boot.repackage.skip=true` 只产出 thin jar；用它启动会报“没有主清单属性”。本轮第一次启动即因此失败，重新执行 repackage 后恢复。

### 3.2 从干净库初始化

```powershell
$env:MYSQL_IT_PASSWORD = '<本机 root 密码>'
.\scripts\dev\InitializeSecurityModelDatabase.ps1 `
  -SchemaName security_model_deploy_20261118 `
  -DbHost 127.0.0.1 -Port 3306 `
  -MysqlExe 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
```

本次成功输出：

```text
security_model initialized: tables=48
```

未提供 `SECURITY_MODEL_VAB_DATASET_ROOT` 时只登记算法，不伪造数据集路径；干净库的 `GET /api/dataset/available` 为空、任务页 `total=0`，属于预期现象。

### 3.3 db 模式

```powershell
$secret = (Get-Content '<repo>\tmp\deploy-stack\jwt-secret.txt' -Raw).Trim()
.\scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode db -Role api -ApiPort 18200 `
  -SchemaName security_model_deploy_20261118 `
  -DbPassword $env:MYSQL_IT_PASSWORD -JwtSecret $secret `
  -StateRoot '<repo>\tmp\deploy-stack\security_model_deploy_20261118\db'

.\scripts\dev\Stop-SecurityModelStack.ps1 `
  -StateRoot '<repo>\tmp\deploy-stack\security_model_deploy_20261118\db' `
  -DbPassword $env:MYSQL_IT_PASSWORD
```

### 3.4 Rabbit 模式

先用 R4 测试夹具启动本地 broker（vhost/user 来自 `tmp/r4-secrets.json`，该文件不入库）：

```powershell
.\scripts\r4\R4.StartRabbit.ps1 -NodePort 5673 `
  -SecretsPath '<repo>\tmp\r4-secrets.json'
```

再启动 API 与 Worker：

```powershell
$s = Get-Content '<repo>\tmp\r4-secrets.json' -Raw | ConvertFrom-Json
.\scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode rabbit -Role all -ApiPort 18210 -WorkerPort 18211 `
  -SchemaName security_model_deploy_20261118 `
  -DbPassword $env:MYSQL_IT_PASSWORD -JwtSecret $secret `
  -RabbitHost 127.0.0.1 -RabbitPort 5673 `
  -RabbitUser $s.rabbitUser -RabbitPassword $s.rabbitPassword -RabbitVhost $s.vhost `
  -StateRoot '<repo>\tmp\deploy-stack\security_model_deploy_20261118\rabbit'
```

停止：

```powershell
.\scripts\dev\Stop-SecurityModelStack.ps1 `
  -StateRoot '<repo>\tmp\deploy-stack\security_model_deploy_20261118\rabbit' `
  -DbPassword $env:MYSQL_IT_PASSWORD
.\scripts\r4\R4.StopRabbit.ps1
```

### 3.5 存活与调度就绪分离

新增 `SchedulingReadinessHealthIndicator`（bean 名 `schedulingReadiness`），readiness 组从原来的 `readinessState` 扩展为：

```yaml
management.endpoint.health.group.readiness.include: readinessState,schedulingReadiness
```

判定语义：

- 调度门未 ready → `DOWN`，原因 `RECOVERY_NOT_READY`；
- 非 Worker 角色（db API / rabbit API）→ 只需调度门 ready；
- rabbit Worker → 必须持有 `WorkerOwnershipLease`，否则 `WORKER_OWNERSHIP_NOT_HELD`；
- lifecycle 缺失、正在恢复、消费容器未启动分别 `DOWN`；
- 归属、恢复、消费均满足才 `UP`。

`/actuator/health/liveness` 只反映进程存活；`/actuator/health/readiness` 与整体 `/actuator/health` 才反映能否接任务。`DispatchOperationsService.summary()` 增加全局 `runningTasks` / `runningTasksAvailable`，供停止脚本和运维判断排空。

负向场景已实测：在源库把 `default-gpu-0` 置为 blocked，启动脚本在 18200 等待 readiness 45 秒后超时退出 1；同一进程 liveness 返回 200 `UP`，readiness 返回 503 `DOWN`；pid 被清理、端口释放、无 `state.json`。测试后已清除 blocked 行。

### 3.6 数据库备份与恢复

源库 `security_model_deploy_20261118`（48 表）备份到 `tmp/stage3-closeout-backup.sql`（753,305 字节），恢复到全新库 `security_model_restore_20261118a`；创建前确认目标不存在，未覆盖任何已有库。

备份命令：

```powershell
mysqldump -h 127.0.0.1 -P 3306 -u root `
  --single-transaction --quick --routines --triggers --events `
  --hex-blob --set-gtid-purged=OFF --no-tablespaces `
  --result-file=tmp\stage3-closeout-backup.sql security_model_deploy_20261118
```

恢复时若 dump 路径含中文，MySQL `SOURCE` 会报 `Failed to open file ... error: 2`；先把 dump 复制到 ASCII 临时路径再导入，导入完成后删除临时文件。

核对结果：

- 表 48/48、约束 80/80、索引 179/179；
- 关键表合计行数 26/26：`sys_user=3`、`sys_pre_defense=4`、`sys_algorithm=14`、`sys_dataset=5`、`sys_dispatch_outbox=0`、`sys_dispatch_consumption=1`、`sys_task_dispatch_intent=0`、`sys_gpu_lease=1`、`sys_knowledge_chunk=0`、`sys_knowledge_document=0`；
- `mysqlcheck --check security_model_restore_20261118a` 全部 48 表 `OK`；
- 用新脚本在 18201 启动恢复库应用：liveness/readiness UP；`/api/algorithm` 14 条（含 LDD `algorithmId=14`），`/api/dataset` 5 条；`/api/observability/dispatch` 返回 `dispatchReady=true`、`consumptionTerminal=1`、`runningTasks=0`、`gpuSlot.available=true`；`/api/task-progress/page` 返回 `total=0`，原因是种子任务 `owner_user_id` 为空、管理员未开启跨用户读取，不是恢复丢数据；
- 正常停止：`task drain confirmed running=0`、pid 24860 停止、18201 释放，evidence `state-stopped-20261118-103350754.json`。

**备份边界**：数据库备份只包含 MySQL schema 数据；不包含本地产物目录、数据集文件、Python 执行器工作区、RabbitMQ 持久消息。恢复后必须另行恢复这些外部依赖，不能把数据库恢复当成完整平台恢复。

### 3.7 前端生产静态托管

本机没有 Nginx；使用 Node 内置 `http` 的 `scripts/dev/frontend-static-server.mjs`，不依赖 Vite dev server：

- `STATIC_ROOT` 默认 `migration-frontend/dist`，`STATIC_PORT` 默认 18220，`API_TARGET` 默认 `http://127.0.0.1:18200`；
- `/api`、`/auth` 反向代理到后端，保留方法、路径、查询串和请求头；
- 未知前端路由在 `Accept: text/html` 时回退 `index.html`，支持 `createWebHistory()` 的 SPA 刷新；
- `/healthz` 返回 `{"status":"UP","service":"frontend-static"}`；
- 拒绝包含 `..` 的路径穿越；静态资源长缓存，`index.html` 使用 `no-cache`；
- 支持 SIGINT/SIGTERM 优雅关闭。

启停脚本：

```powershell
.\scripts\dev\Start-FrontendStatic.ps1 -StaticPort 18220 -ApiPort 18200 `
  -StateRoot '<repo>\tmp\deploy-stack\frontend'
.\scripts\dev\Stop-FrontendStatic.ps1 -StateRoot '<repo>\tmp\deploy-stack\frontend'
```

启动脚本检查 `dist/index.html`、端口空闲和 node，写入 pid/启动时间/可执行文件/命令行/端口/distRoot/apiPort；停止脚本校验 node 身份、确认端口释放，失败保留 `state.json`，成功写 `state-stopped-<timestamp>.json`。

实测（后端 18200 + 前端 18220）：

- `GET /`、`/dashboard`、`/algorithms/14`、`/tasks` 均 200 且返回 SPA 壳；
- 静态 JS 资源 200，`Content-Type: text/javascript`；
- 通过前端代理 `POST /auth/login` 200，admin 登录成功；
- 通过前端代理 `/api/algorithm` 200、14 条；`/api/observability/dispatch` 200、`dispatchReady=true`；
- 任务报告 `/api/pre-defense/report/1` 200（测试时临时把种子任务 owner 设为 admin，验证后已恢复 `NULL`）；未授权访问返回 403 `ACCESS_DENIED`，不是 502；
- 编码路径穿越返回 404，未读取 `STATIC_ROOT` 之外文件；
- 前端与后端均用新脚本正常停止，18220/18200 无 `LISTEN`。

### 3.8 CI 针对性验证

`.github/workflows/migration-quality.yml` 新增：

- `deployment-scripts`（`windows-latest`）：运行 `scripts/dev/Test-SecurityModelStackScripts.ps1`，覆盖含空格参数引号化、默认 `Mode=db`/`Role=api`、伪造非 Java PID 在 `-Force` 下仍拒绝停止、失败后 `state.json` 保留；
- `frontend`：生产构建后运行 `scripts/dev/Test-FrontendStaticHosting.mjs`，覆盖 `/`、`/dashboard`、`/algorithms/14`、静态资源、不可达 API 目标返回 502、路径穿越拒绝；
- `init-scripts`：语法解析扩展到 `SecurityModelStack.Common.ps1`、前后端启停脚本和脚本测试。

后端 `migration-backend` 的 `verify -Pmysql-it` 同时覆盖 `SchedulingReadinessHealthIndicatorTest` 的 7 个 readiness 用例。

## 4. 演练结果

### 4.1 干净库

- schema：`security_model_deploy_20261118`
- 初始化输出：`tables=48`
- db 模式 `GET /api/dataset/available`：空数组
- db 模式 `GET /api/observability/summary`：任务计数全 0，执行池 `acceptingTasks=true`，GPU 槽位 `idle`
- 初始化保护/幂等回归：均通过
  - `guard test passed: schema=security_model_guardtest_b0e365bd83b1 refused; tables=2 unchanged; sentinels intact`
  - `init idempotency passed: schema=security_model_initidem_6722bdf7c5ae tables=48 twice-identical; operator edits preserved; unknown running row untouched and audited`

### 4.2 db 模式生命周期

| 动作 | 时间 | 结果 |
|---|---|---|
| 第一次启动 | 08:48:43 | pid 36964，`/actuator/health` 200 UP；admin 登录 200；`summary`、`dispatch`、`executor-status` 200 |
| 第一次停止 | 08:54:13 | pid 36964 已退出；18200 无 `LISTEN` |
| 重启 | 08:54:45 | pid 13880，health UP；admin 重新登录 200；`dispatch mode=db role=api dispatchReady=true` |
| 第二次停止 | 08:58:58 | pid 13880 已退出；18200 无 `LISTEN` |

db 模式 dispatch 关键事实：

```json
{"mode":"db","role":"api","dispatchReady":true,"workerConsumerExpected":false,"workerOwned":false,"gpuSlot":{"state":"idle"}}
```

### 4.3 Rabbit 模式生命周期

| 动作 | 时间 | 结果 |
|---|---|---|
| 第一次启动 | 09:03:04 | API 40060 / Worker 5180；两端 health UP |
| 第一次停止 | 09:08:16 | 两个 Java 进程退出；18210/18211 无 `LISTEN` |
| 重启 | 09:08:57 | API 38188 / Worker 39604；两端 health UP；Worker 消费者重新出现 |
| 第二次停止 | 09:18:35 | 两个 Java 进程退出；18210/18211 无 `LISTEN` |
| 健康增强启动 | 09:19:25 | API 38268 / Worker 39312；Rabbit 健康项启用，两端 health UP |
| 最终停止 | 09:28:15 | 两个 Java 进程退出；18210/18211 无 `LISTEN` |

首次 Rabbit 启动后的关键事实：

- `api role=api, mode=rabbit, dispatchReady=true, workerOwned=true`
- `worker role=worker, workerConsumerExpected=true, workerConsumerStarted=true, workerOwned=true`
- Rabbit 队列 `security-model.dispatch.worker`：`messages=0, consumers=1`
- API 端 `workerConsumerExpected=false` 正确：API 不承担消费；Worker 端为 `true`

### 4.4 broker 中断与恢复

在 Rabbit 双进程运行期间停止 broker：

- Worker 日志出现重复的 `java.net.ConnectException: Connection refused` 和 `Restarting Consumer`，说明连接断开后进入重连循环；
- `GET /actuator/health` 在启用 Rabbit 健康项后由 200 UP 变为 **503 DOWN**，API 与 Worker 都返回：
  ```json
  {"status":"DOWN","groups":["liveness","readiness"]}
  ```
- 这是本轮新增的健康门禁：`rabbit` 模式的部署健康检查不再只检查进程存活。

恢复 broker 后：

- Worker 日志出现 `Created new connection ... amqp://r4_test_20261116@127.0.0.1:5673/security_model_r4_20261116a`；
- API 与 Worker 的 `/actuator/health` 均恢复 `200 UP`；
- 队列 `security-model.dispatch.worker` 的 `consumers` 恢复为 `1`；
- 说明 broker 中断不是“进程假活”：健康检查先 DOWN，恢复后消费者重新建立。

**口径提醒**：`workerConsumerStarted` 是消费者生命周期/归属标志，不是 broker 连通性探针。broker 中断初期它可能仍显示 `true`；部署健康判断应以 `/actuator/health` 和队列 `consumers` 为准。

### 4.5 登录与 API 注意点

登录响应中的 `token` 字段已经带 `Bearer ` 前缀：

```text
token prefix='Bearer eyJhb' length=210
Authorization: token as-is -> 200
Authorization: 'Bearer ' + token -> 401
```

部署验证时不要重复拼接 `Bearer`。这是本轮 db 模式第一次 smoke 出现 401 的原因，不是 JWT 失效。

### 4.6 收尾前门禁基线（历史）

以下结果来自 `f1ccd5c`/`b1dba42`，用于保留阶段 3 首次演练证据；本轮收尾后的最新门禁见 4.7。

| 门禁 | 结果 | 证据 |
|---|---|---|
| 后端全量 | 343 单测 + 126 IT，0 失败/0 错误/0 跳过，BUILD SUCCESS | `tmp/stage11-full-verify-2.log` |
| 聚焦 Rabbit IT | `DispatchConfigBoundaryRabbitIT` 1/1 | `tmp/stage11-rabbit-it-focused.log` |
| 初始化保护 | 非空无 marker 库被拒绝，表/哨兵不变 | `tmp/stage11-init-guard-test.log` |
| 初始化幂等 | 两次初始化 `tables=48` 一致，运维修改保留 | `tmp/stage11-init-idempotency-test.log` |
| Python 适配器 | 11/11 | `tmp/stage11-python-tests.log` |
| 前端生产构建 | 1706 modules，exit 0 | `tmp/stage11-frontend-build.log` |
| 远端 CI | run `44219531993`，python/frontend/init-scripts/backend 四 job success | GitHub Actions |
| 启停脚本语法 | 两个脚本 ParseFile 无错误 | 本机解析；CI `init-scripts` 新增 parse step |

全量门禁第一次失败在 `DispatchConfigBoundaryRabbitIT`：该测试只读取 `RABBIT_IT_PORT/USER/PASSWORD`，没有读取 `RABBIT_IT_VHOST`，使用隔离 vhost 时仍连接 `/`，broker 返回 `reply-code=530 NOT_ALLOWED`。补上 `spring.rabbitmq.virtual-host` 映射后聚焦 1/1、全量 126 IT 全绿。该修复是测试环境可复现性问题，不改变生产 Rabbit 配置语义。

### 4.7 2026-11-18 收尾整改结果

| 项目 | 结果 | 证据 |
|---|---|---|
| 启停脚本重写 | 默认 db/api；liveness/readiness 分离；身份校验；排空；显式强停；失败保留 state | `scripts/dev/*`；`tmp/stage3-closeout-db-start.log`、`db-stop.log` |
| 正常停止排空 | `task drain confirmed running=0`；pid 40868 停止；端口释放；state 删除 | `state-stopped-20261118-102652284.json` |
| 身份拒绝 | 伪造当前 pwsh 为 state，`-Force` 仍拒绝（“不是 java 进程”），exit 1、state 保留、进程存活 | `tmp/deploy-stack-scripttest/identity/state.json` |
| readiness 聚焦单测 | 7/7 通过 | `tmp/stage3-closeout-readiness-unit.log` |
| readiness 负向 | liveness 200 UP；readiness 503 DOWN；启动脚本超时 exit 1；pid 清理、端口释放、无 state | `tmp/stage3-closeout-readiness-negative-start.log` / `.err.log` |
| 数据库备份恢复 | 48/48 表、80/80 约束、179/179 索引、关键行 26/26；`mysqlcheck` 全 OK；恢复库应用读取 200 | `tmp/stage3-closeout-backup.sql`、`dump.log`、`restore.log`、`mysqlcheck.log`、`restore-start.log` |
| 恢复库停止 | 排空 running=0；pid 24860 停止；18201 释放 | `state-stopped-20261118-103350754.json` |
| 前端生产构建 | Vite 6.4.3，1706 modules，12.40s，exit 0 | `tmp/stage3-closeout-frontend-build.log` |
| 前端静态托管 | SPA 刷新、静态资源、`/api`/`/auth` 代理、admin 登录、报告 200、路径穿越拒绝 | `tmp/stage3-closeout-frontend-http.log`、`frontend-report.log`、`frontend-start.log`、`frontend-stop.log` |
| CI 脚本测试 | Windows 身份/失败路径、前端静态托管回归已加入 workflow | `.github/workflows/migration-quality.yml`、`Test-SecurityModelStackScripts.ps1`、`Test-FrontendStaticHosting.mjs` |
| Rabbit 新脚本复测 | API pid 40840 / Worker pid 24488；两端 liveness/readiness 与整体 health 均 UP；Worker `workerOwned=true`、`workerConsumerStarted=true`；正常排空后双进程停止、18210/18211 释放 | `tmp/stage3-closeout-rabbit-stack-start2.log`、`rabbit-stack-stop2.log`、`state-stopped-20261118-110507813.json` |
| 本轮全量门禁 | 350 单测 + 126 IT，0 失败/0 错误/0 跳过，BUILD SUCCESS；`RabbitDispatchBrokerIT` 2/2 真实 broker 通过 | `tmp/stage3-closeout-full-verify-final.log` |

本轮收尾后工作区仍未提交；以下事实需与提交后的 CI 结果区分：

- 本地 343 单测 + 126 IT 是 `f1ccd5c`/`b1dba42` 时代结果；本轮新增 readiness 用例后全量为 **350 单测 + 126 IT，0 失败/0 错误/0 跳过**，日志 `tmp/stage3-closeout-full-verify-final.log`。
- Rabbit 双进程本轮已用新脚本复测启动、readiness、排空停止；broker 故障恢复仍沿用旧脚本时代证据，未在本次复测中重新中断 broker。
- 含空格 jar 的完整启动仅做了参数构造测试；未实际用含空格 jar 启动 JVM。
- `-Force` 成功停止真实 Java 进程路径只做了身份拒绝测试；正常停止真实 Java 进程已实测。

## 5. 证据路径

| 证据 | 路径 |
|---|---|
| 干净库初始化日志 | `tmp/stage11-init-deploy.log` |
| 初始化保护测试 | `tmp/stage11-init-guard-test.log` |
| 初始化幂等测试 | `tmp/stage11-init-idempotency-test.log` |
| 可执行 jar 打包 | `tmp/stage11-backend-package.log` |
| 后端全量门禁 | `tmp/stage11-full-verify-2.log`；首次失败 `tmp/stage11-full-verify.log` |
| 聚焦 Rabbit IT | `tmp/stage11-rabbit-it-focused.log` |
| Python 适配器 | `tmp/stage11-python-tests.log` |
| 前端生产构建 | `tmp/stage11-frontend-build.log` |
| 收尾：脚本/readiness/备份/静态托管 | `tmp/stage3-closeout-*.log`、`tmp/stage3-closeout-backup.sql` |
| 收尾：脚本身份拒绝 state | `tmp/deploy-stack-scripttest/identity/state.json` |
| 收尾：db 正常停止 evidence | `tmp/deploy-stack/security_model_deploy_20261118/db-safety/state-stopped-20261118-102652284.json` |
| 收尾：恢复库停止 evidence | `tmp/deploy-stack/security_model_restore_20261118a/db/state-stopped-20261118-103350754.json` |
| 收尾：前端启停 evidence | `tmp/deploy-stack/frontend/state-stopped-20261118-104308532.json` |
| db 启动/停止/重启 | `tmp/stage11-db-start-1.log`、`tmp/stage11-db-stop-1.log`、`tmp/stage11-db-start-2.log`、`tmp/stage11-db-stop-2.log` |
| db API smoke | `tmp/deploy-stack/db-smoke.json` |
| db 状态证据 | `tmp/deploy-stack/security_model_deploy_20261118/db*/state-stopped-*.json` |
| Rabbit broker 启停 | `tmp/stage11-rabbit-start.log`、`tmp/stage11-rabbit-broker-stop.log`、`tmp/stage11-rabbit-broker-restart.log`、`tmp/stage11-rabbit-final-stop.log` |
| Rabbit 双进程启停/重启 | `tmp/stage11-rabbit-stack-start.log`、`tmp/stage11-rabbit-stack-stop-1.log`、`tmp/stage11-rabbit-stack-start-2.log`、`tmp/stage11-rabbit-stack-stop-2.log` |
| Rabbit 健康增强启停 | `tmp/stage11-rabbit-health-start.log`、`tmp/stage11-rabbit-health-broker-stop.log`、`tmp/stage11-rabbit-health-broker-restart.log`、`tmp/stage11-rabbit-stack-stop-3.log` |
| Rabbit API/Worker smoke | `tmp/deploy-stack/rabbit-smoke.json`、`tmp/deploy-stack/rabbit-restart-smoke.json` |
| 队列消费者 | `tmp/stage11-rabbit-queues.log`、`tmp/stage11-rabbit-queues-restart.log`、`tmp/stage11-rabbit-queues-recovered.log`、`tmp/stage11-rabbit-queues-health-recovery.log` |
| broker 中断 worker 日志 | `tmp/deploy-stack/security_model_deploy_20261118/rabbit-health/worker.log` |
| health 恢复记录 | `tmp/deploy-stack/rabbit-health-recovery.json` |

`tmp/` 与 `tmp/r4-secrets.json` 均不入库；文档只记录路径与事实，不复制密码或 JWT secret。

## 6. 未执行与后续

- 未执行真实 LDD/GPU；干净库未配置 Python 执行器，任务启动应返回 `EXECUTOR_NOT_CONFIGURED`，本轮没有用部署库重复该负向链路。
- 数据库备份/恢复、前端常驻静态托管已在收尾整改完成；备份不包含本地产物、数据集文件、Python 工作区和 Rabbit 消息，恢复必须同时恢复这些外部依赖。
- Rabbit 双进程模式已用新启停脚本复测启动、liveness/readiness、Worker 归属/消费者、任务排空和停止；broker 中断恢复仍未在本轮重新注入，第 4.4 节旧证据继续保留。
- 前端静态服务器是 Node 单进程方案，不是 Nginx/TLS/多机高可用；生产需在前面加反向代理、HTTPS、进程守护和日志轮转。
- Rabbit 使用 R4 测试 vhost 和测试用户；生产部署必须替换为独立 vhost、最小权限账号和真实 secret 管理。
- 真实模型联调仍被凭据、预算和数据留存授权阻塞，不能以本地短桩替代真实模型验收。
- 生产凭据注入方式尚未确定；正常停止依赖 MySQL 查询 running 任务，需 `-DbPassword` 或 `MYSQL_IT_PASSWORD`，不得写入仓库。

## 7. 结论

阶段 3 的单机部署复现已在干净隔离库完成，并完成 2026-11-18 收尾整改：

- `db` 与 `rabbit` 两种模式均可启动、停止、重启；
- `db` 模式完成登录与只读管理接口 smoke；
- `rabbit` 模式完成 API/Worker 双进程健康、队列消费者、重启恢复；
- broker 中断时健康检查转为 `503 DOWN`，恢复后回到 `200 UP`，消费者重新建立；
- 启停脚本已加入进程身份校验、任务排空、失败保留状态和显式强停；
- readiness 已覆盖调度恢复、Worker 归属和消费容器，health UP 不再被当成可调度；
- 备份已恢复到全新隔离库并通过数据/约束/索引/应用读取核对；
- 前端生产静态托管已通过 SPA 刷新、代理、登录和报告访问验收；
- 所有收尾演练进程已停止，18200/18201/18220 无 `LISTEN`。

这证明的是**单机部署生命周期与故障恢复**，不是真实模型、真实 LDD 或生产高可用验收。
