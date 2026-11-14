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

仍未完成、不得据此宣称 R3 全部验收：R3-02 的“真实 JVM 重启 + 重放执行一次”端到端、R3-04 带意图的短进程崩溃演练、R3-07 新 attempt 覆盖旧 run 的真实库竞争用例、R3-05 取消与瞬时完成全链路，以及生产库 `eladmin` 建表。R3-01 的服务层事务回归也仍需补；当前证据只到数据库事务与单元 mock。

## 7. R4：可选 RabbitMQ 与单机独立 Worker

仅用户选择后实施；前置 R3 全部通过。范围限一台机器上的 API + 独立 Worker，沿用受控共享目录，不默认引入对象存储/Kubernetes。

实施顺序：

1. ADR 固定事件 v1（messageId/taskId/runId/attempt/schemaVersion），配置 `dispatch.mode=db|rabbit`，启用一种时另一种不能派发。
2. 在业务事务写 Outbox；发布器按行条件抢占，publisher confirm 后记成功，未确认有限退避重发。
3. durable queue、持久消息、manual ACK、prefetch=1；Worker 以数据库当前 run/attempt 和消费记录做幂等，消息不携带凭据或任意路径。
4. Worker 领取单槽，复用 LDD 协议/进度/取消/产物授权；只有终态已持久化或受控转移到重试/DLQ 后 ACK。
5. 临时分发失败最多重试 3 次，退避 5/30/120 秒；算法 OOM/协议错误不自动重训。死信查看/人工重试均走 owner/admin 权限及审计。
6. 实现取消意图、Worker 失联和 R2 隔离；提供停机切回 db 模式步骤，活跃消息/任务未清空时拒绝热切换。

验收 ID：R4-01 停 MQ 后恢复投递；R4-02 confirm 丢失/重复消息只产生一次有效业务完成；R4-03 Worker 崩溃不放行仍存活进程；R4-04 DLQ/有限重试；R4-05 取消与迟到结果；R4-06 两模式互斥及回退。必须真实 RabbitMQ + MySQL + 短 Worker 测试，单元 mock 不作阶段完成证据。真实 LDD 不必重跑。

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
