# 项目目标与阶段进度说明

更新时间：2026-11-14（R6 离线检索与引用回答实施）

2026-11-13 文档与实现迭代：R2 第四切片后实现 R3-A/R3-B 代码（持久调度意图、条件抢占、统一启动恢复、有界 pending 扫描、create/start/retry 请求幂等），并完成 R5 第一批（运行指标、日志关联、Redis 代际缓存、管理员观测接口、Actuator 最小暴露、真实统计页），本机实跑 127 项单测 + 41 项 MySQL IT + 1 项真实 Redis 缓存代际 IT；带意图的真实 JVM 重启、R3-04 崩溃演练、真实 Redis 宕机、生产库建表仍未完成，R3 仍待外部复审。文档中未执行的真实 LDD 与生产库迁移仍保持未验证状态。

当前迁移版已接入 Redis 资源目录缓存，并具备 MySQL 集成测试和对应 CI 工作流配置。
最近一次记录的本机 MySQL profile 验收覆盖原子领取、完成与恢复竞争、孤儿任务恢复边界，
测试使用临时隔离 schema。迁移后端以 MySQL 保存任务状态，通过有界 JVM 线程池调用 Python
LDD 进程；RabbitMQ/Kafka 未接入。

R0 已完成一次以源码为准的事实审计（2026-11-13）：修正了 7 处与代码冲突的文档表述（清单见
[05-decisions.md](05-decisions.md) ADR-012），冻结了状态机、执行/结果协议与目录语义，并把
多用户归属、共享资源权限、历史数据迁移、GPU 并发列为待决事项。**本轮不实现授权、不接入
消息队列/RAG/Agent、不改动也不重跑 128 batch / 5 epochs 的既有 LDD 工程验证配置。**

## 1. 项目整体目标

本项目由早期网络安全竞赛项目重构而来，目标是形成“面向数据清洗的安全模型训练系统”。系统围绕图像训练数据的后门中毒风险，提供数据集管理、LDD 清洗任务、任务监控、参数快照、实验报告和清洗子集导出能力。

当前真实执行阶段只开放 LDD 训练前数据清洗；其他训练前对比算法、训练中防御和训练后防御
已经补齐页面展示和算法目录，但仍不进入真实任务执行范围。

## 2. 当前技术基线

当前运行基座为 Spring Boot 3.5、Java 17、Spring Security 6、Vue 3、Vite、Element Plus、
Pinia、MySQL、Redis 和 Python。RabbitMQ/Kafka 尚未接入迁移版运行链路。旧 Spring Boot 2.7 +
Vue 2 工程继续作为业务回归和回滚基线，不参与迁移版主流程。

框架迁移已经完成当前 LDD 业务范围，迁移版保留任务协议和 Python 执行器契约，使算法运行时
可以独立演进。

## 3. 当前业务流程

```text
登记图像数据集
  -> 选择 LDD 和超参数
  -> 创建训练前清洗任务
  -> 校验资源和参数
  -> 生成执行参数快照
  -> 调用 VaB LDD
  -> 保存索引、图像、日志和结构化结果
  -> 查看进度和实验报告
  -> 按保留索引导出清洗后的 CIFAR-10 子集
```

## 4. 已完成

| 模块 | 当前状态 |
| --- | --- |
| SDD 与 README | 已建立系统范围、架构、执行器契约、运行手册、平台实现和项目状态文档。 |
| 首页与菜单 | 迁移版已提供工作台、数据集资源、算法资源、LDD 清洗任务，以及训练前、训练中、训练后三大防御入口。 |
| 数据集与资源管理 | 已保留数据集、模型、算法和超参数模板管理能力。 |
| LDD 算法接入 | 已通过 Python 执行器调用 VaB 外部 LDD，支持任务级目录和结构化结果。 |
| 任务校验 | 新后端原生校验数据集、算法阶段、LDD 实现路径、超参数模板匹配关系和自定义 JSON 参数。 |
| 任务命令 | 新后端原生接管任务创建、编辑、删除、详情、启动、失败重试和取消；LDD 通过 Python 执行器契约运行。 |
| 失败重试 | 失败任务支持重新进入待执行状态，并重新生成启动参数快照。 |
| 参数快照 | 每次启动/重试保存资源、配置、运行环境和时间信息；数据集文件默认保存 SHA-256 内容摘要。 |
| 任务进度 | 支持 pending、running、success、failed、canceled 状态展示、运行概览统计、任务名称搜索和轮询查看。 |
| 执行池观测 | 提供 `/api/task-progress/executor-status`，展示有界线程池的活跃线程、队列占用、完成数、饱和状态、接收状态和任务运行配额；前端任务中心自动刷新。 |
| 知识检索 | MySQL 文档/版本/chunk + ACL 前置检索，确定性中文切词/IDF 排序，引用定位到文档版本/章节/行号；无命中明确拒答，外部模型默认关闭。页面见 `/knowledge`。 |
| 执行日志 | 支持从任务结果登记的日志文件中安全读取内容；运行中的任务会轮询当前尝试目录下的标准输出和错误输出，并按 offset 增量读取，同时返回 Python 适配器的阶段、epoch、进度消息和心跳快照。 |
| 实验报告 | 已展示任务概览、执行尝试、参数快照、样本统计、LDD 指标、失败原因和产物信息。 |
| 实际运行环境 | LDD 结果记录适配器版本、Python、PyTorch、NumPy、CUDA、GPU 和关键 Python 依赖版本，报告页单独展示实际运行环境。 |
| 清洗子集导出 | 根据 LDD 输出的 `selected_indices_<ratio>.npy` 异步重建 CIFAR-10 清洗子集，支持进度、失败重试和 ZIP 下载；启动时 `ExportRecoveryRunner` 将 `pending`/`running` 收敛为 `EXPORT_NOT_STARTED`/`EXPORT_INTERRUPTED`，不自动重派发，需用户显式重试。 |
| 工程验证 | 已完成一次 CIFAR-10 / SIG / batch size 128 / 5 epochs 的 LDD 工程验证；不等同于论文正式实验。 |
| 任务工程约束 | 已增加任务名称搜索、非 pending 任务编辑保护、状态/进度范围校验、批量请求规模与空值校验、run_id/attempt 标识和重试目录隔离。 |
| 并发与事务一致性 | 已使用数据库条件更新保护任务领取；旧运行回调按 run_id 丢弃；外部执行器在事务提交后派发；线程池拒绝会收敛为可重试失败。 |
| 导出并发幂等 | 已通过 `(task_id, isolation_ratio)` 唯一键和原子插入保证并发提交只创建一条导出任务并只调度一次。 |
| 配置治理 | 已按“算法默认配置 -> 超参数模板 -> 任务自定义参数”合并，并对 LDD 白名单、类型、范围和必填项校验。 |
| 产物安全 | 已对 PNG、日志、索引和异步 ZIP 执行真实路径 containment 校验，避免符号链接绕出任务产物目录。 |
| 应用重启恢复 | 已实现训练任务恢复扫描：将无法恢复的 running 任务收敛为可重试失败。导出任务启动时只收敛为失败状态，不自动重新派发；需用户手动重试。 |
| 资源生命周期 | 已阻止删除仍被任务引用的数据集、算法和超参数模板，避免历史报告与导出链路失去依赖。 |
| 资源配额 | 已增加单机任务准入策略，限制运行中任务总数和单用户运行任务数，检查执行池接收状态，并将准入时配额快照写入每次执行记录。 |
| Python 进程控制 | 已将外部进程等待抽离为可测试组件，支持轮询进度快照、取消、超时强制终止和正常退出结果区分；取消/超时时同时回收外层适配器的子进程。 |
| 任务操作审计 | 已新增独立 `security_operation_audit` 表，记录任务创建、编辑、启动、重试、取消、删除及清洗子集导出动作；审计写入与任务命令保持事务一致。 |
| 演示空壳体系 | 已补齐三大防御阶段页面、阶段切换、算法卡片、输入输出说明和接入状态；除 LDD 外均明确标记为展示占位。 |
| 资源管理持久化 | 数据集和算法新增、编辑、停用已接入 MySQL，保存后刷新仍可见；资源操作写入审计，停用保留历史引用。 |
| 资源目录缓存 | 已接入 Redis 资源目录缓存，默认 TTL 5 分钟；Redis 异常自动回源 MySQL，资源变更主动失效缓存。 |
| 多用户归属 | **已实现（R1，2026-11-13）**：`sys_pre_defense.owner_user_id` 为唯一授权依据，`TaskAccessGuard` 统一拦截任务、报告、日志、产物、导出、下载与审计；列表/统计按当前用户过滤；跨用户读默认关闭。 |
| 数据集可用性 | **已补验证入口（R1）**：`POST /api/dataset/{id}/validate`（仅管理员）校验路径存在、可读、非空后把 `parse_status` 推进为 `success`；失败写 `failed` 与原因。 |
| 演示页面 | 工作台、案例库、运行监控、接口扩展预览为前端硬编码常量（含 RTX 3090 节点与攻击成功率），见 §5 边界说明。 |

## 5. 当前可演示能力

- 登录后进入安全训练工作台。
- 查看和维护图像数据集。
- 在算法库中查看 LDD 配置。
- 创建 LDD 训练前数据清洗任务。
- 配置批次大小、训练轮数、攻击方式和清洗参数。
- 启动任务并查看运行进度。
- 查看失败原因并执行失败任务重试。
- 查看某次执行的参数快照和 LDD 实验报告。
- 预览任务产物中的 PNG 图像。
- 按隔离比例提交导出任务，查看导出进度并下载由保留索引重建的 CIFAR-10 清洗子集。
- 在任务详情中查看操作人、动作、状态变化和操作说明。
- 浏览训练前、训练中、训练后三大防御页面及其算法方案卡片。
- 资源、任务、进度、报告、日志、导出和审计页面以接口返回为准；接口失败显示明确错误状态，不用样例数据伪装成功。

**演示数据的边界（R0 校正）**：以下三处页面是**前端硬编码演示内容**，不是真实数据，演示时必须明确说明：

| 页面 | 演示内容 | 事实 |
| --- | --- | --- |
| 工作台 `/dashboard` | 累计实验任务 28、平均攻击成功率 4.7%、近 7 日趋势、RTX 3090 节点 | 全部为常量；真实任务数、成功率需查 `/api/task-progress/statistics` |
| 案例库 `/cases` | 案例编号、清洗规模、攻击成功率降幅 | 全部为 `demoData.js` 常量，无后端接口 |
| 运行监控 `/runtime-monitor` | GPU/CPU/内存利用率、节点心跳 | 全部为常量；平台没有硬件采集实现，机型也不是 RTX 3090 |
| 接口扩展预览 `/training-defense` | 实验配置清单、生效状态、操作人 | 全部为 `TrainingDefenseView.vue` 内的常量数组，不对应数据库记录 |

因此“接口失败显示明确错误状态，不使用样例数据”只适用于资源、任务、报告、导出这条真实链路，
不适用于上述三页。R1/R5 之前不得据此声称平台具备运行监控或案例管理能力。

## 5.1 R0 事实审计结论（2026-11-13）

核对范围：`docs/sdd/01、03、05、07、09、10、11、16、17` + `migration-backend` 全部 Java 源码 +
`algorithm-runtime/vab/algorithm_entry.py` + `sql/` 建表与种子脚本 + `migration-frontend` 关键页面。

| 结论项 | 事实 | 证据 |
| --- | --- | --- |
| 真实算法范围 | 只有 LDD（`vab://pre-defense/ldd`），其它实现路径在启动前被拒绝 | `SUPPORTED_ALGORITHMS`、`validateCommand` |
| 执行模型 | API 进程内 `taskAsync` 线程池 + `ProcessBuilder` 子进程，无独立 Worker、无 MQ | `PythonLddTaskExecutor`、`TaskExecutorConfig` |
| 完成依据 | `result.json` 是唯一依据；缺失即失败（无“补最小成功”路径） | `validateResult`、`invoke()` |
| 进度依据 | `progress.json` 只是诊断，Java 只取 `progress` 字段并 clamp 到 0–99 | `updateProgressFromSnapshot` |
| 恢复语义 | 启动时把所有 `running` 收敛为 `EXECUTOR_RESTARTED`，隐含单实例 | `TaskRecoveryRunner` |
| 配额语义 | 4/用户 2 是 JVM 任务数，不限制 GPU；检查与领取不同事务 | `TaskExecutionQuotaPolicy` |
| GPU 串行 | 仅由适配器 `VAB_ROOT/.security-model-vab.lock` 排他锁保证；陈旧锁会永久阻塞 | `acquire_lock` |
| 产物位置 | 默认在系统临时目录 `<tmp>/security-model-executor/pre_defense-<id>/attempt-<n>` | `ExecutionFingerprintService` 默认值 |
| 归属 | 无 owner 列、无服务端授权；`create_by` 是用户名快照 | 见 18 号文档事实表 |
| 数据集登记 | 路径元数据登记，无上传、无验证推进 | `ResourceCommandService.saveDataset` |
| 演示数据 | 工作台/案例/监控为硬编码常量 | `DashboardView.vue`、`demoData.js`、`RuntimeMonitorView.vue` |

设计契约与待决事项分别落在 [18-multiuser-ownership-and-resources.md](18-multiuser-ownership-and-resources.md)
与 [19-local-data-layout-and-retention.md](19-local-data-layout-and-retention.md)。

## 5.2 R1 实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 归属键 | `sys_pre_defense` 新增 `owner_user_id` / `data_origin` / `owner_backfill_status`；创建任务写入 `owner_user_id` 与 `data_origin='user'` | `sql/security_model_ownership.sql`、`PreDefenseTaskCommandService.create` |
| 身份 | JWT 增加 `adm` 声明；过滤器把 `(userId, username, admin)` 作为 principal 注入。**评审后改为每次请求按 `userId` 读 `sys_user`**：账号禁用/删除则本次不认证，管理员降权后 `ROLE_ADMIN` 立即消失，令牌内 `adm` 不参与授权；旧 token 无 `userId`，任务写操作被拒 | `JwtTokenService`、`JwtAuthenticationFilter.resolve`、`SysUserMapper.findStateById` |
| 授权 | 读写统一走 `TaskAccessGuard`：写=仅 owner；读=owner，或管理员在开关开启时跨用户读；demo 行非跨用户管理员不可见，且**任何角色不可写**；无 owner 行任何人不可写 | `TaskAccessGuard` |
| 查询口径 | 列表、分页、运行中、统计均按 owner 过滤并排除 demo；统计响应新增 `scope` | `TaskProgressQueryService` |
| 共享资源 | 数据集/算法/超参写操作仅管理员，越权 403 `ADMIN_REQUIRED` | `ResourceCommandService`、`ApiExceptionHandler` |
| 数据集验证 | 新增管理员验证接口，失败不抛 500，写 `failed` 与原因 | `ResourceCommandService.verifyDataset` |
| 配额 | 单用户并发按 `owner_user_id` 计数，历史无 owner 行不占配额 | `TaskExecutionQuotaPolicy` |
| 迁移脚本 | 两段式 + 结构化决策表 `sys_pre_defense_ownership_decision`（`demo`/`assign`/`reject`）；删除用户名自动匹配与快照启发式；非法决策整批中止 | `sql/security_model_ownership.sql` |
| 未做 | 日志限额、attempt 级产物策略；真实 LDD 未重跑（GPU 租约见 §5.3） | 见 `18` §9 证据矩阵 |
| 接口审计 | 逐一核对任务、报告、日志、产物、导出、审计、统计、执行器状态、系统版本、共享资源入口，规则统一，无控制器可调用的无保护内部查询 | `18` §8 |

## 5.3 R2 第一切片实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| GPU 单槽租约 | 新表 `sys_gpu_lease`（`slot_id` 主键，`lease_token`/`task_id`/`run_id`/`attempt`/`fencing_token`/`expires_at`）；默认为 `default-gpu-0` | `sql/security_model_gpu_lease.sql`、`GpuLeaseMapper` |
| 原子获取 | 单条条件 UPDATE（空闲或过期才可覆盖）并在同一事务内 `claimForExecution`；抢不到返回 `GPU_BUSY`，任一步失败都会回滚另一半 | `GpuLeaseService.acquire`、`PreDefenseTaskCommandService.launchTask` |
| 续租与 fencing | 租期 300s、60s 续租；每次获取 `fencing_token+1`，续租/释放以 `lease_token` 为条件；续租失败取消本地进程并置 `GPU_LEASE_LOST` | `GpuLeaseHeartbeat` |
| 释放 | 执行器 `finally` 按 token 释放；启动恢复按 `task_id+run_id` 释放，不会清掉新租约 | `PythonLddTaskExecutor`、`TaskRecoveryRunner` |
| 并发口径 | GPU 准入并发由数据库租约强制为 1；JVM 的 4/2 配额保留为拒绝/排队上限，不再当作 GPU 并发数 | 配置 `security-model.executor.gpu.*` |
| 导出重启收敛 | 启动时把 `pending`/`running` 导出置为 `EXPORT_NOT_STARTED`/`EXPORT_INTERRUPTED`，不自动重派发；超时先杀进程树 | `ExportRecoveryRunner`、`CleanSubsetExportExecutor` |
| 未做（第一切片时） | 适配器锁陈旧接管、失败码细分（已在 §5.4 第二切片补齐）；自动重试队列、日志与产物限额仍未做 | 留 R2 后续 / R3 |
| 验证 | 第一切片 62 项单元测试 + 29 项 MySQL IT（持久层 5、迁移 6、端到端 9+4、GPU 租约 5） | 见 §9 |

## 5.4 R2 第二切片实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 适配器锁元数据 | 锁文件写 JSON：`pid/host/task_id/run_id/token/acquired_at`；旧裸 pid 文件仍可读 | `algorithm_entry.acquire_lock` |
| 陈旧接管 | 仅在持有 pid 不存活时接管；隔离移动后比对 token，不一致则恢复并返回 `GPU_LOCK_BUSY`；无 pid 的损坏文件才按 `SECURITY_MODEL_VAB_LOCK_STALE_SECONDS`（默认 21600）年龄判定 | `acquire_lock`、`_lock_is_stale` |
| 安全释放 | 只有磁盘锁 token 与本次运行一致才删除，慢进程不能删后继者的锁 | `release_lock` |
| 存活探测 | Windows `OpenProcess` + `GetExitCodeProcess`；POSIX `os.kill(pid,0)`；不使用会终止进程的 Windows `os.kill(pid,0)` | `_pid_alive` |
| 失败码细分 | `GPU_OOM`（OOM/CUBLAS/CUDNN 分配失败）、`CUDA_UNAVAILABLE`（无设备/驱动不符）、`GPU_LOCK_BUSY`（锁冲突），其余 `VAB_INTEGRATION_ERROR`；写入 `result.json.error_code` | `classify_runtime_failure`、`IntegrationError.error_code` |
| Java 兜底 | 子进程非零退出且无 `result.json` 时按 stderr 归类；有结果时以适配器写入的 `error_code` 为准 | `PythonLddTaskExecutor.classifyProcessFailure` |
| 不自动重试 | `GPU_OOM`/`CUDA_UNAVAILABLE` 明确不自动重试；`GPU_LOCK_BUSY` 仅提示稍后重试 | ADR-015 |
| 未做 | 自动重试队列、日志与 attempt 级产物限额、真实 LDD 实测 | R3 |
| 验证 | Java 69 项单元测试（新增 `PythonLddTaskExecutorFailureCodeTest` 7 项）+ 29 项 MySQL IT；适配器 `python -m unittest` 11 项 | §9 |

## 5.5 R2 第三切片实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| attempt 产物边界 | `<workspace>/pre_defense-<taskId>/attempt-<n>/` 为一次执行的产物目录，重试递增不覆盖 | `PythonLddTaskExecutor.createContext` |
| 日志截断 | 超过 `max-log-bytes`（默认 20 MiB）只保留尾部并写截断标记；先分类后截断 | `ExecutorArtifactPolicy.trimLog` |
| SHA-256 清单 | 每次执行结束写 `manifest.json`（文件名/字节数/SHA-256），失败只 warn | `ExecutorArtifactPolicy.writeManifest` |
| 保留期 | `retention-days` 默认 0（关闭）；开启后只删“终态任务 + 非当前 attempt + 超期 + 位于 workspace 内”的目录 | `ExecutorArtifactRetentionJob` |
| 验证 | Java **85 项单元测试 + 30 项 MySQL IT** 全绿（产物 7、孤儿进程 6、隔离槽位 1、恢复用例） | §9 |
| 恢复护栏 | 启动前写 `process.json`（写失败拒绝启动为 `PROCESS_RECORD_FAILED`），启动后写 pid/command/startInstant；适配器写 `python-children.json`；恢复按 `NOT_STARTED/EXITED/REAPED/KILL_FAILED/UNKNOWN` 判定，只有前三者释放租约 | `AttemptProcessRegistry`、`TaskRecoveryRunner`（ADR-017） |
| 槽位隔离 | `KILL_FAILED/UNKNOWN` 置 `blocked_at/blocked_reason`；TTL 过期仍拒绝领取并返回 `GPU_LEASE_BLOCKED`；`GpuLeaseBlockedProbe` 每 60s 重探，确认退出才解除 | `sys_gpu_lease`、`GpuLeaseBlockedProbe`、`sql/security_model_gpu_lease_block.sql` |
| 未做 | 真实 LDD 产物实测；目录递归哈希；自动算法重试队列（D5，仍不在范围）；生产库建表；带意图的真实 JVM 重启与 R3-04 崩溃演练 | 见 §5.7、§9 |

## 5.6 R2 第四切片：恢复语义实现（2026-11-13，历史记录；复核修复见 §5.6.1）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 进程记录协议 | `schema_version=2`；`prepare` 写 `state:starting`，`record` 写 `state:running + pid/command/started_at`；两次写入均临时文件 + `ATOMIC_MOVE`；无记录、旧协议、损坏 JSON、`starting` 无 pid 一律 `UNKNOWN` | `AttemptProcessRegistry` |
| 身份与整树 | 启动时间缺失/不等、父死但已记录子进程存活 → `UNKNOWN`，不误杀；身份正向匹配才杀；`ExecutorProcessGuard` 同时检查 root、`descendants()`、`python-children.json`；记录缺失/损坏不当作空树，正常结束由适配器写空 `pids` 留证 | `AttemptProcessRegistry`、`ExecutorProcessGuard` |
| 释放语义 | 执行器 `finally` 整树确认退出才 `releaseQuietly`，否则 `markBlocked("EXECUTOR_TREE_ALIVE")`；记录写失败立即杀树并 `PROCESS_RECORD_FAILED`；中断/超时/导出一致 | `PythonLddTaskExecutor`、`CleanSubsetExportExecutor` |
| 启动就绪门 | 恢复循环完成后 `SchedulingReadinessGate.markReady()`；未就绪启动返回 `SCHEDULING_NOT_READY`，只读接口不拦截 | `TaskRecoveryRunner`、`PreDefenseTaskCommandService` |
| 隔离可见性 | `/api/task-progress/executor-status` 返回 `gpuSlot`（blocked/leased/idle/unknown、原因、隔离时间、最近探测时间、指引），不含 taskId/路径；前端任务中心展示 | `TaskProgressController`、`TaskCenterView.vue` |
| 保留期边界 | 删除前 `toRealPath()` 必须位于 `workspace.toRealPath()` 内且等于 `normalize()` 路径，拒绝 symlink/junction 与 `..` 越界 | `ExecutorArtifactRetentionJob.resolveSafe` |
| 验证（R2 收口时实测） | `mvn -B -f migration-backend/pom.xml test` → **104 项单元测试，0 失败，0 跳过**；`verify -Pmysql-it` → 同一单测集 + **33 项 MySQL IT 全绿**；前端 `npm run build` 通过；R3 实施后计数见 §5.7 | `tmp/r2-final-verify.log` |
| 崩溃演练 | `OrphanProcessCrashDrillTest`：独立 JVM 启动短 Python 父子并记录，强杀启动器后由恢复判定 `REAPED`，父子均死；单跑与全量均通过 | 同上 |
| 未做 | 真实 LDD/6GB 显存实测；生产库建表与迁移；日志运行期磁盘上限（当前仅归档截断） | 见 §7、§9 |

## 5.6.1 R2/R3 复核修复记录（2026-11-14，待复审）

针对 `9d21281` 外部评审的 P1/P2，按 20 号任务书完成以下修复；本节只记录已落地代码与可复现证据，不代表外部复审已通过。

| 评审项 | 修复事实 | 证据 |
| --- | --- | --- |
| P1-1 执行器接受后记账失败释放 GPU | 正常派发与重放都拆成“执行器调用”和 `markSubmitted` 两个阶段；执行器接受后回写失败只记 warn，不释放租约、不失败任务；派发前 `AttemptProcessRegistry.markAccepted`，执行器拒绝/异常 `clearAccepted` | `TaskDispatchCoordinator`、`PythonLddTaskExecutor`；`TaskDispatchCoordinatorTest.replayAcceptedWorkIsNotFailedWhenBookkeepingFails` |
| P1-2 恢复扫描误杀/误释放 | pending/submitting 先核对意图与当前 `runId/attempt`；旧意图只 abandon；pending 过期先 `abandonPending` 条件抢占，只有赢家 release；本 JVM 已接受的 attempt 跳过探测；启动收敛同样跳过本地 accepted | `DispatchRecoveryCoordinator`、`TaskDispatchIntentMapper.abandonPending`；`DispatchRecoveryCoordinatorTest` 6 项 |
| P1-3 过期租约被当作空闲 | `acquire` 只允许 `lease_token IS NULL AND blocked_at IS NULL`；发现过期活跃租约时 `block("LEASE_EXPIRED")` 并返回 `GPU_LEASE_EXPIRED`；`renew` 增加 `expires_at >= now`；心跳改用本地租约快照，先取消进程再写失败记录，DB 异常也取消 | `GpuLeaseMapper`、`GpuLeaseService`、`GpuLeaseHeartbeat`；`GpuLeaseMySqlIT` 8 项、`GpuLeaseHeartbeatTest` 3 项 |
| P1-4 子进程记录缺失/身份不足 | 缺失/损坏/无 `pids` 的 `python-children.json` 返回 `UNKNOWN`，不再当空集合；只杀身份正向匹配的根进程及其可证明后代，记录中无法证明父子关系的 PID 不杀、存活则保持隔离；`write_children` 原子写失败会立即终止训练子进程 | `AttemptProcessRegistry`、`algorithm_entry.py`；`AttemptProcessRegistryTest` 13 项、`OrphanProcessCrashDrillTest` |
| P2 幂等提交失败/崩溃 | 业务事务回滚时注册 `afterCompletion` 清理 claim；过期删除只处理 `succeeded`，`processing` 不自动删除；超时处理中转 `failed` 并要求人工核对；前端 create/start/retry 在同一次逻辑请求重试时复用键 | `RequestIdempotencyService`、`IdempotencyRecordStore`、`IdempotencyRecoveryJob`、`RequestIdempotencyMapper`、`TaskCenterView.vue`；`RequestIdempotencyServiceTest` 7 项、`IdempotencyRecoveryJobTest`、`MySqlTaskPersistenceIT` |
| P2 重放前置校验 | `readRequest` 校验 `schema_version` 与 `request_hash`；重放校验快照 task/run/attempt、owner 非空且启用、demo 拒绝、数据集/算法启用、运行时指纹可用（空路径 `available=false`） | `TaskDispatchIntentService`、`TaskDispatchCoordinator`、`ExecutionFingerprintService`；`TaskDispatchIntentServiceTest` 2 项、`TaskDispatchCoordinatorTest` 8 项 |

本机实测（2026-11-14）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` → **137 项单元测试，0 失败/0 跳过；41 项 MySQL IT 全绿；1 项 Redis IT 因未设置 `REDIS_IT=true` 跳过**；`python -m unittest discover -s algorithm-runtime/vab/tests` → **11/11 通过**；`npm --prefix migration-frontend run build` → 通过，仍有大 chunk 警告。日志见 `tmp/r2-r3-fix-verify3.log`、`tmp/r2-r3-python-tests.log`、`tmp/r2-r3-frontend.log`。未跑真实 LDD、未写生产库。

仍未完成：真实 LDD/6GB 显存实测；生产库 `eladmin` 建表与迁移；带意图的真实 JVM 重启、R3-04 崩溃演练与真实库旧/新 run 竞争；Redis 宕机注入；日志运行期磁盘硬上限（当前仅归档截断）。R2/R3 本轮为“修复完成待复审”，不写“验收通过”。

## 5.7 R3 实施记录：持久调度与请求幂等（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 调度意图 | 新增 `sys_task_dispatch_intent`（唯一 `task_id+run_id+attempt`，状态 `pending/submitting/submitted/abandoned`）；`request_json` 由 `DefenseTaskRequest` 序列化，`gpuLeaseToken` 被 `@JsonIgnore` 排除；同时保存 `request_hash` 与 `runtime_hash` | `TaskDispatchIntent`、`TaskDispatchIntentMapper`、`TaskDispatchIntentService` |
| 同事务与条件抢占 | `launchTask` 在领取事务内写 pending；提交后 `UPDATE ... WHERE status='pending'` 仅 1 行可执行；执行器接受后才 `markSubmitted`；接受后记账失败只告警，不失败任务 | `PreDefenseTaskCommandService`、`TaskDispatchCoordinator` |
| 单一恢复 | `TaskRecoveryRunner` 只委托 `DispatchRecoveryCoordinator`；pending 直接重放（不因无进程记录误失败），submitting/submitted 收敛为 `DISPATCH_UNCERTAIN`，无意图旧任务按 R2 证据失败；最后 `markReady` | `TaskRecoveryRunner`、`DispatchRecoveryCoordinator` |
| 重放校验 | 校验任务 `run_id/attempt`、归属账号仍启用、数据集/算法仍可用、运行时指纹未变；先释放本 run 旧租约，再 acquire 新 token；GPU_BUSY/BLOCKED、路径/指纹/身份失效均零执行 + abandoned | `TaskDispatchCoordinator` |
| 有界扫描 | 10s 间隔、初始 30s、每批 20、只扫超过 30s 的 pending；创建超过 5 分钟 → `DISPATCH_EXPIRED`；submitting 失联按进程证据释放或隔离后 `DISPATCH_UNCERTAIN`；submitted 不扫描 | `DispatchRecoveryCoordinator.scanPending`、`application.yml` |
| 请求幂等 | `sys_request_idempotency` 唯一 `(owner_user_id, operation, idempotency_key)`，24h 有效期；create/start/retry 支持可选 `Idempotency-Key`（1–128）；同体复用、异体 409、进行中 409、跨用户独立；成功记录随业务事务提交，失败在独立事务释放键；2026-11-14 增加提交回滚清理、processing 不自动删除、超时转 `failed` 待人工核对 | `RequestIdempotencyService`、`IdempotencyRecordStore`、`IdempotencyRecoveryJob`、`PreDefenseTaskController` |
| 前端 | create/start/retry 每次提交传入幂等键；2026-11-14 改为同一次逻辑请求失败重试复用同一键，成功后才清除；失败详情继续显示 `failure_reason` | `preDefense.js`、`task.js`、`TaskCenterView.vue` |
| 验证（本机实测） | `mvn verify -Pmysql-it` → **120 项单元测试，0 失败/0 跳过；40 项 MySQL IT 全绿**（`MySqlTaskPersistenceIT` 13 项）；前端 `npm run build` 通过 | `tmp/r3-final-verify.log`、`tmp/r3-frontend-build.log` |
| 未做 | 带意图的真实 JVM 重启重放一次、R3-04 短进程崩溃、R3-07 新 attempt 跨旧 run 的真实库竞争、R3-05 取消竞争全链路、生产库建表 | 见 20 §6.5 |

## 5.8 R5 实施记录：观测、缓存代际与最小暴露（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 运行指标 | `TaskRuntimeMetrics` 每 30 秒从 MySQL 聚合非 demo 任务状态与陈旧 running 数，读取执行池和 GPU 槽位；标签仅 status/state/result/outcome，没有 taskId/runId/userId | `TaskRuntimeMetrics`、`application.yml` |
| 耗时与恢复计数 | 执行器按 success/failed/canceled/interrupted 记录耗时；启动恢复记录 completed/failed | `PythonLddTaskExecutor`、`DispatchRecoveryCoordinator` |
| 日志关联 | `RequestCorrelationFilter` 生成/校验 `X-Request-Id` 并写 MDC；执行线程写 taskId/runId/attempt；logback 统一 pattern | `RequestCorrelationFilter`、`logback-spring.xml` |
| 缓存代际 | 读路径先取 generation，miss 后加载 MySQL，只有 generation 未变才条件写回；提交后失效先增代际再删除；Redis 故障回源并计数 degraded | `ResourceCatalogCache`、`ResourceQueryService` |
| 管理端汇总 | `/api/observability/summary` 仅管理员，返回任务状态、陈旧进度、执行池、槽位、缓存计数；不返回 taskId/userId/路径 | `ObservabilitySummaryService`、`ObservabilityController` |
| Actuator 最小暴露 | `/actuator/health` 匿名且不展示详情；`/actuator/metrics`、`/actuator/info` 与 `/api/observability/summary` 仅 `ROLE_ADMIN`；匿名 401、普通用户 403、管理员 200 | `SecurityConfig`、`application.yml`、`OwnershipEndToEndIT` 真实 Security 链 |
| 前端统计 | 统计页改为读取真实 `statusStats` 并排除 demo；管理员可额外看到全局槽位/执行池/缓存；未采集的 ASR/GPU 利用率不再展示演示数字 | `StatisticsView.vue`、`task.js` |
| 文档 | 更新 06 迁移版启动/停止/备份/排障、08 Redis 与观测配置、05 ADR-019 | 06/08/05 |
| 验证（本机） | `mvn verify -Pmysql-it`（`REDIS_IT=true`）→ **127 项单元测试，0 失败/0 跳过；41 项 MySQL IT + 1 项真实 Redis IT 全绿**；前端 `npm run build` 通过；Redis 宕机未注入 | `tmp/r5-final-verify.log`、`tmp/r5-redis-it.log`、`tmp/r5-frontend-final.log` |
| 未做 | 生产库建表、真实 Redis 宕机/并发回填故障注入、MySQL 长时观测、CI 远端结果；统计页未展示真实 ASR（无采集源） | 见 20 §8、§9 |

## 5.9 R6 实施记录：有引用的知识检索（2026-11-14，外部模型联调阻塞）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 知识库 schema | 新增 `sys_knowledge_document` / `sys_knowledge_chunk`；`(source_key, version)` 与 `(document_id, chunk_index)` 唯一；检索只连接 `status='active'` | `sql/security_model_knowledge.sql`、`KnowledgeDocumentMapper` |
| ACL | 共享文档所有稳定认证用户可读；私有报告仅 `owner_user_id`；管理员跨用户读默认关闭，需显式开启 | `KnowledgeDocumentMapper.findSearchableForUser`、`KnowledgeRetrievalService` |
| 导入与版本 | 管理员显式触发；SDD 直接 `.md` 文件拒绝符号链接/越界，UTF-8 去 BOM 后先脱敏再 SHA-256；内容未变跳过；变化时同一事务写新版本+全部 chunk 再 retire 旧 active 版本 | `KnowledgeIndexService`、`KnowledgeDocumentWriter` |
| 分块与切词 | Markdown 保留标题/章节/1-based 行号；中文 Han 连续串展开单字+二元组，英文/数字按词，停用疑问词 | `MarkdownKnowledgeChunker`、`KnowledgeTokenizer` |
| 排序与拒答 | 标题 3/章节 2/正文 1，IDF 加权；`min-coverage=0.25`、`min-score=20`、topK=5；无命中返回 `unknown`，不调用模型 | `KnowledgeRanker`、`KnowledgeRetrievalService` |
| 引用回答 | `POST /api/knowledge/query` 返回 `status/answer/citations/modelStatus/modelMessage`；引用含 sourceKey/version/section/chunk/line/snippet/visibility；模型答案出现候选集外引用时整段丢弃并回退 | `KnowledgeQueryService`、`KnowledgePromptBuilder` |
| 外部模型 | 业务仅依赖 `KnowledgeAnswerModel` 端口；`provider` 选择一个适配器（默认 `openai-compatible`），新增供应商只增加实现并切换配置。默认 `enabled=false`；无 key 返回 `model_not_configured`；超时/429/5xx 最多重试 1 次；4xx/超时/空响应降级为 `retrieval_only`；请求体白名单不含 sourceKey/文档 ID/owner/路径/日志/样本/凭据 | `KnowledgeAnswerModel`、`OpenAiCompatibleKnowledgeAnswerModel` |
| API | `GET /api/knowledge/status`、`POST /api/knowledge/reindex`、`POST /api/knowledge/algorithms/reindex`、`POST /api/knowledge/tasks/{taskId}/index`（owner）、`DELETE /api/knowledge/documents/{documentId}`（管理员） | `KnowledgeController` |
| 前端 | 新增知识检索页、路由与菜单；展示回答/检索模式/未知、引用卡片、模型状态；管理员索引入口对 403 给出明确提示，不伪造成功 | `KnowledgeView.vue`、`src/api/knowledge.js`、`router/index.js`、`App.vue` |
| 验证（本机） | `mvn verify -Pmysql-it`（`REDIS_IT=true`）→ **174 项单元测试，0 失败/0 跳过；45 项 MySQL IT + 1 项真实 Redis IT 全绿**；Python 11/11；前端 `npm run build` 通过（element chunk 947.11 kB 警告仍在）；未跑真实 LDD | `tmp/r6-final-verify3.log`、`tmp/r6-python-tests.log`、`tmp/r6-frontend.log` |
| 未做/阻塞 | 外部模型供应商、费用上限、数据留存未获人类授权，不能真实外发；生产库 `eladmin` 未建表；真实语料规模、模型质量与 20 题之外的失败样例未评估 | 20 §9 |

## 5.10 R7 实施记录：受控实验助手（2026-11-14，模型适配器待接入）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 助手 schema | 新增 `sys_assistant_confirmation`、`sys_assistant_session`、`sys_assistant_tool_audit`、`sys_assistant_usage_daily`；确认 token 只存 SHA-256，会话按用户 7 天保留 | `sql/security_model_assistant.sql` |
| 工具白名单 | 显式注册 4 个只读工具（参数说明、本人任务摘要、本人报告摘要、知识检索）和 5 个写工具（创建/启动/取消/重试/导出）；未知工具名直接拒绝，无 shell/任意 SQL/任意文件工具 | `AssistantToolRegistry`、`AssistantToolDescriptor` |
| 只读授权 | 任务和报告工具复用 `TaskAccessGuard.requireOwned`；知识工具复用 ACL-first 检索；跨用户直接 403 | `TaskSummaryTool`、`ReportSummaryTool`、`KnowledgeSearchTool` |
| 预览不写 | 写工具只做状态/参数校验并返回脱敏预览、风险说明、规范请求和上下文哈希；创建预览不会写库，启动预览不会领取 GPU | `CreateTaskPreviewTool`、`StartTaskPreviewTool`、`AssistantPreviewService` |
| 一次性确认 | 预览发 256-bit 随机 token，数据库仅存哈希，TTL 默认 300 秒；确认时重校验用户/token/过期/task-run-attempt-update_time/参数哈希，条件消费后才调用原命令服务 | `AssistantConfirmationService`、`AssistantConfirmationMapper` |
| 并发边界（评审 P1-1/P1-2） | 任务编辑/删除/启动/重试/取消先在事务内 `SELECT ... FOR UPDATE` 锁行；编辑只写白名单字段并带 `status='pending'` 条件；确认在哈希校验前锁定目标任务，锁内重读确认记录，锁持有到业务命令提交；已补“运行态不可被编辑回 pending”MySQL 测试和“预览后 run 变化返回 409 且执行器零调用”HTTP+MySQL 测试；删除—启动竞争仍只有同一批量行锁路径，尚无独立并发 IT | `PreDefenseTaskMapper`、`PreDefenseTaskCommandService`、`TaskAccessGuard.requireOwnedForUpdate` |
| 出站数据（评审 P1-3/P2-6） | 报告索引只写入指标、错误码、选择类型和生效参数摘要，禁止原始结果/日志/路径；`KnowledgeSanitizer` 增加 `F:/` 路径、JSON 键值凭据、手机号/身份证脱敏；模型只收到显式白名单证据 DTO（指标、引用片段、行号），不接收整个业务响应；总时限同时传入模型适配器并在模型返回后再次检查 | `KnowledgeIndexService.taskReportMarkdown`、`KnowledgeSanitizer`、`AssistantModelEvidence`、`AssistantChatService` |
| 进程与租约（评审 P1-4/P2-5） | 运行期记录 PID 只有在 parent 链能证明是当前 root 后代时才强杀，未认证存活 PID 保留槽位隔离；`python-children.json` 缺失/损坏不再当作空树，正常结束由适配器写空 `pids` 留证；过期租约不再在调用事务内写 `blocked`，改为事务完成后以 `REQUIRES_NEW` 独立提交，调用方回滚不会撤销隔离；启动失败同时清理本 JVM 本地租约 | `ExecutorProcessGuard`、`GpuLeaseService`、`PreDefenseTaskCommandService.launchTask`、`algorithm_entry.py` |
| 防重复与幂等 | 已消费 token 返回原结果并标记 `confirmationReplayed=true`；确认事务接入 R3 `RequestIdempotencyService`；命令失败回滚会恢复 pending，不产生半截写 | `AssistantWriteExecutor`、`AssistantConfirmationEndToEndIT` |
| 配额与时限 | 每用户每日默认 50 次，数据库条件自增，超出 429；每次最多 6 次模型调用、5 次工具调用、60 秒；核心 API 不受影响 | `AssistantQuotaService`、`AssistantChatService` |
| 模型端口 | 业务只依赖 `AssistantModel`；默认 `provider=disabled`，明确返回 `model_disabled`；未来新增供应商适配器并切换 provider，不改编排和确认流程 | `AssistantModel`、`DisabledAssistantModel` |
| 审计与会话 | 工具审计只存工具名/operator/target/resultCode/durationMs，不存 prompt、参数正文或凭据；会话只存问题、状态、工具名、确认 ID、引用 ID；用户可删除自己的会话 | `AssistantAuditService`、`AssistantSessionService`、`AssistantRetentionJob` |
| API/前端 | `POST /api/assistant/chat`、确认、工具目录、预览、会话列表/详情/删除、审计、状态；新增 `/assistant` 页面，展示模型不可用、工具结果、引用和待确认操作 | `AssistantController`、`AssistantView.vue`、`src/api/assistant.js` |
| 验证（本机） | 2026-11-14 评审修复后 `mvn verify -Pmysql-it`（`REDIS_IT=true`）→ **198 项单元测试 + 52 项 MySQL/Redis 集成测试全绿**；助手 HTTP+MySQL 确认链路 4 项；前端构建沿用 R7 通过结果（本轮改后端/文档，未重跑前端）；未跑真实 LDD | `tmp/r7-review-verify3.log`、`tmp/r7-http-it1.log`、`tmp/r7-frontend.log` |
| 未做/阻塞 | 未接真实外部模型；未做真实 LDD；生产库 `eladmin` 未执行助手建表；确认流不是组织级双人审批；删除—启动尚无独立并发 IT；R3 的“真实 JVM 重启重放、带意图崩溃窗口、旧/新 run 竞争、取消与瞬时完成”未闭环；RabbitMQ 与独立 Worker 未实现 | ADR-021、20 §10 |

## 6. 待完成与优化建议

### P0：正式实验闭环

1. 根据论文实验方案确定正式数据集、攻击设置、训练轮次和 LDD 参数。
2. 固化实验配置和数据版本，执行正式实验并保存结果。
3. 确认论文指标定义，补齐清洗率、攻击成功率、干净准确率和性能保持率等指标。

### P1：平台工程完善

1. **R1（已实施，评审“有条件通过”；生产迁移未执行）**：稳定用户 ID 归属、任务/报告/日志/产物/导出授权、共享资源写权限收敛、历史数据迁移。验收见 17 号指导书 R1。
2. **R2（可靠性修复完成，待复审）**：GPU 单槽租约、进程记录/整树回收、隔离槽位与探测、启动就绪门、导出失败收敛、产物清单/保留期均已有实现与本机证据；2026-11-14 已修复评审 P1-1~P1-4。剩余真实 LDD/6GB 实测、生产库建表/迁移和日志运行期限额。
3. **R6（离线检索与适配器完成，外部模型联调阻塞）**：MySQL 文档/版本/chunk、ACL 前置检索、确定性中文切词与 IDF 排序、引用 API、知识页和通用模型适配器已落地；缺供应商/预算/数据留存授权，未真实外发，生产库未建表。详见 §5.9 与 ADR-020。
4. **R7（受控工具与确认流完成，模型适配器待接入）**：4 个只读工具 + 5 个预览写工具、一次性确认、R3 幂等、日配额、会话/审计和助手页已落地；外部模型默认关闭，未做真实 LDD，生产库未建表。详见 §5.10 与 ADR-021。
5. 持续补迁移版领域服务和 API 集成测试；现有 MySQL 集成测试仅覆盖任务原子领取、状态竞争和恢复扫描，不代表完整端到端覆盖。
6. 补充 Python 依赖锁定、部署复现和应用重启演练。
7. 继续补充 Redis 缓存命中率、失效和不可用降级的观测指标。
8. 只有跨进程/多实例调度成为明确需求后，再评估 Outbox + RabbitMQ 和独立 Worker。
9. 视需要将日志轮询升级为 SSE；SSE 属于服务端推送，不替代任务消息队列。
10. 补齐数据集“登记 → 验证 → 可用”的状态推进，消除 `parse_status` 死路。

### P2：迁移收尾与平台增强

1. 完成迁移版登录、JWT、资源、任务、报告、日志和异步导出回归。
2. 根据答辩需要评估日志 SSE/WebSocket；资源管理 MySQL 持久化和任务操作审计已完成首版。
3. 增加清洗后数据集到后续模型训练的衔接入口。
4. 旧 ELADMIN 仅保留为回退基线，是否裁剪由最终答辩和部署方式决定。

当前框架迁移已完成当前 LDD 业务范围，不再把资源、任务、报告或认证描述为兼容代理。

## 8. 框架迁移当前进度

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 新后端 | 已完成 | Java 17、Spring Boot 3.5、Security 6、JWT、MyBatis-Plus、健康检查和版本接口。 |
| 新前端 | 已完成 | Vue 3、Vite、Element Plus、Pinia、Vue Router 4，已接入当前 LDD 展示闭环。 |
| 后端认证迁移 | 已完成 | 新后端原生生成和校验 JWT，验证码由新后端管理，前端不再走旧 RSA 登录链路。 |
| 后端领域接口迁移 | 已完成 | 资源、任务、任务观测、报告、日志、产物预览和清洗子集导出均由新后端原生处理。 |
| Python 算法边界 | 已完成 | 新后端通过统一请求/结果协议调用外部 VaB LDD，不把算法实现耦合进 CRUD 服务。 |
| 前端业务页面迁移 | 已完成当前范围 | Vue 3 页面已接入登录、工作台、资源、LDD 任务、报告、日志和索引导出；非 LDD 算法和其他防御阶段为静态展示，不创建真实任务。 |
| 旧模块裁剪 | 保留基线 | 旧工程暂不删除，用于回归和故障回退，不进入迁移版主流程。 |

详细路线见 `docs/sdd/14-framework-migration-plan.md`。当前迁移版已经可以完成 LDD
任务的前端闭环，同时可以浏览完整的三阶段防御体系页面；当前真实业务范围
已经由 Spring Boot 3 原生处理，外部 Python 仅承担 LDD 算法计算和产物生成。

## 7. 当前演示边界

- 当前不接入 VS、MetaPurifier 或其他对比算法的真实执行。
- 当前不接入训练中防御和训练后防御的真实执行。
- 三大防御阶段及算法卡片保留为完整展示架构，统一使用“展示占位”状态。
- 工作台、案例库、运行监控为演示常量，不得作为真实运行数据或指标。
- 当前不以工程验证结果替代论文正式实验结论。
- 多用户隔离已在 R1 落地：任务归属以 `owner_user_id` 为准，服务端统一校验；R2 已补 GPU 单槽租约、进程记录/整树回收、隔离槽位、启动就绪门、导出失败收敛、适配器陈旧锁接管与失败码细分；日志运行期硬上限与自动重试队列仍未做。

## 9. 迁移版质量门禁现状

- `migration-backend` 现有 **198 项单元测试**（2026-11-14 评审修复后实测，0 失败/0 跳过），以及 8 个 MySQL/Redis 集成测试类共 **52 项**场景：`MySqlTaskPersistenceIT` 14 项、`OwnershipMigrationScriptIT` 6 项、`OwnershipEndToEndIT` 10 项、`OwnershipEndToEndCrossReadIT` 4 项、`GpuLeaseMySqlIT` 9 项、`AssistantConfirmationEndToEndIT` 4 项、`KnowledgeRetrievalMySqlIT` 4 项、`ResourceCatalogCacheRedisIT` 1 项（实际 8 个类、合计 52 项，均属 `mysql-it`/Redis 门禁）。适配器独立 Python 单元测试 11 项（`python -m unittest`，不计入 Maven 计数）。
- R1 新增覆盖：`TaskAccessGuardTest` 8 项（owner/管理员/无 owner/demo 读与**demo 写拒绝**/跨用户）、`TaskExecutionQuotaPolicyTest` 4 项（按 owner 计数、跨用户不互相占用、全局上限、线程池关闭）、`OwnershipHttpContractTest` 8 项（真实 Security 过滤器链 + 真实控制器，持久层为 mock）、资源命令新增管理员拒绝与数据集验证 4 项、进度查询越权与统计 scope 3 项、集成测试新增 owner 范围查询与 demo 排除 2 项。
- 端到端 IT（`OwnershipEndToEndIT` / `OwnershipEndToEndCrossReadIT`）使用真实 `@SpringBootTest` 上下文、真实 Security 过滤器、真实服务、真实隔离库：`OwnershipE2ESupport` 每轮创建随机 schema 并种入 admin/alice/bob 三个账号与四条归属状态不同的任务，两个账号各自 `/auth/code`→`/auth/login` 登录后访问同一任务，覆盖 403、demo 隔离、登录后禁用立即失效、无 `userId` 旧 token、伪造 `adm`、管理员开关 false/true 两种结果。**本机已执行通过（10/10，含 R5 新增 Actuator 鉴权）**。
- 集成测试执行情况（2026-11-14 评审修复后本机实测，`MYSQL_IT_PASSWORD` 由本机环境提供，`REDIS_IT=true`）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` → **198 项单元测试（0 跳过）+ 52 项 MySQL/Redis 集成测试全部通过，BUILD SUCCESS**；适配器 `python -m unittest` 11 项为上一次记录，本轮未重跑。MySQL 分布：`MySqlTaskPersistenceIT` 14、`OwnershipMigrationScriptIT` 6、`OwnershipEndToEndIT` 10、`OwnershipEndToEndCrossReadIT` 4、`GpuLeaseMySqlIT` 9、`AssistantConfirmationEndToEndIT` 4、`KnowledgeRetrievalMySqlIT` 4，另有 `ResourceCatalogCacheRedisIT` 1。每个 IT 都用随机 schema，结束后删除。
- GPU 租约实测（`GpuLeaseMySqlIT` 9/9）：两个线程并发启动只有一个抢到槽位；租约过期后**先隔离为 `blocked`，不自动接管**，需 `GpuLeaseBlockedProbe` 确认旧进程树退出后才解除；隔离写入在调用方事务回滚后仍然保留；旧 token 续租返回 false、释放不会清掉新租约；按 `task+run` 释放只清匹配运行；释放后可再次获取且 fence 继续递增；事务回滚后无幽灵租约且 fence 未被消耗；隔离槽位在 TTL 过期后仍返回 `GPU_LEASE_BLOCKED`，重建服务实例后仍保持隔离。
- 导出重启收敛：`ExportRecoveryRunner` 在 `ApplicationReadyEvent` 把 `pending` 置 `EXPORT_NOT_STARTED`、`running` 置 `EXPORT_INTERRUPTED`，不自动重派发；`ExportRecoveryRunnerTest` 2 项覆盖两类状态与“更新 0 行不审计”；`CleanSubsetExportExecutorTest` 用真实短 Python 写半成品 `.part` 后长睡，超时后断言 `markFailed`、无正式 ZIP、子进程已死、从未 `markSuccess`。
- 迁移脚本实机执行（隔离库，`OwnershipMigrationScriptIT` 6/6）：①只读段加列加索引建决策表、不改业务行；②无决策时即使 `create_by` 命中真实账号也不认领；③1–4 标 `demo`、12 号 `assign user_id=1` 后为 `matched/legacy`；④`reject` 能撤回旧的自动匹配；⑤未知任务 ID 整批中止；⑥未知用户/非法决策值整批中止。旧版字符串开关的两处缺陷（开关被重置、`data_origin IS NULL` 使用户名匹配失效）随设计替换消失。
- **仍未完成**：生产库 `eladmin` 上的实际迁移执行与逐行核对记录。隔离库通过只证明脚本行为正确，不等于生产数据已迁移；`sql/security_model_ownership.sql` 不得表述为“已在生产验证”。
- 真实库只读核对（2026-11-13）：`eladmin.sys_pre_defense` 实际 **9 行**、无 `owner_user_id` 列；3、4 号已被恢复线程收敛为 `failed`（结果非空），8–12 为真实跑批，其中 12 号作者 `system` 在 `sys_user` 中不存在。裁定：1–4 号 `demo`、12 号 `assign user_id=1`（理由：system 为平台批处理作者）、8–11 号待审保持 `unattributed`。详见 `18` §4、§4.1。
- MySQL 集成测试使用唯一随机 schema，验证并发领取仅一个成功、完成操作胜过已读取恢复快照、恢复只收敛孤儿 `running` 任务、owner 范围查询只返回本人行且 demo 行被排除；清理后断言 schema 已删除。
- `.github/workflows/migration-quality.yml` 配置了迁移版 Maven `verify` 和 Vue 3 生产构建。工作流配置存在不代表远端 Actions 已成功，远端状态需以 GitHub Actions 记录为准。
- `mysql-it` Maven profile 通过 Failsafe 启动 MySQL 集成测试；GitHub Actions 配置 MySQL 8.4 与 Redis 7.4 service，并设置 `REDIS_IT=true`，CI 不再跳过真实 Redis IT。远端 Actions 尚未确认时，不将本地通过表述为远端通过。
- 2026-11-13 R3 实测：`mvn test` 通过 120 项单元测试（0 失败、0 跳过）；同日 `verify -Pmysql-it` 通过 120 项单测 + 40 项数据库 IT（新增调度意图唯一键/条件抢占/事务回滚、pending 扫描、幂等键 owner 隔离与过期、SQL 脚本重复执行与回滚）；前端 `npm run build` 通过；`OrphanProcessCrashDrillTest` 仍通过。均未涉及真实 CUDA／真实 LDD。带意图的真实 JVM 重启与 R3-04 崩溃演练仍未补，见 20 §6.5。
- 迁移脚本已在随机隔离 schema 实跑通过，但**生产库尚未执行**；不得写成“迁移已完成/已验证生产”。
- Redis 已进入迁移版依赖和运行配置，用于可重建资源目录；任务状态、报告和执行快照仍由 MySQL 保存。Redis 故障时缓存层降级回源 MySQL。
- CI 工作流包含 MySQL 8.4 集成测试和前端生产构建；本次未查询 GitHub Actions 运行记录，不能据配置推断远端已通过。
- 最近一次记录的本地普通 Maven 验证、MySQL profile 验证和前端生产构建结果见 `docs/sdd/16-engineering-improvement-roadmap.md`；R1 仅重跑普通 Maven 验证，前端未改动也未重跑构建。
- 2026-11-14 评审修复后全量验证：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`MYSQL_IT_PASSWORD`、`REDIS_IT=true`）→ **198 项单元测试 + 52 项 MySQL/Redis 集成测试全部通过，BUILD SUCCESS**；其中新增 `GpuLeaseMySqlIT.expiredLeaseQuarantineSurvivesCallerRollback`（9/9）、`AssistantConfirmationEndToEndIT.taskChangeAfterPreviewMakesConfirmationStaleAndExecutesNothing`（4/4）、`MySqlTaskPersistenceIT.pendingFieldUpdateNeverOverwritesRunningStatusOrRunIdentity`（14/14），以及运行期进程记录缺失/损坏/空记录三类守卫用例和六类写操作 stale 单测。测试执行器为 Mockito 替身，没有启动 Python/LDD。生产库仍未执行 R6/R7 建表脚本。日志：`tmp/r7-review-verify3.log`。
