# 迁移后工程完善路线

更新时间：2026-11-13（R0 记录）

开发执行入口（2026-11-13 补充）：下文 I1–I4 与 R0/R1/R2 实施记录保留各次历史口径；后续阶段范围、默认决策、实施顺序和必选验收统一参见 [17-agent-development-guide.md](17-agent-development-guide.md) 与 [20-stage-execution-and-acceptance.md](20-stage-execution-and-acceptance.md)。新任务书不构成代码已实现或业务库操作授权。

## 目标

以可验证的后端业务能力提升项目质量，而非为简历堆叠未使用的技术名词。SDD 是实现状态的事实来源；README、部署说明和面试材料应与实际代码、配置和测试证据一致。

## 迭代路线

| 阶段 | 范围 | 验收 |
| --- | --- | --- |
| R0：事实审计与设计契约（2026-11-13 完成） | 以源码核对任务状态、数据归属、执行与结果协议；修正与代码冲突的文档；冻结状态机/协议/目录语义；给出待决建议 | 文档与代码无矛盾；演示数据与真实能力可区分；待决事项与风险成文 |
| I1：质量门禁与事实对齐 | 基础门禁已具备；本轮校准文档实现事实，本轮未重跑构建 | 迁移版测试、YAML 解析和前端生产构建以最近一次实际验收记录为准；远端 Actions 记录另行确认 |
| I2：任务可靠性 | 本机数据库验收完成；远端 CI 结果待确认。更广泛的 API 与故障场景集成测试仍可补充 | 测试仅操作随机隔离 schema；并发领取只有一个成功者；完成与恢复竞争只能产生一个终态；恢复只收敛仍匹配 run_id 的 running 任务 |
| I3：真实资源与查询 | 资源增改停用持久化、任务分页、资源批量查询、明确空/错误状态 | 写入后刷新仍可见；列表请求数不随任务行数线性增加；资源历史引用保留 |
| I3b：可观测性 | 结构化日志、关键运行指标 | 任务可通过 taskId/runId 追踪；监控不伪造硬件利用率 |
| I4：按需引入基础设施 | Redis 资源目录缓存已落地；多进程调度需要明确后再评估 Outbox + RabbitMQ | Redis 有 ADR、TTL、主动失效、MySQL 回源和单元测试；消息队列仍需独立需求与验收 |


## R0 实施记录（2026-11-13）

- 核对范围：`migration-backend` 全部 Java 源码、`algorithm-runtime/vab/algorithm_entry.py`、
  `sql/eladmin.sql` 及 6 个 `security_model_*.sql`、`migration-frontend` 路由与关键页面、SDD 01/03/05/07/09/10/11/17。
- 修正 7 处与代码冲突的文档表述，清单见 `05-decisions.md` ADR-012（C1–C7）。
- 冻结内容：任务五态与允许边、`request.json`/`result.json`/`progress.json` v1 字段、`run_id + attempt` 回调校验、
  `(task_id, isolation_ratio)` 导出幂等键、产物真实路径 containment、错误码清单。
- 新增专题：`18-multiuser-ownership-and-resources.md`（归属矩阵、共享资源三方案、历史迁移、GPU 并发）、
  `19-local-data-layout-and-retention.md`（受控目录布局与保留策略草案）。
- 明确未实现项：多用户归属授权、GPU 单槽租约、导出启动恢复、数据集 `parse_status` 推进、硬件指标采集。
- 未改动：任何业务代码、SQL、前端；既有 128 batch / 5 epochs 配置与一次工程验证结果均未触碰、未重跑。

**本轮验证命令与真实结果**

```powershell
# 仓库状态（执行前）
D:\Git\Git\cmd\git.exe status --short     # 无输出：工作区干净
D:\Git\Git\cmd\git.exe log --oneline -1   # d8c33cc docs: define multi-user and agent delivery roadmap

# 单元测试（本机 Java 20 / Maven 3.9.14，编译 release 17）
mvn -B -f migration-backend/pom.xml verify
# 结果：BUILD SUCCESS，Tests run: 30, Failures: 0, Errors: 0, Skipped: 0（9 个测试类），总耗时 03:13 min
```

- 未执行 `-Pmysql-it`：本轮仅文档变更，且本地 MySQL 凭据未在会话中验证；MySQL 集成测试状态沿用
  2026-10-26 的记录（3 项通过），不表述为本轮通过。
- 未执行前端 `npm run build`：前端未改动，沿用 2026-10-26 的构建记录。

## I1 实施记录

- 当前真实执行范围：迁移版后端调用 VaB Python 进程执行 LDD 训练前清洗。
- 当前异步执行方式：事务提交后提交至有界 JVM 线程池；MySQL 保存任务状态。
- 当前 Redis 状态：已接入迁移版运行时，仅缓存可重建的资源目录；RabbitMQ、Kafka 尚未接入。
- 迁移版增加 `AfterCommitTaskDispatcher` 单元测试，覆盖事务提交前不派发、无事务时立即派发、异常处理器和无异常处理器两种失败路径。
- GitHub Actions 运行迁移版 Maven 验证和 Vue 3 前端生产构建。
- 本地验收命令：`mvn -B -f migration-backend/pom.xml verify`；前端执行 `npm run build`。
- 2026-10-26 实际验收：Maven `verify` 成功，10 项迁移版测试全部通过，包含应用 YAML 解析；Vite 生产构建成功。（该数字为当时记录；2026-11-13 实测已增至 9 个测试类、30 项单元测试）
- 前端构建仍提示 Element Plus 聚合 chunk 超过 500 kB（实际输出约 947 kB）；构建通过，但可在后续 I3 评估按需加载和拆包收益。
- CI：GitHub Actions 工作流已加入后端验证与前端生产构建；远端运行状态以对应 Actions 记录为准，本地通过不代表远端已验证。

## I2 实施记录

- 迁移版已有数据库条件更新、`run_id` 校验、失败重试和启动时孤儿任务收敛；本阶段不重复实现这些业务逻辑。
- 新增 `TaskRecoveryRunnerTest`，覆盖孤儿任务被写为 `EXECUTOR_RESTARTED` 失败、无孤儿任务时不写入，以及条件更新因状态竞争返回 0 的场景。
- 当前回归：`mvn -B -f migration-backend/pom.xml verify` 通过，合计 7 项单元测试全部通过；其中新增恢复测试 3 项。
- 重试验收：新服务层测试核对历史 attempt 及其 hash 保留、新 attempt 递增并生成新的 `run_id`。
- 派发拒绝验收：模拟执行器拒绝后，验证失败结果包含 `TASK_QUEUE_FULL`，并携带当前 `run_id` 与 `attempt`。
- 当前回归：`mvn -B -f migration-backend/pom.xml verify` 通过，合计 9 项单元测试全部通过。
- 此项在首次制定路线时尚未完成，现已通过下述 MySQL 集成测试验收。
- MySQL 集成测试使用独立的随机临时 schema，不复用或清理开发数据库；测试完成后仅删除本次创建且名称符合测试前缀的 schema。
- 集成验收场景：两个并发领取请求对同一 `pending` 任务竞争且仅一个成功；正常完成与启动恢复对同一 `running + run_id` 竞争且仅一个终态更新成功；恢复扫描将遗留 `running` 收敛为 `EXECUTOR_RESTARTED`，不修改 pending 或已终态任务。
- 2026-10-26 本机 MySQL 验收通过：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it`，10 项单元测试与 3 项 MySQL 集成测试全部通过。（单元测试数当时为 10 项，2026-11-13 已增至 30 项；MySQL 集成测试本轮未重跑）测试创建随机隔离 schema，清理后断言 schema 不存在。
- CI 使用独立 MySQL 8.4 服务运行集成测试；工作流配置已提交到仓库，但远端运行结果尚未确认。普通本地 `mvn verify` 不要求启动 MySQL，显式运行方式记录在 MVP runbook。

## 本轮自检发现

- `migration-backend/pom.xml` 引入 Spring Data Redis，`application.yml` 配置连接属性，`application-local.yml` 继承公共配置。`ResourceCatalogCache` 实际缓存资源目录，MySQL 仍为事实源。
- 当前活跃执行器为 Python LDD 进程；历史 Mock 执行器不属于迁移版运行链路。其他算法和防御阶段页面属于静态展示，不能据此声称算法已真实接入运行。
- 训练任务有启动恢复扫描；清洗子集导出任务没有启动恢复扫描，文档按此事实修正。
- I3 改造前 `TaskProgressQueryService.findTasks` 无分页，并对每条任务分别查询资源；旧接口保持兼容，新接口采用受限分页和批量查询。

## I3 契约与验收

- `sys_dataset`、`sys_algorithm` 是资源事实源。管理接口支持登记、编辑、停用，不做硬删除；停用后历史任务、报告仍可引用，启动新任务时仍须通过启用与解析状态校验。
- 登记数据集仅记录已有文件的元数据，不能将填入的路径自动视为文件已上传、解析或验证；解析状态由明确的验证结果决定。
- `GET /api/task-progress/page?page=1&size=20` 返回 `records/total/page/size`，页大小最大 100；保留原列表路径给现有调用方。每页至多一次任务查询、一次计数及各一次算法/数据集批量查询。
- 前端资源写入必须等接口确认后刷新；空数据和网络故障分别显示，不再用样例数据伪装持久化成功。任务中心实际业务路径也不从样例合成报告、日志或审计。
- 验收包括参数验证、重名竞争、历史资源引用保留、分页边界、查询批量化、前端构建和 MySQL 隔离集成测试。未通过的项目如实记录，不算完成。

## I4 Redis 实施记录

- 新增 Spring Data Redis 依赖和本地连接配置，默认连接 `localhost:6379`，连接参数支持环境变量覆盖。
- `ResourceCatalogCache` 缓存数据集、算法和超参数目录，以及数据集和算法按 ID 查询结果，默认 TTL 为 5 分钟。
- 资源新增、编辑和停用主动清理对应列表与实体缓存；MySQL 仍是资源事实源。
- Redis 读写、序列化或删除失败时自动降级，不影响 MySQL 查询与资源命令。
- 当前不缓存任务状态、执行快照、报告和日志，不使用 Redis 代替数据库锁或消息队列。
- 验收覆盖缓存命中不查库、未命中回源并回填；Redis 不可用场景由缓存网关降级语义保证。

最近一次记录的普通 Maven 验证和 MySQL profile 验证通过；该记录不代表本轮文档编辑后重新执行。
本机 Redis 不可用时，资源接口按缓存网关回源 MySQL。R5 已新增命中/未命中/降级计数和 key 代际保护，见 ADR-019 与 `09` §5.8；真实 Redis 宕机与并发回填故障注入仍未执行。

## 技术引入约束

### Redis

当前已用于资源目录缓存。新增缓存场景前应确认有可测量的热点读或跨请求共享状态需求；
缓存数据应可由 MySQL 重建，并记录 key、TTL、失效方式、穿透/击穿保护、不可用降级和一致性测试。
任务状态仍以 MySQL 为事实源。

### 消息队列

当前单机进程内线程池适用于现阶段。需要独立 Worker、多实例部署或跨进程调度时，优先评估 MySQL Transactional Outbox + RabbitMQ。方案必须涵盖确认、重复投递、消费幂等、有限重试、死信和任务状态补偿。Redis Streams、Kafka 不与 RabbitMQ 并行引入，除非存在独立的业务理由。

## R1 实施记录（多用户归属与资源收敛，2026-11-13）

- 范围：稳定 `user_id` 身份、任务/报告/日志/产物/导出/审计的服务端归属校验、共享资源写操作收敛为管理员、历史数据迁移脚本、数据集验证入口。
- 数据：`sys_pre_defense` 新增 `owner_user_id`、`data_origin`、`owner_backfill_status` 与 `idx_owner_status`；脚本 `sql/security_model_ownership.sql` 可重复执行，回滚脚本为 `sql/security_model_ownership_rollback.sql`。
- 授权：`TaskAccessGuard` 统一判定；写=仅 owner，读=owner 或开关开启的管理员；demo 行与无 owner 行对普通用户不可写、demo 行任何人不可写；越权返回 403（`ACCESS_DENIED` / `ADMIN_REQUIRED`）。
- 身份撤销（评审修正）：JWT 只证明登录主体，`enabled`/`is_admin` 每次请求回查 `sys_user`；禁用账号、撤销管理员后旧令牌立即失效，令牌内 `adm` 不参与授权。
- 资源：数据集/算法/超参数写操作仅管理员；新增 `POST /api/dataset/{id}/validate`，校验路径存在、可读、非空后置 `parse_status='success'`，失败写 `failed` 与原因。
- 迁移（评审第三轮）：脚本改为两段式 + 结构化决策表 `sys_pre_defense_ownership_decision`（`demo`/`assign`/`reject`，含理由与决策人）；删除用户名自动匹配与“无快照 + 有结果”启发式；未知任务/未知用户/非法决策会让整批回填中止。真实库裁定：1–4 号 `demo`、12 号 `assign user_id=1`、8–11 号待审核。
- 配额：单用户并发改为按 `owner_user_id` 计数，历史无 owner 任务不占配额。
- 未实现（保留到 R2 后续/R3）：日志与产物限额、适配器陈旧锁接管、失败码细分、自动重试队列。
- 验收补充：`OwnershipE2ESupport` 与 `OwnershipEndToEndIT`(9 项)、`OwnershipEndToEndCrossReadIT`(4 项)，用 `@SpringBootTest` + 真实 Security 链 + 真实隔离库跑双账号登录后访问同一任务，覆盖 403、demo/无 owner 隔离、登录后禁用立即失效、旧 token 无 `userId`、伪造 `adm`、管理员开关两种取值，以及导出详情/下载、PNG 预览、导出提交鉴权、审计查询。同时按 `18` §8 逐入口核对归属规则，按 §9 区分单元/契约/端到端证据等级，按 §10 固化迁移执行记录模板。
- 状态口径：R1 为“有条件通过”。已完成归属模型、服务端访问控制、账号状态实时校验与迁移审核机制；真实数据库迁移执行、迁移脚本实机验证与双账号数据库链路仍待补证，不得表述为“多用户生产级权限体系已完成”。
- 验证（2026-11-13 本机实测，R1 阶段数量；R2 第一切片后为 62 + 29，见下条）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` 通过 **57 项单元测试 + 24 项 MySQL 集成测试**（`MySqlTaskPersistenceIT` 5、`OwnershipMigrationScriptIT` 6、`OwnershipEndToEndIT` 9、`OwnershipEndToEndCrossReadIT` 4），BUILD SUCCESS。迁移脚本在隔离库 6/6 通过（只读段、无决策不认领、demo/assign/reject、未知任务与非法决策中止）。**生产库迁移仍未执行**，不能据此宣称生产已迁移。
- 未触碰：LDD 128 batch / 5 epochs 配置、算法执行器、既有工程验证结果。

### R2 第一切片实施记录（2026-11-13）

- 范围：MySQL 单槽 GPU 租约（获取/续租/释放/fencing/过期接管）、启动恢复释放租约、导出 pending/running 重启收敛、导出超时进程树回收。
- 数据：新增 `sys_gpu_lease`（`slot_id` 主键、`lease_token`、`task_id`、`run_id`、`attempt`、`fencing_token`、`expires_at`、`released_at`）；迁移 `sql/security_model_gpu_lease.sql`，回滚 `sql/security_model_gpu_lease_rollback.sql`。
- 并发：GPU 准入由数据库租约强制为 1（`GPU_BUSY`），JVM 的 4/2 配额只作拒绝上限；租约与 `claimForExecution` 同一事务。
- 续租：300s TTL、60s 心跳；续租失败取消进程并置 `GPU_LEASE_LOST`，不静默继续。
- 导出：`ExportRecoveryRunner` 把 `pending`/`running` 显式置失败（`EXPORT_NOT_STARTED`/`EXPORT_INTERRUPTED`），不自动重派发；超时先杀进程树。
- 验证：第一切片 62 项单元测试 + 29 项 MySQL IT 全部通过；`GpuLeaseMySqlIT` 5/5（并发唯一、过期接管、fencing、按 run 释放、释放后可再获取）。**未跑真实 LDD**，生产库未建表。

### R2 第二切片实施记录（2026-11-13）

- 范围：适配器陈旧锁接管、失败码细分、Java 无结果时 stderr 兜底归类、attempt 级日志截断/SHA-256 清单/保留期、恢复前确认执行子进程已退出并隔离未确认槽位（ADR-017）。
- 锁：锁文件改为 JSON（`pid/host/task_id/run_id/token/acquired_at`）；只有 pid 不存活才接管，接管前后比对 token 防止误删新锁；释放校验 token；Windows 用 `OpenProcess`/`GetExitCodeProcess` 探测存活（不使用会误杀进程的 `os.kill(pid,0)`）。
- 失败码：`GPU_LOCK_BUSY`（锁被活进程占用，稍后重试）、`GPU_OOM`（OOM/CUBLAS/CUDNN 分配失败，人工调小 batch）、`CUDA_UNAVAILABLE`（无设备/驱动不符，不自动重试）；其余仍 `VAB_INTEGRATION_ERROR`。Java 仅在子进程没写 `result.json` 时按 stderr 兜底，有结果时以适配器写入的 `error_code` 为准。
- 验证：Java **85 项单元测试 + 30 项 MySQL IT** 全绿（新增 `ExecutorArtifactPolicyTest` 3 项、`ExecutorArtifactRetentionJobTest` 4 项、`AttemptProcessRegistryTest` 6 项、恢复与隔离用例）；适配器 `python -m unittest` **11 项**全绿。**未跑真实 CUDA/LDD**。
- 隔离：遗留进程无法确认退出时置 `blocked_at`，TTL 过期仍拒绝领取（`GPU_LEASE_BLOCKED`），由 60s 探测确认退出后解除；不影响正常释放路径。
- 未实现：自动重试队列（D5）、真实 LDD 显存实测；保留期默认关闭，需按磁盘情况显式开启。

### R2 收口切片实施记录（2026-11-13）

- 进程协议：`process.json` 改为 `schema_version=2`，`starting`/`running` 均原子写；无记录、旧协议、损坏、`starting` 无 pid、启动时间缺失一律 `UNKNOWN`，不再当作未启动。
- 整树回收：新增 `ExecutorProcessGuard`，同时检查 root、`descendants()`、`python-children.json`；执行器 `finally`、训练超时/取消/中断、导出超时只有确认整树退出才释放租约，否则 `markBlocked("EXECUTOR_TREE_ALIVE")`。
- 启动门与可见性：恢复完成前 `SCHEDULING_NOT_READY`；`executor-status` 返回 `gpuSlot` 的 blocked/leased/idle/unknown、原因、隔离时间、最近探测时间与指引；前端任务中心展示。
- 边界：保留期删除前要求 `toRealPath()` 位于 workspace 且等于自身归一化路径；日志截断明确为“归档上限”，不限制运行期增长。
- 验证（R2 收口时）：**104 项单元测试（0 跳过）+ 33 项 MySQL IT**，`OrphanProcessCrashDrillTest` 独立 JVM 演练通过；前端 `npm run build` 通过；未跑真实 LDD。R3 实施后为 120 单测 + 40 IT，见 `09` §5.7。

## 文档同步规则

1. 新需求先更新范围/架构/验收标准，再实施代码。
2. 代码完成时同步更新本路线的阶段状态，以及 `09-project-status.md` 和受影响的模块设计文档。
3. 技术依赖、运行配置、执行边界、测试结果均按代码和 CI 证据填写；规划项不得写成已实现。
4. 旧版 ELADMIN 的测试结果单独说明，不计入迁移版测试覆盖。
