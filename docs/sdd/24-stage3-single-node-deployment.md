# 阶段 3：单机部署复现与 db / Rabbit 生命周期演练

Date: 2026-11-18

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
- 前端生产静态服务托管；前端只保留 `npm run build` 构建门禁；
- 数据库备份/恢复演练；本轮记录命令与边界，不宣称备份验收完成。

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

新增：

- `scripts/dev/Start-SecurityModelStack.ps1`
- `scripts/dev/Stop-SecurityModelStack.ps1`

启动脚本的约束：

- schema 必须是 `security_model` 或以 `security_model_` 开头，拒绝 `eladmin` 和其他业务库；
- `db` 模式只允许 `Role=api`；`rabbit` 模式支持 `Role=all|api|worker`；
- 必须提供数据库密码和至少 32 字节 JWT secret；
- 默认关闭知识 embedding/vector/model 和 Assistant 外部模型，避免部署演练误触发付费调用；
- `rabbit` 模式设置 `MANAGEMENT_HEALTH_RABBIT_ENABLED=true`，让 `/actuator/health` 包含 broker 连接；`db` 模式设为 `false`；
- 启动后轮询 `/actuator/health`，要求 HTTP 200 且 `"status":"UP"`；
- 启动前检查目标端口无 `LISTEN`，避免把其他服务误判成新进程；
- 状态写入 `tmp/deploy-stack/<schema>/<mode>/state.json`，记录 pid、端口、启动时间。

停止脚本：

- 只读取 `state.json` 中记录的 Java 进程，先递归停止其子进程，再停止父进程；
- 若 pid 已被非 Java 进程复用则拒绝停止；
- 停止后把状态改名为 `state-stopped-<timestamp>.json`，保留审计证据；
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
  -StateRoot '<repo>\tmp\deploy-stack\security_model_deploy_20261118\db'
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
  -StateRoot '<repo>\tmp\deploy-stack\security_model_deploy_20261118\rabbit'
.\scripts\r4\R4.StopRabbit.ps1
```

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

### 4.6 门禁结果

| 门禁 | 结果 | 证据 |
|---|---|---|
| 后端全量 | 343 单测 + 126 IT，0 失败/0 错误/0 跳过，BUILD SUCCESS | `tmp/stage11-full-verify-2.log` |
| 聚焦 Rabbit IT | `DispatchConfigBoundaryRabbitIT` 1/1 | `tmp/stage11-rabbit-it-focused.log` |
| 初始化保护 | 非空无 marker 库被拒绝，表/哨兵不变 | `tmp/stage11-init-guard-test.log` |
| 初始化幂等 | 两次初始化 `tables=48` 一致，运维修改保留 | `tmp/stage11-init-idempotency-test.log` |
| Python 适配器 | 11/11 | `tmp/stage11-python-tests.log` |
| 前端生产构建 | 1706 modules，exit 0 | `tmp/stage11-frontend-build.log` |
| 启停脚本语法 | 两个脚本 ParseFile 无错误 | 本机解析；CI `init-scripts` 新增 parse step |

全量门禁第一次失败在 `DispatchConfigBoundaryRabbitIT`：该测试只读取 `RABBIT_IT_PORT/USER/PASSWORD`，没有读取 `RABBIT_IT_VHOST`，使用隔离 vhost 时仍连接 `/`，broker 返回 `reply-code=530 NOT_ALLOWED`。补上 `spring.rabbitmq.virtual-host` 映射后聚焦 1/1、全量 126 IT 全绿。该修复是测试环境可复现性问题，不改变生产 Rabbit 配置语义。

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
- 未执行数据库备份/恢复演练；R5-06 的“备份”只保留命令和边界，不能据此宣称备份验收完成。
- 未把前端 `dist` 纳入常驻服务；本轮只要求前端生产构建通过，静态托管方式待部署环境确定。
- Rabbit 使用 R4 测试 vhost 和测试用户；生产部署必须替换为独立 vhost、最小权限账号和真实 secret 管理。
- 真实模型联调仍被凭据、预算和数据留存授权阻塞，不能以本地短桩替代真实模型验收。

## 7. 结论

阶段 3 的单机部署复现已在干净隔离库完成：

- `db` 与 `rabbit` 两种模式均可启动、停止、重启；
- `db` 模式完成登录与只读管理接口 smoke；
- `rabbit` 模式完成 API/Worker 双进程健康、队列消费者、重启恢复；
- broker 中断时健康检查转为 `503 DOWN`，恢复后回到 `200 UP`，消费者重新建立；
- 所有演练进程已停止，18200/18210/18211/5673/15672 均无 `LISTEN`，无残留 Java/Erlang/RabbitMQ 进程。

这证明的是**单机部署生命周期与故障恢复**，不是真实模型、真实 LDD 或生产高可用验收。
