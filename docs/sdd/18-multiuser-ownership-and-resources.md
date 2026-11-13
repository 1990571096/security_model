# 单机多用户归属、共享资源与 GPU 并发（R0 设计契约）

版本：2026-11-13；状态：**R1 已按本文推荐方案实施**（D1/D2/D3/D6 已落地，D4/D5/D7 留给 R2）。

本文回答 R0 需要冻结的三件事：谁拥有什么（数据归属）、共享资源谁可以改（权限）、同一张 GPU 上能同时跑几个 LDD（并发）。所有“现状”结论均来自 R0 核对的实际源码与 SQL，锚点写在每节；所有“建议”均为待用户确认的方案，未经确认不进入实现。

> **R1 实施结果（2026-11-13）**：归属列为 `owner_user_id`，授权集中在 `TaskAccessGuard`；资源写操作仅管理员；历史数据按“精确匹配 / unattributed / demo”三类标记；数据集新增 `POST /api/dataset/{id}/validate` 验证入口。GPU 单槽租约与导出重启收敛已在 R2 第一切片落地（见 §5），适配器陈旧锁与失败码细分仍未实现。

本文回答 R0 需要冻结的三件事：谁拥有什么（数据归属）、共享资源谁可以改（权限）、同一张 GPU 上能同时跑几个 LDD（并发）。所有“现状”结论均来自 R0 核对的实际源码与 SQL，锚点写在每节；所有“建议”均为待用户确认的方案，未经确认不进入实现。

## 1. 事实基线（R0 核对时的代码事实，非规划）

> 本节记录 2026-11-13 R0 核对时的现状，用于解释“为什么需要 R1”。R1 已按本文 §6/§7 的方案修正了归属与资源权限，
> 现在的实现状态以 §7 与 `09-project-status.md` §5.2 为准；本表不作为当前行为描述。

| 事实 | 代码/SQL 锚点 | 说明 |
| --- | --- | --- |
| `sys_pre_defense` 没有 owner 列 | `sql/eladmin.sql` 建表段；`PreDefenseTask.java` | 只有 `create_by varchar(255)` / `update_by`，取值是 JWT 用户名 |
| JWT 签发包含 `userId`，但鉴权过滤丢弃它 | `JwtTokenService.issue()`、`JwtAuthenticationFilter` | 过滤器只把 `claims.getSubject()`（用户名）放进 SecurityContext，权限固定 `ROLE_USER` |
| 当前用户只有“用户名”一种身份 | `CurrentUserService.username()` | 取不到认证时返回字符串 `system`，因此 `system` 不是真实账号却可能成为 `create_by` |
| 所有任务查询不做归属过滤 | `TaskProgressQueryService.query()`、`findTask()`、`statistics()` | 列表、分页、统计、按 ID 直取都是全库范围 |
| 所有任务命令不校验归属 | `PreDefenseTaskCommandService`（`update`/`delete`/`start`/`retry`/`cancel`/`findRequired`） | 任何已认证用户可对任意 taskId 执行编辑、删除、启动、重试、取消 |
| 报告、日志、产物、导出、下载都不校验归属 | `PreDefenseReportQueryService.getReport()`、`PreDefenseLogQueryService.getLogs()`、`PreDefenseReportController` | 只做“任务存在”与“路径 containment”，不做“是否属于当前用户” |
| 报告会把本机绝对路径返回给调用方 | `getReport()` 直接返回 `taskInfo`、`dataset`、`executionSnapshot`、`cleaningResult` | 含 `dataset.filePath`、`executorWorkspace`、`pythonScript`、`realPath`、产物绝对路径 |
| 数据集/算法/超参数是全局共享目录 | `ResourceController`（`/api/dataset`、`/api/algorithm`）、`ResourceCommandService` | 无 owner 过滤；任何已认证用户可新增、编辑、停用，影响所有用户 |
| 资源注册后无法进入可执行状态 | `ResourceCommandService.saveDataset()` 固定写 `parse_status='pending'`；`PreDefenseTaskCommandService.validateCommand()` 要求 `parseStatus='success'` | 平台没有把 `pending` 推进到 `success` 的验证入口，只有 SQL 种子直接写 `success` |
| 单用户配额按用户名计数 | `TaskExecutionQuotaPolicy.validateAndBuildSnapshot()` | `create_by` 字符串比较；改名或同名不同账号会破坏配额语义 |
| 配额检查与任务领取不是同一事务 | 同上 + `PreDefenseTaskMapper.claimForExecution()` | `synchronized` 只覆盖方法体，锁释放后才领取，并发下可越过上限 |
| 配额不限制 GPU | `TaskExecutionQuotaPolicy`（只数行、只看线程池） | 默认 `max-running-tasks=4`、`max-running-tasks-per-user=2` 是 JVM 任务数上限，不是显存预算 |
| 真实 GPU 串行只靠适配器文件锁 | `algorithm_entry.py` 的 `acquire_lock()` / `VAB_ROOT/.security-model-vab.lock` | `O_CREAT|O_EXCL` 排他；持锁进程崩溃后锁文件残留，后续任务全部报“another VaB task is already using the shared GPU/runtime lock” |
| 并发 LDD 的失败码不可区分 | 适配器 `IntegrationError` 统一映射 `VAB_INTEGRATION_ERROR` | 锁冲突、CUDA 不可用、显存不足、脚本错误在结果 JSON 中同一错误码 |
| 适配器强制要求 CUDA | `preflight()` | `torch.cuda.is_available()` 为假直接失败，没有 CPU 降级路径 |
| 真实算法仅 LDD | `SUPPORTED_ALGORITHMS`、`PreDefenseTaskCommandService.LDD_IMPLEMENTATION` | 其它 implementation path 在启动前被拒绝 |
| 历史数据里混有演示行 | `sql/eladmin.sql` 中 `sys_pre_defense` 的 1–4 号记录 | `cleaning_result` 是 zscore/isolation 模拟指标，没有 `execution_snapshot`；其中两条为 `running` 但无 `run_id` |

## 2. 数据归属矩阵

“当前”列为代码事实，“目标”列为 R1 设计目标（待实现），“R1 变更”列出必须改的位置。

| 对象 | 当前归属 | 当前可见性 | 目标归属 | R1 变更 |
| --- | --- | --- | --- | --- |
| 实验任务 `sys_pre_defense` | 仅 `create_by` 用户名 | 全库可见、可改、可删 | `owner_user_id`（稳定 ID）+ 创建者快照 | 新增列与索引；列表/分页/统计按 owner 过滤；`findRequired` 改 owner-aware |
| 任务命令（启动/重试/取消/编辑/删除） | 无校验 | 任意用户可用 | 仅 owner；管理员需显式授权 + 审计 | 服务层入口统一 `requireOwner`；403 稳定返回 |
| 进度快照 `/api/task-progress/*` | 无校验（含 `/statistics`、`/executor-status`） | 全库 | 按 owner；统计仅返回本人，或管理员授权后返回全局 | query 增 owner 条件；统计口径改为“我的实验” |
| 报告 `/api/pre-defense/report/{id}` | 无校验 | 全库，且含本机绝对路径 | 仅 owner；路径脱敏后展示 | owner 校验；对普通用户隐藏绝对路径（仅显示相对位置） |
| 日志 `/api/pre-defense/logs/{id}` | 无校验 | 全库 | 仅 owner | owner 校验；保持现有 containment |
| 产物预览 `/artifact-preview/{id}` | 无校验 | 只要任务存在且路径在产物根内即可读 | 仅 owner | owner 校验 |
| 导出任务 `sys_pre_defense_export` | `create_by`，且不校验 | 全库，可提交/查询/下载 | 继承主任务 owner，提交时校验 | `submit` 先校验任务 owner；`findRequired`/下载同样校验 |
| ZIP 下载 | 无校验 | 全库 | 仅 owner | 同上 |
| 审计 `security_operation_audit` | `operator` 用户名 | 按 taskId 查询，无 owner 校验 | 仅 owner 可看自己的任务审计；管理员另设 | 查询入口加 owner 校验 |
| 数据集 `sys_dataset` | 无 owner（`create_by` 仅记录） | 共享全局 | 共享只读 + 管理员维护（推荐，见 §3） | 写接口收敛为管理员；读接口保持共享 |
| 算法 `sys_algorithm` | 同上 | 共享全局 | 共享只读 + 管理员维护（推荐） | 同上；`implementationPath` 必须受控 |
| 超参数模板 `sys_hyper` | 同上 | 共享全局 | 共享只读 + 管理员维护（推荐），允许用户“复制为我的模板”作为后续项 | 同上 |
| 任务产物目录（本地文件） | 无元数据归属 | 通过任务 API 间接暴露 | 跟随任务 owner；文件系统按 taskId 隔离，不按用户目录隔离 | 权限靠服务端校验，不靠目录名 |
| Redis 资源目录缓存 | 全局键 | 全局 | 保持全局（资源本身共享） | 不做 per-user 键，避免命中率下降 |

结论：**当前实现是“单库单租户”语义**。在 R1 完成前，任何“多用户各自管理实验”的表述都不成立；本期文档已按此修正。

## 3. 共享资源权限：三个方案与推荐

真实约束：单机、用户数少（答辩/实验室规模）、算法路径属于“可执行入口”级别输入、数据集路径指向本机任意目录、没有独立的对象存储与审批系统。

**方案 A（推荐）：共享只读 + 管理员维护**
- 数据集、算法、超参数模板是平台级共享资源，普通用户只读；新增/编辑/停用仅管理员。
- 普通用户可创建自己的任务，任务归属自己的 `owner_user_id`；可选择任意启用中的共享数据集与 LDD 算法。
- 管理员操作全部写审计，并新增“跨用户查看”显式开关（默认关）。
- 理由：与“单机真实运行”规模匹配；避免用户 A 停用共享数据集导致用户 B 历史任务/导出失效；`implementationPath` 与 `filePath` 是受控输入，不能让普通用户随意改。
- 代价：用户不能自助登记数据集；需要管理员在 R1 提供“登记 + 验证 + 启用”三步，或先由管理员预置（见 §4 的 parseStatus 缺口）。

**方案 B：私有资源 + 显式共享授权**
- 每个数据集/算法/超参模板有 owner；另建 `security_resource_share(resource_type, resource_id, grantee_user_id)`。
- 优点：真正“各自管理自己的资源”，更贴论文叙述。
- 代价：R1 需要多两张表、共享申请与撤销界面、共享失效后的历史任务可读性规则；工作量约为方案 A 的 2 倍，且单机用户规模下收益有限。
- 建议：作为 R2 之后的可选项，不在 R1 做。

**方案 C（不推荐）：沿用全局可写**
- 即当前行为。任何用户可改他人依赖的资源，破坏实验可追溯性，也让“多用户隔离”验收无法通过。

风险（三方案共有）：共享数据集是**本机绝对路径**，管理员登记即等于授权平台读取该目录。R1 必须同时约束路径白名单（建议：只允许配置根 `SECURITY_MODEL_DATA_ROOT` 之下），否则管理员误配一条 `C:\` 也能被任务读到。

## 4. 历史无 owner 实验：迁移与隔离

现状（事实）：`sys_pre_defense` 只有 `create_by`；种子数据 1–4 号是演示行（模拟 zscore/isolation 结果、无 `execution_snapshot`、其中两行 `running` 且无 `run_id`）；`create_by` 实际取值只有 `admin`、`test`（`sys_user` 里另有从未创建任务的账号 `12`）。用户名不是稳定身份：可改名、可复用，且平台未把 JWT 里的 `userId` 落到业务行。

迁移方案（评审后两次收紧：**结构化决策表** + **不再按 `create_by` 自动匹配**）：

1. 新增 `owner_user_id BIGINT NULL`、`data_origin VARCHAR(16) NULL`、`owner_backfill_status VARCHAR(16) NULL` 三列与 `idx_owner_status`，均允许 NULL，先不改既有查询。
2. **第一段（默认，只读）**：`@apply_backfill = 0`，加列/加索引、建决策表并打印审核查询——逐行清单、疑似演示行、`running` 但无 `run_id` 的行、按作者分组计数、已登记的决策。**不写任何业务行**。
3. **决策表**：`sys_pre_defense_ownership_decision(task_id PK, decision, user_id, reason, decided_by, decided_at)`，`decision` 仅取 `demo` / `assign` / `reject`。`task_id` 是主键，重复或冲突映射在插入时即失败；`assign` 必须带存在的 `user_id`，`demo`/`reject` 不得带 `user_id`；每行必须有 `reason` 与 `decided_by`。
4. **第二段（审核后）**：同一会话里 `SET @apply_backfill = 1` 后重跑。脚本先校验决策（未知任务 ID、未知用户、非法决策值、字段缺失），任一非法就打印明细并**整批中止**（用故意非法列引用触发失败，因为 MySQL 不允许在 PREPARE 中执行 SIGNAL），不会写半批。
5. **应用顺序**：`demo` → `assign` → `reject` → 剩余无决策行 `unattributed` → `data_origin='legacy'`（NULL 时）。**脚本不再按 `create_by` 与用户名自动认领任何行**：没有决策就只能是 `unattributed`。
6. 幂等：重复执行只补 NULL 标记。回滚：三列可 `DROP COLUMN`；决策表保留为审计证据，不随回滚删除。迁移前对 `sys_pre_defense`、`sys_pre_defense_export` 做 `mysqldump` 备份并记录文件名。

5. `running` 但 `current_run_id IS NULL` 的历史行：脚本仍不改状态（避免迁移顺带改业务语义），只输出计数与清单，由人工决定是否按 `EXECUTOR_RESTARTED` 收敛为 `failed`。未收敛的行会以“运行中”出现在其 owner 列表里，这是已知残留，必须人工处理。
6. 回滚：三列均为新增且可为空，回滚只需 `DROP COLUMN`；回填脚本不改 `create_by`/`cleaning_result`/`status`，因此回滚不丢原始证据。迁移前对 `sys_pre_defense`、`sys_pre_defense_export` 做 `mysqldump` 备份，并在文档记录备份文件名。

已核实的历史数据（种子 SQL 逐行确认，2026-11-13）：`sql/eladmin.sql` 的 `sys_pre_defense` 种子共 4 行，**全部没有 `execution_snapshot`**；1、2 号是 `admin` 名下的 `success` 行，`cleaning_result` 是模拟的 zscore/isolation 指标；3、4 号是 `test` 名下的 `running` 行，`cleaning_result` 为 NULL、`progress` 分别为 65/1、`end_time` 为 NULL、`current_run_id` 无值（该列在种子库中尚不存在），任务名分别是“MNIST数据预处理”（dataset 2）与“文本数据清洗任务”（dataset 3）。平台唯一的真实算法是 LDD 图像清洗，且这几行早于 VaB 适配器（2025-07/08），因此 3、4 号**不是真实 LDD 实验，应作为演示行隔离**。
注意：以上是 `eladmin.sql` 种子文件的 4 行；当前真实库已演化为 9 行（3、4 被恢复线程改为 failed，新增 8–12 号真实跑批），逐行现状见 §4.1，不要拿种子文件当生产现状。

风险：
- 用户名复用会把旧实验错绑到新账号。这里已经不靠用户名匹配：`assign` 必须由人写进决策表并给出理由，`matched` 的含义是“人工确认”，不是“同名推断”。
- 回填前若把 `test` 名下 3、4 号当成真实实验，`test` 会看到两个永远“运行中/失败但从未执行”的假任务，并且（修复前）还能编辑/启动/删除它们。现在的规则是：这两条在决策表里标 `demo`；即使漏标，`TaskAccessGuard` 也禁止任何角色写 demo 行。
- 8–11 号是 `create_by='admin'` 的工程烟测，但**没有逐条证据时不得自动认领**：脚本会一律置为 `unattributed`，由人工核对后再写 `assign` 决策。

**实机执行结果（2026-11-13，随机隔离 schema `security_model_owner_it_*`，`OwnershipMigrationScriptIT` 6/6 通过）**：

- 只读段：加列、加索引、建决策表，任何业务行不被修改，决策为空。
- 无决策段：即使 `create_by='admin'` 对应真实账号，行仍保持 `owner NULL + unattributed`——验证“不自动认领”。
- 决策段：1–4 标 `demo`，12 号 `assign user_id=1` → `owner=1 / matched / legacy`；5 号未知作者保持 `unattributed`；重复执行结果不变（幂等）。
- 撤回段：先用 SQL 模拟旧版自动匹配，再把 2 号标 `reject`，回填后回到 `owner NULL + unattributed`。
- 校验段：未知任务 ID、未知用户、非法决策值三种情况都在写入前中止，合法决策也一并不写（整批失败）。

上一版（字符串开关 `@demo_task_ids`/`@reject_task_ids`）在实跑中暴露过两处缺陷，现已随设计替换而消失，记录保留：① 开关被脚本重置为 0，第二段退化为只读；② `data_origin <> 'demo'` 遇 NULL 使用户名匹配全部失效。新设计不再解析 ID 字符串、也不做用户名匹配，这两类问题没有复发路径。

仍未做：生产库 `eladmin` 上的实跑。隔离库证明的是脚本行为正确，不等于生产数据已迁移；生产执行仍须按 §10 记录备份、决策表快照与逐行核对。

### 4.1 真实库现状（2026-11-13 只读核对 `eladmin`，未写入）

`eladmin.sys_pre_defense` 实际有 **9 行**，且**尚无** `owner_user_id` 列（迁移未执行）。逐行事实：

| task_id | 任务名 | status | create_by | 关键事实 |
| --- | --- | --- | --- | --- |
| 1 | CIFAR-10数据清洗任务 | success | admin | 2025-07 种子；结果是模拟 `sample_stats`（0.984 保留率） |
| 2 | 中毒数据集清洗任务 | success | admin | 2025-07 种子；模拟结果（0.9519 保留率） |
| 3 | MNIST数据预处理 | failed | test | 原种子 running；2026-09-10 被恢复线程写成 `failed`，结果文案为“应用重启后未能恢复原执行进程” |
| 4 | 文本数据清洗任务 | failed | test | 同上，同一时间被恢复线程收敛 |
| 8 | codex-pre-smoke-20260711205832 | success | admin | 2026-07 工程烟测，algorithm 7 |
| 9 | smoke_iqr_20260711212417 | success | admin | 2026-07 工程烟测，algorithm 10 |
| 10 | VaB LDD CUDA smoke 20260725001747 | failed | admin | 真实执行器失败（`PYTHON_EXECUTION_ERROR`） |
| 11 | VaB LDD CUDA smoke retry | success | admin | 真实 LDD 执行成功，含 `clean_purity_percent_by_isolation_ratio` |
| 12 | LDD CIFAR-10 SIG 5轮工程验证 | success | **system** | 真实 LDD 执行成功；`sys_user` 中**没有** `system` 账号 |

账号现状：admin(1, 管理员, enabled)、test(2, **enabled=0**)、12(3, 非管理员, enabled=0)。

**裁定结果（2026-11-13 评审，已据此实现脚本）**：

| 任务 | 决策 | 落库结果 |
| --- | --- | --- |
| 1、2 | `demo` | owner NULL / unattributed / demo；模拟结果不作为真实实验展示 |
| 3、4 | `demo` | 同上；恢复线程写的 `failed` 不代表真实执行 |
| 8–11 | **待审，不自动认领** | 无决策时保持 unattributed；确认后由人工写 `assign` 决策并留痕 |
| 12 | `assign user_id=1` | owner=admin / matched / legacy；理由“system 为平台批处理作者，人工确认归属 admin”，**不加永久 `system → admin` 规则** |

两点必须写入迁移审核记录：
- 3、4 号不再是“running 且无结果”。它们有恢复线程写入的失败结果，**用“无结果”判定 demo 的旧依据已失效**；按裁定仍以 `demo` 隔离，理由必须写清“失败结果由恢复线程生成”。
- 12 号是真实实验但作者 `system` 无对应账号；若不做 `assign`，它会变成 `unattributed`，**任何普通账号都看不到**。裁定为显式归属 admin，不能靠脚本猜测。

**副本演练（`mysqldump` 克隆 `eladmin` 到随机 schema，源库只读，演练后已 DROP）**：上一版脚本按 `@demo_task_ids='3,4'` 的回填结果为 1/2/8/9/10/11 → admin matched、3/4 → demo、12 → unattributed（风险在真实数据上复现）。新决策表版本在隔离库验证了“12 显式 assign admin、其余无决策保持 unattributed”的行为；生产库未做任何写入。


## 5. GPU 并发：事实、推荐与风险

事实：
- 开发机 RTX 2060 6GB / 内存 16GB；LDD 工程验证配置为 CIFAR-10 / SIG / batch 128 / 5 epochs，本轮不修改、不重跑。
- 平台配额（4 / 单用户 2）只是 JVM 行数与线程池判断；同一时刻允许多个 Python 进程同时进入 CUDA，显存由驱动决定谁 OOM。
- 真正的串行保护在适配器：`VAB_ROOT/.security-model-vab.lock` 排他锁。锁冲突表现为任务失败 `VAB_INTEGRATION_ERROR`，不是“排队等待”。
- 锁文件在 `finally` 删除，但 `SIGKILL`／断电／蓝屏后残留；残留锁会让**所有**后续 LDD 任务永久失败，直到人工删文件。

推荐（R2 实施，R0 冻结语义）：
1. 数据库单槽 GPU 租约：`security_model_resource_lease(resource_key='gpu:0', holder_task_id, holder_run_id, holder_attempt, lease_until, fencing_token)`。领取任务与占用槽位同一事务；完成/取消/超时后释放；过期可被新任务接管，旧 `run_id` 回调只能更新 0 行（沿用现有 `current_run_id` 机制）。
2. LDD 的准入并发默认改为 **1**（配置项，不硬编码），与 6GB 显存匹配；`max-running-tasks-per-user` 保留为“排队/拒绝”的业务上限。多用户不等于多 GPU 并发。
3. 适配器锁降级为第二道防线，并增加陈旧判定：锁文件写入 pid + 启动时间，超时或 pid 不存在时记录告警并允许接管（需先确认进程确已退出）。平台侧仍以数据库租约为准。
4. 失败码细分：`GPU_LOCK_BUSY`（可重试，进队列）、`GPU_OOM`（不可自动重试，需人工调小 batch）、`CUDA_UNAVAILABLE`（不可重试）。
5. 不引入“按用户配额换算 GPU”的做法：GPU 是设备级资源，不能按用户数切分。

风险：
- 把默认并发从 4 改成 1 会改变现有行为，必须在 R2 连同并发测试一起做，不能在本轮悄悄改配置。
- 单槽租约的租期必须大于最长任务时长（当前 `timeout-seconds=3600`），否则会出现“任务还在跑但租约被抢”。租期与超时需要同一份配置来源。
- 6GB 显存对本配置是否宽裕没有实测数据支撑；R2 第一次真实运行需记录 `torch.cuda.max_memory_allocated()` 才能给出可信并发上限，本轮不写具体数字。

**R2 第一切片实施结果（2026-11-13）**：推荐 1、2、5 在第一切片落地；推荐 3（适配器陈旧锁）与 4（失败码细分）在第二切片落地。

| 推荐项 | 状态 | 实现 |
| --- | --- | --- |
| 数据库单槽租约 | 已实现（表名 `sys_gpu_lease`，非原建议的通用 `resource_lease`） | 条件 UPDATE 获取；同事务领取；`fencing_token` 每次 +1；续租与释放校验 `lease_token`；过期可接管 |
| GPU 准入并发 = 1 | 已实现（由租约强制，不改 JVM 配额默认值） | `GpuLeaseService`；抢不到返回 `GPU_BUSY` |
| 适配器锁降级 + 陈旧判定 | 已实现（第二切片） | 锁写 `pid/token/task_id/run_id`；仅 pid 不存活才接管，接管与释放都校验 token；活进程一律 `GPU_LOCK_BUSY` |
| 失败码细分 | 已实现（第二切片） | `GPU_LOCK_BUSY`/`GPU_OOM`/`CUDA_UNAVAILABLE` 写入 `result.json.error_code`；Java 仅在无结果时按 stderr 兜底 |
| 不按用户切分 GPU | 已遵守 | 单 row、单 slot |
| 续租（原建议未列） | 已实现 | 300s TTL、60s 心跳；失败置 `GPU_LEASE_LOST` 并取消进程 |
| 导出重启收敛（R2 另项） | 已实现 | `ExportRecoveryRunner` 显式置失败，不自动重派发 |

## 6. 待决事项清单（需要你确认后才进入 R1/R2）

| 编号 | 待决问题 | 推荐方案 | 主要风险 | 阻塞的迭代 | 状态 |
| --- | --- | --- | --- | --- | --- |
| D1 | 共享数据集/算法/超参谁可写 | 方案 A：管理员维护、用户只读 | 用户不能自助登记数据集，需要管理员流程 | R1 | **已采纳并实现**（`ResourceCommandService.requireAdmin`） |
| D2 | 历史 `create_by` 回填口径 | 两段式：先只读审核，再按确认的演示 ID/驳回 ID 回填；不可匹配隔离为 `unattributed` | 用户名复用导致错绑；启发式误判真实实验为演示 | R1 | **已采纳并实现**（`sql/security_model_ownership.sql`，默认 `@apply_backfill=0`） |
| D3 | 历史数据是否给现有 `test` 账号 | 1–4 号标 `demo` + `unattributed`（模拟结果/恢复线程结果均不算真实执行）；12 号 `assign user_id=1` 并写明“system 为平台批处理作者”；8–11 号进审核清单，确认后才显式 `assign` | 一旦放权无法收回 | R1 | 规则与隔离库验证已完成；**生产执行待授权，8–11 号待逐条证据** |
| D4 | LDD 同机并发上限 | 1（数据库单槽租约 + 适配器锁兜底） | 与现有配额默认值冲突，需连测试一起改 | R2 | **租约已实现并实测**；适配器陈旧锁接管与失败码细分已实现（未跑真实 LDD） |
| D5 | 人工/有限自动重试策略 | 只允许人工重试；`GPU_OOM`/`RESULT_PROTOCOL_ERROR` 不自动重试，`GPU_LOCK_BUSY` 进队列 | 自动重试会在显存不足时反复失败 | R2/R3 | 失败码已就绪；**自动重试队列未实现** |
| D6 | 路径登记是否仍是唯一输入 | 保留路径登记，但增加“管理员验证后才可用”的状态推进（当前缺口） | 若不补，用户/管理员都登记出不可用数据集 | R1 | **已采纳并实现**（`POST /api/dataset/{id}/validate`） |
| D7 | 部署数据根目录 | 固定 `SECURITY_MODEL_DATA_ROOT`（数据集）与 `SECURITY_MODEL_EXECUTOR_WORKSPACE`（产物），均须在仓库之外 | 现有默认产物目录在系统临时目录 | R1 | 未实现（R2 随目录迁移一起做） |

## 7. R1 最小实现集合（本文的验收映射）

| 验收项 | 状态 | 实现位置 |
| --- | --- | --- |
| 任务、报告、日志、产物、导出、下载、审计：服务端 owner 校验，拒绝统一 403 | 已完成 | `TaskAccessGuard` + 各服务入口 |
| 列表、分页、统计、executor-status 的可见范围与“我的实验”口径一致 | 已完成（统计返回 `scope` 字段） | `TaskProgressQueryService` |
| 管理员跨用户读默认关闭，开启后可读并留审计 | 已完成（读审计写入依赖操作审计表；本轮为接口层开关） | `security-model.ownership.admin-cross-user-read` |
| 共享资源写操作仅管理员 | 已完成 | `ResourceCommandService` |
| 数据集“登记 → 验证 → 可用” | 已完成（存在性/可读性/非空） | `POST /api/dataset/{id}/validate` |
| **令牌可撤销**：禁用账号、撤销管理员后旧 token 立即失效（评审补充） | 已实现；每个请求按 `userId` 读 `sys_user.enabled/is_admin`，令牌 `adm` 声明不参与授权 | `JwtAuthenticationFilter.resolve` |
| **demo 行不可写**：迁移回填 owner 后仍不能编辑/启动/删除（评审补充） | 已实现；读写规则一致 | `TaskAccessGuard.requireOwner` |
| 历史迁移脚本 + 回滚 + 两段式审核与计数核对 | 脚本已改为两段式；**本机未执行**（`root` 无密码连接被 MySQL 拒绝） | `sql/security_model_ownership.sql` |
| 迁移脚本可执行性验证（隔离库） | 已写 IT，但本机缺凭据未跑通 | `OwnershipMigrationScriptIT` |
| 双账号 HTTP 越权链路 | 已写 MockMvc 契约测试（真实 `/auth/login` 登录取令牌、真实过滤器/守卫/控制器，持久层为 mock），8 项通过；真实库 + 完整 Spring 上下文的端到端仍未做 | `OwnershipHttpContractTest` |
| 双账号数据库层归属 | 已实现并在评审环境通过 5 项隔离库 IT | `MySqlTaskPersistenceIT` |
| GPU 单槽租约 / 导出恢复 | 已实现（R2 第一切片）；日志/产物限额未开始 | `sys_gpu_lease`、`GpuLeaseService`、`ExportRecoveryRunner` |

补充约束（改脚本前必读）：

- 现在回填**只在 `@apply_backfill=1` 时发生**，且只填 `owner_backfill_status IS NULL` 的行；默认第一段跑完，历史行仍是 NULL，对普通用户不可见（fail-closed，不是静默放行），这是刻意的。
- 管理员在跨用户读开关**关闭**时也看不到 demo 行与无 owner 行；这是刻意行为，不是缺陷。
- `data_origin` 只由脚本回填 `legacy`/`demo`，平台创建的任务写 `user`；没有其他写入路径。
- demo 判定完全由操作者按任务 ID 指定，脚本不再按“无快照/有结果”自动推断。

## 8. 接口归属审计（逐入口核对，2026-11-13）

| 入口 | 规则 | 校验位置 |
| --- | --- | --- |
| `GET /api/pre-defense/{id}` | 仅 owner；跨用户管理员开关开启时可读；demo/无 owner 无权限 | `findRequired` → `requireReadable` |
| `PUT /api/pre-defense`、`PUT /{id}`、`DELETE` | 仅 owner；demo/无 owner 一律拒绝 | `requireOwned` / `requireOwner` |
| `POST /api/pre-defense/start`、`retry`、`cancel` | 仅 owner；demo 拒绝 | `requireOwned` |
| `GET /api/pre-defense/report/{id}`、`logs/{id}` | 仅 owner（跨用户管理员例外） | 服务入口 `requireReadable` |
| `GET /api/pre-defense/artifact-preview/{id}` | 仅 owner，且产物路径必须落在任务 artifacts 根内、只允许 `.png` | 控制器 `requireReadable` + containment |
| `POST /cleaned-dataset/exports/{taskId}` | 提交=仅 owner；demo 拒绝 | `submit` → `requireOwned` |
| `GET /cleaned-dataset/exports/{exportId}`、`/task/{taskId}`、`/{exportId}/download` | 读=父任务可读；下载前二次校验 | `findReadable`、`resolveDownloadFile` |
| `GET /api/audit/task/{id}` | 仅 owner（跨用户管理员例外） | 控制器先 `findRequired` |
| `GET /api/task-progress`、`/page`、`/running`、`/{type}/{id}`、`/statistics` | 按 owner 过滤并排除 demo；`/statistics` 返回 `scope` | `TaskProgressQueryService` |
| `GET /api/task-progress/executor-status` | 系统级汇总：线程池、全局运行数、配额上限；**不包含任务名、ID、路径、报告内容** | `TaskProgressController` |
| `POST /api/task-progress/refresh` | 返回静态文本，无数据 | — |
| `GET /api/dataset*`、`/api/algorithm*`、`/api/hyper*` | 共享资源读=认证；写=管理员 | `ResourceCommandService` |
| `POST /api/dataset/{id}/validate` | 仅管理员 | `requireAdmin` |
| `GET /api/v1/system/version` | 公开，无业务数据 | `SecurityConfig` permitAll |

内部专用方法（`CleanSubsetExportService.findRequired`、`requiredTask`）仅供无安全上下文的异步执行器使用，均为服务内部调用，未暴露为控制器入口。

## 9. R1 验收证据矩阵（区分证据类型，不要把 mock 当真实链路）

| 验收点 | 证据类型 | 用例 | 状态 |
| --- | --- | --- | --- |
| 越权读/写返回 403，且不泄露任务是否存在 | 单元（真实守卫 + mock mapper） | `TaskAccessGuardTest` 8 项 | 通过 |
| demo 行不可读、不可写（含 owner 与管理员） | 单元 + 契约 | `TaskAccessGuardTest`、`OwnershipHttpContractTest` | 通过 |
| 禁用账号、删除账号、降权、旧 token、伪造 `adm` | 契约（真实过滤器 + mock 持久层） | `OwnershipHttpContractTest` 8 项 | 通过 |
| 双账号登录后访问同一任务 | 契约（MockMvc + mock mapper） | `OwnershipHttpContractTest` | 通过，但**持久层是 mock** |
| 双账号 + 真实 MySQL + 真实 Security 链路 | 端到端 IT（`@SpringBootTest` + 真实库） | `OwnershipEndToEndIT`（9 项）、`OwnershipEndToEndCrossReadIT`（4 项） | **已执行通过**（本机 MySQL，2026-11-13；共 13 项） |
| 导出详情/下载/PNG 预览的跨用户隔离 | 端到端 IT（真实文件 + 真实库） | `OwnershipEndToEndIT.exportDownloadAndArtifactPreviewAreOwnerScoped` | 通过：owner 200，他人 403 |
| 导出提交先鉴权、后校验比例 | 端到端 IT | `OwnershipEndToEndIT.exportSubmitChecksOwnershipBeforeRatioValidation` | 通过：他人 403，owner 非法比例 400 |
| 审计查询跨用户隔离、管理员开关两种取值 | 端到端 IT | `auditQueryIsOwnerScoped`、`adminReadsExportsArtifactsAndAuditButStillCannotSubmit` | 通过：普通用户 403；开关 false 时管理员 403，true 时管理员可读但不可提交 |
| 数据库层 owner 范围查询、demo 排除 | 隔离库 IT | `MySqlTaskPersistenceIT` 5 项 | 已执行通过 |
| 迁移脚本：只读段、决策表、显式 assign、reject、非法决策中止 | 隔离库 IT，真实执行 SQL | `OwnershipMigrationScriptIT` 6 项 | **已执行通过**（6/6，2026-11-13） |
| GPU 单槽租约：并发唯一、过期接管、fencing、按 run 释放、隔离槽位 TTL 过期仍不可接管 | 隔离库 IT | `GpuLeaseMySqlIT` 6 项 | **已执行通过**（6/6，2026-11-13） |
| 租约与领取同事务、续租失败收敛 | 单元（mock mapper/service） + 代码核对 | `GpuLeaseServiceTest` 4 项、`PreDefenseTaskCommandServiceTest` | 单元通过；**真实并发领取+租约的组合未单独跑真实 LDD** |
| 导出 pending/running 重启收敛 | 代码核对 | `ExportRecoveryRunner` | 未写测试，**未验证** |
| 账号状态不依赖 Redis/本地缓存 | 代码核对 | 过滤器只查 `sys_user`；Redis 仅缓存资源目录 | 通过（静态核对） |

## 10. 迁移执行记录模板（生产操作必须留档）

| 字段 | 填写要求 |
| --- | --- |
| 执行时间 / 执行人 | 精确到分钟，记录操作账号 |
| 脚本版本 | `sql/security_model_ownership.sql` 的 commit 或文件摘要 |
| 备份 | `mysqldump` 文件名、大小、存放位置，恢复演练结果 |
| 审核依据 | 逐行清单（task_id / status / create_by / 是否演示）与判断人 |
| 决策表快照 | 迁移前 `SELECT task_id, decision, user_id, reason, decided_by, decided_at FROM sys_pre_defense_ownership_decision ORDER BY task_id` 的完整导出（含 12 号 `assign user_id=1` 的理由） |
| 决策校验输出 | 脚本打印的非法/冲突决策明细（应为空）与实际应用条数 |
| 执行前后核对 | 逐行 owner/origin 对比与聚合计数（不能只看脚本末尾聚合查询） |
| 回滚预案 | 回滚脚本路径、触发条件、负责人 |


