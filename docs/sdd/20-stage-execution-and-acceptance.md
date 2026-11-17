# 阶段实施与验收任务书

版本：2026-11-13 v1。配套总纲：[17-agent-development-guide.md](17-agent-development-guide.md)。

本文定义目标行为，不是实现完成证明。当前代码已有 R1/R2 多项实现，开发前必须做差异核查；特别是新增的进程启动标记、子进程记录、隔离槽位不能只凭文档描述验收。实现事实与实际测试记录统一写入 [09-project-status.md](09-project-status.md)。

## 1. 领取任务与完成定义

用户说“完成 Rn”即领取该阶段所有必选需求，包括代码、迁移脚本、测试、必要前端反馈和 SDD 同步。阶段内允许按顺序分提交，不需要每个切片重新确认。R3-A/R3-B 是实施批次，不是可省略范围；仅完成 A 时不得标整个 R3 已完成。

每阶段先建立下表，开发中更新，最后逐行提交证据：

| 需求 ID | 目标行为 | 当前差距 | 实现文件/方法 | 测试类/用例 | 实跑证据 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 例如 R2-01 | 未确认设备空闲时拒绝领取 | 核查后填写 | 实际位置 | 具体方法 | 命令、日期、日志位置 | 通过/失败/阻塞/不适用 |

“不适用”必须有本任务书允许的条件或用户范围调整；不能自行删除难测用例。单测、真实 MySQL IT、HTTP E2E、短进程测试、真实 GPU 实验分别标注；不同层级不能互相代替。历史测试数量只能作历史记录。

**阶段完成的六个条件**：

1. 所有必选需求均有实现或经核查无需改动，有对应行为证据。
2. 必选验收全部通过，未执行、跳过和失败不能计入通过数。
3. 新增表/列具备新库初始化、旧库升级、重复执行验证及回滚说明。
4. 原有用户隔离、真实 LDD 配置、文件下载和任务 API 无回归。
5. SDD、配置说明、状态表及受影响的 README/面试材料一致。
6. 交接报告能让审查者独立复现，且没有未处理的 P0/P1 缺陷。

环境不足时交付“实现完成、验收阻塞”，列清缺失条件；不能宣称阶段完成。一次交付目标是减少需求澄清，不是绕过独立审查。

## 2. 默认决策与授权矩阵

| 事项 | 默认处理 | 何时必须提问 |
| --- | --- | --- |
| 类拆分、命名、事务实现 | 沿用现有 Java/MyBatis/Spring 模式，记录理由 | 需要替换核心框架或破坏已有接口 |
| 多用户、共享资源 | owner 隔离；管理员维护资源；跨用户读默认 false | 改变权限模型或赋予历史数据访问权 |
| GPU | 单机单槽；未确认退出则持久隔离 | 增加 GPU 并发或真实硬件实验 |
| 算法失败 | 人工重试；新 runId、新 attempt | 新增自动算法重试 |
| 调度恢复 | 仅恢复证明未提交的 pending；不确定即显式失败 | 改为重放可能已执行的任务 |
| 请求快照 | 版本化、字段白名单、服务端保存必要路径和参数 | 增加凭据、个人信息或外发范围 |
| 数据迁移测试 | 随机隔离 schema、合成数据，可反复执行 | 写业务库 eladmin、历史回填、删除实际产物 |
| 文件清理 | 默认关闭；测试仅操作独立临时目录 | 开启实际保留期、搬迁已有目录 |
| Redis | 资源目录缓存、MySQL 回源；不用作任务真相 | 引入新事实源或新增外部服务 |
| 外部模型 | 可配置供应商适配层、默认关闭 | 首次真实接入的供应商/预算/留存与凭据缺失 |
| Git | 保留已有修改，完成可审查差异 | 推送、合并按用户已有授权执行，不自行推断 |
| 可选阶段 | R4/R8 未被用户选择时跳过，不阻塞 R5/R6 | 准备首次启用 R4 或 R8 |

必须提问时一次列出阻塞项、推荐值和影响；继续完成不依赖答案的代码与测试。凭据只从安全本地配置或环境取得，不在交接输出中显示，不要求用户重复提供已经可用的凭据。

## 3. 通用实施顺序与验证命令

1. 读取本阶段章节、17、相关 ADR、接口契约、源码和测试；执行 `git status --short`，记录基线提交和已有修改。
2. 建立需求映射，核对数据库实际表结构、配置默认值、恢复入口及 API 调用方。
3. 修改需求/ADR/状态图/错误码/迁移设计；先确定不变量和失败语义，再写业务代码。
4. 依次实现数据库原子操作、服务事务、执行器/恢复、API/前端反馈；每个故障窗口用确定性测试覆盖。
5. 顺序运行验证，修复失败，完成兼容性和文档检查。禁止两个 Maven 进程同时写同一 target。
6. 按第 13 节交接。不要在只写完设计、表或单测时结束整个阶段。

| 范围 | 命令 | 要求 |
| --- | --- | --- |
| Java/MySQL | `mvn -B -f migration-backend/pom.xml verify -Pmysql-it` | MySQL 凭据通过既有 MYSQL_IT 环境变量；只建/删随机隔离库 |
| 无 MySQL 环境 | `mvn -B -f migration-backend/pom.xml verify` | 仅部分证据，不能替代 IT |
| Python 适配器 | `python -m unittest discover -s algorithm-runtime/vab/tests` | 修改适配器或进程协议时必跑，使用项目可用 Python |
| 前端 | 在 migration-frontend 下执行 `npm run build` | API/错误码/页面变化时必跑；缺依赖时按锁文件安装 |
| CI | 核对 `.github/workflows/migration-quality.yml` | 增补新测试依赖；远端未执行不得写 CI 已通过 |

短进程替身可以打印 epoch/写进度、睡眠、派生子进程或故意失败；不得伪装为真实 LDD 实验结果。并发测试用屏障控制交错，不靠长时间 sleep 碰运气；超时由测试配置缩短，禁止为了测试等待真实 300 秒租约。

## 4. R0/R1 既有能力的保护门槛

不重复开发已存在身份/授权体系，进入后续阶段前回归以下行为：

| ID | 行为与验收 |
| --- | --- |
| R1-01 | 两个有效用户经真实认证链访问隔离 MySQL；A 的任务不会出现在 B 的列表/统计中 |
| R1-02 | B 对 A 的详情、进度、日志、报告、PNG、导出提交/下载、审计无权限，服务端拒绝且不泄露绝对路径 |
| R1-03 | 禁用/删除账号、撤销管理员后旧 token 不继续保留权限；跨用户读开关两种值均覆盖 |
| R1-04 | demo/无 owner 任务不能被普通用户或管理员写入；共享资源写操作仅管理员 |
| R1-05 | 历史迁移只按明确决策表；未知行隔离，重复运行不覆盖已审核归属；隔离库执行升级/回滚测试 |

业务库迁移的授权与执行记录单独登记，不作为编写 R2/R3 代码的硬阻塞；生产迁移未做时如实保留状态。不得把审查建议自动转成历史归属的人类确认。

## 5. R2：单机执行安全与产物治理

### 5.1 范围、入口与顺序

目标：取消、超时、崩溃和恢复后，不出现“旧 Python 仍可能占 GPU，新任务已经获准执行”；导出能失败收敛，产物可追踪。非目标：MQ、多实例 GPU 调度、算法自动重跑、真实 6GB 性能结论。

入口：`GpuLeaseService`、`GpuLeaseMapper`、`TaskRecoveryRunner`、`GpuLeaseBlockedProbe`、`AttemptProcessRegistry`、`PythonLddTaskExecutor`、`PythonProcessWaiter`、导出执行器/恢复器、`ExecutorArtifactPolicy`、`ExecutorArtifactRetentionJob`、`algorithm_entry.py`。

实施顺序：

1. 核查租约表、条件领取/续租/释放和 blocked 字段，补升级脚本及 MySQL 测试。
2. 统一执行前记录、身份确认、进程树终止与退出确认协议；训练/导出取消、超时、异常、启动恢复复用一致规则。
3. 实现 UNKNOWN/KILL_FAILED 持久隔离与有界重探，补过期仍拒绝领取测试。
4. 接通进度、错误码和前端状态；完成产物清单、清理边界测试。
5. 全部 R2 用例通过后同步 ADR-014 至 ADR-017、07、09、18、19 和运行手册。

### 5.2 必须保持的不变量

- 领取任务与领取 GPU 槽位同事务；失败回滚不能遗留半领取。
- TTL 仅表示租约时效，不能证明物理进程已经退出；非空旧持有者过期接管前须验证退出，否则隔离。不能只在启动恢复里检查，正常领取/续租丢失也要覆盖。
- 只有身份正向匹配才可杀进程；pid 相同不足以证明身份。启动时间缺失/损坏/无法读取是 UNKNOWN。
- 父进程退出不等于后代退出。先记录将启动的状态，启动后记录 pid/启动时间；所有训练子进程纳入身份与退出检查。记录写入应使用临时文件加原子替换，解析失败不能当作未启动。
- 没有记录不能无条件当作“从未启动”：旧版本任务、记录被删除、启动后写记录失败均存在。仅版本化协议及持久状态能正向证明未提交时，才返回 NOT_STARTED；其余 UNKNOWN。
- 子进程启动与记录之间仍有窗口。必须通过子进程握手/受控进程容器，或在证据不完整时保守隔离解决；仅增加一个 pid 文件不能宣称已彻底回收进程树。
- blocked 不随 TTL 自动解除；重探只有确认退出才解除。无法证明时持续隔离并给出运维恢复指引。
- 任务失败、取消与释放租约是独立事件；finally 不得无条件释放仍可能有存活进程的槽位。
- `runId/attempt` 防止旧回调覆盖新结果，但不能阻止旧 CUDA 进程运行；文档不能混用这两个保证。

### 5.3 验收矩阵

| ID | 故障/场景 | 必须断言 | 最低证据 |
| --- | --- | --- | --- |
| R2-01 | 两任务并发领取；领取后事务回滚 | 最多一槽；回滚无幽灵租约 | MySQL IT |
| R2-02 | 无记录、损坏记录、starting 无 pid、启动时间缺失 | 无法证明未启动则 UNKNOWN；不误杀、不放行 | 单测 + 短进程 |
| R2-03 | pid 复用 | 不杀无关进程；后代未确认则仍隔离 | 可注入进程探测单测 |
| R2-04 | 父死子活、后代拒绝/延迟退出 | 不因父死返回整树已退出 | 真实短进程 + 单测 |
| R2-05 | KILL_FAILED/UNKNOWN，TTL 已过 | 新请求仍返回 GPU_LEASE_BLOCKED；重启后保留隔离 | MySQL IT |
| R2-06 | 重探确认退出；旧持有者迟到释放 | 正确解除；旧 token 不能清除新持有者 | MySQL IT |
| R2-07 | 续租失败/数据库中断、线程被中断、超时、取消 | 终止相关进程；未确认退出则隔离，finally 不放行 | 故障注入 + 短进程 |
| R2-08 | 导出 pending/running 重启、超时 | 明确失败码；半成品 ZIP 不作为成功下载 | HTTP/DB + 短进程 |
| R2-09 | 心跳变旧与训练继续 | 前端显示陈旧/最近更新时间，不据此直接失败 | 服务测试 + 页面核验 |
| R2-10 | 重试及归档 | attempt 递增，旧产物不覆盖；清单字节数/哈希正确 | 临时目录测试 |
| R2-11 | 超限日志/短日志 | 失败分类先于截断；尾部、标记、字节数符合契约 | 临时文件测试 |
| R2-12 | 清理关闭/运行中/当前 attempt/未知目录/链接越界 | 全部不删；只删超期终态非当前目录 | 临时目录测试 |
| R2-13 | 配置不同 workspace、空配置、记录位于错误根目录 | 执行/探测/清理使用同一已解析根；缺失记录不被误当未启动；不回退清理仓库根 | 配置测试 + 临时目录 |

日志当前若只在执行结束截断，应明确命名“归档日志上限”，不得宣称运行期间磁盘硬上限。保留期默认 0，删除前采用真实路径边界并拒绝符号链接/junction 越界；不能只比较 normalize 后的字符串前缀。目录年龄口径明确为归档/完成时间，避免把原始创建时间当作终态保留起点。

启动恢复不得在恢复完成前接受新启动命令；通过 readiness/调度就绪标志保证，普通只读接口可继续工作。隔离槽位至少提供管理员可见的原因、最近探测时间和恢复指引；不新增无需身份核验的一键解锁。错误返回不包含本机路径。

R2 送审必须包含无需 GPU 的真实崩溃演练：独立 Java/启动器启动短 Python 父子进程，终止启动器，再调用恢复；提供进程身份与槽位前后状态。平台限制无法执行时列为阻塞，不能以 mock 已通过替代。

### 5.4 R2 需求映射与证据（2026-11-13 本机实测）

| 需求 ID | 实现位置 | 测试/证据 | 结果 |
| --- | --- | --- | --- |
| R2-01 | `GpuLeaseService.acquire`、`PreDefenseTaskCommandService.launchTask` | `GpuLeaseMySqlIT`：并发 1/2 成功；`transactionRollbackLeavesNoGhostLease` 回滚无租约且 fence 未消耗 | 通过（MySQL IT） |
| R2-02 | `AttemptProcessRegistry.reapIfOrphaned` | `AttemptProcessRegistryTest` 13 项：无记录/旧协议/`starting` 无 pid/损坏/启动时间缺失/子进程记录缺失或损坏 → `UNKNOWN`，不杀不放行 | 通过（单测） |
| R2-03 | 同上（`startInstant` 正向匹配） | pid 复用不杀且 `EXITED`；有存活已记录子进程时不判退出；无法证明父子关系的记录子 PID 不杀 | 通过（单测，使用真实短进程 pid） |
| R2-04 | `ExecutorProcessGuard`、`AttemptProcessRegistry` | 父死子活 → `UNKNOWN`；`PythonLddTaskExecutorProcessTest` 超时/取消后父子均死 | 通过（真实短进程） |
| R2-05 | `sys_gpu_lease.blocked_at`、`GpuLeaseMapper` | `GpuLeaseMySqlIT`：TTL 过期后 acquire 抛 `GPU_LEASE_EXPIRED` 并写 block，`releaseByRun` 确认退出后才可再次 acquire；`quarantineSurvivesAServiceRecreation` 重建服务仍隔离 | 通过（MySQL IT） |
| R2-06 | `GpuLeaseBlockedProbe`、`releaseByRun` | `GpuLeaseBlockedProbeTest` 3 项：仅 `REAPED` 解除；`UNKNOWN/KILL_FAILED` 保持；`GpuLeaseMySqlIT` 旧 token 不能释放新租约 | 通过（单测 + MySQL IT） |
| R2-07 | `GpuLeaseHeartbeat`、执行器 `finally`、`ExecutorProcessGuard` | `GpuLeaseHeartbeatTest` 3 项（续租失败、数据库异常均先取消进程）；`PythonLddTaskExecutorProcessTest` 2 项（超时/取消整树回收后释放）；`PythonLddTaskExecutorFailureCodeTest` 7 项 | 通过（单测 + 短进程；数据库中断仅单测 mock） |
| R2-08 | `ExportRecoveryRunner`、`CleanSubsetExportExecutor`、`ExecutorProcessGuard` | `ExportRecoveryRunnerTest` 2 项；`MySqlTaskPersistenceIT` 新增真实库 pending/running 收敛；`CleanSubsetExportExecutorTest` 半成品 ZIP + 超时 | 通过（单测 + MySQL IT + 短进程；HTTP 下载契约沿用既有 E2E） |
| R2-09 | `TaskCenterView.vue` 心跳阈值与“较久未更新”提示；`TaskProgressQueryService` 只返回最近心跳 | `TaskProgressQueryServiceTest` 5 项；前端 `npm run build`；页面逻辑不因陈旧心跳改任务状态 | 通过（服务测试 + 页面构建；未做浏览器手工核验） |
| R2-10 | `PythonLddTaskExecutor.createContext`、`ExecutorArtifactPolicy` | `ExecutorArtifactPolicyTest` 3 项；`PythonLddTaskExecutorFailureCodeTest` 覆盖 attempt 递增与旧产物不覆盖 | 通过（临时目录） |
| R2-11 | `ExecutorArtifactPolicy.trimLog/writeManifest` | 同上：先分类后截断、尾部/标记/字节数/SHA-256；边界文档化为“归档上限” | 通过（临时目录） |
| R2-12 | `ExecutorArtifactRetentionJob.resolveSafe` | `ExecutorArtifactRetentionJobTest` 6 项（关闭/运行中/当前/未知/越界拒绝/链接）；junction 越界在本机实跑通过（链接指向 workspace 外的目录，清理任务拒绝删除） | 通过（临时目录 + junction） |
| R2-13 | `AttemptProcessRegistry`、`ApplicationConfiguration`、清理任务 | 空 workspace 不回退仓库根；workspace 外 `prepare` 抛 `IOException`；越界路径拒绝 | 通过（单测） |

补充说明：

- 崩溃演练 `OrphanProcessCrashDrillTest`：独立 JVM `CrashLauncherMain` 启动短 Python 父子并写 `process.json`，强杀启动器后由 `AttemptProcessRegistry` 判定 `REAPED`，断言父子均死。**未与真实 MySQL 租约的“隔离→解除”串联**；槽位前后状态由 `GpuLeaseMySqlIT` 单独覆盖，两者合起来构成 R2 送审证据。
- 日志截断发生在进程结束后，只约束归档文件大小，不能限制运行期磁盘增长。
- 本机实测命令（R2 收口时）：`mvn -B -f migration-backend/pom.xml test` → 104 项（0 跳过）；`verify -Pmysql-it` → 104 + 33 全绿；`migration-frontend && npm run build` 通过。未执行真实 LDD、未对生产库 `eladmin` 做任何写操作。R3 后为 120 + 40，见 §6.5。

## 5.5 R2 复核修复映射（2026-11-14，待复审）

| 评审问题 | 需求 ID | 代码位置 | 确定性测试 | 结果 |
| --- | --- | --- | --- | --- |
| 重放执行器接受后记账失败仍释放 GPU | R3-04/R3-05 | `TaskDispatchCoordinator.replayPending` | `TaskDispatchCoordinatorTest.replayAcceptedWorkIsNotFailedWhenBookkeepingFails`：`markSubmitted` 抛异常仍返回 true，不失败、不额外释放 | 通过（单测） |
| 恢复扫描误杀新 attempt/误释放 | R3-04/R3-09 | `DispatchRecoveryCoordinator.scanPendingIntents/scanSubmittingIntents` | `oldSubmittingIntentDoesNotReapOrFailTheNewAttempt`、`locallyAcceptedAttemptIsNotReapedOrFailedByTheScanner`、`pendingExpiryLoserDoesNotReleaseTheSlot` | 通过（单测） |
| TTL 过期被当作设备空闲 | R2-05/R2-07 | `GpuLeaseMapper.acquire/renew`、`GpuLeaseService.acquire`、`GpuLeaseHeartbeat` | `GpuLeaseMySqlIT.expiredLeaseIsQuarantinedUntilProcessExitIsConfirmed`、`quarantinedSlotCannotBeReclaimedAfterExpiry`；`GpuLeaseHeartbeatTest.databaseFailureStillCancelsLocalProcessBeforeFailingTask` | 通过（MySQL IT + 单测） |
| 子进程记录缺失/身份不足 | R2-02/R2-03/R2-04 | `AttemptProcessRegistry.reapIfOrphaned/readChildren`、`algorithm_entry.write_children` | `missingChildrenRecordIsUnknownEvenWhenRootIsExited`、`corruptedChildrenRecordDoesNotKillUnverifiedPid`、`exitedParentWithLiveRecordedChildIsUnknown`、`OrphanProcessCrashDrillTest` | 通过（单测 + 真实短进程） |
| 幂等提交失败/崩溃 | R3-08 | `RequestIdempotencyService`、`IdempotencyRecordStore`、`IdempotencyRecoveryJob` | `RequestIdempotencyServiceTest`、`IdempotencyRecoveryJobTest`、`MySqlTaskPersistenceIT.idempotencyKeyIsScopedByOwnerAndExpiry` | 通过（单测 + MySQL IT） |
| 重放前置校验 | R3-06 | `TaskDispatchIntentService.readRequest`、`TaskDispatchCoordinator.validateRequestIdentity/validateResources`、`ExecutionFingerprintService.fingerprint` | `TaskDispatchIntentServiceTest.replayRejectsUnsupportedSchemaAndTamperedRequestHash`、`TaskDispatchCoordinatorTest.changedRuntimeFingerprintIsNotReplayed/disabledOwnerIsNotReplayed` | 通过（单测） |

本机实跑：137 项单元测试（0 失败/0 跳过）、41 项 MySQL IT 全绿、1 项 Redis IT 因未设置 `REDIS_IT=true` 跳过；Python 适配器 11/11；前端生产构建通过（大 chunk 警告）。未跑真实 LDD，未写业务库。**本表只证明已补故障窗口，不等于 R2/R3 整体外部验收通过。**

## 6. R3：持久调度、命令幂等与有限扫描

### 6.1 共同契约

入口：`PreDefenseTaskCommandService`、`AfterCommitTaskDispatcher`、`TaskRecoveryRunner`、任务/租约 mapper、新调度意图 mapper/service、现有 HTTP 命令接口。复用现有五态，不重写执行器。

`sys_task_dispatch_intent` 至少包含：主键、task_id、run_id、attempt、status、schema_version、request_json、请求摘要、创建/更新时间、提交时间、错误码；唯一键 `(task_id, run_id, attempt)`，按 status/created_at 建扫描索引。具体 SQL 与现有 ID/时间类型一致。请求快照只存重放必要白名单，租约 token 每次重新获取，不落该快照。

内部状态：`pending → submitting → submitted`；失败/取消/失效意图转 `abandoned`；submitted 表示执行器已接受，不等于算法成功。所有抢占用条件 UPDATE，仅成功影响 1 行的调用者可提交。插入唯一键不代替抢占。

不承诺 exactly-once。submitting 状态下发生崩溃可能无法判断执行是否开始，因此恢复为 DISPATCH_UNCERTAIN，用户手工重试；不得自动重放。

### 6.2 R3-A 实施步骤

1. 先更新 ADR-018 与 SQL：新库初始化、增量升级、重复运行、回滚；回滚前必须停止调度且不存在活跃任务，不删除业务任务。
2. 领取、冻结快照、获取租约、写 pending 意图放进同事务；事务回滚后没有任一残留。
3. 提交后条件抢占 pending→submitting，再提交线程池；接受后记 submitted；拒绝时任务失败、意图 abandoned，并按 R2 规则释放/隔离。
4. 处理“线程已执行完成但 submitted 尚未回写”的竞态：后写不能覆盖 abandoned 或任务终态，必要时以执行接收凭据补偿，只允许有条件更新。
5. 使用单一启动协调器：先探测/隔离遗留进程，再分类意图；pending 且当前身份、账号、路径、资源状态、快照版本、数据指纹均有效时恢复一次；其余明确收敛。
6. pending 重放复用本 run/attempt，但重新获取租约；不得复用过期 lease token。同 run 旧租约先确认退出并有条件处理，避免把自己的遗留租约误判成别的任务 GPU_BUSY。
7. submitting/submitted 的遗留任务失败收敛；GPU_BUSY/BLOCKED 明确失败并 abandoned，不留无限 running；无意图旧任务按旧恢复语义安全收敛。

### 6.3 R3-B 实施步骤

1. 新增请求幂等记录：唯一 `(owner_user_id, operation, idempotency_key)`，保存规范化请求哈希、资源 ID、处理状态、稳定响应、时间。默认键有效期 24 小时，过期前重复请求复用；不自动清除处理中记录。
2. 创建、启动、重试支持可选 `Idempotency-Key`，长度 1–128，超长/空白拒绝；旧客户端不传时维持旧行为。前端对同一次提交复用键，新操作生成新键。
3. 同键同体返回原始结果；同键异体 409；不同用户同键不冲突。业务写与幂等成功记录同事务；进行中并发请求返回稳定 409 REQUEST_IN_PROGRESS，不重复启动。失败/回滚不永久占键。
4. 默认开启有界扫描：10 秒间隔，每批最多 20 条，只扫年龄超过 30 秒的 pending；值可配置。进入单一扫描协调服务，启动恢复完成前不扫描。
5. 扫描与提交后回调竞争仍以条件抢占为准；扫描不重放 submitting/submitted，不新增算法自动重试。pending 超过 5 分钟仍不能提交则明确失败并 abandoned；默认值允许测试配置缩短。
6. 对 submitting 失联不能仅凭年龄直接杀活任务：先核查本机执行注册和进程记录；状态不确定则依 R2 隔离，再失败。正常运行的 submitted 任务不被扫描超时误杀。
7. 同步 ADR-018/新 ADR、API 幂等说明、任务状态图、前端错误反馈和运维排障。

### 6.4 验收矩阵

| ID | 场景 | 必须断言 |
| --- | --- | --- |
| R3-01 | 业务事务回滚 | 任务领取、租约、意图均不残留 |
| R3-02 | 提交后、抢占前退出 | pending 被恢复一次，不提前被统一失败 |
| R3-03 | 两派发者/扫描器同时竞争 | 一个成功抢占，执行器调用计数为 1 |
| R3-04 | 抢占后提交前退出；接受后记账前退出 | 不自动重放；明确 DISPATCH_UNCERTAIN；设备未确认则隔离 |
| R3-05 | 执行器拒绝/瞬时完成/取消竞争 | 任务与意图最终一致，无终态回滚，无租约泄漏 |
| R3-06 | GPU_BUSY/BLOCKED；身份/指纹/路径/账号失效 | 零执行；明确失败码和 abandoned |
| R3-07 | 旧 run 迟到结果、旧表无意图任务 | 不覆盖新 attempt；旧任务安全失败 |
| R3-08 | 相同幂等键的并发同体/异体/跨用户 | 一次业务写；稳定重放/409；无跨用户泄露 |
| R3-09 | 回调丢失但 JVM 存活；重复扫描 | 有界扫描补偿 pending；无永久悬挂或重复提交 |
| R3-10 | 新库/升级/重复脚本/停机回滚 | schema 与数据约束正确，业务数据不被误删 |

R3-01/02/03/05/06/07/08/09/10 必须含真实 MySQL 证据；R3-04 包含短进程崩溃演练。失败窗口用可注入派发器/屏障控制；既有 HTTP 双账号回归必跑。

### 6.5 R3 实施记录（2026-11-13，待外部评审）

已落地代码：`TaskDispatchIntent`、`TaskDispatchIntentMapper`、`TaskDispatchIntentService`、`TaskDispatchCoordinator`、`DispatchRecoveryCoordinator`、`RequestIdempotencyRecord`、`RequestIdempotencyMapper`、`IdempotencyRecordStore`、`RequestIdempotencyService`；`PreDefenseTaskCommandService` 在领取事务内写 pending 意图，提交后条件抢占；`TaskRecoveryRunner` 只委托统一协调器；三个命令接口支持可选 `Idempotency-Key`。SQL：`sql/security_model_task_dispatch.sql`、`sql/security_model_request_idempotency.sql` 及对应回滚脚本。

自动化证据（本机 `mvn verify -Pmysql-it`）：**120 项单元测试，0 失败/0 跳过；40 项 MySQL IT 全绿**，其中 `MySqlTaskPersistenceIT` 13 项新增覆盖意图唯一键、条件抢占、事务回滚后任务/租约/意图无残留、pending 扫描筛选、幂等键 owner 隔离与过期、SQL 脚本重复执行/回滚。前端 `npm run build` 通过。

逐项对应：R3-01 用真实 JDBC 事务回滚证明任务领取、租约、意图三行均不残留（原始 SQL 层，不是完整命令服务回归）；R3-02 用真实库中的 pending 行 + mock 重放验证启动不会先失败；R3-03 单元与真实库条件更新均证明只有一个抢占者；R3-05/06 单元覆盖执行器拒绝、GPU_BUSY、接受后记账失败；R3-08 单元覆盖同体复用、异体 409、进行中 409、失败释放，真实库覆盖跨用户唯一键；R3-09 单元覆盖 5 分钟超时显式失败；R3-10 通过脚本重复执行与回滚表断言。

仍未完成、不得据此宣称 R3 全部验收（2026-11-14 复审结论；2026-11-15 已补 R3-01/02/03/04，见 §6.7）：R3-01 服务层事务异常回滚已由 `TaskCommandServicePipelineIT` 补；R3-02 的新 JVM 真实租约+短 Python 桩重放、R3-03 双 JVM 竞争、R3-04 接受后未记账崩溃隔离已由 `MySqlTaskPersistenceIT` 补。`TaskCommandServicePipelineIT` 现为 7 项：双启动只有 1 个成功/1 个 run/1 条意图/执行器 1 次；删除—启动不执行已删除任务；编辑—启动请求参数与库中最终版本一致；取消先/完成先两种顺序只有一个终态；瞬时成功与取消只有一个终态；服务层事务异常无残留。仍未完成：R3 子 JVM 使用测试最小上下文，runtime 指纹/用户/资源 Mapper 为代理，真实执行器是短 Python 桩而非 LDD/GPU；未覆盖 KILL_FAILED 实际杀进程失败；生产库 `eladmin` 建表仍未执行。

2026-11-15 最新补充：`R3ProductionExecutorChainIT` 的四个正常/取消/超时/非法结果用例使用完整 Spring `@Async` 代理和真实 `PythonLddTaskExecutor`；第五个真实崩溃用例使用 `R3ProductionCrashChildMain` 的真实派发 + 独立 JVM 恢复，但崩溃子 JVM 仍是测试最小上下文和手动异步包装。短 Python 桩只实现协议，不运行 LDD、不占 GPU；因此它证明 Java 派发链、进程登记、租约和恢复闭环，不证明真实算法/GPU 生产部署。详细证据见 §6.7 R3-04 行与 §10 第 13 条。

### 6.6 N1–N6 可靠性与确认闭环（2026-11-14，基线 `5dec92c`，取消通道提交 `cb8c441`）

| 编号 | 本轮结果 | 证据与边界 |
|---|---|---|
| N1 完整冻结执行计划 | 已实现 | `ResourceQueryService.findDatasetAuthoritative/findAlgorithmAuthoritative` 直读 MySQL；`TaskExecutionPlan` 保存 dataset/algorithm/hyper `DefenseResourceRef` 与最终参数；`buildExecutionRequest` 只从计划构造，不再二次查询。`PreDefenseTaskCommandServiceTest.startBuildsRequestOnlyFromOneAuthoritativePlan` 断言权威读取各 1 次、缓存读取 0 次；`approvedPlanKeepsCompleteSnapshotWhenCatalogRowsMutateAfterPlanning` 断言计划生成后修改资源对象不改变快照。规则为“权威读 + 确认指纹二次校验 + 请求只用快照”，不是资源行锁/版本号锁定；并发更新发生在二次校验前返回 409 且零派发，发生在计划读取后则使用该完整快照。 |
| N2 删除—启动/编辑—启动/双派发竞争 | 已完成 | `TaskCommandServicePipelineIT` 7 项通过（双启动、删除—启动、编辑—启动、取消先于完成、完成先于取消、瞬时成功与取消、服务层事务回滚）；R3-03 另用两个独立 JVM 同时重放同一 pending intent，条件 `pending→submitting` 只有一个成功，见 §6.7。 |
| N3 真实完成与回滚链路 | 历史证据 + 当前实现验收 | 历史证据：`TaskCommandServicePipelineIT` 按两种确定性顺序验证终态，取消先发生时迟到完成不能改终态，完成先发生时取消返回业务拒绝且执行器零调用；R3-01 已补真实服务层事务异常回滚：任务/租约/意图/幂等记录无残留，执行器零调用。该批用例的执行器是 Mockito 替身，只作为 2026-11-14 的历史证据保留。当前实现验收改由 `R3ProductionExecutorChainIT` 的完整 Spring `@Async` 代理 + 真实 `PythonLddTaskExecutor` 正常/取消/超时/非法结果 4 项，以及 R3-04 真实派发崩溃窗口承接；不得再用 Mockito 描述当前执行器证据。 |
| N4 真实进程崩溃恢复 | 已完成（测试最小上下文） | R3-02/03/04 用 `R3RecoveryChildMain` 在独立 JVM 中构建真实 MyBatis mapper、真实 `GpuLeaseService`/`TaskDispatchIntentService`/`TaskDispatchCoordinator`/`DispatchRecoveryCoordinator`、真实 MySQL 租约和短 Python 协议桩；R3-02 新 JVM 恢复 pending 并执行一次；R3-03 双 JVM 竞争只有一个派发；R3-04 接受后未记账崩溃扫描为 `UNKNOWN`，`markBlocked + failAndAbandon`，不重放，Python 仍存活。证据写入 `sys_r3_execution_evidence`。 |
| N5 五类写工具确认矩阵 | 已完成（可控阻塞） | `AssistantConfirmationEndToEndIT` 22 项：create/start/retry/cancel/export 均有首次与重复或并发同凭据证据；cancel 有过期/跨用户；start/create 有资源变化 409 且零租约/零派发；export 有跨用户下载 403；alice/bob 工具线程复用身份与有效 MyBatis-Plus 超时；新增真实 MySQL 触发器 `SLEEP(0.8)` 下 insert 跨 deadline 的 0 行确认、错误 token 篡改 403 + `TOKEN_MISMATCH` 审计、alice/bob 并发 chat 身份隔离。 |
| N6 质量门禁与文档同步 | 本机与远端 CI 通过 | 2026-11-16 四项阻断修复后本机 Temurin 17.0.21 `mvn verify -Pmysql-it` 为 **287 单测 + 106 IT** 全绿（`tmp/stage2-full-verify-new.log`，含 `RabbitDispatchBrokerIT` 2/2、`WorkerConsumerLifecycleTest` 8/8、`DispatchMessageHandlerTest` 12/12、`RabbitDispatchTransportTest` 5/5、`MySqlTaskPersistenceIT` 28/28、`KnowledgeRetrievalMySqlIT` 8/8）；此前 277 + 104 为阶段 2 收尾版（`tmp/stage2-full-verify-final3.log`），267 + 103 为 R4 六项整改版（`tmp/r4-final-verify-new.log`），263 + 100 为旧代码基线（`tmp/r4-final-verify.log`）。Python 11/11（`tmp/round5-r4-python-tests2.log`）；前端构建历史通过；文档改动后 `KnowledgeEvaluationTest`/`KnowledgeHybridEvaluationTest` 7/7（`tmp/stage2-doc-eval.log`）。提交 `ee10704`/`cd5a6ae`，远端 run `43846823071` backend/python/frontend 全绿。 |

**附带修正（2026-11-14，取消通道收口）**：`AfterCommitTaskDispatcher` 恢复为提交后同步执行，不再向 `taskAsync` 二次提交；训练链路的唯一异步边界回到 `PythonLddTaskExecutor.runPreDefense` 的 `@Async("taskAsync")`。取消信号改由独立 `TaskCancellationDispatcher` 在事务提交后提交到专用 `cancelAsync`（core=1、max=1、queue=32），队列满时在提交线程执行，避免与训练/导出/派发共池后被排队或拒绝。`TaskCancellationDispatcherTest` 覆盖队列饱和回退和“提交前不发信号”；`TaskCommandServicePipelineIT` 覆盖取消先/完成先两种顺序，当前 7 项通过。

**结论**：N1/N2 完成；N3 历史 Mockito 证据保留，但当前执行器证据已由 `R3ProductionExecutorChainIT` 的真实 Spring 异步代理 + 真实 `PythonLddTaskExecutor` 4 项和 R3-04 崩溃窗口替代；N4/N5 属测试最小上下文/可控阻塞；N6 本机 287 单测 + 106 IT 通过，提交 `ee10704`/`cd5a6ae` 的远端 run `43846823071` backend/python/frontend 全绿；green CI 也不等于 R4 全部故障场景或生产部署通过。R3/R6/R7 的冻结必选 ID 按 §6.8 判定为“实现验收通过（隔离环境），部署验收未执行”，不再写成“仅本机通过”或“未整体验收”这种无法定位到具体 ID 的结论。

### 6.7 验收 ID 五列映射（2026-11-15，基于 `930c934` 五项收口，并补 `08f11c0` 后 P1/P2/R3 收口）

表内“实际依赖”说明替换了哪些真实组件；“剩余限制”说明在哪些地方仍是测试替身/最小上下文，不能把本表当作阶段整体验收。

| 验收 ID | 实现方法 | 测试方法 | 实际依赖 | 证据 | 剩余限制 |
| --- | --- | --- | --- | --- | --- |
| R3-01 业务事务回滚 | 真实 `TaskCommandService.start` 在 `RequestIdempotencyService.execute` 事务内领取租约、claim 任务；在 `createPending` 前注入指纹异常 | `TaskCommandServicePipelineIT.launchFailureRollsBackClaimLeaseIntentAndIdempotencyRecord` | 真实 Spring 事务、任务 Mapper、`sys_gpu_lease`、`sys_request_idempotency`、协调器；执行器替身 | `tmp/round2-r3-01.log`、`tmp/round2-r3-pipeline.log` | 未覆盖连接中断/提交阶段失败 |
| R3-02 提交后抢占前退出 | 父进程写 running+pending；新 JVM `releaseByRun+acquire` 后 `replayPending`，短 Python 桩执行并 `markSubmitted` | `MySqlTaskPersistenceIT.newJvmRecoversPendingIntentWithRealLeaseAndPythonStub` | 真实 MySQL 租约/意图/任务表、真实 MyBatis、真实 Python 进程 | `tmp/round2-r3-02.log` | 最小 Spring 上下文；runtime/资源 Mapper 为测试代理 |
| R3-03 双派发竞争 | 两个新 JVM 同时重放同一 pending；条件 `pending→submitting` 只有一个成功 | `MySqlTaskPersistenceIT.twoNewJvmsRaceSamePendingIntentOnlyOneDispatches` | 真实 MySQL 条件更新、两个 JVM、两个 Python 桩 | `tmp/round2-r3-03.log` | 单机双进程，不是网络分区 |
| R3-04 接受后记账前崩溃（构造状态，保留） | `submitting` 意图 + 真实长睡眠 Python + `process.json` 缺 `python-children.json`；启动扫描 `UNKNOWN` 后隔离不重放 | `MySqlTaskPersistenceIT.acceptedBeforeBookkeepingCrashKeepsSlotQuarantinedWhileProcessUnconfirmed` | 真实 MySQL、`DispatchRecoveryCoordinator`、`AttemptProcessRegistry`、真实 Python | `tmp/round2-r3-04.log` | 用缺文件构造“未确认”，未覆盖 KILL_FAILED/杀进程失败实际路径 |
| R3-04 真实派发崩溃（2026-11-15 新增） | 父进程种子 running+pending；`R3ProductionCrashChildMain` 用真实 MyBatis、真实 `PythonLddTaskExecutor`、真实协调器接受并启动 Python 后，在 `markSubmitted` 屏障阻塞；父进程 `destroyForcibly` 杀子 JVM，再启动 `R3RecoveryChildMain` 识别 `submitting`，杀孤儿、释放租约、任务 failed、意图 abandoned | `R3ProductionExecutorChainIT.crashAfterAcceptBeforeBookkeepingRecoversWithoutReplayOrReassigningTheSlot` | 真实 MySQL 任务/意图/租约、真实派发协调器、真实执行器、真实 Python 子进程、独立 JVM 恢复；崩溃子 JVM 为测试最小上下文 + 手动异步包装 | `tmp/round3-r3-prod.log`（5/5） | 崩溃子 JVM 不是完整 Spring 容器；仍是单机进程崩溃，不是网络分区/多实例 |
| R3-05 执行器拒绝/瞬时完成/取消竞争 | 服务层按两种确定性顺序收敛终态；执行器替身返回拒绝/完成/取消；新增真实执行器正常/取消/超时/非法结果四类 | `TaskCommandServicePipelineIT` 取消先/完成先/瞬时成功用例；`R3ProductionExecutorChainIT` 正常/取消/超时/非法结果 | 真实事务/任务表；执行器 Mockito 替身 + 完整 Spring 真实执行器链路 | `tmp/round2-r3-pipeline.log`、`tmp/round3-r3-prod.log` | 真实链路不运行 LDD/GPU；拒绝码仍以替身/单测为主 |
| R3-06 GPU_BUSY/BLOCKED/身份失效 | `GpuLeaseService` 条件 UPDATE、blocked_at 隔离、身份/指纹/路径校验后再派发 | `GpuLeaseServiceTest`、`TaskDispatchCoordinatorTest`、`TaskCommandServicePipelineIT` | 真实租约表 + 单元协调器 | `tmp/round2-full-verify-final.log` | 未接真实 GPU/LDD |
| R3-07 旧 run 迟到结果 | 终态条件更新与 run/attempt 校验，迟到结果不能覆盖新 attempt | `MySqlTaskPersistenceIT` 旧/新 run 条件更新用例 | 真实 MySQL | `tmp/round2-full-verify-final.log` | 未做真实进程长时间迟到回调 |
| R3-08 相同幂等键 | `sys_request_idempotency` owner 作用域、过期、同体复用/异体 409 | `RequestIdempotencyServiceTest`、`MySqlTaskPersistenceIT.idempotencyKeyIsScopedByOwnerAndExpiry` | 真实 MySQL 唯一键 + 单元服务 | `tmp/round2-full-verify-final.log` | 无跨实例锁 |
| R3-09 回调丢失/重复扫描 | 有界扫描 pending，超时显式失败，不重复提交 | `DispatchRecoveryCoordinatorTest`、`IdempotencyRecoveryJobTest`、`TaskCommandServicePipelineIT` | 真实 MySQL + 单元扫描 | `tmp/round2-full-verify-final.log` | 未做长时挂起/多实例扫描 |
| R3-10 新库/升级/回滚 | SQL 脚本 `CREATE TABLE IF NOT EXISTS`、回滚脚本只删本阶段表 | `OwnershipMigrationScriptIT` | 真实 MySQL 随机 schema | `tmp/round2-full-verify-final.log` | 生产库 `eladmin` 未执行 |
| R6-01 top5 命中 | 词法候选 + 向量重排，固定 20 题 | `KnowledgeEvaluationTest.fixedEvaluationSetMeetsR6Thresholds`、`KnowledgeHybridEvaluationTest` | 本地 SDD 语料、真实服务路径 | `tmp/round2-knowledge-regression.log` | 固定小语料，不是大规模评估 |
| R6-02 引用可定位 | 引用带 sourceKey/version/章节/行号，回答后校验 | `KnowledgeControllerTest`、`KnowledgeEvaluationTest` | MySQL + 本地语料 | `tmp/round2-full-verify-final.log` | 未接付费模型生产质量评估 |
| R6-03 未知不捏造 | 词法候选为空直接拒答；IDF 覆盖阈值 | `KnowledgeEvaluationTest`、`KnowledgeHybridEvaluationTest` | 本地语料 + 真实 ranker | `tmp/round2-knowledge-regression.log` | 阈值仍可能被新语料边界触发误命中，需持续回归 |
| R6-04 越权/退役零泄露 | ACL 前置过滤 + `status='active'`；退役立即不可召回；退役清理用持久状态 `pending/clean/failed` + `next_at` + 公平排序，失败有限退避，成功不再占据首批窗口 | `KnowledgeRetrievalMySqlIT`（含 `retiredVectorCleanupAdvancesPastFailuresAndSurvivesRestart`）、`KnowledgeVectorStoreRedisIT`、`KnowledgeHybridEvaluationTest` | 真实 MySQL ACL、真实 Redis 8、真实清理状态与退避 | `tmp/round3-p2.log`（6/6）、`tmp/round2-r6.log`、`tmp/round3-full-verify-final.log` | 向量残留窗口靠对账，不是强一致；清理失败期间只保证不进入 ACL 检索，不保证 Redis 立即清空 |
| R6-04a 退役清理迁移 | 基础表新增四列与索引；增量脚本用 `information_schema` 动态补列/建索引/回填 retired 行，回滚脚本动态删索引/删列 | `KnowledgeVectorCleanupMigrationIT.upgradeIsRepeatableBackfillsRetiredRowsAndRollsBackCleanly`：隔离 schema 升级两次、回滚两次、再升级 | 真实 MySQL 随机隔离 schema | `tmp/round3-p2-migration.log`（1/1） | 只在隔离库执行；生产 `eladmin` 未迁移；回滚要求停机且无活跃任务 |
| R6-05 外发禁发字段为零 | `KnowledgeSanitizer` 四层路径 + 凭据/PII；单段规则覆盖单字符/数字/隐藏/空格目录；所有渠道复用同一 `forModel()` | `KnowledgeSanitizerTest`（9/9）、`AssistantOutboundPrivacyTest`（1/1，真实 ChatService/适配器/假 HTTP 两轮最终 body） | 真实 ChatService/适配器/假 HTTP | `tmp/round3-p1.log` | 自由文本形态无法穷举；保守正则过滤，不替代 DLP |
| R6-06 超时/限额/无密钥降级 | 模型预算、工具预算、`provider=disabled` 降级 | `AssistantChatServiceTest`、`AssistantPreviewServiceTest`、`KnowledgeControllerTest` | 单元 + 真实 HTTP | `tmp/round2-full-verify-final.log` | 付费供应商额度未形成生产配置 |
| R6-07 文档注入不扩权 | system prompt 声明 context 不可信；工具白名单；注入题零私有候选 | `KnowledgeEvaluationTest.unsafeOrUnauthorizedPromptsNeverExposeForeignPrivateData`、`KnowledgeHybridEvaluationTest` | 本地语料 + ACL 候选 | `tmp/round2-knowledge-regression.log` | 非对抗性红队覆盖 |
| R7-01 跨用户工具拒绝 | 只读工具复用 `TaskAccessGuard.requireOwned`；写预览校验 owner | `AssistantConfirmationEndToEndIT` 跨用户 403 用例 | 真实 HTTP + MySQL | `tmp/round2-full-verify-final.log` | 单进程权限，不是组织级 RBAC |
| R7-02 注入不扩工具集 | 注册表白名单，模型不能新增工具；system prompt 限定 | `AssistantToolRegistryTest`、`AssistantChatServiceTest` | 单元 + 真实 HTTP | `tmp/round2-full-verify-final.log` | 模型输出仍可能尝试未知工具，被拒绝 |
| R7-03 无确认零写入 | 写工具只返回预览；确认前不写任务/不领租约/不派发 | `AssistantConfirmationEndToEndIT` 预览零写入用例 | 真实 MySQL/HTTP | `tmp/round2-full-verify-final.log` | 无 |
| R7-04 重复/过期/篡改/跨用户凭据 | token 哈希、TTL、条件消费、跨用户校验；篡改写 `TOKEN_MISMATCH` 审计 | `AssistantConfirmationEndToEndIT` 重复、过期、跨用户、`tamperedTokenCannotConfirmAndIsAudited` | 真实 MySQL 确认/审计表 + HTTP | `tmp/round2-r7-a.log` | 无数据库泄露后的离线重放防护 |
| R7-05 参数/版本变化失效 | 确认重校验 task/run/attempt/update_time、参数哈希、资源指纹；变化 409 且零派发 | `AssistantConfirmationEndToEndIT` 资源变化/算法默认值变化用例 | 真实 MySQL/HTTP | `tmp/round2-full-verify-final.log` | 无 |
| R7-06 模型断线/预算超限不影响核心 | 模型线程池有界、总预算/工具预算、超时取消；核心 API 不共享失败 | `AssistantChatServiceTest`、`AssistantConfirmationEndToEndIT` 超时用例 | 单元 + 真实 HTTP + MySQL 触发器阻塞 | `tmp/round2-r7-a.log` | 底层 JDBC 物理终止不保证 |
| R7-07 审计/请求体无禁发 | 审计只存工具名/operator/target/resultCode/duration；请求体脱敏 | `AssistantOutboundPrivacyTest`、`AssistantConfirmationEndToEndIT` 审计断言 | 真实 HTTP/MySQL/假 HTTP | `tmp/round2-p1-final.log`、`tmp/round2-r7-a.log` | 无 |

## 6.8 R3/R6/R7/R4 冻结必选需求逐项结果（2026-11-16）

判定口径：**实现验收**只看代码、迁移脚本、隔离测试和可复现实跑证据；**部署验收**要求生产库迁移、真实 GPU/LDD、组织级审批或多实例网络条件下运行。两者分开记录，不把“部署未执行”反向写成“实现失败”，也不把隔离测试写成部署通过。本轮没有 R3/R6/R7 必选 ID 判定为实现失败；R4 已在真实 RabbitMQ + 隔离 MySQL + 双 JVM + 短 Python 桩下执行 R4-01（含事务回滚）、R4-02（幂等/不可路由/旧 claim token/真实 confirm-return/7s 延迟 confirm）、R4-03、R4-04（GPU_BUSY/毒消息/DB 中断/无重启自动恢复消费）、R4-05（运行中+完成后取消）、R4-06（启动/drain/v2 旧队列隔离/完整双向停机切换）、R4-07、R4-08（自然 dead 重放/越权/replay+cancel 并发/旧 dead 与新 attempt 真实竞争）。剩余边界只登记 R4-04 长期 DB 故障/多任务退避压力与跨主机；R6-03 的阈值 `0.30` 属于有限样本校准，后续增加独立保留题集是质量改进，不是新增验收阻塞。

#### R3 调度与恢复

| 需求 ID | 实现方法 | 具体测试 | 实跑证据 | 实现验收 | 部署验收 | 剩余边界 |
|---|---|---|---|---|---|---|
| R3-01 | `TaskCommandService` 事务内领取/租约/意图/幂等；异常全回滚 | `TaskCommandServicePipelineIT.launchFailureRollsBackClaimLeaseIntentAndIdempotencyRecord` | `tmp/round2-r3-01.log`、`tmp/round2-r3-pipeline.log` | 通过（隔离） | 未执行 | 连接中断/提交阶段失败未覆盖 |
| R3-02 | 新 JVM 释放旧租约后重放 pending，短 Python 桩执行并记账 | `MySqlTaskPersistenceIT.newJvmRecoversPendingIntentWithRealLeaseAndPythonStub` | `tmp/round2-r3-02.log` | 通过（隔离） | 未执行 | 最小 Spring 上下文；runtime/资源 Mapper 为测试代理 |
| R3-03 | 两个 JVM 条件 `pending→submitting`，只有一个成功 | `MySqlTaskPersistenceIT.twoNewJvmsRaceSamePendingIntentOnlyOneDispatches` | `tmp/round2-r3-03.log` | 通过（隔离） | 未执行 | 单机双进程，不是网络分区 |
| R3-04 | 真实 `PythonLddTaskExecutor` 接受后、`markSubmitted` 前杀子 JVM；恢复识别 `submitting`，杀孤儿、释放租约、task failed/intent abandoned，不重放 | `R3ProductionExecutorChainIT.crashAfterAcceptBeforeBookkeepingRecoversWithoutReplayOrReassigningTheSlot`；构造状态用例保留 | `tmp/round3-r3-prod.log`（5/5） | 通过（隔离） | 未执行 | 崩溃子 JVM 是测试最小上下文；KILL_FAILED 实际失败路径未覆盖 |
| R3-05 | 完整 Spring `@Async` 代理 + 真实执行器正常/取消/超时/非法结果；取消测试最多等 20 秒确认 pid 消失 | `R3ProductionExecutorChainIT` 四个终态用例；`TaskCommandServicePipelineIT` 取消先/完成先 | `tmp/round3-r3-prod.log`、`tmp/round2-r3-pipeline.log` | 通过（隔离） | 未执行 | 真实链路不跑 LDD/GPU；执行器拒绝码仍以替身/单测为主 |
| R3-06 | 条件租约、blocked 隔离、身份/指纹/路径校验后再派发 | `GpuLeaseServiceTest`、`TaskDispatchCoordinatorTest`、`TaskCommandServicePipelineIT` | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 未接真实 GPU/LDD；GPU_BUSY 由替身/条件更新模拟 |
| R3-07 | 终态条件更新 + run/attempt 校验，迟到结果不能覆盖新 attempt | `MySqlTaskPersistenceIT` 旧/新 run 条件更新用例 | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 未做真实进程长时间迟到回调 |
| R3-08 | `sys_request_idempotency` owner 作用域、过期、同体复用/异体 409 | `RequestIdempotencyServiceTest`、`MySqlTaskPersistenceIT.idempotencyKeyIsScopedByOwnerAndExpiry` | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 无跨实例锁；以数据库唯一键为准 |
| R3-09 | 有界 pending 扫描、超时显式失败、不重复提交 | `DispatchRecoveryCoordinatorTest`、`IdempotencyRecoveryJobTest`、`TaskCommandServicePipelineIT` | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 未做长时挂起/多实例扫描 |
| R3-10 | 新库/升级/重复执行/停机回滚脚本 | `OwnershipMigrationScriptIT` | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 生产库 `eladmin` 未执行迁移 |

#### R6 知识检索与出站

| 需求 ID | 实现方法 | 具体测试 | 实跑证据 | 实现验收 | 部署验收 | 剩余边界 |
|---|---|---|---|---|---|---|
| R6-01 | 词法候选 + 向量重排，固定 20 题 | `KnowledgeEvaluationTest`、`KnowledgeHybridEvaluationTest` | `tmp/round4-docs-knowledge-eval.log`（7/7） | 通过（隔离，可答 10/10） | 未执行 | 固定小语料，不是大规模评估 |
| R6-02 | 引用带 sourceKey/version/章节/行号，回答后校验 | `KnowledgeControllerTest`、`KnowledgeEvaluationTest` | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 未接付费模型生产质量评估 |
| R6-03 | 词法候选为空直接拒答；IDF 覆盖率阈值 `0.30` + 最低分 20 | `KnowledgeEvaluationTest`、`KnowledgeHybridEvaluationTest`、`AssistantRankerSampleEvaluationTest` | `tmp/round4-docs-knowledge-eval.log`（7/7，未知 0 命中）；排序器样例 `tmp/stage7-scenario-tests.log` | 通过（隔离，未知 0 命中；样例未知 2/2 拒答） | 未执行 | 有限样本校准；`AssistantRankerSampleEvaluationTest` 仅是排序器样例，不证明生产 ACL/注入 |
| R6-04 | ACL 前置 + `status='active'`；退役立即不可召回；持久清理状态/退避/公平排序 | `KnowledgeRetrievalMySqlIT`、`KnowledgeVectorStoreRedisIT`、`KnowledgeHybridEvaluationTest` | `tmp/round3-p2.log`（6/6） | 通过（隔离） | 未执行 | 向量残留靠对账，不是强一致；失败期间只保证 ACL 不召回 |
| R6-04a | 基础表四列/索引；增量/回滚脚本动态判断，可重复执行 | `KnowledgeVectorCleanupMigrationIT` | `tmp/round3-p2-migration.log`（1/1） | 通过（隔离） | 未执行 | 仅隔离 schema；生产库未迁移；回滚需停机 |
| R6-05 | 四层路径规则 + 凭据/PII；所有外发渠道复用 `forModel()` | `KnowledgeSanitizerTest` 9/9、`AssistantOutboundPrivacyTest` 1/1 两轮最终 body | `tmp/round3-p1.log` | 通过（隔离） | 未执行 | 正则形态过滤，不能穷举自由文本隐私形态 |
| R6-06 | 模型/工具预算、超时、`provider=disabled` 降级 | `AssistantChatServiceTest`、`AssistantPreviewServiceTest`、`KnowledgeControllerTest` | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 付费供应商额度未形成生产配置 |
| R6-07 | system prompt 声明 context 不可信；工具白名单；注入题零私有候选 | `KnowledgeEvaluationTest.unsafeOrUnauthorizedPromptsNeverExposeForeignPrivateData`、`KnowledgeHybridEvaluationTest`、`AssistantScenarioExecutionTest` 的越界工具拒绝 | `tmp/round4-docs-knowledge-eval.log`（7/7）；`tmp/stage7-scenario-tests.log` | 通过（隔离，仅服务端工具边界与词法候选） | 未执行 | 非对抗性红队覆盖；真实模型自然语言注入行为未由本轮证明 |

**2026-11-17 排序器样例（质量改进，不新增必选 ID）**：`AssistantRankerSampleEvaluationTest` 的题集只存在于测试类，不写入 `docs/sdd`，避免被检索语料污染。固定输出维度为 `retrieval_hit`、`citation_correct`、`refusal_unknown`、`permission_blocked`、`injection_blocked`，并单独断言参数默认值/schema、报告白名单指标与绝对路径脱敏、过期确认拒绝。该测试在辅助方法中已按 owner 过滤私有语料，因此权限/注入计数只是排序器样例，不证明生产 ACL 或模型注入防御。评估首跑发现 `ParameterGuideTool` 会把算法默认配置的 `script` 绝对路径带入结果，已修为递归丢弃 path/script/dir/file/log/workspace/root 字段并把绝对路径文本替换为 `[redacted]`；该用例固化为回归。结果只代表隔离环境确定性测试，不代表真实 DeepSeek 多轮质量或部署验收。

#### R7 受控助手

| 需求 ID | 实现方法 | 具体测试 | 实跑证据 | 实现验收 | 部署验收 | 剩余边界 |
|---|---|---|---|---|---|---|
| R7-01 | 只读工具复用 `TaskAccessGuard.requireOwned`；写预览校验 owner | `AssistantConfirmationEndToEndIT` 跨用户 403 | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 单进程权限，不是组织级 RBAC/双人审批 |
| R7-02 | 注册表白名单；场景进一步收窄工具集，执行前二次校验，越界 403 并写 `SCENARIO_DENIED` 审计 | `AssistantToolRegistryTest`、`AssistantChatServiceTest`、`AssistantScenarioExecutionTest` | `tmp/stage7-scenario-tests.log` | 通过（隔离） | 未执行 | 模型仍可能尝试未知/越界工具，被服务端拒绝 |
| R7-03 | 写工具只返回预览；确认前不创建或修改实验任务、不写调度意图、不领取 GPU；允许保存确认与审计记录 | `AssistantConfirmationEndToEndIT` 预览用例；`AssistantScenarioExecutionTest.resultExplanationCannotRequestAWritePreview` | `tmp/round3-full-verify-final.log`、`tmp/stage7-scenario-tests.log` | 通过（隔离） | 未执行 | 确认记录和审计会落库，不能表述为“零写入” |
| R7-04 | token 哈希、TTL、条件消费、跨用户校验、篡改审计 | `AssistantConfirmationEndToEndIT` 重复/过期/跨用户/篡改 | `tmp/round2-r7-a.log` | 通过（隔离） | 未执行 | 无数据库泄露后的离线重放防护 |
| R7-05 | 重校验 task/run/attempt/update_time、参数哈希、资源指纹；变化 409 零派发 | `AssistantConfirmationEndToEndIT` 资源/默认值变化 | `tmp/round3-full-verify-final.log` | 通过（隔离） | 未执行 | 无 |
| R7-06 | 模型线程池有界、总预算/工具预算、超时取消；模型 401/429/5xx/非法响应稳定降级，核心 API 不共享失败 | `AssistantChatServiceTest`、`AssistantConfirmationEndToEndIT`、`DeepSeekAssistantModelTest`、`AssistantOutboundPrivacyTest`、`AssistantModelFailureEndToEndIT` | `tmp/round2-r7-a.log`、`tmp/stage7-model-failure-e2e.log`、`tmp/stage7-full-verify.log` | 通过（隔离） | 未执行 | 底层 JDBC 物理终止不保证；真实供应商生产额度未验证 |
| R7-07 | 审计只存工具名/operator/target/resultCode/duration；请求体脱敏 | `AssistantOutboundPrivacyTest`、`AssistantConfirmationEndToEndIT` | `tmp/round2-p1-final.log`、`tmp/round2-r7-a.log` | 通过（隔离） | 未执行 | 无 |

**2026-11-17 三场景执行契约与配置缺失边界（质量改进）**：`scenario` 可选字段只向模型暴露该场景工具，执行前二次校验，越界 403 `ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO` 并审计；缺必要证据降级 `scenario_incomplete`；`structured` 由服务端工具结果组装，模型伪造指标/引用不进入证据。`DispatchConfigBoundaryIT` 2/2 是服务层集成（mock transport），真实 Broker 另由 `DispatchConfigBoundaryRabbitIT` 覆盖重复投递、账本、队列收敛、零 Python 与租约释放；该用例暴露并修复 Worker 配置缺失分支未释放租约。真实 ACL/引用由 `KnowledgeRetrievalMySqlIT` 的检索服务用例验证；`AssistantRankerSampleEvaluationTest` 仅保留为排序器样例。写工具仍走“预览→确认→后端授权与状态机”，口径为“确认前不创建/修改实验任务、不写调度意图、不领取 GPU；允许保存确认与审计”。真实模型自然语言注入行为未由这些服务端边界测试证明。本机全量门禁（`RABBIT_IT=true`、`REDIS_IT=true`）为 **322 单测 + 117 IT，0 失败/0 跳过，BUILD SUCCESS，2:05**（`tmp/stage7-full-verify.log`）；远端 CI run `44053148789`（`612685e`）backend/frontend/python/init-scripts 四个 job 全部 success。首轮 `d71558b` 的 backend 失败已定位为 Broker 用例写死本地端口，修复后通过。

#### R4 单机可靠派发（真实 Broker 双 JVM 已执行，仍有未执行边界）

| 需求 ID | 实现方法 | 具体测试 | 实跑证据 | 实现验收 | 部署验收 | 剩余边界 |
|---|---|---|---|---|---|---|
| R4-01 | rabbit API 同事务写任务/意图/Outbox，不领租约；Relay 条件领取后事务外发布，失败退避 | 真实 RabbitMQ + MySQL + API/Worker；任务 `2122209791151980545` 正常派发；停 Broker 后创建 `2122210212130078722`，重启 Broker 后 Outbox 由 failed 重试至 published；`MySqlTaskPersistenceIT#rabbitDispatchRollbackLeavesNoTaskIntentOrOutbox` 在同一真实 MySQL 连接插入三表后 rollback，三表均 0 行 | `tmp/r4-stack/api.log`、`tmp/r4-stack/worker.log`、`tmp/r4-stack-start5.log`；`tmp/r4-rollback-it1.log` | 通过（正常/停 Broker 恢复/事务回滚零残留） | 未执行 | 跨主机未执行；生产库/真实 LDD 未执行 |
| R4-02 | `message_id` 唯一消费账本、terminal 幂等、旧 token 条件回写；mandatory return + per-publish confirm | 管理 API 重发同一 payload：`routed=true`，Python 启动次数不增，consumption 保持 terminal/success；删除 worker 队列且 API 关闭 RabbitAdmin 自动声明后创建 `2122214846991208449`，Outbox 立即 dead、未进入 published；`RabbitDispatchBrokerIT` 2/2 对真实 Broker 验证已确认消息入队与不可路由 mandatory return；`expiredPublishingIsReclaimedAndOldConfirmCannotWin` 验证旧 claim token 的迟到 confirm 不覆盖新状态；**真实延迟 confirm**：`r4_amqp_confirm_delay_proxy.py` 把 `Basic.Ack` 延迟 7s（应用 confirm 超时 5s），任务 `2122269157720920066` Outbox 3 次 `RABBIT_PUBLISH_FAILED` 后 dead，task 保持 running，未把超时写成成功 | `tmp/r4-stack/workspace/r4-python-invocations.jsonl`；`tmp/r4-confirm-proxy.log`（delayed 18:02:52→released 18:02:59）；`tmp/r4-02-late-dead-row.txt`；`tmp/r4-02-late-api-lines.txt`；`tmp/r4-broker-it1.log` | 通过（重复幂等 + 不可路由 + 旧 token 条件回写 + 真实 confirm/return + 延迟 confirm 不误判成功） | 未执行 | 生产网络分区/跨主机未执行；延迟代理是本地真实 AMQP 帧注入，不改应用与 DB |
| R4-03 | Worker 心跳、进程身份、孤儿隔离、未确认退出不释放槽位 | 短桩 sleep 300 秒启动 Python 后强杀 Worker（PID 14424），重启 Worker；recovery 检测 PID 4736 存活并杀进程树；任务 `2122215986243866625` 收敛 failed、intent abandoned、consumption terminal `DISPATCH_UNCERTAIN_RECOVERY`，无新 attempt | `tmp/r4-stack/worker.log`（`Killed orphaned executor process tree ... pid=4736`）、`tmp/r4-stack/taskid-orphan.txt`；`sys_gpu_lease` 该任务行 0，槽位 released | 通过（孤儿隔离/不重跑） | 未执行 | 跨主机/网络分区未执行 |
| R4-04 | `GPU_BUSY` 回滚 pending；DB 故障延迟重投；毒消息 REJECT→DLQ；5/30/120 退避 | 人工占用 `default-gpu-0` 后启动任务 `2122216323369439234`；Worker 三次 5/30/120 重试后消费记录 failed、任务 failed、intent abandoned、Outbox dead、DLQ 1；向队列发布 `not-json` 毒消息，Worker 拒绝并进入 DLQ；任务 `2122228571957653506` 的 worker 通过 TCP 代理连 MySQL，切断代理后 worker 租约失联、consumer 停止，消息保留在 worker 队列且未 ACK；恢复代理并重启 worker 后任务 success、consumption terminal、Python 1 次。补充任务 `2122257618284634114`：切断代理后 `17:17:18` 停止消费，`17:17:25` 恢复代理但**不重启 Worker**，`17:17:28` 自动重取归属并重跑恢复；本地 accepted=1 时连续四次拒绝开消费，`17:17:46` 任务 success、accepted 清零后 `17:17:48` 重跑一次恢复并启动容器；任务 success、run `ca09e4d5-cd98-418f-adb1-241228c4834b`、intent submitted、Outbox published 1、consumption `TASK_SUCCESS`、Python 1 次 | `tmp/r4-stack/taskid-gpubusy.txt`、`tmp/r4-stack/worker.log`（GPU_BUSY、`拒绝非法调度事件`、`Communications link failure`）；Rabbit 管理 API DLQ messages 2；`tmp/r4-db-outage-db.txt`；`tmp/r4-db-auto-recover3-worker.txt`、`tmp/r4-db-auto-recover3-db.txt` | 通过（GPU_BUSY 耗尽 + 毒消息 + DB 中断不误 ACK + 无重启自动恢复并延迟开消费） | 未执行 | 长期 DB 故障、多任务退避与跨主机未压测 |
| R4-05 | 取消独立巡检；取消先/完成先条件更新；不确定不重跑 | 真实任务 `2122210811173199873` 启动 2 秒后取消；任务 `2122227016860782594` 先完成 success，再调用 cancel 返回 `TASK_COMMAND_INVALID`，终态仍为 success | 任务 `2122210811173199873` canceled、consumption terminal/canceled `TASK_CANCELED`、GPU lease released、Python 启动 1 次；`tmp/r4-cancel-after-success.json` | 通过（执行中取消 + 完成后取消不覆盖终态） | 未执行 | 无 |
| R4-06 | `mode/role` 校验；`db/worker` 拒绝启动；旧队列隔离与停机切换 | 独立 JVM 以 `mode=db role=worker` 启动，20 秒内退出码 1；30 秒 sleep 任务运行期间 `R4.DrainCheck.ps1` 拒绝切换；停机旧队列隔离；**完整双向停机切换**：`db` 任务 `2122266301773209601` success → 停 API 停止接收 → drain 全 0 → `rabbit` API+Worker → 任务 `2122266483373920257` success（Outbox published 1、consumption terminal/success、Python 1）→ 停 API、drain、停 Worker → 切回 `db` API → 任务 `2122266707249192962` success | `tmp/r4-06-drain-db.txt`、`tmp/r4-06-drain-rabbit.txt`、`tmp/r4-06-db-task.json`、`tmp/r4-06-rabbit-task.json`、`tmp/r4-06-db-back-task.json`、`tmp/r4-06-db-rows.txt`、`tmp/r4-mode-reject2.log`、`tmp/r4-old-queue-isolation.json` | 通过（启动拒绝 + drain 拒绝 + 旧队列隔离 + db→rabbit→db 双向切换无丢失/无双派发） | 未执行 | 应用层没有运行期热切换入口，切换必须停机；跨主机切换未执行 |
| R4-07 | rabbit API 恢复只标记就绪，不扫描/误杀 Worker 任务 | Worker 执行 sleep 任务 `2122210676426948609` 时重启 API（PID 29980→12676） | Worker 不中断，任务 success、Outbox published 1、consume terminal success、Python 启动 1 次；`tmp/r4-stack/taskid-apirestart.txt` | 通过 | 未执行 | 多 API 实例未执行 |
| R4-08 | 运维查询 admin-only；`POST /api/observability/dispatch/dead/{messageId}/replay` 仅重放 `dead + running + pending`，failed/uncertain 拒绝并指向任务重试 | 对已 failed 死信重放返回 `DLQ_REPLAY_REQUIRES_NEW_ATTEMPT`；非 owner 403；Broker 故障自然 dead 后成功重放；replay/cancel 并发；**旧 dead 与新 attempt 真实竞争**：任务 `2122268541581815809` attempt 1 因 `RABBIT_UNROUTABLE` dead `74532340...`、task failed；重试 attempt 2 运行中调用 replay，HTTP 400 `DLQ_REPLAY_IDENTITY_MISMATCH`，attempt 2 最终 success、consumption terminal/success、旧 dead 仍 dead、Python 仅 attempt 2 一次 | `tmp/r4-natural-dead-*`、`tmp/r4-race-*`、`tmp/r4-08-unroutable3-task.json`、`tmp/r4-08-replay-response.json`、`tmp/r4-08-new-attempt-rows.txt`、`tmp/r4-08-new-attempt-final.json` | 通过（自然 dead + 成功重放 + failed 拒绝 + 非 owner 403 + replay/cancel 并发 + replay/新 attempt 竞争） | 未执行 | 跨主机/多 Worker 并发未执行；旧 SQL 注入 dead 证据仅保留历史 |

**R4 当前结论**：真实 RabbitMQ 4.1.8 + Erlang 27.3.4.18 + 隔离 schema/vhost + 双 JVM + 短 Python 桩已执行 R4-01（正常派发、停 Broker 恢复、事务回滚）、R4-02（重复幂等、不可路由、旧 claim token、真实 confirm/return、**7s 延迟 confirm 不误判成功**）、R4-03、R4-04（GPU_BUSY、毒消息、DB TCP 中断、无重启自动恢复消费）、R4-05（执行中取消、完成后取消）、R4-06（启动拒绝、drain 拒绝、v2 旧队列隔离、**完整 db→rabbit→db 停机切换**）、R4-07、R4-08（Broker 故障自然 dead、成功重放、权限、replay/cancel 并发、**旧 dead 与新 attempt 真实竞争**）。剩余边界仅是 R4-04 长期 DB 故障/多任务退避压力与跨主机；本轮指定必选缺口已补。**不得标记为生产部署通过**：生产库 `eladmin` 未迁移、真实 LDD/GPU 未运行。提交 `ee10704`（代码/测试）与 `cd5a6ae`（文档）；run `43846823071` backend/python/frontend 全绿，但仍不等于生产部署通过。

**未通过 ID**：本轮 R4-01..08 指定必选用例未发现失败；长期 DB 故障/多任务退避与跨主机仍是压力/部署边界，不把隔离环境结果写成生产通过。**未执行部署验收**：R3/R6/R7 全部条目；直接原因是生产库 `eladmin` 未迁移、未运行真实 LDD/GPU。组织级双人审批、多实例网络分区属于未来扩展，不是本轮单机部署的必选门槛；R4/R8 也不因它们提前阻塞。

## 7. R4：单机 API + 独立 Worker + Transactional Outbox + RabbitMQ

仅用户选择后实施；前置 R3 全部通过。范围限一台机器上的 API + 独立 Worker，沿用受控共享目录，不默认引入对象存储/Kubernetes。**2026-11-16 已按评审修订契约并实现主体代码**：`21-r4-outbox-rabbitmq-design.md` / ADR-026 冻结“rabbit API 不领租约、角色化恢复、Outbox 领取回收、真实完成信号、取消/完成先后语义、DLQ 重放分级”；真实 MySQL + RabbitMQ + 短 Python 桩验收必须在环境具备后执行，缺少 RabbitMQ 时登记阻塞。

已落地实现：`sql/security_model_task_outbox.sql`/回滚脚本、`DispatchOutbox`/`DispatchConsumption`/`DispatchEvent`、`DispatchRuntimePolicy`、`TaskOutboxService`、`TaskDispatchConsumptionService`、`DispatchTransport`/`RabbitDispatchTransport`、`RabbitDispatchConfiguration`、`TaskOutboxRelay`、`DispatchMessageHandler`/`DispatchMessageListener`、`WorkerCancellationMonitor`、`DispatchOperationsService`/`DispatchObservabilityController`；命令服务 rabbit 分支、`TaskDispatchCoordinator.consume`、角色化 `DispatchRecoveryCoordinator`、`DefenseTaskExecutor.executeAndWait`。单测覆盖 Relay/Transport/Handler；MySQL IT 增加事务回滚，真实 Broker IT `RabbitDispatchBrokerIT` 2 项通过。默认仍为 `db/api`，Rabbit 组件仅 `mode=rabbit` 加载。

实施顺序：

1. ADR 固定事件 v1（messageId/taskId/runId/attempt/schemaVersion），配置 `dispatch.mode=db|rabbit`、`dispatch.role=api|worker|all`；db 仅 api/all，rabbit 的 API 只写 Outbox、Worker 才执行。
2. 在业务事务写任务冻结快照/意图/Outbox；rabbit 模式 API 不领取 GPU。发布器按行条件抢占并写 `claim_owner/claim_token/claim_until`，publisher confirm 后条件记账，过期 `publishing` 回收，重复发布保持同一 `messageId`。
3. durable queue、持久消息、manual ACK、prefetch=1；Worker 以数据库当前 run/attempt 和消费记录做幂等，消息不携带凭据或任意路径。
4. Worker 在消费事务内领取单槽，复用 LDD 协议/进度/取消/产物授权；`executeAndWait` 真实完成后才 ACK；`GPU_BUSY` 回滚意图为 pending 并保持可重试。
5. 临时分发失败最多重试 3 次，退避 5/30/120 秒；算法 OOM/协议错误不自动重训。死信查看/人工重试均走 owner/admin 权限及审计；pending 可同 attempt 续传，failed/uncertain 创建新 attempt。
6. 独立取消巡检扫描数据库取消状态；取消先成功拒绝迟到结果，完成先成功拒绝取消并保留原终态；实现 Worker 失联和 R2 隔离；提供停机切回 db 模式步骤，活跃消息/任务未清空时拒绝热切换，旧队列消息隔离。

验收 ID：R4-01 事务回滚/停 MQ 后恢复投递；R4-02 confirm 丢失/重复消息/迟到确认；R4-03 Worker 崩溃不放行仍存活进程；R4-04 GPU_BUSY、DB 故障、毒消息的有限重试/DLQ；R4-05 取消与迟到结果（完成后取消保留原终态）；R4-06 两模式互斥、旧消息隔离及回退；R4-07 API 重启不误杀 Worker 任务；R4-08 权限隔离与人工重试。必须真实 RabbitMQ + MySQL + 短 Worker 测试，单元 mock 不作阶段完成证据。真实 LDD 不必重跑。

## 8. R5：真实观测、Redis 与部署复现

前置 R2；可在不启用 R4 时实施。已有 Actuator/Redis 先复用，不重复选框架。

实施顺序与验收：

| ID | 开发步骤 | 指标/验收标准 |
| --- | --- | --- |
| R5-01 | 定义计数/耗时、执行池队列、槽位 active/blocked、恢复次数 | 指标来自实际事件/数据库；禁止 userId/taskId 作为高基数指标标签 |
| R5-02 | 日志关联 taskId/runId/attempt；敏感字段统一过滤 | 一次短任务可串联创建到终态；无 token、凭据、完整路径和样本 |
| R5-03 | 缓存命中/回源/降级计数；资源写入提交后失效 | 真实 Redis 验证 hit/miss、更新后不长期读旧值、宕机仍回源；并发旧值回填有版本/代际或等效保护，TTL 仅作兜底 |
| R5-04 | 仪表盘展示任务、槽位、缓存和进度陈旧情况 | 两账号统计不泄露对方数据；全局机器指标仅管理员；不可采集显示未知 |
| R5-05 | 管理端健康接口鉴权、最小暴露 | 匿名不能访问指标/配置；不暴露 env、heapdump 或敏感详情 |
| R5-06 | 提供本地配置样例、启动/停止/备份/排障和 CI | 从干净隔离数据库依手册启动；不提交密码，记录本地/远端证据区别 |

CPU/GPU 利用率采集为可选指标，未启用可显示未知；任务/队列/槽位/缓存指标为必选。未做 R4 时不显示虚构 Outbox/DLQ 数据。

### R5 实施记录（2026-11-13）

| 需求 ID | 实现 | 证据/结果 | 未完成边界 |
| --- | --- | --- | --- |
| R5-01 | `TaskRuntimeMetrics` 聚合任务状态、陈旧 running、执行池、槽位、恢复和执行耗时 | 单元测试编译/运行；指标标签低基数 | 未做真实 MySQL 长时观测 |
| R5-02 | `RequestCorrelationFilter` + MDC taskId/runId/attempt + logback pattern | `RequestCorrelationFilterTest` 2 项 | 未做日志脱敏全量审计 |
| R5-03 | Redis hit/miss/degraded 计数 + generation 条件回填 + 提交后失效 | `ResourceCatalogCacheTest` 10 项；`ResourceQueryServiceTest` 4 项；`ResourceCatalogCacheRedisIT` 1 项真实 Redis 并发回填/代际测试通过 | 未注入真实 Redis 宕机（停止共享 Redis 会影响本机其他服务，未执行） |
| R5-04 | `/api/observability/summary` 管理员汇总；统计页读真实计数 | 服务单测 2 项；真实 Security 链 IT 覆盖管理员 200/普通用户 403；管理端不返回 taskId/userId | 前端管理卡片仅在管理员 200 时显示 |
| R5-05 | `/actuator/health` 匿名；metrics/info 仅 `ROLE_ADMIN`；`show-details=never`；显式 401 entry point | `OwnershipEndToEndIT` 真实 Security 链覆盖 health 200、metrics 匿名 401、普通用户 summary 403、管理员 summary 200 | 未测试 env/heapdump 等未暴露端点（配置未开启） |
| R5-06 | 06 迁移版启动/停止/备份/排障、08 Redis 配置、`.env.example` | 文档 | 未从干净隔离库完整照手册演练 |

本机单测为 **127 项，0 失败/0 跳过**；`REDIS_IT=true` 时另有 1 项真实 Redis 缓存代际 IT；前端 `npm run build` 通过；MySQL IT 41 项全绿。不得据此宣称 R5 全部验收或“生产可观测体系已完成”。

## 9. R6：有引用的知识检索与外部模型

前置 R1/R5。默认先采用本地全文/关键词检索，复用 MySQL 或成熟嵌入式索引，不部署本地模型、不新增向量数据库。中文切词/排序采用可验证方案写 ADR；以后引入 embedding 不作为本阶段默认门槛。

实施顺序：

1. 知识源白名单仅 SDD、算法参数说明和经允许的报告摘要；建立 document/version/hash/section/ACL/chunk 元数据。默认只索引共享技术文档；私有报告必须绑定 owner。
2. 导入先脱敏并验证来源，分块保留章节和稳定定位；更新采用新版本构建完成后切换，删除后不可召回旧块。
3. 检索前按用户权限限定候选集合，再排序；禁止取全库结果后靠前端过滤。
4. 业务只依赖 `KnowledgeAnswerModel` 端口，外部模型适配器独享供应商协议/鉴权/重试/解析；用 `provider` 选择一个适配器，默认 `openai-compatible`，新增供应商时只增加实现并切换配置。适配器支持 baseUrl/model/key/timeout 配置，默认关闭；超时 30 秒、每请求最多一次自动网络重试，避免无限费用。具体供应商/预算/留存需人类确认。
5. 服务端组装外发字段白名单，过滤绝对路径、凭据、身份信息及被禁止内容；问题本身也需校验/脱敏，不能只过滤检索结果。
6. 返回答案与文档版本/章节/引用 ID；命中不足明确未知。模型不可用降级为检索片段和明确状态，不编造回答。
7. 建立至少 20 题评估集：10 题有效知识、5 题未知、5 题越权/注入；页面包含引用查看、空结果、失败和禁用状态。

验收：R6-01 固定有效题至少 8/10 在 top5 命中标注来源；R6-02 所有输出引用可定位且与当前版本一致；R6-03 未知题不捏造事实；R6-04 越权/删除文档零泄露；R6-05 捕获实际 HTTP 请求体验证禁发字段为零；R6-06 超时/限额/无密钥降级；R6-07 文档注入不能改变工具权限。记录评估集和失败样例，不把这些阈值当成算法论文指标。

没有供应商授权/密钥时继续完成离线检索、模型适配器和假 HTTP 服务测试；状态标“外部模型联调阻塞”，不是完整 R6 完成。

实施记录（2026-11-14，外部模型联调阻塞）：

1. schema：`sys_knowledge_document`/`sys_knowledge_chunk` 已落地；旧版本保留、`active` 才可召回；私有报告绑定 `owner_user_id`。
2. 导入：管理员显式触发 SDD/算法重建；SDD 扫描拒绝符号链接与越界，先脱敏再 SHA-256，内容未变跳过，变化时同一事务写新版本+chunk 再 retire 旧版本；算法只索引启用项；任务报告仅 owner 可索引，demo 拒绝。
3. 检索：先用 ACL SQL 限定候选，再用确定性中文单字/二元组 + IDF 加权排序；无命中 `unknown`，不调用模型。
4. 回答：模型默认关闭；无 key/未配置/超时/4xx/5xx/空响应均降级 `retrieval_only`；模型编造候选集外引用时丢弃答案；请求体为白名单，不含路径/凭据/身份/样本/日志。
5. 页面：知识检索页包含答案、检索模式、无依据、引用卡片和引用行号；管理员索引维护 403 有明确提示。
6. 证据：R6 固定评估集 10 题有效全部在 top5 命中标注来源，5 题未知零召回，5 题越权/注入不泄露私有报告；真实 MySQL ACL IT 覆盖共享/私有隔离、旧版本不可召回、显式删除与写入版本切换；假 HTTP 服务捕获请求体验证禁发字段为零；本机 `mvn verify -Pmysql-it`（`REDIS_IT=true`）为 174 项单测 + 45 项 MySQL IT + 1 项 Redis IT 全绿，Python 11/11，前端构建通过。具体见 `09` §5.9、§9。
7. 未完成：供应商/预算/数据留存授权、真实模型质量、生产库建表、20 题之外的更大语料评估；因此本轮只写“离线检索与适配器完成，外部模型联调阻塞”，不写 R6 验收通过。

## 10. R7：受控实验助手

前置 R3/R6。先完成只读助手，再完成有确认的写工具；两部分完成才称 R7 完成。模型不直接执行 SQL、shell、Python 或任意 HTTP。

实施步骤：

1. 工具注册表采用显式名称和 typed schema；只读工具仅参数说明、本人任务摘要、本人报告摘要、知识检索。每次调用都走现有 Java 授权服务。
2. 模型仅接收允许的脱敏摘要；记录工具名、操作者内部 ID、目标 task/run、耗时、结果码，审计不保存完整提示词/原日志/密钥。
3. 默认单请求最多 6 次模型调用、5 次工具调用、链路总超时 60 秒；按用户配置日请求配额，默认 50。超额明确拒绝，不影响普通任务 API。
4. 参数草稿先 schema 校验，助手不得自动改变已有 128/5 参数或直接启动真实训练。
5. 写工具限定创建/启动/取消/重试/导出，先返回预览与服务端生成的随机确认凭据；绑定 userId、操作、目标、规范参数 hash、task/run 版本，TTL 5 分钟，数据库仅存凭据 hash。
6. 确认接口重新鉴权、校验状态/参数/有效期，原子消费凭据；与业务提交和 R3 幂等衔接，重复确认返回原结果或明确已处理，不能重复启动。确认内容变化必须重新预览。
7. 默认会话保留脱敏结构化摘要 7 天，用户可删本人会话；定期清理。增加助手页面、引用、工具结果、待确认与失败状态。

验收 ID：R7-01 跨用户工具调用拒绝；R7-02 提示/文档注入不扩大工具集；R7-03 无确认零写入；R7-04 重复/过期/篡改/跨用户凭据不能二次执行；R7-05 参数或任务版本变化使确认失效；R7-06 模型断线/预算超限不影响核心服务；R7-07 审计和请求体无禁发内容。写工具必须真实 MySQL + HTTP 测试，执行器用短替身，不启动 LDD 长训练。

模型调用额度和会话保留期是可配置工程默认值，不等于获准使用付费供应商或外发新增数据类型。

实施记录（2026-11-14 初版；2026-11-15 本机 DeepSeek 与审计整改）：
1. 新增 `sql/security_model_assistant.sql`：确认凭据、会话、工具审计、日配额四张表；确认 token 只存 SHA-256。
2. `AssistantToolRegistry` 注册 4 个只读工具和 5 个写预览工具，未知名称拒绝；没有 shell/SQL/任意文件工具。
3. 只读任务/报告复用 `TaskAccessGuard.requireOwned`，知识检索复用 ACL-first 服务；跨用户 403。
4. 写工具只返回预览、风险说明、规范请求和上下文哈希；创建/启动/重试预览重新解析资源并返回 `configFingerprint` 与 `finalParameters`；token 为 256-bit 随机值，TTL 默认 300 秒；无确认不写业务表、不领取 GPU。
5. 确认服务在事务内重校验 user/token/TTL/task-run-attempt-update_time/参数哈希；create 重新构造命令并比对 `expectedConfigFingerprint`，start/retry 用当前 `plan(task).fingerprint()` 生成上下文哈希；参数或资源变化返回 409 `ASSISTANT_CONFIRMATION_STALE`，不领取 GPU、不写意图、不派发；条件 `pending→consuming` 后执行原命令，并接入 R3 幂等；重复确认返回原结果并带 `confirmationReplayed=true`。
6. `AssistantChatService` 使用有界模型线程池（4 线程 + 16 队列）、全局 4 请求并发槽和单用户并发槽（`max-concurrent-per-user`，默认 1，范围 1–4）；模型调用 Future 最长等待剩余预算并在超时后 `cancel(true)`。2026-11-15 起读工具和写预览都通过独立 `toolExecutor`（4 线程 + 16 队列，`DelegatingSecurityContextExecutorService` 包装以传播登录身份）接收剩余预算，`AssistantTool.execute(arguments, timeoutMillis)` 默认委托原方法；超时 `cancel(true)` 并返回 `ASSISTANT_TOOL_TIMEOUT`，主循环映射 `status=timeout`、`modelStatus=tool_timeout`，不再发起后续工具/模型调用。`application.yml` 的有效键为 `mybatis-plus.configuration.default-statement-timeout=5s`，并有 `SqlSessionFactory` 断言；Redis 命令仍有 500ms 约束。写预览在工具返回后、插入确认记录前再次检查 deadline，超时不 insert；`930c934` 后三个 preview 重载加事务，insert 完成后再检查 deadline，若 insert 阻塞跨过 deadline 则抛 `ASSISTANT_TOOL_TIMEOUT` 并回滚。真实 MySQL 触发器 `SLEEP(0.8)` 已验证 100ms 预算下确认表 0 行、审计 1 条 FAILED、任务仍 running；边界是触发器模拟可控阻塞，不是真实行锁/表锁等待，`Future` 取消也不等价于底层 JDBC/Redis 被物理终止。
7. 模型端口 `AssistantModel` 默认 `provider=disabled`，明确返回 `model_disabled`；本机 `deepseek` profile 已接入真实 `DeepSeekAssistantModel`，但生产默认仍关闭，核心任务/报告/知识接口不受影响。2026-11-15 强化 `AssistantOutboundPrivacyTest`：真实 `AssistantChatService` + 真实适配器 + 本地假 HTTP 连续两次请求，第一次返回工具调用、第二次返回最终回答；断言两次最终 body 都不含 `/datasets/private`、`/训练数据`、`/secret.txt`、`/api/private`、`/custom dir`，且出现 `[已过滤路径]`，API Key 与 `Authorization` 不进入 body。
8. 新增 `/api/assistant/*` 和 `/assistant` 页面；会话 7 天、可删除，审计只存工具名/operator/target/resultCode/durationMs。
9. 当前证据（2026-11-15 基于 `930c934` 五项收口，并在 `08f11c0` 后补 P1/P2/R3；2026-11-16 追加 R4 真实 Broker 证据与四项阻断修复）：助手核心单测 12 项（含工具线程真实身份、工具预算传播与超时）+ 参数证据 4 项 + 脱敏 7 项 + 预览 deadline 1 项；真实 HTTP + MySQL 助手确认/会话链路 22 项（五类写工具首次/重复/并发、预览零写入、重复返回原结果、跨用户 403、删除会话不可复活、并发会话不丢记录、预览后算法默认值变化 409 且零租约/零派发、导出跨用户下载 403、alice/bob 线程复用身份、有效 MyBatis-Plus 超时=5、insert 阻塞超时、错误 token 篡改、并发身份隔离）；真实命令服务链路 7 项；独立 JVM 崩溃/恢复/竞争 5 项（执行器为短 Python 桩）；最新 `mvn verify -Pmysql-it`（`REDIS_IT=true`、`RABBIT_IT=true`，2026-11-16 四项阻断修复后）为 **287 项单元测试 + 106 项 MySQL/Redis/RabbitMQ 集成测试全绿**，日志 `tmp/stage2-full-verify-new.log`；R4-02/06/08 的剩余真实缺口已补（7s 延迟 confirm、db→rabbit→db 完整停机切换、旧 dead 与新 attempt 真实竞争），R4-04 长期 DB 故障/多任务退避仍为压力边界；Python 11/11，前端构建历史通过；提交 `ee10704`/`cd5a6ae` 的 run `43846823071` 全绿，R3/R7 仍不得整体验收。
10. 2026-11-15 外发与检索整改：`KnowledgeSanitizer` 新增可含空格的绝对文件路径规则，且不再豁免 `/api/` 等 API 路由前缀；自由文本中的任意绝对多段路径统一替换为 `[已过滤路径]`。`KnowledgeVectorStore` 在 `VADD` 写入 `SETATTR`（visibility/owner/documentId），`VSIM` 用 `FILTER (.visibility == "shared" or .owner == <userId>)` 在候选阶段完成 ACL，管理员跨用户读不加过滤；`KnowledgeIndexService.retire()` 成功后 best-effort 清理向量；`retrievalMode` 仅在本轮向量命令成功时返回 `hybrid`，失败降级为 `lexical`。
11. 2026-11-15 混合检索评估：`KnowledgeRetrievalService` 先按 `candidateLimit` 生成词法池，词法池为空直接返回空，向量只合并进词法池；`KnowledgeHybridEvaluationTest` 覆盖可答 10 + 未知 5 + 越权/注入 5 共 20 题，`hybrid answerable top5 hits: 10/10`，并验证私有 `task_report:99` 向量不能注入共享语料结果。哈希 embedding 仍只是词法候选重排，不是神经语义召回。
12. 2026-11-15 `08f11c0` 后 R3 证据补齐：`CrashWindowChildMain` 改为写入真实 `request_json` 与 SHA-256、runtime hash；`independentJvmPendingIntentRunsRealReplayCoordinatorOnce` 用真实 `TaskDispatchCoordinator` + 真实持久意图 + 真实本地接受登记，断言执行器收到一次请求、意图变 `submitted`、任务仍 `running`，第二次恢复不再执行；同时修复 `DefenseTaskRequest`/`DefenseResourceRef` 缺少 Jackson 反序列化构造器导致的 `DISPATCH_SNAPSHOT_INVALID`。**该用例执行器仍是测试替身**，作为构造状态/协调器证据保留，不再代表“生产执行器真实链路”。
13. 2026-11-15 最新收口（P1/P2/R3）：`R3ProductionExecutorChainIT` 5/5 使用完整 Spring `@Async` 代理、真实 `PythonLddTaskExecutor`、真实事务/Mapper/租约/协调器，覆盖正常、取消、超时、非法结果，以及 `R3ProductionCrashChildMain` 真实派发后崩溃 + `R3RecoveryChildMain` 恢复（孤儿 Python 被杀、租约释放、意图 abandoned、`executions.log` 仅 1 行）；`KnowledgeRetrievalMySqlIT` 6/6 用真实 MySQL+Redis 验证退役清理跨批/失败不阻塞/重启继续/幂等，`KnowledgeVectorCleanupMigrationIT` 1/1 验证升级/回滚重复执行；`KnowledgeSanitizerTest` 9/9 + `AssistantOutboundPrivacyTest` 1/1 覆盖单字符/数字/隐藏/空格目录和两轮最终 body。门禁为 **232 单测 + 96 IT**（JDK20/JDK17 各一次），Python 11/11，前端构建通过；日志 `tmp/round3-*.log`。
14. 仍未做：真实 LDD、生产库 `eladmin` 建表/迁移、组织级双人审批/分布式锁、真实行锁/表锁等待（R7 触发器是可控阻塞）、多实例网络分区下的恢复、R4 长期 DB 故障自动退避收敛压力、R4 跨主机停机切换。R4-02 延迟 confirm、R4-06 db→rabbit→db 双向停机切换、R4-08 旧 dead 与新 attempt 真实竞争已补；已执行的真实 Broker 场景与边界见 §6.8；不得因“代码已实现”写成部署验收通过。真实执行器链路使用短协议桩，不运行 LDD、不占 GPU；崩溃子 JVM 是测试最小上下文 + 手动异步包装，不是完整 Spring 容器。上述未做项不否定已经通过的隔离测试；按 §6.8 只能写“实现验收通过（隔离）/部署验收未执行”，不得写成生产部署验收通过。

## 11. R8：可选多实例学习验收

仅用户选择且 R4/R5 已通过后实施。单机两个独立进程/容器可验证控制面竞争，但不能据此宣称多机 GPU 高可用。

步骤：标记实例 ID → 限定恢复只处理失联持有者 → 协调器/Outbox 多实例条件领取 → 两 Worker 指向同一受控共享目录 → 故障/重复投递/取消测试 → 记录资源与吞吐。单卡仍单槽。

验收 ID：R8-01 一个实例启动不杀另一实例活任务；R8-02 两发布器/消费者竞争无双执行；R8-03 失联实例未确认退出前隔离；R8-04 共享存储不可用时拒绝成功回报；R8-05 重复和迟到结果不覆盖新 run。提供两进程实际证据；跨机器存储和远程 GPU 未测试时列为未实现。

## 12. 文档同步与变更范围

每轮至少更新 09 的实际状态、相关 ADR 的决策/实施/证据分栏，以及本任务书需求映射。涉及协议更新 07；权限更新 18；进程/目录/清理更新 19；部署更新 06/08；架构改变更新 03/11；对外能力改变同步 README 与面试材料。

不得只在 09 填“完成”，保留 17/18/19 旧的相反描述。历史记录保留日期并写“当时状态”，当前状态明确指向最新验收。未真实执行的测试写编译保证/未执行；本地通过不等于 CI 通过，隔离库迁移通过不等于业务库已迁移。

实现与本文冲突时：无安全影响的内部实现选择可自行记 ADR；扩大权限、外发范围、自动执行范围、修改算法或触及真实数据时停止该依赖项并汇总提问。不要为满足表面“完成”降低验收。

## 13. 开发交接模板

```text
阶段 / 基线提交 / 最终提交或未提交状态：
本轮范围与非目标：
需求映射：逐个 Rn-ID → 实现方法 → 测试方法 → 证据 → 结果
关键不变量与状态转换：
数据库：新库初始化 / 升级 / 重复运行 / 回滚及停机条件：
API/配置：新增、默认值、兼容性、失败码、权限：
测试：原样命令、日期、环境、通过/失败/跳过数量、日志位置：
故障演练：注入点、进程/任务/槽位前后状态：
文档：逐文件说明同步了哪些事实：
未执行项与原因 / 仍需人类提供的外部条件：
独立审查需要重点检查的风险：
结论：阶段完成待审 / 实现完成验收阻塞 / 未完成（不得混写）
```

给开发 Agent 的指令：

```text
请按 docs/sdd/17-agent-development-guide.md 和
docs/sdd/20-stage-execution-and-acceptance.md 完成 <R编号> 全部必选项。
先做源码差异核查，已有实现不重写；建立逐项验收映射，按默认决策连续开发。
内部切片完成后继续完成其余必选项，不逐次等待我确认。
只在真实数据操作、长训练、付费服务或权限/范围变化时汇总提问；
不依赖答案的任务继续完成。测试不足不得标通过，不能用 mock 替代要求的 IT。
阶段结束按第13节交接，由独立审查 Agent 复核后再进入下一阶段。
```
