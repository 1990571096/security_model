# 项目目标与阶段进度说明

更新时间：2026-11-13（R0 事实审计）

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
| 执行日志 | 支持从任务结果登记的日志文件中安全读取内容；运行中的任务会轮询当前尝试目录下的标准输出和错误输出，并按 offset 增量读取，同时返回 Python 适配器的阶段、epoch、进度消息和心跳快照。 |
| 实验报告 | 已展示任务概览、执行尝试、参数快照、样本统计、LDD 指标、失败原因和产物信息。 |
| 实际运行环境 | LDD 结果记录适配器版本、Python、PyTorch、NumPy、CUDA、GPU 和关键 Python 依赖版本，报告页单独展示实际运行环境。 |
| 清洗子集导出 | 根据 LDD 输出的 `selected_indices_<ratio>.npy` 异步重建 CIFAR-10 清洗子集，支持进度、失败重试和 ZIP 下载；当前没有导出任务启动恢复扫描。 |
| 工程验证 | 已完成一次 CIFAR-10 / SIG / batch size 128 / 5 epochs 的 LDD 工程验证；不等同于论文正式实验。 |
| 任务工程约束 | 已增加任务名称搜索、非 pending 任务编辑保护、状态/进度范围校验、批量请求规模与空值校验、run_id/attempt 标识和重试目录隔离。 |
| 并发与事务一致性 | 已使用数据库条件更新保护任务领取；旧运行回调按 run_id 丢弃；外部执行器在事务提交后派发；线程池拒绝会收敛为可重试失败。 |
| 导出并发幂等 | 已通过 `(task_id, isolation_ratio)` 唯一键和原子插入保证并发提交只创建一条导出任务并只调度一次。 |
| 配置治理 | 已按“算法默认配置 -> 超参数模板 -> 任务自定义参数”合并，并对 LDD 白名单、类型、范围和必填项校验。 |
| 产物安全 | 已对 PNG、日志、索引和异步 ZIP 执行真实路径 containment 校验，避免符号链接绕出任务产物目录。 |
| 应用重启恢复 | 已实现训练任务恢复扫描：将无法恢复的 running 任务收敛为可重试失败。导出任务尚无启动恢复扫描；pending 导出任务不会自动重新派发。 |
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

## 6. 待完成与优化建议

### P0：正式实验闭环

1. 根据论文实验方案确定正式数据集、攻击设置、训练轮次和 LDD 参数。
2. 固化实验配置和数据版本，执行正式实验并保存结果。
3. 确认论文指标定义，补齐清洗率、攻击成功率、干净准确率和性能保持率等指标。

### P1：平台工程完善

1. **R1（下一个迭代）**：稳定用户 ID 归属、任务/报告/日志/产物/导出授权、共享资源写权限收敛、历史数据迁移。验收见 17 号指导书 R1。
2. **R2（进行中）**：已完成 GPU 单槽租约（MySQL 条件更新 + 续租 + fencing + 重启释放）与导出重启收敛；剩余：适配器陈旧锁接管、失败码细分、日志与产物限额、自动重试策略。
3. 持续补迁移版领域服务和 API 集成测试；现有 MySQL 集成测试仅覆盖任务原子领取、状态竞争和恢复扫描，不代表完整端到端覆盖。
4. 补充 Python 依赖锁定、部署复现和应用重启演练。
5. 继续补充 Redis 缓存命中率、失效和不可用降级的观测指标。
6. 只有跨进程/多实例调度成为明确需求后，再评估 Outbox + RabbitMQ 和独立 Worker。
7. 视需要将日志轮询升级为 SSE；SSE 属于服务端推送，不替代任务消息队列。
8. 补齐数据集“登记 → 验证 → 可用”的状态推进，消除 `parse_status` 死路。

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
- 多用户隔离已在 R1 落地：任务归属以 `owner_user_id` 为准，服务端统一校验；R2 已补 GPU 单槽租约、导出重启收敛、适配器陈旧锁接管与失败码细分，日志/产物限额与自动重试队列仍未做。

## 9. 迁移版质量门禁现状

- `migration-backend` 现有 **14 个单元测试类、69 项单元测试**（2026-11-13 R2 第二切片后实测），以及 5 个 MySQL 集成测试类共 **29 项**场景：`MySqlTaskPersistenceIT` 5 项、`OwnershipMigrationScriptIT` 6 项、`OwnershipEndToEndIT` 9 项、`OwnershipEndToEndCrossReadIT` 4 项、`GpuLeaseMySqlIT` 5 项，均属 `mysql-it` profile。另有适配器独立 Python 单元测试 11 项（`python -m unittest`，不计入 Maven 计数）。
- R1 新增覆盖：`TaskAccessGuardTest` 8 项（owner/管理员/无 owner/demo 读与**demo 写拒绝**/跨用户）、`TaskExecutionQuotaPolicyTest` 4 项（按 owner 计数、跨用户不互相占用、全局上限、线程池关闭）、`OwnershipHttpContractTest` 8 项（真实 Security 过滤器链 + 真实控制器，持久层为 mock）、资源命令新增管理员拒绝与数据集验证 4 项、进度查询越权与统计 scope 3 项、集成测试新增 owner 范围查询与 demo 排除 2 项。
- 端到端 IT（`OwnershipEndToEndIT` / `OwnershipEndToEndCrossReadIT`）使用真实 `@SpringBootTest` 上下文、真实 Security 过滤器、真实服务、真实隔离库：`OwnershipE2ESupport` 每轮创建随机 schema 并种入 admin/alice/bob 三个账号与四条归属状态不同的任务，两个账号各自 `/auth/code`→`/auth/login` 登录后访问同一任务，覆盖 403、demo 隔离、登录后禁用立即失效、无 `userId` 旧 token、伪造 `adm`、管理员开关 false/true 两种结果。**本机已执行通过（9/9）**。
- 集成测试执行情况（2026-11-13 本机实测，`MYSQL_IT_PASSWORD` 由本机环境提供）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` → **69 项单元测试 + 29 项 MySQL 集成测试全部通过，BUILD SUCCESS**；适配器 `python -m unittest` 11 项通过。分布：`MySqlTaskPersistenceIT` 5、`OwnershipMigrationScriptIT` 6、`OwnershipEndToEndIT` 9、`OwnershipEndToEndCrossReadIT` 4、`GpuLeaseMySqlIT` 5。每个 IT 都用随机 schema，结束后删除。
- GPU 租约实测（`GpuLeaseMySqlIT` 5/5）：两个线程并发启动只有一个抢到槽位；租约过期后可被接管且 `fencing_token` 从 1 变 2；旧 token 续租返回 false、释放不会清掉新租约；按 `task+run` 释放只清匹配运行；释放后可再次获取且 fence 继续递增。
- 导出重启收敛：`ExportRecoveryRunner` 在 `ApplicationReadyEvent` 把 `pending` 置 `EXPORT_NOT_STARTED`、`running` 置 `EXPORT_INTERRUPTED`，不自动重派发；未写单测/IT（本机未构造半写 ZIP 场景），列为未验证。
- 迁移脚本实机执行（隔离库，`OwnershipMigrationScriptIT` 6/6）：①只读段加列加索引建决策表、不改业务行；②无决策时即使 `create_by` 命中真实账号也不认领；③1–4 标 `demo`、12 号 `assign user_id=1` 后为 `matched/legacy`；④`reject` 能撤回旧的自动匹配；⑤未知任务 ID 整批中止；⑥未知用户/非法决策值整批中止。旧版字符串开关的两处缺陷（开关被重置、`data_origin IS NULL` 使用户名匹配失效）随设计替换消失。
- **仍未完成**：生产库 `eladmin` 上的实际迁移执行与逐行核对记录。隔离库通过只证明脚本行为正确，不等于生产数据已迁移；`sql/security_model_ownership.sql` 不得表述为“已在生产验证”。
- 真实库只读核对（2026-11-13）：`eladmin.sys_pre_defense` 实际 **9 行**、无 `owner_user_id` 列；3、4 号已被恢复线程收敛为 `failed`（结果非空），8–12 为真实跑批，其中 12 号作者 `system` 在 `sys_user` 中不存在。裁定：1–4 号 `demo`、12 号 `assign user_id=1`（理由：system 为平台批处理作者）、8–11 号待审保持 `unattributed`。详见 `18` §4、§4.1。
- MySQL 集成测试使用唯一随机 schema，验证并发领取仅一个成功、完成操作胜过已读取恢复快照、恢复只收敛孤儿 `running` 任务、owner 范围查询只返回本人行且 demo 行被排除；清理后断言 schema 已删除。
- `.github/workflows/migration-quality.yml` 配置了迁移版 Maven `verify` 和 Vue 3 生产构建。工作流配置存在不代表远端 Actions 已成功，远端状态需以 GitHub Actions 记录为准。
- `mysql-it` Maven profile 通过 Failsafe 启动 MySQL 集成测试；GitHub Actions 配置 MySQL 8.4 service。远端 Actions 尚未确认时，不将本地通过表述为远端通过。
- 2026-11-13 R2 实测：`mvn test` 通过 69 项单元测试（0 失败）；同日 `verify -Pmysql-it` 通过 69 项单测 + 29 项数据库 IT（含 GPU 租约、迁移脚本、导出/下载/预览/审计端到端与跨用户开关）；适配器锁与失败码另有 11 项 Python 单测。均未涉及真实 CUDA／真实 LDD。
- 迁移脚本已在随机隔离 schema 实跑通过，但**生产库尚未执行**；不得写成“迁移已完成/已验证生产”。
- Redis 已进入迁移版依赖和运行配置，用于可重建资源目录；任务状态、报告和执行快照仍由 MySQL 保存。Redis 故障时缓存层降级回源 MySQL。
- CI 工作流包含 MySQL 8.4 集成测试和前端生产构建；本次未查询 GitHub Actions 运行记录，不能据配置推断远端已通过。
- 最近一次记录的本地普通 Maven 验证、MySQL profile 验证和前端生产构建结果见 `docs/sdd/16-engineering-improvement-roadmap.md`；R1 仅重跑普通 Maven 验证，前端未改动也未重跑构建。
