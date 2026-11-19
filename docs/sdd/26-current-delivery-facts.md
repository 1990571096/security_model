# 当前交付事实（唯一权威入口）

> 本文件是当前交付状态、测试口径和验收边界的唯一权威入口。
> 其他文档只链接本文件，不各自维护容易过期的“最新数字”。
> 历史状态保留在 `09-project-status.md`、`21-r4-outbox-rabbitmq-design.md`、
> `24-stage3-single-node-deployment.md` 等文件中，但必须标注为历史快照。

## 1. 交付口径

- 交付物：**单机实验平台工程交付版**。
- 当前结论：**单机交付版的文档收口、干净检出、隔离库端到端短验收和集成门禁已完成，代码冻结于 `907e30e`。**
- 本文件所在的证据更新提交只修改交付文档，不改变冻结代码。
- 正式 5 epochs 验收只支持以下结论：工程执行链路、配置快照、产物、GPU 租约、进程收尾和验收脚本闭环。
- 不得据此宣称：论文效果验证完成、生产级多机部署完成、真实外部模型生产联调完成、生产库 `eladmin` 已迁移。

## 2. 当前代码与运行事实

| 项 | 当前事实 |
|---|---|
| 后端 | Java 17 编译目标、Spring Boot 3.5、Spring Security 6、JWT、MyBatis-Plus |
| 前端 | Vue 3、Vite 6、Element Plus、Pinia、Vue Router 4 |
| 事实数据源 | MySQL；任务、状态机、快照、租约、Outbox 账本均以数据库为准 |
| 缓存 | Redis；资源目录缓存和可选知识向量检索，缓存不可用时回源 MySQL |
| 派发 | 默认 `dispatch.mode=db`，事务提交后由进程内有界执行池调度 |
| RabbitMQ | 可选 `dispatch.mode=rabbit`；Outbox + Relay + 独立 Worker + 手动 ACK；默认不启用 |
| Python 执行器 | `PythonLddTaskExecutor` 调用 `algorithm-runtime/vab/algorithm_entry.py`，真实 VaB 目录在仓库外 |
| 数据库默认 | `DB_NAME` 默认 `security_model`；初始化/启停脚本只接受 `security_model` 前缀隔离库，拒绝 `eladmin` |
| 认证 | `SECURITY_MODEL_JWT_SECRET` 至少 32 字节，缺失或过短时拒绝启动 |
| 冻结代码提交 | `907e30e1c5354aa3445d1a2e6548dd290a3de44e` |

## 3. 正式 5 epochs 工程验收

| 项 | 结果 |
|---|---|
| 任务 | `2122901116713205761` |
| run / attempt | `37204a64-2b3d-4c72-8cb1-5b7bc2617dd9` / 1 |
| 状态 | `success`、`progress=100` |
| 时间 | 2026-11-18 17:03:48.372 至 17:09:12.986（UTC+8），约 324.6 秒 |
| 实际轮数 | 原始日志含 `Epoch: 0..4` |
| 验收 | `acceptance.state=passed`，80 项检查，0 失败 |
| 产物 | 16 个文件、46,351,536 B；模型 `0.1.pth` 44,767,430 B |
| 指标 | clean_purity 5%–30% 为 100.00%，35%–60% 为 99.99% |
| GPU 租约 | `default-gpu-0`，17:03:47.786 获取，17:09:13.513 释放，`blocked_at=NULL` |
| 运行期资源 | 最低空闲 RAM 4.13 GB；GPU 峰值 1,153 MiB；利用率峰值 100% |
| 证据包 | `F:\security-model-data\ldd-acceptance-20261118\formal-freeze-20261118\formal-run-20261118` |

关键证据文件：

- `formal-5epochs-run-summary.json`
- `report-final.json`
- `logs-final.json`
- `runtime-samples.csv`
- `artifact-sha256.json`

验收汇总：`F:\Study\postgraduate\毕业论文\security_model\tmp\ldd-formal-5epochs-20261118-run1\run-summary.json`。

正式运行代码为 `4c544c4`；文档提交为 `3c86a12`；对应 CI run `44274820789` 的五个 job 全部成功：
<https://github.com/1990571096/security_model/actions/runs/44274820789>。
该 CI 是正式冻结时的记录，不是本轮文档修改后的 CI 结果。

## 4. 本轮（2026-11-19）单机交付短验收

### 4.1 候选提交链

| 提交 | 内容 |
|---|---|
| `c8fe24935bca84391a7c023358a2a17f5f10c68c` | 文档收口：README 拆“只启动看界面 / 跑通短桩任务”两条路径；新增 `docs/learning/README.md`；新增本文件；默认库名改为 `security_model`；`06`、`22` 的 `eladmin` 标注历史 |
| `5469f99a8188153874a4ea21cfee79e692d04fb9` | 修正 README 执行器变量：补齐 `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE`，并说明它必须与 `SECURITY_MODEL_EXECUTOR_WORKSPACE` 指向同一受控目录 |
| `907e30e1c5354aa3445d1a2e6548dd290a3de44e` | 前端最小修复：终态任务不再显示残留运行进度面板；“当前进度”在终态使用任务详情接口的 `progress` |

`5469f99` 的来源是一次真实失败：第一次短桩任务 `2123247204292915201` 返回
`failed`、`progress=0`、`error_code=PROCESS_RECORD_FAILED`，后端堆栈为
`AttemptProcessRegistry.requireInsideWorkspace` 拒绝 attempt 目录。根因是 README 只设置了
`SECURITY_MODEL_EXECUTOR_WORKSPACE`（进程记录边界），没有设置
`SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE`（attempt 目录根），两者落点不一致。
失败 schema `security_model_delivery_demo` 保留为失败证据，不用于最终验收。

### 4.2 干净检出与环境

- 最终 clean worktree：`tmp/delivery-acceptance-20261119-final`。
- 先以 detached HEAD `5469f99` 检出，修复 UI 后切到 `907e30e`；`git status --short` 为空。
- 隔离 MySQL 数据目录：`F:\security-model-data\acceptance-mysql-20261119`，端口 `3307`。
- 最终 schema：`security_model_delivery_final`，初始化 **48 张表**；未传 `-VabDatasetRoot`，因此没有种子数据集。
- 后端实际连接库经 `information_schema.processlist` 核对，仅为 `security_model_delivery_final`，没有 `eladmin`。
- 测试 JWT 为本地临时值；现有业务库和真实实验环境未参与本轮验收。

### 4.3 构建与测试

| 验证 | 结果 | 证据 |
|---|---|---|
| 最终干净 worktree 后端单测 | **351 项，0 失败、0 错误、0 跳过** | `tmp/final-backend-test.log` |
| 最终后端打包 | **BUILD SUCCESS**，生成可执行 jar | `tmp/final-backend-package.log` |
| Python 适配器测试 | **11 项通过** | `tmp/final-python-tests.log` |
| 前端依赖安装 | **97 packages** | `tmp/final-frontend-ci.log` |
| 前端构建（`5469f99`） | Vite 构建成功，`✓ built in 12.75s` | `tmp/final-frontend-build.log` |
| 前端构建（`907e30e`） | Vite 构建成功，`✓ built in 12.88s` | `tmp/final-ui-build.log` |
| Markdown 相对链接 | **154 条，0 broken** | `tmp/check_md_links.py` |
| 空库初始化 | **48 张表**，LDD 算法和默认超参模板已注册 | `tmp/final-init.log` |

### 4.4 端到端路径

最终后端：

- 启动参数：`--security-model.dispatch.mode=db --security-model.dispatch.role=api --server.port=8100`。
- 进程：PID `37880`，API 端口 `8100`。
- `/healthz`、readiness、scheduling readiness 均为 `UP`。
- 执行器环境：`SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE=python`，
  `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT=...\scripts\r4\r4_python_stub.py`，
  `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE` 与 `SECURITY_MODEL_EXECUTOR_WORKSPACE`
  同为 `...\runtime\executor`。

前端：

- 最终静态构建托管在 `8014`，代理目标 `http://127.0.0.1:8100`。
- `/healthz` 返回 `200`，`/` 返回 `200`。
- 浏览器访问 `http://127.0.0.1:8014/login`，使用本地种子账号 `admin / 123456` 和验证码 `5712` 登录，成功进入 `/dashboard`。
- 旧前端实例先停止（PID `16996`），最终实例状态文件为
  `tmp/deploy-stack/frontend-final2/state.json`。

数据集：

- API 新建 `Stub Demo Dataset`，ID `2123248796094193665`。
- 路径：`...\delivery-acceptance-20261119-final\runtime\demo-dataset`，目录非空。
- 创建后 `parseStatus=pending`；可用列表 `AVAILABLE_BEFORE=1, CONTAINS=False`。
- 调用 `POST /api/dataset/2123248796094193665/validate` 后 `parseStatus=success`，
  返回 `路径验证通过：存在且可读`；可用列表 `AVAILABLE_AFTER=1, CONTAINS=True`。

任务：

- 通过前端“新建防御任务”创建 `Final Delivery Stub Task`，选择 `Stub Demo Dataset` 和 `LDD (VaB)`，点击“创建并启动”。
- 任务 ID `2123248940453748738`，status `success`，progress `100`，attempt `1`，
  runId `304e59b0-6481-4c0a-bde1-79a0042813f2`。
- 数据库行核对：`sys_pre_defense` 中 `task_id=2123248940453748738, status=success, progress=100`。
- 参数快照 hash：`0df67e2b58d2eed6e0fd58d06363670df6f5d5fa44a6d93773c512eb1d95a739`。
- `cleaningResult` 显示 `executor=r4-python-stub`、`message=R4 short protocol stub`、
  `artifacts.marker=r4-python-invocations.jsonl`。

产物：

- marker：`...\runtime\executor\r4-python-invocations.jsonl`。
  最后一行含 `taskId=2123248940453748738`、`runId=304e59b0-...`、`mode=success`、`pid=32200`。
- 任务目录：`...\runtime\executor\pre_defense-2123248940453748738`，包含
  `manifest.json`、`process.json`、`progress.json`、`python-children.json`、
  `request.json`、`result.json`、`stdout.log`、`stderr.log` 和 `attempt-1` 子目录。
- `stdout.log`、`stderr.log` 均为 0 字节，短桩没有写日志。

### 4.5 报告与界面

- `GET /api/pre-defense/report/2123248940453748738` 返回 `200`。
- 报告包含 `taskInfo`、`dataset`、`algorithm`、`executionSnapshot`、`cleaningResult`、
  `cleanDatasetDownload`、`cleanDatasetExports`、`reportGeneratedAt`。
- 前端“导出报告 JSON”已实际下载：
  `D:\Doubao\DownLoads\task-2123248940453748738-report.json`，14,457 B；
  解析后 `taskInfo.status=success`、`taskInfo.progress=100`、
  `cleaningResult.artifacts.marker=r4-python-invocations.jsonl`、
  `cleaningResult.executor=r4-python-stub`、
  `executionSnapshot.attempts[0].runId=304e59b0-...`。
- UI 修复前：任务状态“成功”，但详情弹窗显示“当前进度 10%”“任务执行中”“等待进度”；
  根因是 `loadLogs()` 把日志接口残留的 `progress=10` 覆盖了详情接口的 `100`。
- UI 修复后：任务详情显示“任务状态 成功 / 当前进度 100%”，终态不再出现运行进度面板。
- 日志接口对短桩返回 `available=false`、`logs=[]`，这是短桩没有 stdout 的预期边界。
- `artifact-preview` 要求 `artifactPath` 且目标必须以 `.png` 结尾、位于
  `cleaningResult.artifacts.path` 下；短桩只有 JSONL marker，没有 PNG，因此没有可预览 PNG。
  报告 JSON 和 marker 文件已作为产物核验，不把该接口的 500 当作产物失败。

### 4.6 MySQL / Redis / RabbitMQ 集成门禁

| 门禁 | 执行方式 | 结果 |
|---|---|---|
| MySQL | 隔离 MySQL `127.0.0.1:3307`，`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` | 单测 **351/0/0/0**；IT **126 项，0 失败、0 错误、10 跳过**；`BUILD SUCCESS`；日志 `tmp/final-mysql-it.log` |
| Redis | `REDIS_IT=true`，选中 `ResourceCatalogCacheRedisIT` | **1 项，0 失败、0 错误、0 跳过**；`BUILD SUCCESS`；日志 `tmp/final-redis-it.log` |
| RabbitMQ | 隔离 RabbitMQ 4.1.8，`5673`，独立 vhost `security_model_r4_delivery20261119`；`RABBIT_IT=true` | `RabbitDispatchBrokerIT` **2 项** + `DispatchConfigBoundaryRabbitIT` **1 项**，合计 **3/0/0/0**；`BUILD SUCCESS`；日志 `tmp/final-rabbit-it.log` |

- MySQL 门禁的 10 项跳过属于该 profile 下未启用/条件不满足的用例；Redis 与 RabbitMQ
  已用上述专项命令单独真实执行，不能把“跳过”误读为“未验证”。
- RabbitMQ 首次专项运行 `DispatchConfigBoundaryRabbitIT` 时，因该命令未注入
  `MYSQL_IT_*` 而报 `Access denied ... (using password: NO)`；补上隔离 MySQL 凭据后
  同一用例通过，最终三门禁均为 0 失败。

### 4.7 停止与端口释放

- 后端停止：`Stop-SecurityModelStack.ps1` 先确认任务排空 `running=0`，再停止 PID `37880`；
  停止证据 `tmp/deploy-stack/security_model_delivery_final/db/state-stopped-20261119-111015914.json`。
- 前端停止：`Stop-FrontendStatic.ps1` 停止 PID `5044`；
  停止证据 `tmp/deploy-stack/frontend-final2/state-stopped-20261119-111019104.json`。
- RabbitMQ 停止：节点 `r4test@localhost` PID `32972` 正常关闭。
- 端口核对：`8100 LISTEN=False`、`8014 LISTEN=False`、
  `5673/5672/15672 LISTEN=False`。
- 隔离 MySQL 数据目录保留；验收结束后服务已关闭，`3307` 不再监听。

### 4.8 文档一致性收口

- 根 `README.md`：
  - **路径 A：只启动看界面**：只要求初始化、启动后端和前端；不创建任务，不要求数据集 `success`。
  - **路径 B：跑通短桩任务**：准备非空演示目录、设置四个执行器变量、启动、登录、
    登记并验证数据集、创建 LDD 任务、查看报告/产物、停止并确认端口释放。
  - 两个 workspace 变量必须指向同一目录；否则会重现 `PROCESS_RECORD_FAILED`。
- `docs/sdd/06-mvp-runbook.md`：原“当前默认仍是 `DB_NAME=eladmin`”已标为 2026-11-17 历史快照；
  当前 2026-11-19 默认是 `${DB_NAME:security_model}`。
- `docs/sdd/22-database-boundary-and-convergence-plan.md`：当前默认改为
  `${DB_NAME:security_model}`，旧 `eladmin` 标注为历史；启动后仍需核对实际连接库。

## 5. R4 与单机部署的当前结论

- **R4 实现验收**：2026-11-16 在真实 RabbitMQ 4.1.8、隔离 schema/vhost、双 JVM、
  短 Python 桩下完成 R4-01..08；包括 publisher confirm 丢失/延迟、重复投递、
  DB 中断恢复、取消、死信重放、replay 与新 attempt 竞争。
- **单机部署验收**：2026-11-18 完成 `db` 与 `rabbit` 两种模式的启动、停止、重启、
  排空、端口释放、前端静态托管和健康检查演练。
- **默认运行模式**：`dispatch.mode=db`；不启用 RabbitMQ/Outbox/Worker。
- **未完成边界**：多机 Worker、跨节点 GPU 调度、容器级隔离、长期 Broker 故障压力、
  生产库迁移和生产级高可用均未验收。

历史证据入口：`21-r4-outbox-rabbitmq-design.md` §16、`24-stage3-single-node-deployment.md` §4。

## 6. 当前未完成与不能推断的事项

1. 论文效果评估、基线对照、统计显著性检验未完成；正式 5 epochs 不是论文最终实验。
2. 未做生产级多机部署、Kubernetes、对象存储、跨节点调度和 HA。
3. 未做真实外部模型生产联调；Assistant/RAG 的外部模型默认关闭，需显式配置和预算审批。
4. 生产库 `eladmin` 未迁移、未访问、未修改；`sql/eladmin.sql` 仅作为基础 schema 保留。
5. `screenshots/`、验收证据、测试夹具、历史 SQL 和本机 ignored/untracked 文件均保留，
   本轮未做删除。
6. 代码清理审计：`infrastructure/legacy/LegacyBackendClient.java` 全仓仅命中自身声明/构造器，
   是 Spring `@Component` 但当前无注入引用；因仍有迁移兼容用途且无专项测试覆盖，本轮保留未删。
   `src/`、`.agents` 为空目录，`secrets/`、`runtime/`、`tmp/` 等 ignored/untracked 内容均未移动或删除。
7. 本轮未 push、未创建 Tag/Release、未触发 CI；冻结只落在本地提交和本文件。
8. 生产部署、真实外部模型联调、论文效果评估继续作为独立事项，不纳入本次冻结。

## 7. 文档使用规则

- 需要“当前状态/测试数字/验收边界”时，只引用本文件。
- `09-project-status.md` 是历史状态汇编；引用时必须带“历史快照/当时状态”。
- `21-r4-outbox-rabbitmq-design.md` 顶部“部署验收未执行”是 2026-11-16 的历史口径；
  当前结论以本文件 §5 为准。
- `24-stage3-single-node-deployment.md` §4.6/§4.7 的 343/350 单测数字是历史快照；
  当前单测口径以本文件 §4 为准。
- `25-ldd-real-acceptance-checklist.md` 顶部“正式 5 epochs 未启动”是准备阶段历史；
  当前结论以本文件 §3 为准。
- 其他仍出现 `287/106`、`343/126`、`部署验收未执行` 的文档（如 `03`、`05`、`06`、`13`、`14`、
  `16`、`17`、`20`、`22`、`23` 及顶层 `project-*.md`）均已加历史/当前事实入口提示；
  这些数字和状态不是本轮结论，不能单独引用为“当前通过”。
- 干净检出、构建、启动、学习和源码阅读入口见：
  [`../learning/README.md`](../learning/README.md)、[`README.md`](../../README.md)。
